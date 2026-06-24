import { browser, type Browser } from "wxt/browser";
import { hostLabel, isHttpUrl } from "@hyper-sync";
import {
  activeBookmarksFromState,
  activeWebAppsFromState,
  appendLocalSnapshotOperations,
  appendWebAppDelete,
  appendWebAppUpsert,
  canonicalJson,
  layoutFromState,
  syncV2,
  type SyncV2State,
  type SyncV2Store,
} from "@hyper-sync/op-log";
import { DEFAULT_DEVICE_NAME } from "./identity";
import { launcherLayoutStorage } from "./launcher-layout";
import { loadSettings, loadSyncV2Store, saveSettings, saveSyncV2Store } from "./storage";
import type { BookmarkRecord, SyncResult, SyncSettings, WebAppRecord } from "./types";

let localLock: Promise<void> = Promise.resolve();

export async function syncNow(): Promise<SyncResult> {
  let settings = await loadSettings();
  settings = await ensureBookmarkFolder(settings);
  const before = (await loadSyncV2Store()).state;
  let result = {
    state: before,
    uploadedOperationCount: 0,
    remoteOperationCount: 0,
    pendingOperationCount: 0,
    syncedAt: Date.now(),
  };
  if (settings.webDavUrl.trim()) {
    result = await syncV2({
      settings,
      loadStore: loadSyncV2Store,
      saveStore: saveSyncV2Store,
      loadLocalSnapshot: () => loadLocalSnapshot(settings),
      applyState: (state) => saveLocalState(settings, state),
      withLocalLock,
    });
  } else {
    await withLocalLock(async () => {
      const current = await loadSyncV2Store();
      const next = appendLocalSnapshotOperations(current, await loadLocalSnapshot(settings));
      await saveSyncV2Store(next);
      await saveLocalState(settings, next.state);
      result = {
        state: next.state,
        uploadedOperationCount: 0,
        remoteOperationCount: 0,
        pendingOperationCount: next.outbox.length,
        syncedAt: Date.now(),
      };
    });
  }
  return syncResultFromState(result.state, before, settings, result);
}

export async function loadRemoteWebApps(): Promise<WebAppRecord[]> {
  const settings = await loadSettings();
  if (settings.webDavUrl.trim()) await syncNow();
  return activeWebAppsFromState((await loadSyncV2Store()).state);
}

export async function saveRemoteWebApp(input: Partial<WebAppRecord> & { name: string; startUrl: string }): Promise<WebAppRecord[]> {
  const settings = await loadSettings();
  await withLocalLock(async () => {
    const current = await loadSyncV2Store();
    const itemId = input.id ? `app:${input.id}` : "";
    const placement = itemId && current.state.layout.items[itemId]
      ? undefined
      : { container: "desktop:0", order: nextDesktopOrder(current.state) };
    const { store } = appendWebAppUpsert(current, input, placement);
    await saveSyncV2Store(store);
  });
  if (settings.webDavUrl.trim()) await syncNow();
  return activeWebAppsFromState((await loadSyncV2Store()).state);
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

export async function deleteRemoteWebApp(input: string | Partial<WebAppRecord> | null | undefined): Promise<WebAppRecord[]> {
  const settings = await loadSettings();
  const id = typeof input === "string" ? input.trim() : input?.id?.trim();
  if (!id) return activeWebAppsFromState((await loadSyncV2Store()).state);
  await withLocalLock(async () => {
    const current = await loadSyncV2Store();
    await saveSyncV2Store(appendWebAppDelete(current, id));
  });
  if (settings.webDavUrl.trim()) await syncNow();
  return activeWebAppsFromState((await loadSyncV2Store()).state);
}

async function loadLocalSnapshot(settings: SyncSettings) {
  return {
    bookmarks: await collectLocalBookmarkRecords(settings),
    layout: await launcherLayoutStorage.load(),
  };
}

async function saveLocalState(settings: SyncSettings, state: SyncV2State): Promise<void> {
  await applyBookmarkRecords(settings, activeBookmarksFromState(state));
  void layoutFromState(state);
}

async function withLocalLock<T>(operation: () => Promise<T>): Promise<T> {
  const previous = localLock;
  let release!: () => void;
  localLock = new Promise<void>((resolve) => {
    release = resolve;
  });
  await previous;
  try {
    return await operation();
  } finally {
    release();
  }
}

function nextDesktopOrder(state: SyncV2State): number {
  return Object.values(state.layout.items)
    .filter((item) => item.container === "desktop:0")
    .reduce((max, item) => Math.max(max, item.order), -1000) + 1000;
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

async function collectLocalBookmarkRecords(settings: SyncSettings): Promise<BookmarkRecord[]> {
  const now = Date.now();
  const nodes = await getFolderBookmarks(settings.folderId);
  return nodes
    .filter((node) => !!node.url)
    .map((node) => ({
      url: node.url!,
      title: node.title || node.url!,
      createdAt: node.dateAdded || now,
      updatedAt: now,
      deletedAt: null,
      sourceDeviceId: settings.deviceId,
      iconDataUrl: null,
    }));
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
  const activeUrls = new Set(records.map((record) => record.url));
  for (const node of nodes) {
    if (node.url && !activeUrls.has(node.url)) {
      await removeBookmark(node.id);
      removed += 1;
    }
  }
  for (const record of records.sort((a, b) => a.title.localeCompare(b.title))) {
    const currentNodes = byUrl.get(record.url) || [];
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

function syncResultFromState(
  state: SyncV2State,
  previous: SyncV2State,
  settings: SyncSettings,
  sync: { uploadedOperationCount: number; remoteOperationCount: number; pendingOperationCount: number; syncedAt: number },
): SyncResult {
  const bookmarks = activeBookmarksFromState(state);
  const webApps = activeWebAppsFromState(state);
  const previousBookmarks = new Set(activeBookmarksFromState(previous).map((bookmark) => bookmark.url));
  const previousWebApps = new Set(activeWebAppsFromState(previous).map((app) => app.id));
  return {
    bookmarkCount: bookmarks.length,
    deletedBookmarkCount: Object.keys(state.bookmarkTombstones).length,
    importedBookmarkCount: bookmarks.filter((bookmark) => !previousBookmarks.has(bookmark.url)).length,
    removedBookmarkCount: [...previousBookmarks].filter((url) => !state.bookmarks[url]).length,
    webAppCount: webApps.length,
    deletedWebAppCount: Object.keys(state.appTombstones).length,
    importedWebAppCount: webApps.filter((app) => !previousWebApps.has(app.id)).length,
    removedWebAppCount: [...previousWebApps].filter((id) => !state.apps[id]).length,
    syncedAt: sync.syncedAt,
    folderTitle: settings.folderTitle,
    uploadedOperationCount: sync.uploadedOperationCount,
    remoteOperationCount: sync.remoteOperationCount,
    pendingOperationCount: sync.pendingOperationCount,
  };
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
