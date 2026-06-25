import { hostLabel, isHttpUrl, type BookmarkRecord, type SyncSettings, type WebAppRecord } from "./index";
import {
  activeBookmarksFromState,
  activeWebAppsFromState,
  appendLocalSnapshotOperations,
  appendOperation,
  appendWebAppDelete,
  appendWebAppUpsert,
  canonicalJson,
  findBookmarkByUrlInState,
  identityKeyForUrl,
  layoutFromState,
  syncV2,
  type SyncV2LocalSnapshot,
  type SyncV2Result,
  type SyncV2State,
  type SyncV2Store,
} from "./op-log";

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

export type BrowserBookmarkNode = {
  id: string;
  title: string;
  url?: string;
  children?: BrowserBookmarkNode[];
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
  syncNow: () => Promise<BrowserSyncResult>;
  loadRemoteBookmarks: () => Promise<BookmarkRecord[]>;
  findBookmarkByUrl: (url: string) => Promise<BookmarkRecord | null>;
  deleteRemoteBookmark: (input: string | { url?: string } | null | undefined) => Promise<BrowserSyncResult>;
  loadRemoteWebApps: () => Promise<WebAppRecord[]>;
  saveRemoteWebApp: (input: Partial<WebAppRecord> & { name: string; startUrl: string }) => Promise<WebAppRecord[]>;
  deleteRemoteWebApp: (input: string | Partial<WebAppRecord> | null | undefined) => Promise<WebAppRecord[]>;
  addBookmarkToSyncFolder: (input: { title: string; url: string; iconDataUrl?: string | null }) => Promise<BrowserSyncResult>;
};

export type BrowserSyncServiceOptions = {
  loadSettings: () => Promise<SyncSettings>;
  saveSettings: (settings: SyncSettings) => Promise<void>;
  loadSyncV2Store: () => Promise<SyncV2Store>;
  saveSyncV2Store: (store: SyncV2Store) => Promise<void>;
  loadLauncherLayout: () => Promise<SyncV2LocalSnapshot["layout"] | undefined>;
  bookmarks: {
    getTree: () => Promise<BrowserBookmarkNode[]>;
    get: (id: string) => Promise<BrowserBookmarkNode[]>;
    getSubTree: (id: string) => Promise<BrowserBookmarkNode[]>;
    create: (bookmark: BrowserBookmarkCreateDetails) => Promise<BrowserBookmarkNode>;
    update: (id: string, changes: BrowserBookmarkUpdateChanges) => Promise<BrowserBookmarkNode>;
    remove: (id: string) => Promise<void>;
    removeTree: (id: string) => Promise<void>;
  };
};

