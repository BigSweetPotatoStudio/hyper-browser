import { browser, type Browser } from "wxt/browser";
import { loadMetadata, loadSettings, saveMetadata, saveSettings } from "./storage";
import type { BookmarkRecord, RemoteSyncManifest, SyncDocument, SyncMetadata, SyncResult, SyncSettings, WebAppRecord } from "./types";
import { WebDavClient, WebDavConflictError } from "./webdav";

const BOOKMARKS_FILE = "bookmarks.json";
const WEBAPPS_FILE = "webapps.json";
const MAX_SYNC_ATTEMPTS = 3;

export async function syncNow(): Promise<SyncResult> {
  let settings = await loadSettings();
  settings = await ensureBookmarkFolder(settings);
  const client = new WebDavClient(settings);
  await client.ensureCollections();

  let lastConflict: unknown;
  for (let attempt = 1; attempt <= MAX_SYNC_ATTEMPTS; attempt += 1) {
    const metadata = await loadMetadata();
    const remote = await client.getJson<SyncDocument<BookmarkRecord>>(BOOKMARKS_FILE);
    const remoteRecords = remote?.data.items || [];
    const localRecords = await collectLocalBookmarkRecords(settings, metadata);
    const merged = mergeRecords(
      indexBookmarks(remoteRecords),
      indexBookmarks(localRecords),
    );
    const mergedItems = Object.values(merged);
    const remoteChanged = !sameBookmarkRecords(remoteRecords, mergedItems);

    try {
      if (remoteChanged) {
        await client.putJson(BOOKMARKS_FILE, bookmarksDocument(mergedItems), remote?.etag);
        await client.putManifest();
      }
      await client.putDeviceState();
      const applied = await applyBookmarkRecords(settings, mergedItems);
      await saveMetadata({ bookmarks: merged });
      return {
        bookmarkCount: mergedItems.filter((item) => !item.deletedAt).length,
        deletedBookmarkCount: mergedItems.filter((item) => !!item.deletedAt).length,
        importedBookmarkCount: applied.imported,
        removedBookmarkCount: applied.removed,
        syncedAt: Date.now(),
        folderTitle: settings.folderTitle,
        attemptCount: attempt,
      };
    } catch (error) {
      if (error instanceof WebDavConflictError) {
        lastConflict = error;
        continue;
      }
      throw error;
    }
  }
  throw lastConflict instanceof Error ? lastConflict : new Error("WebDAV sync failed.");
}

export async function loadRemoteWebApps(): Promise<WebAppRecord[]> {
  const settings = await loadSettings();
  const client = new WebDavClient(settings);
  const remote = await client.getJson<SyncDocument<WebAppRecord>>(WEBAPPS_FILE);
  return (remote?.data.items || []).filter((item) => !item.deletedAt);
}

export async function readRemoteSyncManifest(settings?: SyncSettings): Promise<RemoteSyncManifest | null> {
  settings = settings || await loadSettings();
  if (!settings.webDavUrl.trim()) return null;
  const client = new WebDavClient(settings);
  const remote = await client.getJson<Partial<RemoteSyncManifest>>("manifest.json");
  if (!remote) return null;
  const updatedAt = normalizeTimestamp(remote.data.updatedAt);
  if (!updatedAt) return null;
  return {
    updatedAt,
    lastWriter: typeof remote.data.lastWriter === "string" ? remote.data.lastWriter : "",
  };
}

export async function saveRemoteWebApp(input: Partial<WebAppRecord> & { name: string; startUrl: string }): Promise<WebAppRecord[]> {
  return updateRemoteWebApps((records, settings) => {
    const now = Date.now();
    const startUrl = input.startUrl.trim();
    if (!startUrl) throw new Error("Start URL is required.");
    const id = input.id?.trim() || crypto.randomUUID();
    const existing = records[id];
    const hasIconDataUrl = Object.prototype.hasOwnProperty.call(input, "iconDataUrl");
    const iconDataUrl = hasIconDataUrl ? input.iconDataUrl ?? null : existing?.iconDataUrl ?? null;
    const iconSource = input.iconSource || existing?.iconSource || (iconDataUrl ? "custom" : "title");
    records[id] = {
      id,
      name: input.name.trim() || startUrl,
      startUrl,
      scopeUrl: input.scopeUrl?.trim() || existing?.scopeUrl || scopeFor(startUrl),
      themeColor: input.themeColor ?? existing?.themeColor ?? 0xff126d6a,
      displayMode: input.displayMode || existing?.displayMode || "standalone",
      createdAt: existing?.createdAt || input.createdAt || now,
      lastOpenedAt: input.lastOpenedAt || existing?.lastOpenedAt || now,
      updatedAt: now,
      deletedAt: null,
      sourceDeviceId: settings.deviceId,
      iconDataUrl,
      iconSource,
    };
  });
}

