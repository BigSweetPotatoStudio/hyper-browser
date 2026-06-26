import { hostLabel, isHttpUrl, type SyncSettings } from "./index";
import {
  activeBookmarksFromState,
  identityKeyForUrl,
  layoutFromState,
  saveSyncStateToFiles,
  type SyncStateFileStorage,
  type SyncV2LocalSnapshot,
  type SyncV2Mode,
  type SyncV2State,
} from "./op-log";
import { createSyncStateService } from "./state-sync";
import type { BookmarkRecord, WebAppRecord } from "./sync-json-types";

export type BrowserSyncResult = {
  stateChanged: boolean;
  launcherChanged: boolean;
  bookmarkCount: number;
  deletedBookmarkCount: number;
  importedBookmarkCount: number;
  removedBookmarkCount: number;
  webAppCount: number;
  deletedWebAppCount: number;
  importedWebAppCount: number;
  removedWebAppCount: number;
  syncedAt: number;
  folderTitle: string;
  uploadedOperationCount: number;
  remoteOperationCount: number;
  pendingOperationCount: number;
};

export type BrowserSyncResultLike = Pick<BrowserSyncResult,
  "bookmarkCount" |
  "webAppCount" |
  "deletedBookmarkCount" |
  "deletedWebAppCount" |
  "pendingOperationCount"
>;

export type BrowserSyncResultFormatLabels = {
  complete: (counts: { bookmarks: number; webApps: number; deleted: number }) => string;
  pending?: (pending: number) => string;
};

export function isBrowserSyncResult(value: unknown): value is BrowserSyncResultLike {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<BrowserSyncResultLike>;
  return typeof candidate.bookmarkCount === "number" &&
    typeof candidate.webAppCount === "number" &&
    typeof candidate.deletedBookmarkCount === "number" &&
    typeof candidate.deletedWebAppCount === "number" &&
    typeof candidate.pendingOperationCount === "number";
}

export function formatBrowserSyncResult(
  result: BrowserSyncResultLike,
  labels: BrowserSyncResultFormatLabels = defaultBrowserSyncResultLabels,
): string {
  const deleted = result.deletedBookmarkCount + result.deletedWebAppCount;
  const summary = labels.complete({
    bookmarks: result.bookmarkCount,
    webApps: result.webAppCount,
    deleted,
  });
  if (result.pendingOperationCount <= 0) return summary;
  return `${summary}${labels.pending?.(result.pendingOperationCount) || ""}`;
}

const defaultBrowserSyncResultLabels: BrowserSyncResultFormatLabels = {
  complete: ({ bookmarks, webApps, deleted }) => {
    const tombstones = deleted > 0 ? `, ${deleted} tombstones` : "";
    return `Synced ${bookmarks} bookmarks and ${webApps} WebApps${tombstones}`;
  },
  pending: (pending) => `, ${pending} pending changes`,
};

export type BrowserSyncRunOptions = {
  mode?: SyncV2Mode;
};

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

export type BrowserSyncService = {
  syncNow: (options?: BrowserSyncRunOptions) => Promise<BrowserSyncResult>;
  recordLocalBookmarkFolderSnapshot: (events?: BrowserBookmarkEvent[]) => Promise<boolean | null>;
  loadRemoteBookmarks: () => Promise<BookmarkRecord[]>;
  findBookmarkByUrl: (url: string) => Promise<BookmarkRecord | null>;
  deleteRemoteBookmark: (input: string | { url?: string } | null | undefined) => Promise<BrowserSyncResult>;
  loadRemoteWebApps: () => Promise<WebAppRecord[]>;
  findWebAppsByUrl: (url: string) => Promise<WebAppRecord[]>;
  saveRemoteWebApp: (input: Partial<WebAppRecord> & { name: string; startUrl: string }) => Promise<WebAppRecord[]>;
  deleteRemoteWebApp: (input: string | Partial<WebAppRecord> | null | undefined) => Promise<WebAppRecord[]>;
  loadLauncherLayout: () => Promise<SyncV2LocalSnapshot["layout"]>;
  saveLauncherLayout: (layout: SyncV2LocalSnapshot["layout"]) => Promise<void>;
  addBookmarkToSyncFolder: (input: { title: string; url: string }) => Promise<BrowserSyncResult>;
};