export function createBrowserSyncService(options: BrowserSyncServiceOptions): BrowserSyncService {
  let localLock: Promise<void> = Promise.resolve();

  async function syncNow(): Promise<BrowserSyncResult> {
    let settings = await options.loadSettings();
    settings = await ensureBookmarkFolder(settings);
    const before = (await options.loadSyncV2Store()).state;
    let result = {
      state: before,
      stateChanged: false,
      launcherChanged: false,
      uploadedOperationCount: 0,
      remoteOperationCount: 0,
      pendingOperationCount: 0,
      syncedAt: Date.now(),
    };
    if (settings.webDavUrl.trim()) {
      result = await syncV2({
        settings,
        loadStore: options.loadSyncV2Store,
        saveStore: options.saveSyncV2Store,
        loadLocalSnapshot: () => loadLocalSnapshot(),
        applyState: (state) => saveLocalState(settings, state),
        withLocalLock,
      });
    } else {
      await withLocalLock(async () => {
        const current = await options.loadSyncV2Store();
        const next = appendLocalSnapshotOperations(current, await loadLocalSnapshot());
        await options.saveSyncV2Store(next);
        await saveLocalState(settings, next.state);
        result = {
          state: next.state,
          stateChanged: canonicalJson(before) !== canonicalJson(next.state),
          launcherChanged: false,
          uploadedOperationCount: 0,
          remoteOperationCount: 0,
          pendingOperationCount: next.outbox.length,
          syncedAt: Date.now(),
        };
      });
    }
    return syncResultFromState(result.state, before, settings, result);
  }

  async function loadRemoteWebApps(): Promise<WebAppRecord[]> {
    return activeWebAppsFromState((await options.loadSyncV2Store()).state);
  }

  async function loadRemoteBookmarks(): Promise<BookmarkRecord[]> {
    return activeBookmarksFromState((await options.loadSyncV2Store()).state);
  }

  async function findBookmarkByUrl(url: string): Promise<BookmarkRecord | null> {
    const normalizedUrl = url.trim();
    if (!isHttpUrl(normalizedUrl)) return null;
    return findBookmarkByUrlInState((await options.loadSyncV2Store()).state, normalizedUrl);
  }

  async function saveRemoteWebApp(input: Partial<WebAppRecord> & { name: string; startUrl: string }): Promise<WebAppRecord[]> {
    const settings = await options.loadSettings();
    await withLocalLock(async () => {
      const current = await options.loadSyncV2Store();
      const itemId = input.id ? `app:${input.id}` : "";
      const placement = itemId && current.state.layout.items[itemId]
        ? undefined
        : { container: "desktop:0", index: nextDesktopIndex(current.state) };
      const { store } = appendWebAppUpsert(current, input, placement);
      await options.saveSyncV2Store(store);
    });
    if (settings.webDavUrl.trim()) await syncNow();
    return activeWebAppsFromState((await options.loadSyncV2Store()).state);
  }

  async function addBookmarkToSyncFolder(input: { title: string; url: string; iconDataUrl?: string | null }): Promise<BrowserSyncResult> {
    let settings = await options.loadSettings();
    settings = await ensureBookmarkFolder(settings);
    const url = identityKeyForUrl(input.url.trim());
    if (!isHttpUrl(url)) throw new Error("Current tab must be an http:// or https:// page.");
    const title = input.title.trim() || hostLabel(url);
    if (settings.webDavUrl.trim()) {
      await syncNow();
      settings = await ensureBookmarkFolder(await options.loadSettings());
    }
    await withLocalLock(async () => {
      const current = await options.loadSyncV2Store();
      const existing = findBookmarkByUrlInState(current.state, url);
      const now = Date.now();
      const bookmark: BookmarkRecord = {
        url,
        title,
        createdAt: existing?.createdAt || now,
        updatedAt: now,
        deletedAt: null,
        sourceDeviceId: settings.deviceId,
        iconDataUrl: input.iconDataUrl ?? existing?.iconDataUrl ?? null,
      };
      await options.saveSyncV2Store(appendOperation(current, { type: "bookmark.upsert", bookmark }));
    });
    return syncNow();
  }

  async function deleteRemoteBookmark(input: string | { url?: string } | null | undefined): Promise<BrowserSyncResult> {
    const settings = await ensureBookmarkFolder(await options.loadSettings());
    const url = typeof input === "string" ? input.trim() : input?.url?.trim() || "";
    const record = url ? await findBookmarkByUrl(url) : null;
    if (!record) return syncNow();
    await removeLocalBookmarksByUrl(settings.folderId, record.url);
    await withLocalLock(async () => {
      const current = await options.loadSyncV2Store();
      await options.saveSyncV2Store(appendOperation(current, {
        type: "bookmark.delete",
        url: record.url,
        title: record.title,
      }));
    });
    return syncNow();
  }

  async function deleteRemoteWebApp(input: string | Partial<WebAppRecord> | null | undefined): Promise<WebAppRecord[]> {
    const settings = await options.loadSettings();
    const id = typeof input === "string" ? input.trim() : input?.id?.trim();
    if (!id) return activeWebAppsFromState((await options.loadSyncV2Store()).state);
    await withLocalLock(async () => {
      const current = await options.loadSyncV2Store();
      await options.saveSyncV2Store(appendWebAppDelete(current, id));
    });
    if (settings.webDavUrl.trim()) await syncNow();
    return activeWebAppsFromState((await options.loadSyncV2Store()).state);
  }

  async function loadLocalSnapshot(): Promise<SyncV2LocalSnapshot> {
    return {
      layout: await options.loadLauncherLayout(),
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

  function nextDesktopIndex(state: SyncV2State): number {
    return Object.values(state.layout.items)
      .filter((item) => item.container === "desktop:0")
      .reduce((max, item) => Math.max(max, item.index), -1) + 1;
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
    const canonicalRecords = canonicalizeFlatBookmarkRecords(records);
    const root = (await options.bookmarks.getSubTree(settings.folderId))[0];
    if (bookmarkNodeTreeSignature(root?.children || []) === bookmarkRecordsTreeSignature(canonicalRecords)) {
      return { imported: 0, removed: 0 };
    }
    const removed = root?.children ? countBookmarkTree(root.children) : 0;
    await removeFolderChildren(settings.folderId);

    let imported = 0;
    for (const record of canonicalRecords) {
      await options.bookmarks.create({ parentId: settings.folderId, title: record.title || record.url, url: record.url });
      imported += 1;
    }

    return { imported, removed };
  }

  function syncResultFromState(
    state: SyncV2State,
    previous: SyncV2State,
    settings: SyncSettings,
    sync: Pick<SyncV2Result, "stateChanged" | "launcherChanged" | "uploadedOperationCount" | "remoteOperationCount" | "pendingOperationCount" | "syncedAt">,
  ): BrowserSyncResult {
    const bookmarks = activeBookmarksFromState(state);
    const webApps = activeWebAppsFromState(state);
    const previousBookmarks = new Set(activeBookmarksFromState(previous).map((bookmark) => bookmark.url));
    const previousWebApps = new Set(activeWebAppsFromState(previous).map((app) => app.id));
    return {
      stateChanged: sync.stateChanged,
      launcherChanged: sync.launcherChanged,
      bookmarkCount: bookmarks.length,
      deletedBookmarkCount: Object.keys(state.bookmarkTombstones).length,
      importedBookmarkCount: bookmarks.filter((bookmark) => !previousBookmarks.has(bookmark.url)).length,
      removedBookmarkCount: [...previousBookmarks].filter((id) => !state.bookmarks[id]).length,
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

  async function getBookmarkNode(id: string): Promise<BrowserBookmarkNode | null> {
    const nodes = await options.bookmarks.get(id);
    return nodes[0] || null;
  }

  async function getFolderBookmarkNodes(folderId: string): Promise<BrowserBookmarkNode[]> {
    const root = (await options.bookmarks.getSubTree(folderId))[0];
    const items: BrowserBookmarkNode[] = [];
    const visit = (nodes: BrowserBookmarkNode[] = []): void => {
      nodes.forEach((node) => {
        if (node.url) {
          items.push(node);
          return;
        }
        visit(node.children || []);
      });
    };
    visit(root?.children || []);
    return items;
  }

  async function removeLocalBookmarksByUrl(folderId: string, url: string): Promise<void> {
    const nodes = await getFolderBookmarkNodes(folderId);
    for (const node of nodes) {
      if (!node.url) continue;
      if (identityKeyForUrl(node.url) === identityKeyForUrl(url)) {
        await options.bookmarks.remove(node.id);
      }
    }
  }

  async function removeFolderChildren(folderId: string): Promise<void> {
    const root = (await options.bookmarks.getSubTree(folderId))[0];
    const children = [...(root?.children || [])].reverse();
    for (const child of children) {
      if (child.url) {
        await options.bookmarks.remove(child.id);
      } else {
        await options.bookmarks.removeTree(child.id);
      }
    }
  }

  return {
    syncNow,
    loadRemoteBookmarks,
    findBookmarkByUrl,
    deleteRemoteBookmark,
    loadRemoteWebApps,
    saveRemoteWebApp,
    deleteRemoteWebApp,
    addBookmarkToSyncFolder,
  };
}

function countBookmarkTree(nodes: BrowserBookmarkNode[]): number {
  return nodes.reduce((count, node) => count + 1 + countBookmarkTree(node.children || []), 0);
}

function bookmarkNodeTreeSignature(nodes: BrowserBookmarkNode[]): string {
  return JSON.stringify(nodes.map((node) => node.url
    ? { kind: "bookmark", title: node.title || node.url, url: node.url }
    : { kind: "folder", title: node.title || "Folder", children: bookmarkNodeTreeSignatureValue(node.children || []) }));
}

function bookmarkNodeTreeSignatureValue(nodes: BrowserBookmarkNode[]): unknown[] {
  return nodes
    .map((node) => node.url
      ? { title: node.title || node.url, url: identityKeyForUrl(node.url || "") }
      : { folder: true, title: node.title || "Folder", children: bookmarkNodeTreeSignatureValue(node.children || []) })
    .sort((left, right) => JSON.stringify(left).localeCompare(JSON.stringify(right)));
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
    .filter((record) => record.deletedAt == null && record.url)
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
  return left.title.localeCompare(right.title) ||
    left.url.localeCompare(right.url);
}