export async function addBookmarkToSyncFolder(input: { title: string; url: string }): Promise<SyncResult> {
  let settings = await loadSettings();
  settings = await ensureBookmarkFolder(settings);
  const url = input.url.trim();
  if (!isHttpUrl(url)) throw new Error("Current tab must be an http:// or https:// page.");
  const title = input.title.trim() || hostLabel(url);
  const existing = (await getFolderBookmarks(settings.folderId)).find((node) => node.url === url);
  if (existing) {
    if (existing.title !== title) await updateBookmark(existing.id, { title });
  } else {
    await createBookmark({ parentId: settings.folderId, title, url });
  }
  return syncNow();
}

export async function deleteRemoteWebApp(idOrStartUrl: string): Promise<WebAppRecord[]> {
  return updateRemoteWebApps((records, settings) => {
    const key = idOrStartUrl.trim();
    const existing = records[key] || Object.values(records).find((item) => item.startUrl === key);
    if (!existing) return;
    records[existing.id] = {
      ...existing,
      updatedAt: Date.now(),
      deletedAt: Date.now(),
      sourceDeviceId: settings.deviceId,
    };
  });
}

async function updateRemoteWebApps(mutator: (records: Record<string, WebAppRecord>, settings: SyncSettings) => void): Promise<WebAppRecord[]> {
  const settings = await loadSettings();
  const client = new WebDavClient(settings);
  await client.ensureCollections();
  let lastConflict: unknown;

  for (let attempt = 0; attempt < MAX_SYNC_ATTEMPTS; attempt += 1) {
    const remote = await client.getJson<SyncDocument<WebAppRecord>>(WEBAPPS_FILE);
    const records = indexWebApps(remote?.data.items || []);
    mutator(records, settings);
    try {
      await client.putJson(WEBAPPS_FILE, webAppsDocument(Object.values(records)), remote?.etag);
      await client.putManifest();
      await client.putDeviceState();
      return Object.values(records).filter((item) => !item.deletedAt);
    } catch (error) {
      if (error instanceof WebDavConflictError) {
        lastConflict = error;
        continue;
      }
      throw error;
    }
  }

  throw lastConflict instanceof Error ? lastConflict : new Error("Unable to save WebApps.");
}

async function ensureBookmarkFolder(settings: SyncSettings): Promise<SyncSettings> {
  const folderTitle = settings.folderTitle.trim() || "Hyper Browser";
  if (settings.folderId) {
    const existing = await getBookmarkNode(settings.folderId).catch(() => null);
    if (existing) {
      if (existing.title !== folderTitle) {
        await updateBookmark(existing.id, { title: folderTitle });
      }
      const next = { ...settings, folderTitle, folderId: existing.id };
      if (next.folderTitle !== settings.folderTitle) await saveSettings(next);
      return next;
    }
  }
  const root = (await getBookmarkTree())[0];
  const parent = root.children?.[0] || root;
  const existing = parent.children?.find((node) => !node.url && node.title === folderTitle);
  const folder = existing || await createBookmark({ parentId: parent.id, title: folderTitle });
  const next = { ...settings, folderTitle, folderId: folder.id };
  await saveSettings(next);
  return next;
}

async function collectLocalBookmarkRecords(settings: SyncSettings, metadata: SyncMetadata): Promise<BookmarkRecord[]> {
  const now = Date.now();
  const nodes = await getFolderBookmarks(settings.folderId);
  const localByUrl = new Map<string, Browser.bookmarks.BookmarkTreeNode>();
  nodes.forEach((node) => {
    if (node.url) localByUrl.set(node.url, node);
  });
  const records: Record<string, BookmarkRecord> = {};

  Object.values(metadata.bookmarks)
    .filter((known) => !!known.deletedAt)
    .forEach((known) => {
      records[known.url] = known;
    });

  Object.values(metadata.bookmarks)
    .filter((known) => !known.deletedAt && !localByUrl.has(known.url))
    .forEach((known) => {
      records[known.url] = { ...known, updatedAt: now, deletedAt: now, sourceDeviceId: settings.deviceId };
    });

  localByUrl.forEach((node, url) => {
    const known = metadata.bookmarks[url];
    const changed = !known || !!known.deletedAt || known.title !== node.title;
    records[url] = {
      url,
      title: node.title || url,
      createdAt: known?.createdAt || now,
      updatedAt: changed ? now : known.updatedAt,
      deletedAt: null,
      sourceDeviceId: changed ? settings.deviceId : known.sourceDeviceId,
      iconDataUrl: known?.iconDataUrl || null,
    };
  });

  return Object.values(records);
}

async function applyBookmarkRecords(settings: SyncSettings, records: BookmarkRecord[]): Promise<{ imported: number; removed: number }> {
  const nodes = await getFolderBookmarks(settings.folderId);
  const byUrl = new Map<string, Browser.bookmarks.BookmarkTreeNode[]>();
  nodes.forEach((node) => {
    if (!node.url) return;
    byUrl.set(node.url, [...(byUrl.get(node.url) || []), node]);
  });

  let imported = 0;
  let removed = 0;
  for (const record of records.sort((a, b) => b.updatedAt - a.updatedAt)) {
    const currentNodes = byUrl.get(record.url) || [];
    if (record.deletedAt) {
      for (const node of currentNodes) {
        await removeBookmark(node.id);
        removed += 1;
      }
      continue;
    }
    const [first, ...duplicates] = currentNodes;
    if (!first) {
      await createBookmark({ parentId: settings.folderId, title: record.title || record.url, url: record.url });
      imported += 1;
    } else if (first.title !== record.title) {
      await updateBookmark(first.id, { title: record.title || record.url, url: record.url });
      imported += 1;
    }
    for (const duplicate of duplicates) {
      await removeBookmark(duplicate.id);
      removed += 1;
    }
  }
  return { imported, removed };
}

