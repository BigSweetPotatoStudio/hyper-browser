import { hostLabel, isHttpUrl, type SyncSettings } from "@hyper-sync";
import { identityKeyForUrl } from "@hyper-sync/op-log";
import type { BookmarkRecord } from "@hyper-sync/sync-json-types";

export type BrowserBookmarkNode = {
  id: string;
  parentId?: string;
  title: string;
  url?: string;
  children?: BrowserBookmarkNode[];
};

export type BrowserBookmarkEvent = {
  id?: string;
  parentId?: string;
  oldParentId?: string;
};

export type BrowserBookmarkCreateDetails = {
  parentId?: string;
  title: string;
  url?: string;
};

export type BrowserBookmarkUpdateChanges = {
  title?: string;
  url?: string;
};

export type CompanionBookmarkProjection = {
  ensureBookmarkFolder: (settings: SyncSettings) => Promise<SyncSettings>;
  recordLocalBookmarkFolderSnapshot: (
    events: BrowserBookmarkEvent[],
    existingRecords: BookmarkRecord[],
    saveSnapshot: (bookmarks: BookmarkRecord[]) => Promise<boolean | null>,
  ) => Promise<boolean | null>;
  addBookmarkToSyncFolder: (
    input: { title: string; url: string },
    saveBookmark: (input: { title: string; url: string }) => Promise<BookmarkRecord[]>,
  ) => Promise<BookmarkRecord[]>;
  deleteBookmarkFromSyncFolder: (
    input: string | { url?: string } | null | undefined,
    loadBookmarks: () => Promise<BookmarkRecord[]>,
    deleteBookmark: (input: { url: string }) => Promise<BookmarkRecord[]>,
  ) => Promise<BookmarkRecord[]>;
  collectLocalBookmarkRecords: (settings: SyncSettings, existingRecords: BookmarkRecord[]) => Promise<BookmarkRecord[]>;
  applyBookmarkRecords: (settings: SyncSettings, records: BookmarkRecord[]) => Promise<{ imported: number; removed: number }>;
};

export type CompanionBookmarkProjectionOptions = {
  loadSettings: () => Promise<SyncSettings>;
  saveSettings: (settings: SyncSettings) => Promise<void>;
  bookmarks: {
    getTree: () => Promise<BrowserBookmarkNode[]>;
    get: (id: string) => Promise<BrowserBookmarkNode[]>;
    getSubTree: (id: string) => Promise<BrowserBookmarkNode[]>;
    create: (bookmark: BrowserBookmarkCreateDetails) => Promise<BrowserBookmarkNode>;
    update: (id: string, changes: BrowserBookmarkUpdateChanges) => Promise<BrowserBookmarkNode>;
    remove: (id: string) => Promise<void>;
  };
};

const LOCAL_BOOKMARK_PROJECTION_QUIET_MS = 4000;

