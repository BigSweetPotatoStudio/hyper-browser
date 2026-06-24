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
  loadRemoteBookmarks: () => Promise<BookmarkRecord[]>;
  findBookmarkByUrl: (url: string) => Promise<BookmarkRecord | null>;
  deleteRemoteBookmark: (input: string | { id?: string; url?: string } | null | undefined) => Promise<BrowserSyncResult>;
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

  async function loadRemoteBookmarks(): Promise<BookmarkRecord[]> {
    return activeBookmarksFromState((await options.loadSyncV2Store()).state);
  }

  async function findBookmarkByUrl(url: string): Promise<BookmarkRecord | null> {
    const normalizedUrl = url.trim();
    if (!isHttpUrl(normalizedUrl)) return null;
    let store = await options.loadSyncV2Store();
    let match = findBookmarkByUrlInState(store.state, normalizedUrl);
    if (match) return match;

    const settings = await ensureBookmarkFolder(await options.loadSettings());
    await normalizeLocalBookmarkFolder(settings);
    const localNodes = await getFolderBookmarkNodes(settings.folderId);
    if (!localNodes.some((node) => node.url && identityKeyForUrl(node.url) === identityKeyForUrl(normalizedUrl))) {
      return null;
    }
    store = appendLocalSnapshotOperations(store, {
      bookmarks: await collectLocalBookmarkRecords(settings, store.state),
      bookmarkSnapshotMode: "tree",
    });
    await options.saveSyncV2Store(store);
    return findBookmarkByUrlInState(store.state, normalizedUrl);
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
    const identityKey = identityKeyForUrl(url);
    const existing = (await getFolderBookmarkNodes(settings.folderId)).find((node) => node.url && identityKeyForUrl(node.url) === identityKey);
    if (existing) {
      if (existing.title !== title) await options.bookmarks.update(existing.id, { title });
    } else {
      await options.bookmarks.create({ parentId: settings.folderId, title, url });
    }
    return syncNow();
  }

  async function deleteRemoteBookmark(input: string | { id?: string; url?: string } | null | undefined): Promise<BrowserSyncResult> {
    const settings = await ensureBookmarkFolder(await options.loadSettings());
    const id = typeof input === "string" ? input.trim() : input?.id?.trim() || "";
    const url = typeof input === "object" && input ? input.url?.trim() || "" : "";
    let record = id ? activeBookmarksFromState((await options.loadSyncV2Store()).state).find((item) => item.id === id) || null : null;
    if (!record && url) record = await findBookmarkByUrl(url);
    if (!record || record.kind === "folder") return syncNow();
    await removeLocalBookmarksByIdentity(settings.folderId, record.identityKey || identityKeyForUrl(record.url));
    await withLocalLock(async () => {
      const current = await options.loadSyncV2Store();
      await options.saveSyncV2Store(appendOperation(current, {
        type: "bookmark.delete",
        bookmarkId: record.id,
        identityKey: record.identityKey,
        kind: record.kind,
        url: record.url,
        title: record.title,
        parentId: record.parentId ?? null,
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

  async function loadLocalSnapshot(settings: SyncSettings): Promise<SyncV2LocalSnapshot> {
    await normalizeLocalBookmarkFolder(settings);
    const store = await options.loadSyncV2Store();
    return {
      bookmarks: await collectLocalBookmarkRecords(settings, store.state),
      bookmarkSnapshotMode: "tree",
      layout: await options.loadLauncherLayout(),
    };
  }

  async function normalizeLocalBookmarkFolder(settings: SyncSettings): Promise<void> {
    const root = (await options.bookmarks.getSubTree(settings.folderId))[0];
    const localRecords = bookmarkRecordsFromChromeNodes(root?.children || []);
    const canonicalRecords = canonicalizeBookmarkRecordsForChrome(localRecords);
    if (bookmarkNodeTreeSignature(root?.children || []) === bookmarkRecordsTreeSignature(canonicalRecords)) {
      return;
    }
    await applyBookmarkRecords(settings, canonicalRecords);
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

  async function collectLocalBookmarkRecords(settings: SyncSettings, state: SyncV2State): Promise<BookmarkRecord[]> {
    const now = Date.now();
    const root = (await options.bookmarks.getSubTree(settings.folderId))[0];
    const records: BookmarkRecord[] = [];

    function visit(nodes: BrowserBookmarkNode[] = [], parentId: string | null, folderPath: string[]): void {
      nodes.forEach((node, index) => {
        if (node.url) {
          const identityKey = bookmarkLocationIdentityKey(folderPath, node.url);
          const existing = activeBookmarksFromState(state).find((record) => record.kind !== "folder" && record.identityKey === identityKey);
          records.push({
            id: existing?.id || crypto.randomUUID(),
            kind: "bookmark",
            identityKey,
            parentId,
            index,
            url: node.url,
            title: node.title || node.url,
            createdAt: existing?.createdAt || node.dateAdded || now,
            updatedAt: bookmarkChanged(existing, node, parentId, index) ? now : existing?.updatedAt || now,
            deletedAt: null,
            sourceDeviceId: bookmarkChanged(existing, node, parentId, index) ? settings.deviceId : existing?.sourceDeviceId || settings.deviceId,
            iconDataUrl: existing?.iconDataUrl || null,
          });
          return;
        }

        const title = node.title || "Folder";
        const identityKey = folderPathIdentityKey([...folderPath, title]);
        const existing = activeBookmarksFromState(state).find((record) => record.kind === "folder" && record.identityKey === identityKey);
        const id = existing?.id || crypto.randomUUID();
        records.push({
          id,
          kind: "folder",
          identityKey,
          parentId,
          index,
          url: "",
          title,
          createdAt: existing?.createdAt || node.dateAdded || now,
          updatedAt: folderChanged(existing, title, parentId, index) ? now : existing?.updatedAt || now,
          deletedAt: null,
          sourceDeviceId: folderChanged(existing, title, parentId, index) ? settings.deviceId : existing?.sourceDeviceId || settings.deviceId,
          iconDataUrl: null,
        });
        visit(node.children || [], id, [...folderPath, title]);
      });
    }

    visit(root?.children || [], null, []);
    return records;
  }

  async function applyBookmarkRecords(settings: SyncSettings, records: BookmarkRecord[]): Promise<{ imported: number; removed: number }> {
    const canonicalRecords = canonicalizeBookmarkRecordsForChrome(records);
    const root = (await options.bookmarks.getSubTree(settings.folderId))[0];
    if (bookmarkNodeTreeSignature(root?.children || []) === bookmarkRecordsTreeSignature(canonicalRecords)) {
      return { imported: 0, removed: 0 };
    }
    const removed = root?.children ? countBookmarkTree(root.children) : 0;
    await removeFolderChildren(settings.folderId);

    let imported = 0;
    const childrenByParent = new Map<string | null, BookmarkRecord[]>();
    canonicalRecords
      .filter((record) => record.deletedAt == null)
      .forEach((record) => {
        const parentId = record.parentId || null;
        childrenByParent.set(parentId, [...(childrenByParent.get(parentId) || []), record]);
      });

    async function createChildren(parentRecordId: string | null, parentChromeId: string): Promise<void> {
      const children = (childrenByParent.get(parentRecordId) || []).sort(compareBookmarkPlacement);
      for (const record of children) {
        if (record.kind === "folder") {
          const folder = await options.bookmarks.create({ parentId: parentChromeId, title: record.title || "Folder" });
          imported += 1;
          await createChildren(record.id, folder.id);
          continue;
        }
        if (!record.url) continue;
        await options.bookmarks.create({ parentId: parentChromeId, title: record.title || record.url, url: record.url });
        imported += 1;
      }
    }

    await createChildren(null, settings.folderId);
    return { imported, removed };
  }

  function syncResultFromState(
    state: SyncV2State,
    previous: SyncV2State,
    settings: SyncSettings,
    sync: Pick<SyncV2Result, "stateChanged" | "launcherChanged" | "uploadedOperationCount" | "remoteOperationCount" | "pendingOperationCount" | "syncedAt">,
  ): BrowserSyncResult {
    const bookmarks = activeBookmarksFromState(state).filter((bookmark) => bookmark.kind !== "folder");
    const webApps = activeWebAppsFromState(state);
    const previousBookmarks = new Set(activeBookmarksFromState(previous).filter((bookmark) => bookmark.kind !== "folder").map((bookmark) => bookmark.id));
    const previousWebApps = new Set(activeWebAppsFromState(previous).map((app) => app.id));
    return {
      stateChanged: sync.stateChanged,
      launcherChanged: sync.launcherChanged,
      bookmarkCount: bookmarks.length,
      deletedBookmarkCount: Object.keys(state.bookmarkTombstones).length,
      importedBookmarkCount: bookmarks.filter((bookmark) => !previousBookmarks.has(bookmark.id)).length,
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
    return (await getFolderBookmarkNodesWithPaths(folderId)).map((item) => item.node);
  }

  async function getFolderBookmarkNodesWithPaths(folderId: string): Promise<Array<{ node: BrowserBookmarkNode; folderPath: string[] }>> {
    const root = (await options.bookmarks.getSubTree(folderId))[0];
    const items: Array<{ node: BrowserBookmarkNode; folderPath: string[] }> = [];
    const visit = (nodes: BrowserBookmarkNode[] = [], folderPath: string[]): void => {
      nodes.forEach((node) => {
        if (node.url) {
          items.push({ node, folderPath });
          return;
        }
        visit(node.children || [], [...folderPath, node.title || "Folder"]);
      });
    };
    visit(root?.children || [], []);
    return items;
  }

  async function removeLocalBookmarksByIdentity(folderId: string, identityKey: string): Promise<void> {
    const nodes = await getFolderBookmarkNodesWithPaths(folderId);
    const targetIsLocation = isBookmarkLocationIdentityKey(identityKey);
    for (const { node, folderPath } of nodes) {
      if (!node.url) continue;
      const candidate = targetIsLocation
        ? bookmarkLocationIdentityKey(folderPath, node.url)
        : identityKeyForUrl(node.url);
      if (candidate === identityKey) {
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

function bookmarkRecordsFromChromeNodes(nodes: BrowserBookmarkNode[], parentId: string | null = null, folderPath: string[] = []): BookmarkRecord[] {
  const now = Date.now();
  return nodes.flatMap((node, index) => {
    if (node.url) {
      return [{
        id: node.id,
        kind: "bookmark" as const,
        identityKey: bookmarkLocationIdentityKey(folderPath, node.url),
        parentId,
        index,
        url: node.url,
        title: node.title || node.url,
        createdAt: node.dateAdded || now,
        updatedAt: node.dateAdded || now,
        deletedAt: null,
        sourceDeviceId: "",
        iconDataUrl: null,
      }];
    }

    const title = node.title || "Folder";
    const nextPath = [...folderPath, title];
    const folder: BookmarkRecord = {
      id: node.id,
      kind: "folder",
      identityKey: folderPathIdentityKey(nextPath),
      parentId,
      index,
      url: "",
      title,
      createdAt: node.dateAdded || now,
      updatedAt: node.dateAdded || now,
      deletedAt: null,
      sourceDeviceId: "",
      iconDataUrl: null,
    };
    return [folder, ...bookmarkRecordsFromChromeNodes(node.children || [], node.id, nextPath)];
  });
}

function bookmarkNodeTreeSignature(nodes: BrowserBookmarkNode[]): string {
  return JSON.stringify(nodes.map((node) => node.url
    ? { kind: "bookmark", title: node.title || node.url, url: node.url }
    : { kind: "folder", title: node.title || "Folder", children: bookmarkNodeTreeSignatureValue(node.children || []) }));
}

function bookmarkNodeTreeSignatureValue(nodes: BrowserBookmarkNode[]): unknown[] {
  return nodes.map((node) => node.url
    ? { kind: "bookmark", title: node.title || node.url, url: node.url }
    : { kind: "folder", title: node.title || "Folder", children: bookmarkNodeTreeSignatureValue(node.children || []) });
}

function bookmarkRecordsTreeSignature(records: BookmarkRecord[]): string {
  const childrenByParent = new Map<string | null, BookmarkRecord[]>();
  records
    .filter((record) => record.deletedAt == null)
    .forEach((record) => {
      const parentId = record.parentId || null;
      childrenByParent.set(parentId, [...(childrenByParent.get(parentId) || []), record]);
    });
  const visit = (parentId: string | null): unknown[] => (childrenByParent.get(parentId) || [])
    .sort(compareBookmarkPlacement)
    .map((record) => record.kind === "folder"
      ? { kind: "folder", title: record.title || "Folder", children: visit(record.id) }
      : { kind: "bookmark", title: record.title || record.url, url: record.url });
  return JSON.stringify(visit(null));
}

function canonicalizeBookmarkRecordsForChrome(records: BookmarkRecord[]): BookmarkRecord[] {
  const childrenByParent = new Map<string | null, BookmarkRecord[]>();
  records
    .filter((record) => record.deletedAt == null)
    .forEach((record) => {
      const parentId = record.parentId || null;
      childrenByParent.set(parentId, [...(childrenByParent.get(parentId) || []), record]);
    });

  const result: BookmarkRecord[] = [];
  const foldersByPath = new Map<string, BookmarkRecord>();
  const seenBookmarkLocations = new Set<string>();

  const visit = (parentId: string | null, folderPath: string[], canonicalParentId: string | null): void => {
    const siblings = (childrenByParent.get(parentId) || []).sort(compareBookmarkPlacement);
    siblings.forEach((record) => {
      if (record.kind === "folder") {
        const title = record.title || "Folder";
        const nextPath = [...folderPath, title];
        const pathKey = folderPathKey(nextPath);
        let folder = foldersByPath.get(pathKey);
        if (!folder) {
          folder = {
            ...record,
            title,
            parentId: canonicalParentId,
            identityKey: folderPathIdentityKey(nextPath),
          };
          foldersByPath.set(pathKey, folder);
          result.push(folder);
        }
        visit(record.id, nextPath, folder.id);
        return;
      }

      if (!record.url) return;
      const locationKey = bookmarkLocationIdentityKey(folderPath, record.url);
      if (seenBookmarkLocations.has(locationKey)) return;
      seenBookmarkLocations.add(locationKey);
      result.push({
        ...record,
        parentId: canonicalParentId,
      });
    });
  };

  visit(null, [], null);
  return result;
}

function compareBookmarkPlacement(left: BookmarkRecord, right: BookmarkRecord): number {
  return (left.index ?? 0) - (right.index ?? 0) ||
    (left.kind === right.kind ? 0 : left.kind === "folder" ? -1 : 1) ||
    left.title.localeCompare(right.title) ||
    left.id.localeCompare(right.id);
}

function folderIdentityKey(parentId: string | null, title: string): string {
  return `folder:${parentId || "root"}:${title.trim().toLowerCase()}`;
}

function bookmarkLocationIdentityKey(folderPath: string[], url: string): string {
  return `bookmark-location:${folderPathKey(folderPath)}:${identityKeyForUrl(url)}`;
}

function folderPathIdentityKey(folderPath: string[]): string {
  return `folder-path:${folderPathKey(folderPath)}`;
}

function isBookmarkLocationIdentityKey(identityKey: string): boolean {
  return identityKey.startsWith("bookmark-location:");
}

function folderPathKey(folderPath: string[]): string {
  const parts = folderPath.map((title) => folderNameKey(title)).filter(Boolean);
  return parts.length ? parts.join("\u001f") : "root";
}

function folderNameKey(title: string): string {
  return (title.trim().toLowerCase() || "folder");
}

function bookmarkChanged(existing: BookmarkRecord | undefined, node: BrowserBookmarkNode, parentId: string | null, index: number): boolean {
  return !existing ||
    existing.title !== (node.title || node.url || "") ||
    existing.parentId !== parentId ||
    existing.index !== index;
}

function folderChanged(existing: BookmarkRecord | undefined, title: string, parentId: string | null, index: number): boolean {
  return !existing ||
    existing.title !== title ||
    existing.parentId !== parentId ||
    existing.index !== index;
}