function mergeRecords(remote: Record<string, BookmarkRecord>, local: Record<string, BookmarkRecord>): Record<string, BookmarkRecord> {
  const result: Record<string, BookmarkRecord> = {};
  new Set([...Object.keys(remote), ...Object.keys(local)]).forEach((key) => {
    const left = remote[key];
    const right = local[key];
    result[key] = chooseLatest(left, right);
  });
  return result;
}

function sameBookmarkRecords(left: BookmarkRecord[], right: BookmarkRecord[]): boolean {
  return recordListSignature(left, bookmarkRecordSignature) === recordListSignature(right, bookmarkRecordSignature);
}

function recordListSignature<T>(items: T[], signature: (item: T) => unknown): string {
  return JSON.stringify(items.map(signature).sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right))));
}

function bookmarkRecordSignature(item: BookmarkRecord) {
  return {
    url: item.url,
    title: item.title,
    createdAt: item.createdAt,
    updatedAt: item.updatedAt,
    deletedAt: item.deletedAt,
    sourceDeviceId: item.sourceDeviceId,
    iconDataUrl: item.iconDataUrl || null,
  };
}

function chooseLatest<T extends { updatedAt: number; deletedAt: number | null }>(left?: T, right?: T): T {
  if (!left && !right) throw new Error("Missing records.");
  if (!left) return right!;
  if (!right) return left;
  if (right.updatedAt > left.updatedAt) return right;
  if (left.updatedAt > right.updatedAt) return left;
  return right.deletedAt && !left.deletedAt ? right : left;
}

function normalizeTimestamp(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : 0;
}

function bookmarksDocument(items: BookmarkRecord[]): SyncDocument<BookmarkRecord> {
  return {
    type: "hyper-browser-bookmarks",
    schemaVersion: 1,
    updatedAt: Date.now(),
    items: items.sort((a, b) => Number(!!a.deletedAt) - Number(!!b.deletedAt) || a.title.localeCompare(b.title)),
  };
}

function webAppsDocument(items: WebAppRecord[]): SyncDocument<WebAppRecord> {
  return {
    type: "hyper-browser-webapps",
    schemaVersion: 1,
    updatedAt: Date.now(),
    items: items.sort((a, b) => Number(!!a.deletedAt) - Number(!!b.deletedAt) || a.name.localeCompare(b.name)),
  };
}

function indexBookmarks(items: BookmarkRecord[]): Record<string, BookmarkRecord> {
  return Object.fromEntries(items.filter((item) => item.url).map((item) => [item.url, item]));
}

function indexWebApps(items: WebAppRecord[]): Record<string, WebAppRecord> {
  return Object.fromEntries(items.filter((item) => item.id).map((item) => [item.id, item]));
}

function scopeFor(url: string): string {
  try {
    const value = new URL(url);
    value.pathname = "/";
    value.search = "";
    value.hash = "";
    return value.toString();
  } catch {
    return url;
  }
}

function hostLabel(url: string): string {
  try {
    return new URL(url).hostname || url;
  } catch {
    return url;
  }
}

function isHttpUrl(url: string): boolean {
  return /^https?:\/\//i.test(url);
}

function getBookmarkTree(): Promise<Browser.bookmarks.BookmarkTreeNode[]> {
  return browser.bookmarks.getTree();
}

async function getBookmarkNode(id: string): Promise<Browser.bookmarks.BookmarkTreeNode | null> {
  const nodes = await browser.bookmarks.get(id);
  return nodes[0] || null;
}

async function getFolderBookmarks(folderId: string): Promise<Browser.bookmarks.BookmarkTreeNode[]> {
  const nodes = await browser.bookmarks.getSubTree(folderId);
  return flattenBookmarkNodes(nodes[0]).filter((node) => !!node.url);
}

function flattenBookmarkNodes(node?: Browser.bookmarks.BookmarkTreeNode): Browser.bookmarks.BookmarkTreeNode[] {
  if (!node) return [];
  const children = node.children || [];
  return [
    node,
    ...children.flatMap((child) => flattenBookmarkNodes(child)),
  ];
}

function createBookmark(bookmark: Browser.bookmarks.CreateDetails): Promise<Browser.bookmarks.BookmarkTreeNode> {
  return browser.bookmarks.create(bookmark);
}

function updateBookmark(id: string, changes: Browser.bookmarks.UpdateChanges): Promise<Browser.bookmarks.BookmarkTreeNode> {
  return browser.bookmarks.update(id, changes);
}

function removeBookmark(id: string): Promise<void> {
  return browser.bookmarks.remove(id);
}