export function createCompanionBookmarkProjection(options: CompanionBookmarkProjectionOptions): CompanionBookmarkProjection {
  let localBookmarkProjectionDepth = 0;
  let localBookmarkProjectionQuietUntil = 0;

  async function ensureBookmarkFolder(settings: SyncSettings): Promise<SyncSettings> {
    const folderTitle = settings.folderTitle.trim() || "Hyper Browser";
    if (settings.folderId) {
      const existing = await getBookmarkNode(settings.folderId).catch(() => null);
      if (existing) {
        if (existing.title !== folderTitle) {
          await options.bookmarks.update(existing.id, { title: folderTitle });
        }
        const next = { ...settings, folderTitle, folderId: existing.id };
        if (next.folderTitle !== settings.folderTitle) await options.saveSettings(next);
        return next;
      }
    }
    const root = (await options.bookmarks.getTree())[0];
    const parent = root.children?.[0] || root;
    const existing = parent.children?.find((node) => !node.url && node.title === folderTitle);
    const folder = existing || await options.bookmarks.create({ parentId: parent.id, title: folderTitle });
    const next = { ...settings, folderTitle, folderId: folder.id };
    await options.saveSettings(next);
    return next;
  }

  async function recordLocalBookmarkFolderSnapshot(
    events: BrowserBookmarkEvent[] = [],
    existingRecords: BookmarkRecord[],
    saveSnapshot: (bookmarks: BookmarkRecord[]) => Promise<boolean | null>,
  ): Promise<boolean | null> {
    if (shouldIgnoreLocalBookmarkEvents()) return null;
    const settings = await options.loadSettings();
    if (!settings.folderId.trim()) return false;
    const folder = await getBookmarkNode(settings.folderId).catch(() => null);
    if (!folder) return false;
    if (events.length > 0 && !(await hasBookmarkEventInFolder(settings.folderId, events))) return false;
    const bookmarks = await collectLocalBookmarkRecords(settings, existingRecords);
    return saveSnapshot(bookmarks);
  }

  async function addBookmarkToSyncFolder(
    input: { title: string; url: string },
    saveBookmark: (input: { title: string; url: string }) => Promise<BookmarkRecord[]>,
  ): Promise<BookmarkRecord[]> {
    const settings = await ensureBookmarkFolder(await options.loadSettings());
    const url = identityKeyForUrl(input.url.trim());
    if (!isHttpUrl(url)) throw new Error("Current tab must be an http:// or https:// page.");
    const title = input.title.trim() || hostLabel(url);
    await upsertLocalBookmarkNode(settings.folderId, title, url);
    return saveBookmark({ title, url });
  }

  async function deleteBookmarkFromSyncFolder(
    input: string | { url?: string } | null | undefined,
    loadBookmarks: () => Promise<BookmarkRecord[]>,
    deleteBookmark: (input: { url: string }) => Promise<BookmarkRecord[]>,
  ): Promise<BookmarkRecord[]> {
    const settings = await ensureBookmarkFolder(await options.loadSettings());
    const url = identityKeyForUrl(typeof input === "string" ? input.trim() : input?.url?.trim() || "");
    if (!url) return loadBookmarks();
    await removeLocalBookmarkNodes(settings.folderId, url);
    return deleteBookmark({ url });
  }

  async function collectLocalBookmarkRecords(settings: SyncSettings, existingRecords: BookmarkRecord[]): Promise<BookmarkRecord[]> {
    const now = Date.now();
    const existingByUrl = new Map(existingRecords.map((bookmark) => [identityKeyForUrl(bookmark.url), bookmark]));
    const selected = new Map<string, BookmarkRecord>();
    for (const node of await getFolderBookmarkNodes(settings.folderId)) {
      if (!node.url) continue;
      const url = identityKeyForUrl(node.url);
      if (!isHttpUrl(url) || selected.has(url)) continue;
      const existing = existingByUrl.get(url);
      selected.set(url, {
        url,
        title: node.title?.trim() || existing?.title || hostLabel(url),
        createdAt: existing?.createdAt || now,
        updatedAt: now,
      });
    }
    return [...selected.values()];
  }

  async function applyBookmarkRecords(settings: SyncSettings, records: BookmarkRecord[]): Promise<{ imported: number; removed: number }> {
    return withLocalBookmarkProjectionQuiet(async () => {
      const canonicalRecords = canonicalizeFlatBookmarkRecords(records);
      const root = (await options.bookmarks.getSubTree(settings.folderId))[0];
      if (bookmarkNodeTreeSignature(root?.children || []) === bookmarkRecordsTreeSignature(canonicalRecords)) {
        return { imported: 0, removed: 0 };
      }
      const removed = countDirectBookmarkChildren(root?.children || []);
      await removeDirectBookmarkChildren(settings.folderId);

      let imported = 0;
      for (const record of canonicalRecords) {
        await options.bookmarks.create({ parentId: settings.folderId, title: record.title || record.url, url: record.url });
        imported += 1;
      }

      return { imported, removed };
    });
  }

  async function getBookmarkNode(id: string): Promise<BrowserBookmarkNode | null> {
    const nodes = await options.bookmarks.get(id);
    return nodes[0] || null;
  }

  async function getFolderBookmarkNodes(folderId: string): Promise<BrowserBookmarkNode[]> {
    const root = (await options.bookmarks.getSubTree(folderId))[0];
    return (root?.children || []).filter((node) => !!node.url);
  }

  async function upsertLocalBookmarkNode(folderId: string, title: string, url: string): Promise<void> {
    await withLocalBookmarkProjectionQuiet(async () => {
      const matches = (await getFolderBookmarkNodes(folderId))
        .filter((node) => identityKeyForUrl(node.url || "") === url);
      const [primary, ...duplicates] = matches;
      if (primary) {
        if (primary.title !== title || primary.url !== url) {
          await options.bookmarks.update(primary.id, { title, url });
        }
      } else {
        await options.bookmarks.create({ parentId: folderId, title, url });
      }
      for (const duplicate of duplicates) {
        await options.bookmarks.remove(duplicate.id);
      }
    });
  }

  async function removeLocalBookmarkNodes(folderId: string, url: string): Promise<void> {
    await withLocalBookmarkProjectionQuiet(async () => {
      const matches = (await getFolderBookmarkNodes(folderId))
        .filter((node) => identityKeyForUrl(node.url || "") === url);
      for (const match of matches) {
        await options.bookmarks.remove(match.id);
      }
    });
  }

  async function hasBookmarkEventInFolder(folderId: string, events: BrowserBookmarkEvent[]): Promise<boolean> {
    for (const event of events) {
      const ids = uniqueStrings([event.id, event.parentId, event.oldParentId]);
      for (const id of ids) {
        if (id === folderId || await isBookmarkNodeUnderFolder(id, folderId)) return true;
      }
    }
    return false;
  }

  async function isBookmarkNodeUnderFolder(nodeId: string, folderId: string): Promise<boolean> {
    let currentId = nodeId;
    const seen = new Set<string>();
    while (currentId && !seen.has(currentId)) {
      if (currentId === folderId) return true;
      seen.add(currentId);
      const node = await getBookmarkNode(currentId).catch(() => null);
      const parentId = node?.parentId?.trim() || "";
      if (!parentId) return false;
      currentId = parentId;
    }
    return false;
  }

  function shouldIgnoreLocalBookmarkEvents(): boolean {
    return localBookmarkProjectionDepth > 0 || Date.now() < localBookmarkProjectionQuietUntil;
  }

  async function withLocalBookmarkProjectionQuiet<T>(operation: () => Promise<T>): Promise<T> {
    localBookmarkProjectionDepth += 1;
    try {
      return await operation();
    } finally {
      localBookmarkProjectionDepth -= 1;
      if (localBookmarkProjectionDepth <= 0) {
        localBookmarkProjectionDepth = 0;
        localBookmarkProjectionQuietUntil = Date.now() + LOCAL_BOOKMARK_PROJECTION_QUIET_MS;
      }
    }
  }

  async function removeDirectBookmarkChildren(folderId: string): Promise<void> {
    const root = (await options.bookmarks.getSubTree(folderId))[0];
    const children = [...(root?.children || [])].reverse();
    for (const child of children) {
      if (child.url) {
        await options.bookmarks.remove(child.id);
      }
    }
  }

  return {
    ensureBookmarkFolder,
    recordLocalBookmarkFolderSnapshot,
    addBookmarkToSyncFolder,
    deleteBookmarkFromSyncFolder,
    collectLocalBookmarkRecords,
    applyBookmarkRecords,
  };
}