export type BrowserSyncServiceOptions = {
  loadSettings: () => Promise<SyncSettings>;
  saveSettings: (settings: SyncSettings) => Promise<void>;
  syncFiles: SyncStateFileStorage;
  loadLauncherLayout: () => Promise<SyncV2LocalSnapshot["layout"] | undefined>;
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

export function createBrowserSyncService(options: BrowserSyncServiceOptions): BrowserSyncService {
  let localBookmarkProjectionDepth = 0;
  let localBookmarkProjectionQuietUntil = 0;

  const stateSync = createSyncStateService({
    loadSettings: options.loadSettings,
    ensureSettings: ensureBookmarkFolder,
    toSyncSettings: (settings) => settings,
    syncFiles: options.syncFiles,
    loadLocalSnapshot: ({ syncSettings }, mode) => loadLocalSnapshot(syncSettings, mode),
    saveSyncedState: ({ syncSettings }, state) => saveLocalState(syncSettings, state),
    allowLocalOnlySync: true,
    buildResult: ({ base, syncSettings }) => ({
      ...base,
      folderTitle: syncSettings.folderTitle,
    }),
  });

  async function syncNow(syncOptions: BrowserSyncRunOptions = {}): Promise<BrowserSyncResult> {
    return stateSync.syncNow(syncOptions);
  }

  async function loadRemoteWebApps(): Promise<WebAppRecord[]> {
    return stateSync.loadWebApps();
  }

  async function loadRemoteBookmarks(): Promise<BookmarkRecord[]> {
    return stateSync.loadBookmarks();
  }

  async function loadLauncherLayout(): Promise<SyncV2LocalSnapshot["layout"]> {
    return stateSync.loadLauncherLayout();
  }

  async function saveLauncherLayout(layout: SyncV2LocalSnapshot["layout"]): Promise<void> {
    await stateSync.saveLauncherLayout(layout);
  }

  async function recordLocalBookmarkFolderSnapshot(events: BrowserBookmarkEvent[] = []): Promise<boolean | null> {
    if (shouldIgnoreLocalBookmarkEvents()) return null;
    const settings = await options.loadSettings();
    if (!settings.folderId.trim()) return false;
    const folder = await getBookmarkNode(settings.folderId).catch(() => null);
    if (!folder) return false;
    if (events.length > 0 && !(await hasBookmarkEventInFolder(settings.folderId, events))) return false;
    const bookmarks = await collectLocalBookmarkRecords(settings, await stateSync.loadBookmarks());
    return stateSync.saveBookmarkSnapshot(bookmarks);
  }

  async function findBookmarkByUrl(url: string): Promise<BookmarkRecord | null> {
    const normalizedUrl = url.trim();
    if (!isHttpUrl(normalizedUrl)) return null;
    return stateSync.findBookmarkByUrl(normalizedUrl);
  }

  async function findWebAppsByUrl(url: string): Promise<WebAppRecord[]> {
    const normalizedUrl = url.trim();
    if (!isHttpUrl(normalizedUrl)) return [];
    return stateSync.findWebAppsByUrl(normalizedUrl);
  }

  async function saveRemoteWebApp(input: Partial<WebAppRecord> & { name: string; startUrl: string }): Promise<WebAppRecord[]> {
    return stateSync.saveWebApp(input);
  }

  async function addBookmarkToSyncFolder(input: { title: string; url: string }): Promise<BrowserSyncResult> {
    let settings = await options.loadSettings();
    settings = await ensureBookmarkFolder(settings);
    const url = identityKeyForUrl(input.url.trim());
    if (!isHttpUrl(url)) throw new Error("Current tab must be an http:// or https:// page.");
    const title = input.title.trim() || hostLabel(url);
    if (settings.webDavUrl.trim()) {
      await syncNow();
      settings = await ensureBookmarkFolder(await options.loadSettings());
    }
    await stateSync.saveBookmark({ title, url });
    return syncNow();
  }

  async function deleteRemoteBookmark(input: string | { url?: string } | null | undefined): Promise<BrowserSyncResult> {
    const settings = await ensureBookmarkFolder(await options.loadSettings());
    const url = typeof input === "string" ? input.trim() : input?.url?.trim() || "";
    const record = url ? await findBookmarkByUrl(url) : null;
    if (!record) return syncNow();
    await stateSync.deleteBookmark({ url: record.url });
    return syncNow();
  }

  async function deleteRemoteWebApp(input: string | Partial<WebAppRecord> | null | undefined): Promise<WebAppRecord[]> {
    return stateSync.deleteWebApp(input);
  }

  async function loadLocalSnapshot(settings: SyncSettings, mode: SyncV2Mode): Promise<SyncV2LocalSnapshot> {
    const layout = await options.loadLauncherLayout();
    if (mode !== "pushLocal") {
      return { layout };
    }
    return {
      bookmarks: await collectLocalBookmarkRecords(settings, await stateSync.loadBookmarks()),
      layout,
    };
  }

  async function saveLocalState(settings: SyncSettings, state: SyncV2State): Promise<void> {
    await saveStateFiles(state);
    await applyBookmarkRecords(settings, activeBookmarksFromState(state));
    void layoutFromState(state);
  }

  async function saveStateFiles(state: SyncV2State): Promise<void> {
    await saveSyncStateToFiles(options.syncFiles, state);
  }

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
    syncNow,
    recordLocalBookmarkFolderSnapshot,
    loadRemoteBookmarks,
    findBookmarkByUrl,
    deleteRemoteBookmark,
    loadRemoteWebApps,
    findWebAppsByUrl,
    saveRemoteWebApp,
    deleteRemoteWebApp,
    loadLauncherLayout,
    saveLauncherLayout,
    addBookmarkToSyncFolder,
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
