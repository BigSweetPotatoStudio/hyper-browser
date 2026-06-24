import { hostLabel, isHttpUrl, type BookmarkRecord, type SyncSettings, type WebAppRecord } from "./index";
import {
  activeBookmarksFromState,
  activeWebAppsFromState,
  appendLocalSnapshotOperations,
  appendWebAppDelete,
  appendWebAppUpsert,
  canonicalJson,
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
  dateAdded?: number;
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
  loadRemoteWebApps: () => Promise<WebAppRecord[]>;
  saveRemoteWebApp: (input: Partial<WebAppRecord> & { name: string; startUrl: string }) => Promise<WebAppRecord[]>;
  deleteRemoteWebApp: (input: string | Partial<WebAppRecord> | null | undefined) => Promise<WebAppRecord[]>;
  addBookmarkToSyncFolder: (input: { title: string; url: string }) => Promise<BrowserSyncResult>;
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
        loadLocalSnapshot: () => loadLocalSnapshot(settings),
        applyState: (state) => saveLocalState(settings, state),
        withLocalLock,
      });
    } else {
      await withLocalLock(async () => {
        const current = await options.loadSyncV2Store();
        const next = appendLocalSnapshotOperations(current, await loadLocalSnapshot(settings));
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

  async function addBookmarkToSyncFolder(input: { title: string; url: string }): Promise<BrowserSyncResult> {
    let settings = await options.loadSettings();
    settings = await ensureBookmarkFolder(settings);
    const url = input.url.trim();
    if (!isHttpUrl(url)) throw new Error("Current tab must be an http:// or https:// page.");
    const title = input.title.trim() || hostLabel(url);
    const existing = (await getFolderBookmarks(settings.folderId)).find((node) => node.url === url);
    if (existing) {
      if (existing.title !== title) await options.bookmarks.update(existing.id, { title });
    } else {
      await options.bookmarks.create({ parentId: settings.folderId, title, url });
    }
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

  async function loadLocalSnapshot(settings: SyncSettings): Promise<SyncV2LocalSnapshot> {
    return {
      bookmarks: await collectLocalBookmarkRecords(settings),
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
    const byUrl = new Map<string, BrowserBookmarkNode[]>();
    nodes.forEach((node) => {
      if (!node.url) return;
      byUrl.set(node.url, [...(byUrl.get(node.url) || []), node]);
    });
    let imported = 0;
    let removed = 0;
    const activeUrls = new Set(records.map((record) => record.url));
    for (const node of nodes) {
      if (node.url && !activeUrls.has(node.url)) {
        await options.bookmarks.remove(node.id);
        removed += 1;
      }
    }
    for (const record of records.sort((a, b) => a.title.localeCompare(b.title))) {
      const currentNodes = byUrl.get(record.url) || [];
      const [first, ...duplicates] = currentNodes;
      if (!first) {
        await options.bookmarks.create({ parentId: settings.folderId, title: record.title || record.url, url: record.url });
        imported += 1;
      } else if (first.title !== record.title) {
        await options.bookmarks.update(first.id, { title: record.title || record.url, url: record.url });
        imported += 1;
      }
      for (const duplicate of duplicates) {
        await options.bookmarks.remove(duplicate.id);
        removed += 1;
      }
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

  async function getBookmarkNode(id: string): Promise<BrowserBookmarkNode | null> {
    const nodes = await options.bookmarks.get(id);
    return nodes[0] || null;
  }

  async function getFolderBookmarks(folderId: string): Promise<BrowserBookmarkNode[]> {
    const nodes = await options.bookmarks.getSubTree(folderId);
    return flattenBookmarkNodes(nodes[0]).filter((node) => !!node.url);
  }

  return {
    syncNow,
    loadRemoteWebApps,
    saveRemoteWebApp,
    deleteRemoteWebApp,
    addBookmarkToSyncFolder,
  };
}

function flattenBookmarkNodes(node?: BrowserBookmarkNode): BrowserBookmarkNode[] {
  if (!node) return [];
  const children = node.children || [];
  return [
    node,
    ...children.flatMap((child) => flattenBookmarkNodes(child)),
  ];
}