function countDirectBookmarkChildren(nodes: BrowserBookmarkNode[]): number {
  return nodes.filter((node) => !!node.url).length;
}

function bookmarkNodeTreeSignature(nodes: BrowserBookmarkNode[]): string {
  const selected = new Map<string, { title: string; url: string }>();
  nodes.forEach((node) => {
    if (!node.url) return;
    const url = identityKeyForUrl(node.url);
    if (isHttpUrl(url) && !selected.has(url)) {
      selected.set(url, { title: node.title || url, url });
    }
  });
  return JSON.stringify([...selected.values()]);
}

function bookmarkRecordsTreeSignature(records: BookmarkRecord[]): string {
  return JSON.stringify(canonicalizeFlatBookmarkRecords(records).map((record) => ({
    title: record.title || record.url,
    url: record.url,
  })));
}

function canonicalizeFlatBookmarkRecords(records: BookmarkRecord[]): BookmarkRecord[] {
  const selected = new Map<string, BookmarkRecord>();
  records
    .filter((record) => record.url)
    .forEach((record) => {
      const url = identityKeyForUrl(record.url);
      if (!url) return;
      const current = selected.get(url);
      if (!current || (record.updatedAt || 0) > (current.updatedAt || 0)) {
        selected.set(url, {
          ...record,
          url,
        });
      }
    });
  return [...selected.values()].sort(compareBookmarkPlacement);
}

function compareBookmarkPlacement(left: BookmarkRecord, right: BookmarkRecord): number {
  return (right.createdAt || 0) - (left.createdAt || 0) ||
    left.title.localeCompare(right.title) ||
    left.url.localeCompare(right.url);
}

function uniqueStrings(values: Array<string | undefined>): string[] {
  const seen = new Set<string>();
  return values
    .map((value) => value?.trim() || "")
    .filter((value) => {
      if (!value || seen.has(value)) return false;
      seen.add(value);
      return true;
    });
}
