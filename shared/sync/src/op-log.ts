import type { BookmarkRecord, WebAppRecord } from "./index";
import { WebDavClient, WebDavConflictError, type WebDavSettings } from "./webdav";

export const SYNC_V2_SCHEMA_VERSION = 2;
const BOOKMARKS_FILE = "bookmarks.json";
const WEBAPPS_FILE = "webapps.json";
const LAUNCHER_FILE = "launcher.json";
const MANIFEST_FILE = "manifest.json";

export type Revision = {
  counter: number;
  deviceId: string;
};

export type SyncV2Bookmark = BookmarkRecord & {
  rev: Revision;
};

export type SyncV2App = WebAppRecord & {
  identityKey?: string;
  rev: Revision;
};

export type SyncV2Tombstone = {
  deletedAt: string;
  rev: Revision;
};

export type SyncV2LayoutItem = {
  id: string;
  kind: "app" | "folder" | "system";
  appId?: string;
  title?: string;
  container: string | null;
  order: number;
  rev: Revision;
};

export type SyncV2State = {
  schemaVersion: 2;
  bookmarks: Record<string, SyncV2Bookmark>;
  bookmarkTombstones: Record<string, SyncV2Tombstone>;
  apps: Record<string, SyncV2App>;
  appTombstones: Record<string, SyncV2Tombstone>;
  layout: {
    items: Record<string, SyncV2LayoutItem>;
    itemTombstones: Record<string, SyncV2Tombstone>;
  };
};

export type SyncV2Operation =
  | SyncV2BaseOperation<"bookmark.upsert"> & { bookmark: BookmarkRecord }
  | SyncV2BaseOperation<"bookmark.delete"> & { url: string }
  | SyncV2BaseOperation<"app.upsert"> & { app: Partial<WebAppRecord> & { id: string; name: string; startUrl: string; identityKey?: string } }
  | SyncV2BaseOperation<"app.delete"> & { appId: string }
  | SyncV2BaseOperation<"layout.place"> & { item: Omit<SyncV2LayoutItem, "rev"> }
  | SyncV2BaseOperation<"layout.hide"> & { itemId: string }
  | SyncV2BaseOperation<"layout.deleteItem"> & { itemId: string };

type SyncV2BaseOperation<T extends string> = {
  schemaVersion: 2;
  opId: string;
  deviceId: string;
  counter: number;
  createdAt: number;
  type: T;
};

type SyncV2OperationInput =
  | { type: "bookmark.upsert"; bookmark: BookmarkRecord }
  | { type: "bookmark.delete"; url: string }
  | { type: "app.upsert"; app: Partial<WebAppRecord> & { id: string; name: string; startUrl: string; identityKey?: string } }
  | { type: "app.delete"; appId: string }
  | { type: "layout.place"; item: Omit<SyncV2LayoutItem, "rev"> }
  | { type: "layout.hide"; itemId: string }
  | { type: "layout.deleteItem"; itemId: string };

export type SyncV2Store = {
  schemaVersion: 2;
  deviceId: string;
  counter: number;
  state: SyncV2State;
  outbox: SyncV2Operation[];
};

export type LauncherLayoutLike = {
  version?: number;
  cells?: Array<{ id: string; page?: number; row?: number; column?: number; index?: number }>;
  dock?: string[];
  folders?: Array<{ id: string; title?: string; childIds?: string[] }>;
  gridColumns?: number;
  updatedAt?: number;
};

export type SyncV2LocalSnapshot = {
  bookmarks?: BookmarkRecord[];
  webApps?: WebAppRecord[];
  layout?: LauncherLayoutLike | null;
};

export type SyncV2Result = {
  state: SyncV2State;
  uploadedOperationCount: number;
  remoteOperationCount: number;
  pendingOperationCount: number;
  syncedAt: number;
};

type RemoteStateFile = {
  path: string;
  exists: boolean;
  etag: string | null;
  canonical: string;
};

type RemoteStateFiles = {
  state: SyncV2State;
  files: Record<string, RemoteStateFile>;
  manifest: unknown;
};

type WebAppInput = Partial<WebAppRecord> & {
  id?: string;
  name: string;
  startUrl: string;
  identityKey?: string;
};

const cloneJson = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;

export function createEmptyState(): SyncV2State {
  return {
    schemaVersion: SYNC_V2_SCHEMA_VERSION,
    bookmarks: {},
    bookmarkTombstones: {},
    apps: {},
    appTombstones: {},
    layout: {
      items: {},
      itemTombstones: {},
    },
  };
}

export function createEmptyStore(deviceId = ""): SyncV2Store {
  return {
    schemaVersion: SYNC_V2_SCHEMA_VERSION,
    deviceId,
    counter: 0,
    state: createEmptyState(),
    outbox: [],
  };
}

export function ensureStore(value: unknown, deviceId: string): SyncV2Store {
  if (!value || typeof value !== "object" || (value as Partial<SyncV2Store>).schemaVersion !== SYNC_V2_SCHEMA_VERSION) {
    return createEmptyStore(deviceId);
  }
  const store = value as Partial<SyncV2Store>;
  const state = ensureState(store.state);
  const outbox = Array.isArray(store.outbox)
    ? store.outbox.filter(isOperation)
    : [];
  return {
    schemaVersion: SYNC_V2_SCHEMA_VERSION,
    deviceId: store.deviceId || deviceId,
    counter: Math.max(
      Number.isSafeInteger(store.counter) ? Number(store.counter) : 0,
      maxStateCounter(state),
      maxOperationCounter(outbox),
    ),
    state,
    outbox,
  };
}

export function ensureState(value: unknown): SyncV2State {
  if (!value || typeof value !== "object" || (value as Partial<SyncV2State>).schemaVersion !== SYNC_V2_SCHEMA_VERSION) {
    return createEmptyState();
  }
  const state = value as Partial<SyncV2State>;
  return {
    schemaVersion: SYNC_V2_SCHEMA_VERSION,
    bookmarks: state.bookmarks || {},
    bookmarkTombstones: state.bookmarkTombstones || {},
    apps: state.apps || {},
    appTombstones: state.appTombstones || {},
    layout: {
      items: state.layout?.items || {},
      itemTombstones: state.layout?.itemTombstones || {},
    },
  };
}

export function canonicalJson(value: unknown): string {
  return JSON.stringify(canonicalize(value));
}

function canonicalize(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value && typeof value === "object") {
    const result: Record<string, unknown> = {};
    Object.keys(value as Record<string, unknown>).sort().forEach((key) => {
      result[key] = canonicalize((value as Record<string, unknown>)[key]);
    });
    return result;
  }
  return value;
}

export function compareRevision(a: Partial<Revision> = {}, b: Partial<Revision> = {}): number {
  const ac = Number.isSafeInteger(a.counter) ? Number(a.counter) : 0;
  const bc = Number.isSafeInteger(b.counter) ? Number(b.counter) : 0;
  if (ac !== bc) return ac < bc ? -1 : 1;
  const ad = (a.deviceId || "").toLowerCase();
  const bd = (b.deviceId || "").toLowerCase();
  return ad === bd ? 0 : (ad < bd ? -1 : 1);
}

export function reduceOperations(operations: SyncV2Operation[]): SyncV2State {
  const state = createEmptyState();
  uniqueOperations(operations).sort(compareOperations).forEach((operation) => {
    applyOperationInPlace(state, operation);
  });
  return state;
}

export function appendOperation(store: SyncV2Store, operation: SyncV2OperationInput): SyncV2Store {
  const next = cloneJson(store);
  next.counter += 1;
  const fullOperation = {
    schemaVersion: SYNC_V2_SCHEMA_VERSION,
    opId: crypto.randomUUID(),
    deviceId: next.deviceId,
    counter: next.counter,
    createdAt: Date.now(),
    ...operation,
  } as SyncV2Operation;
  applyOperationInPlace(next.state, fullOperation);
  next.outbox.push(fullOperation);
  return next;
}

export function appendWebAppUpsert(store: SyncV2Store, input: WebAppInput, placement?: { container: string | null; order: number }): { store: SyncV2Store; app: SyncV2App } {
  const identityKey = input.identityKey || identityKeyForUrl(input.startUrl);
  const existing = input.id
    ? store.state.apps[input.id]
    : Object.values(store.state.apps).find((item) => item.identityKey === identityKey);
  const id = existing?.id || input.id?.trim() || crypto.randomUUID();
  if (store.state.appTombstones[id]) throw new Error("Deleted WebApp UUID cannot be reused.");
  const now = Date.now();
  const app: WebAppRecord & { identityKey?: string } = {
    id,
    identityKey,
    name: input.name.trim() || input.startUrl,
    startUrl: input.startUrl,
    scopeUrl: input.scopeUrl || existing?.scopeUrl || scopeFor(input.startUrl),
    themeColor: input.themeColor ?? existing?.themeColor ?? 0xff126d6a,
    displayMode: input.displayMode || existing?.displayMode || "standalone",
    createdAt: existing?.createdAt || input.createdAt || now,
    lastOpenedAt: input.lastOpenedAt || existing?.lastOpenedAt || now,
    updatedAt: now,
    deletedAt: null,
    sourceDeviceId: store.deviceId,
    iconDataUrl: input.iconDataUrl ?? existing?.iconDataUrl ?? null,
    iconSource: input.iconSource || existing?.iconSource || (input.iconDataUrl ? "custom" : "title"),
  };
  let next = appendOperation(store, { type: "app.upsert", app });
  const itemId = `app:${id}`;
  if (placement && !next.state.layout.items[itemId] && !next.state.layout.itemTombstones[itemId]) {
    next = appendOperation(next, {
      type: "layout.place",
      item: {
        id: itemId,
        kind: "app",
        appId: id,
        container: placement.container,
        order: placement.order,
      },
    });
  }
  return { store: next, app: next.state.apps[id] };
}

export function appendWebAppDelete(store: SyncV2Store, appId: string): SyncV2Store {
  const id = appId.trim();
  if (!id) return store;
  let next = appendOperation(store, { type: "app.delete", appId: id });
  next = appendOperation(next, { type: "layout.deleteItem", itemId: `app:${id}` });
  return next;
}

export function appendLocalSnapshotOperations(store: SyncV2Store, snapshot: SyncV2LocalSnapshot): SyncV2Store {
  let next = cloneJson(store);
  if (snapshot.bookmarks) next = appendBookmarkSnapshotOperations(next, snapshot.bookmarks);
  if (snapshot.webApps) next = appendWebAppSnapshotOperations(next, snapshot.webApps);
  if (snapshot.layout) next = appendLayoutSnapshotOperations(next, snapshot.layout);
  return next;
}

function appendBookmarkSnapshotOperations(store: SyncV2Store, bookmarks: BookmarkRecord[]): SyncV2Store {
  let next = store;
  const local = new Map<string, BookmarkRecord>();
  bookmarks.forEach((bookmark) => {
    if (bookmark.deletedAt != null) return;
    const key = normalizeBookmarkKey(bookmark.url);
    if (key) local.set(key, { ...bookmark, url: key });
  });
  activeBookmarksFromState(store.state).forEach((bookmark) => {
    if (!local.has(bookmark.url)) {
      next = appendOperation(next, { type: "bookmark.delete", url: bookmark.url });
    }
  });
  local.forEach((bookmark, url) => {
    const current = next.state.bookmarks[url];
    if (!current || bookmarkFieldsChanged(current, bookmark)) {
      next = appendOperation(next, { type: "bookmark.upsert", bookmark });
    }
  });
  return next;
}

function appendWebAppSnapshotOperations(store: SyncV2Store, webApps: WebAppRecord[]): SyncV2Store {
  let next = store;
  const local = new Map<string, WebAppRecord>();
  webApps.forEach((app) => {
    if (app.deletedAt != null) return;
    const id = app.id?.trim();
    if (id && !next.state.appTombstones[id]) local.set(id, { ...app, id });
  });
  activeWebAppsFromState(store.state).forEach((app) => {
    if (!local.has(app.id)) next = appendWebAppDelete(next, app.id);
  });
  local.forEach((app, id) => {
    const current = next.state.apps[id];
    if (!current || appFieldsChanged(current, app)) {
      next = appendOperation(next, {
        type: "app.upsert",
        app: {
          ...app,
          identityKey: current?.identityKey || identityKeyForUrl(app.startUrl),
          sourceDeviceId: next.deviceId,
          deletedAt: null,
        },
      });
    }
  });
  return next;
}

export function appendLayoutSnapshotOperations(store: SyncV2Store, layout: LauncherLayoutLike): SyncV2Store {
  let next = store;
  const placements = layoutPlacements(layout);
  placements.forEach((item) => {
    const current = next.state.layout.items[item.id];
    if (!current || layoutItemChanged(current, item)) {
      next = appendOperation(next, { type: "layout.place", item });
    }
  });
  Object.values(next.state.layout.items).forEach((item) => {
    if (item.container !== null && !placements.has(item.id) && !next.state.layout.itemTombstones[item.id]) {
      next = appendOperation(next, { type: "layout.hide", itemId: item.id });
    }
  });
  return next;
}

export function activeBookmarksFromState(state: SyncV2State): BookmarkRecord[] {
  return Object.values(ensureState(state).bookmarks)
    .sort((left, right) => left.title.localeCompare(right.title))
    .map(({ rev: _rev, ...bookmark }) => bookmark as BookmarkRecord);
}

export function activeWebAppsFromState(state: SyncV2State): WebAppRecord[] {
  return Object.values(ensureState(state).apps)
    .sort((left, right) => right.updatedAt - left.updatedAt || left.name.localeCompare(right.name))
    .map(({ rev: _rev, identityKey: _identityKey, ...app }) => app as WebAppRecord);
}

export function layoutFromState(state: SyncV2State, fallbackGridColumns = 4): LauncherLayoutLike {
  const clean = ensureState(state);
  const folderItems = Object.values(clean.layout.items).filter((item) => item.kind === "folder" && item.container !== null);
  const folderIdSet = new Set(folderItems.map((item) => item.id));
  const validItems = Object.values(clean.layout.items).filter((item) => {
    if (item.container === null) return false;
    if (item.kind === "app" && (!item.appId || !clean.apps[item.appId])) return false;
    if (item.container?.startsWith("folder:") && !folderIdSet.has(item.container)) return false;
    return true;
  });
  const desktop = validItems.filter((item) => item.container === "desktop:0").sort(compareLayoutItems);
  const dock = validItems
    .filter((item) => item.container === "dock")
    .sort(compareLayoutItems)
    .slice(0, 4)
    .map((item) => item.id);
  const cells = desktop.map((item, index) => ({
    id: item.id,
    page: 0,
    row: Math.floor(index / fallbackGridColumns),
    column: index % fallbackGridColumns,
    index,
  }));
  const folders = folderItems.map((folder) => ({
    id: folder.id,
    title: folder.title || "Folder",
    childIds: validItems
      .filter((item) => item.container === folder.id)
      .sort(compareLayoutItems)
      .map((item) => item.id),
  })).filter((folder) => folder.childIds.length > 0 || cells.some((cell) => cell.id === folder.id) || dock.includes(folder.id));
  return {
    version: 4,
    cells,
    dock,
    folders,
    gridColumns: fallbackGridColumns,
    updatedAt: Date.now(),
  };
}

export async function syncV2(options: {
  settings: WebDavSettings & { deviceId: string };
  loadStore: () => Promise<SyncV2Store>;
  saveStore: (store: SyncV2Store) => Promise<void>;
  loadLocalSnapshot?: () => Promise<SyncV2LocalSnapshot>;
  applyState?: (state: SyncV2State) => Promise<void>;
  withLocalLock: <T>(operation: () => Promise<T>) => Promise<T>;
}): Promise<SyncV2Result> {
  const client = new WebDavClient(options.settings);
  await client.ensureCollections();

  for (let attempt = 0; attempt < 8; attempt += 1) {
    const remote = await readRemoteStateFiles(client);
    let mergedState = createEmptyState();
    let uploadedOperationCount = 0;
    let pendingOperationCount = 0;

    await options.withLocalLock(async () => {
      let store = ensureStore(await options.loadStore(), options.settings.deviceId);
      if (options.loadLocalSnapshot) {
        store = appendLocalSnapshotOperations(store, await options.loadLocalSnapshot());
      }
      uploadedOperationCount = store.outbox.length;
      mergedState = mergeState(store.state, remote.state);
      store = {
        ...store,
        counter: Math.max(store.counter, maxStateCounter(mergedState)),
        state: mergedState,
      };
      pendingOperationCount = store.outbox.length;
      await options.saveStore(store);
      if (options.applyState) await options.applyState(mergedState);
    });

    const desiredFiles = stateToRemoteFiles(mergedState);
    const changedFiles = changedRemoteFiles(remote.files, desiredFiles);

    if (changedFiles.length === 0) {
      await options.withLocalLock(async () => {
        const latest = ensureStore(await options.loadStore(), options.settings.deviceId);
        if (canonicalJson(latest.state) !== canonicalJson(mergedState)) {
          pendingOperationCount = latest.outbox.length;
          return;
        }
        pendingOperationCount = 0;
        await options.saveStore({ ...latest, outbox: [] });
      });
      if (pendingOperationCount > 0) continue;
      return {
        state: mergedState,
        uploadedOperationCount,
        remoteOperationCount: countRemoteRecords(remote.state),
        pendingOperationCount,
        syncedAt: Date.now(),
      };
    }

    try {
      await writeRemoteStateFiles(client, remote.files, desiredFiles, changedFiles);
    } catch (error) {
      if (error instanceof WebDavConflictError) continue;
      throw error;
    }

    await options.withLocalLock(async () => {
      const latest = ensureStore(await options.loadStore(), options.settings.deviceId);
      if (canonicalJson(latest.state) !== canonicalJson(mergedState)) {
        pendingOperationCount = latest.outbox.length;
        return;
      }
      pendingOperationCount = 0;
      await options.saveStore({ ...latest, outbox: [] });
    });

    if (pendingOperationCount > 0) continue;

    return {
      state: mergedState,
      uploadedOperationCount,
      remoteOperationCount: countRemoteRecords(remote.state),
      pendingOperationCount,
      syncedAt: Date.now(),
    };
  }

  throw new Error("WebDAV sync conflict retry limit reached.");
}

function mergeState(leftState: SyncV2State, rightState: SyncV2State): SyncV2State {
  const left = ensureState(leftState);
  const right = ensureState(rightState);
  const bookmarkTombstones = mergeLwwMap(left.bookmarkTombstones, right.bookmarkTombstones);
  const bookmarks = mergeLwwMap(left.bookmarks, right.bookmarks);
  Object.keys(bookmarkTombstones).forEach((url) => {
    delete bookmarks[url];
  });

  const appTombstones = mergeLwwMap(left.appTombstones, right.appTombstones);
  const apps = mergeLwwMap(left.apps, right.apps);
  Object.keys(appTombstones).forEach((appId) => {
    delete apps[appId];
  });

  const itemTombstones = mergeLwwMap(left.layout.itemTombstones, right.layout.itemTombstones);
  const items = mergeLwwMap(left.layout.items, right.layout.items);
  Object.keys(itemTombstones).forEach((itemId) => {
    delete items[itemId];
  });

  return {
    schemaVersion: SYNC_V2_SCHEMA_VERSION,
    bookmarks,
    bookmarkTombstones,
    apps,
    appTombstones,
    layout: {
      items,
      itemTombstones,
    },
  };
}

function mergeLwwMap<T extends { rev?: Revision }>(left: Record<string, T> = {}, right: Record<string, T> = {}): Record<string, T> {
  const result: Record<string, T> = {};
  new Set([...Object.keys(left), ...Object.keys(right)]).forEach((key) => {
    const value = pickLww(left[key], right[key]);
    if (value) result[key] = value;
  });
  return result;
}

function pickLww<T extends { rev?: Revision }>(left?: T, right?: T): T | undefined {
  if (!left) return right ? cloneJson(right) : undefined;
  if (!right) return cloneJson(left);
  const order = compareRevision(left.rev, right.rev);
  if (order > 0) return cloneJson(left);
  if (order < 0) return cloneJson(right);
  return canonicalJson(left) >= canonicalJson(right) ? cloneJson(left) : cloneJson(right);
}

async function readRemoteStateFiles(client: WebDavClient): Promise<RemoteStateFiles> {
  const [bookmarks, webapps, launcher, manifest] = await Promise.all([
    readRemoteJsonFile(client, BOOKMARKS_FILE),
    readRemoteJsonFile(client, WEBAPPS_FILE),
    readRemoteJsonFile(client, LAUNCHER_FILE),
    readRemoteJsonFile(client, MANIFEST_FILE),
  ]);
  const state = createEmptyState();
  mergeBookmarkFileIntoState(state, bookmarks.data);
  mergeWebAppFileIntoState(state, webapps.data);
  mergeLauncherFileIntoState(state, launcher.data);
  return {
    state: ensureState(state),
    manifest: manifest.data,
    files: {
      [BOOKMARKS_FILE]: bookmarks.file,
      [WEBAPPS_FILE]: webapps.file,
      [LAUNCHER_FILE]: launcher.file,
      [MANIFEST_FILE]: manifest.file,
    },
  };
}

async function readRemoteJsonFile(client: WebDavClient, path: string): Promise<{ data: unknown; file: RemoteStateFile }> {
  const remote = await client.getJson<unknown>(path);
  return {
    data: remote?.data,
    file: {
      path,
      exists: !!remote,
      etag: remote?.etag || null,
      canonical: remote ? canonicalJson(remote.data) : "",
    },
  };
}

function stateToRemoteFiles(state: SyncV2State): Record<string, unknown> {
  const clean = ensureState(state);
  return {
    [BOOKMARKS_FILE]: {
      schemaVersion: SYNC_V2_SCHEMA_VERSION,
      bookmarks: clean.bookmarks,
      bookmarkTombstones: clean.bookmarkTombstones,
    },
    [WEBAPPS_FILE]: {
      schemaVersion: SYNC_V2_SCHEMA_VERSION,
      apps: clean.apps,
      appTombstones: clean.appTombstones,
    },
    [LAUNCHER_FILE]: {
      schemaVersion: SYNC_V2_SCHEMA_VERSION,
      layout: clean.layout,
    },
    [MANIFEST_FILE]: {
      schemaVersion: SYNC_V2_SCHEMA_VERSION,
      updatedAt: maxStateCounter(clean),
      stateCounter: maxStateCounter(clean),
      files: {
        bookmarks: BOOKMARKS_FILE,
        webApps: WEBAPPS_FILE,
        launcher: LAUNCHER_FILE,
        manifest: MANIFEST_FILE,
      },
      counts: {
        bookmarks: Object.keys(clean.bookmarks).length,
        bookmarkTombstones: Object.keys(clean.bookmarkTombstones).length,
        webApps: Object.keys(clean.apps).length,
        appTombstones: Object.keys(clean.appTombstones).length,
        layoutItems: Object.keys(clean.layout.items).length,
        layoutItemTombstones: Object.keys(clean.layout.itemTombstones).length,
      },
    },
  };
}

function changedRemoteFiles(remoteFiles: Record<string, RemoteStateFile>, desiredFiles: Record<string, unknown>): string[] {
  return Object.keys(desiredFiles).filter((path) => remoteFiles[path]?.canonical !== canonicalJson(desiredFiles[path]));
}

async function writeRemoteStateFiles(
  client: WebDavClient,
  remoteFiles: Record<string, RemoteStateFile>,
  desiredFiles: Record<string, unknown>,
  changedFiles: string[],
): Promise<void> {
  for (const path of changedFiles) {
    const remote = remoteFiles[path];
    await client.putJson(path, desiredFiles[path], remote?.etag || null, {
      ifNoneMatch: !remote?.exists,
      bodyText: canonicalJson(desiredFiles[path]),
    });
  }
}

function mergeBookmarkFileIntoState(state: SyncV2State, value: unknown): void {
  if (!value) return;
  if (Array.isArray(value)) {
    legacyBookmarkRecords(value).forEach((record) => applyLegacyBookmarkRecord(state, record));
    return;
  }
  if (typeof value !== "object") return;
  const root = value as Record<string, unknown>;
  mergeLwwMapInto(state.bookmarks, readRecordMap<SyncV2Bookmark>(root.bookmarks));
  mergeLwwMapInto(state.bookmarkTombstones, readRecordMap<SyncV2Tombstone>(root.bookmarkTombstones || root.tombstones));
  legacyBookmarkRecords(root.items || root.bookmarks).forEach((record) => applyLegacyBookmarkRecord(state, record));
}

function mergeWebAppFileIntoState(state: SyncV2State, value: unknown): void {
  if (!value) return;
  if (Array.isArray(value)) {
    legacyWebAppRecords(value).forEach((record) => applyLegacyWebAppRecord(state, record));
    return;
  }
  if (typeof value !== "object") return;
  const root = value as Record<string, unknown>;
  mergeLwwMapInto(state.apps, readRecordMap<SyncV2App>(root.apps || root.webApps));
  mergeLwwMapInto(state.appTombstones, readRecordMap<SyncV2Tombstone>(root.appTombstones || root.tombstones));
  legacyWebAppRecords(root.items || root.webApps).forEach((record) => applyLegacyWebAppRecord(state, record));
}

function mergeLauncherFileIntoState(state: SyncV2State, value: unknown): void {
  if (!value || typeof value !== "object") return;
  const root = value as Record<string, unknown>;
  const layout = root.layout;
  if (layout && typeof layout === "object" && !Array.isArray(layout)) {
    const clean = layout as Partial<SyncV2State["layout"]> & { tombstones?: unknown };
    mergeLwwMapInto(state.layout.items, readRecordMap<SyncV2LayoutItem>(clean.items));
    mergeLwwMapInto(state.layout.itemTombstones, readRecordMap<SyncV2Tombstone>(clean.itemTombstones || clean.tombstones));
    return;
  }
  const rev = revisionFromLegacy(root);
  layoutPlacements(root as LauncherLayoutLike).forEach((item) => {
    state.layout.items[item.id] = { ...item, rev };
  });
}

function mergeLwwMapInto<T extends { rev?: Revision }>(target: Record<string, T>, source: Record<string, T>): void {
  const merged = mergeLwwMap(target, source);
  Object.keys(target).forEach((key) => {
    delete target[key];
  });
  Object.assign(target, merged);
}

function readRecordMap<T extends { rev?: Revision }>(value: unknown): Record<string, T> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const result: Record<string, T> = {};
  Object.entries(value as Record<string, unknown>).forEach(([key, raw]) => {
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return;
    const record = raw as T;
    if (!record.rev) return;
    result[key] = cloneJson(record);
  });
  return result;
}

function legacyBookmarkRecords(value: unknown): BookmarkRecord[] {
  if (!Array.isArray(value)) return [];
  return value
    .filter((item): item is Partial<BookmarkRecord> => !!item && typeof item === "object")
    .map((item) => ({
      url: String(item.url || "").trim(),
      title: String(item.title || item.url || ""),
      createdAt: Number(item.createdAt) || Number(item.updatedAt) || 0,
      updatedAt: Number(item.updatedAt) || Number(item.createdAt) || 0,
      deletedAt: item.deletedAt == null ? null : Number(item.deletedAt) || Date.parse(String(item.deletedAt)) || 0,
      sourceDeviceId: String(item.sourceDeviceId || "legacy"),
      iconDataUrl: typeof item.iconDataUrl === "string" ? item.iconDataUrl : null,
    }))
    .filter((item) => !!item.url);
}

function legacyWebAppRecords(value: unknown): WebAppRecord[] {
  if (!Array.isArray(value)) return [];
  return value
    .filter((item): item is Partial<WebAppRecord> => !!item && typeof item === "object")
    .map((item) => ({
      id: String(item.id || "").trim(),
      name: String(item.name || item.startUrl || ""),
      startUrl: String(item.startUrl || ""),
      scopeUrl: String(item.scopeUrl || item.startUrl || ""),
      themeColor: Number(item.themeColor) || 0xff126d6a,
      displayMode: String(item.displayMode || "standalone"),
      createdAt: Number(item.createdAt) || Number(item.updatedAt) || 0,
      lastOpenedAt: Number(item.lastOpenedAt) || Number(item.updatedAt) || Number(item.createdAt) || 0,
      updatedAt: Number(item.updatedAt) || Number(item.createdAt) || 0,
      deletedAt: item.deletedAt == null ? null : Number(item.deletedAt) || Date.parse(String(item.deletedAt)) || 0,
      sourceDeviceId: String(item.sourceDeviceId || "legacy"),
      iconDataUrl: typeof item.iconDataUrl === "string" ? item.iconDataUrl : null,
      iconSource: item.iconSource === "custom" || item.iconSource === "site" || item.iconSource === "title" ? item.iconSource : "title",
    }))
    .filter((item) => !!item.id && !!item.startUrl);
}

function applyLegacyBookmarkRecord(state: SyncV2State, record: BookmarkRecord): void {
  const url = normalizeBookmarkKey(record.url);
  if (!url) return;
  const rev = revisionFromLegacy(record);
  if (record.deletedAt != null) {
    state.bookmarkTombstones[url] = { deletedAt: new Date(record.deletedAt).toISOString(), rev };
    delete state.bookmarks[url];
    return;
  }
  state.bookmarks[url] = { ...record, url, deletedAt: null, rev };
}

function applyLegacyWebAppRecord(state: SyncV2State, record: WebAppRecord): void {
  const id = record.id.trim();
  if (!id) return;
  const rev = revisionFromLegacy(record);
  if (record.deletedAt != null) {
    state.appTombstones[id] = { deletedAt: new Date(record.deletedAt).toISOString(), rev };
    delete state.apps[id];
    delete state.layout.items[`app:${id}`];
    return;
  }
  state.apps[id] = { ...record, id, deletedAt: null, identityKey: identityKeyForUrl(record.startUrl), rev };
}

function revisionFromLegacy(value: Record<string, unknown> | BookmarkRecord | WebAppRecord): Revision {
  const updatedAt = Number((value as { updatedAt?: number }).updatedAt) ||
    Number((value as { deletedAt?: number | null }).deletedAt) ||
    Number((value as { createdAt?: number }).createdAt) ||
    0;
  const deviceId = String((value as { sourceDeviceId?: string }).sourceDeviceId || "legacy");
  return {
    counter: Math.max(0, Math.floor(updatedAt)),
    deviceId,
  };
}

function countRemoteRecords(state: SyncV2State): number {
  const clean = ensureState(state);
  return Object.keys(clean.bookmarks).length +
    Object.keys(clean.bookmarkTombstones).length +
    Object.keys(clean.apps).length +
    Object.keys(clean.appTombstones).length +
    Object.keys(clean.layout.items).length +
    Object.keys(clean.layout.itemTombstones).length;
}

function applyOperationInPlace(state: SyncV2State, operation: SyncV2Operation): void {
  const rev = operationRevision(operation);
  if (operation.type === "bookmark.upsert") {
    const url = normalizeBookmarkKey(operation.bookmark.url);
    if (!url) return;
    const tombstone = state.bookmarkTombstones[url];
    if (tombstone && compareRevision(tombstone.rev, rev) >= 0) return;
    const current = state.bookmarks[url];
    if (current && compareRevision(current.rev, rev) > 0) return;
    state.bookmarks[url] = {
      ...operation.bookmark,
      url,
      updatedAt: operation.bookmark.updatedAt || operation.createdAt,
      deletedAt: null,
      sourceDeviceId: operation.bookmark.sourceDeviceId || operation.deviceId,
      rev,
    };
    delete state.bookmarkTombstones[url];
    return;
  }
  if (operation.type === "bookmark.delete") {
    const url = normalizeBookmarkKey(operation.url);
    if (!url) return;
    const current = state.bookmarks[url];
    const tombstone = state.bookmarkTombstones[url];
    if (current && compareRevision(current.rev, rev) > 0) return;
    if (tombstone && compareRevision(tombstone.rev, rev) >= 0) return;
    delete state.bookmarks[url];
    state.bookmarkTombstones[url] = { deletedAt: new Date(operation.createdAt).toISOString(), rev };
    return;
  }
  if (operation.type === "app.upsert") {
    const id = operation.app.id.trim();
    if (!id || state.appTombstones[id]) return;
    const current = state.apps[id];
    if (current && compareRevision(current.rev, rev) > 0) return;
    state.apps[id] = {
      id,
      identityKey: operation.app.identityKey || current?.identityKey || identityKeyForUrl(operation.app.startUrl),
      name: operation.app.name.trim() || operation.app.startUrl,
      startUrl: operation.app.startUrl,
      scopeUrl: operation.app.scopeUrl || current?.scopeUrl || scopeFor(operation.app.startUrl),
      themeColor: operation.app.themeColor ?? current?.themeColor ?? 0xff126d6a,
      displayMode: operation.app.displayMode || current?.displayMode || "standalone",
      createdAt: operation.app.createdAt || current?.createdAt || operation.createdAt,
      lastOpenedAt: operation.app.lastOpenedAt || current?.lastOpenedAt || operation.createdAt,
      updatedAt: operation.app.updatedAt || operation.createdAt,
      deletedAt: null,
      sourceDeviceId: operation.app.sourceDeviceId || operation.deviceId,
      iconDataUrl: operation.app.iconDataUrl ?? current?.iconDataUrl ?? null,
      iconSource: operation.app.iconSource || current?.iconSource || (operation.app.iconDataUrl ? "custom" : "title"),
      rev,
    };
    return;
  }
  if (operation.type === "app.delete") {
    const id = operation.appId.trim();
    if (!id) return;
    if (!state.appTombstones[id] || compareRevision(state.appTombstones[id].rev, rev) < 0) {
      state.appTombstones[id] = { deletedAt: new Date(operation.createdAt).toISOString(), rev };
    }
    delete state.apps[id];
    return;
  }
  if (operation.type === "layout.place") {
    const item = operation.item;
    if (!item.id || state.layout.itemTombstones[item.id]) return;
    const current = state.layout.items[item.id];
    if (current && compareRevision(current.rev, rev) > 0) return;
    state.layout.items[item.id] = { ...item, rev };
    return;
  }
  if (operation.type === "layout.hide") {
    const itemId = operation.itemId.trim();
    if (!itemId || state.layout.itemTombstones[itemId]) return;
    const current = state.layout.items[itemId];
    if (current && compareRevision(current.rev, rev) > 0) return;
    state.layout.items[itemId] = {
      ...(current || { id: itemId, kind: layoutItemKind(itemId), ...(itemId.startsWith("app:") ? { appId: itemId.slice(4) } : {}), order: 0 }),
      container: null,
      rev,
    };
    return;
  }
  if (operation.type === "layout.deleteItem") {
    const itemId = operation.itemId.trim();
    if (!itemId) return;
    if (!state.layout.itemTombstones[itemId] || compareRevision(state.layout.itemTombstones[itemId].rev, rev) < 0) {
      state.layout.itemTombstones[itemId] = { deletedAt: new Date(operation.createdAt).toISOString(), rev };
    }
    delete state.layout.items[itemId];
  }
}

function operationRevision(operation: SyncV2Operation): Revision {
  return { counter: operation.counter, deviceId: operation.deviceId };
}

function compareOperations(left: SyncV2Operation, right: SyncV2Operation): number {
  return left.counter - right.counter ||
    left.deviceId.localeCompare(right.deviceId) ||
    left.opId.localeCompare(right.opId);
}

function uniqueOperations(operations: SyncV2Operation[]): SyncV2Operation[] {
  const result = new Map<string, SyncV2Operation>();
  operations.filter(isOperation).forEach((operation) => {
    result.set(operation.opId, operation);
  });
  return [...result.values()];
}

function isOperation(value: unknown): value is SyncV2Operation {
  if (!value || typeof value !== "object") return false;
  const operation = value as Partial<SyncV2Operation>;
  return operation.schemaVersion === SYNC_V2_SCHEMA_VERSION &&
    typeof operation.opId === "string" &&
    typeof operation.deviceId === "string" &&
    Number.isSafeInteger(operation.counter) &&
    Number.isSafeInteger(operation.createdAt) &&
    typeof operation.type === "string";
}

function maxOperationCounter(operations: SyncV2Operation[]): number {
  return operations.reduce((max, operation) => Math.max(max, operation.counter || 0), 0);
}

function maxStateCounter(state: SyncV2State): number {
  let max = 0;
  const visit = (record: { rev?: Revision }) => {
    if (Number.isSafeInteger(record.rev?.counter)) max = Math.max(max, Number(record.rev?.counter));
  };
  Object.values(state.bookmarks).forEach(visit);
  Object.values(state.bookmarkTombstones).forEach(visit);
  Object.values(state.apps).forEach(visit);
  Object.values(state.appTombstones).forEach(visit);
  Object.values(state.layout.items).forEach(visit);
  Object.values(state.layout.itemTombstones).forEach(visit);
  return max;
}

function layoutPlacements(layout: LauncherLayoutLike): Map<string, Omit<SyncV2LayoutItem, "rev">> {
  const result = new Map<string, Omit<SyncV2LayoutItem, "rev">>();
  const columns = Math.max(1, Math.floor(layout.gridColumns || 4));
  (layout.cells || []).forEach((cell, index) => {
    if (!cell.id) return;
    result.set(cell.id, {
      id: cell.id,
      kind: layoutItemKind(cell.id),
      ...(cell.id.startsWith("app:") ? { appId: cell.id.slice(4) } : {}),
      container: "desktop:0",
      order: stableCellIndex(cell, columns, index) * 1000,
    });
  });
  (layout.dock || []).forEach((id, index) => {
    if (!id) return;
    result.set(id, {
      id,
      kind: layoutItemKind(id),
      ...(id.startsWith("app:") ? { appId: id.slice(4) } : {}),
      container: "dock",
      order: index * 1000,
    });
  });
  (layout.folders || []).forEach((folder) => {
    if (folder.id) {
      const current = result.get(folder.id);
      result.set(folder.id, {
        ...(current || { id: folder.id, kind: "folder" as const, container: null, order: 0 }),
        title: folder.title,
      });
    }
    (folder.childIds || []).forEach((id, index) => {
      if (!id) return;
      result.set(id, {
        id,
        kind: layoutItemKind(id),
        ...(id.startsWith("app:") ? { appId: id.slice(4) } : {}),
        container: folder.id,
        order: index * 1000,
      });
    });
  });
  return result;
}

function stableCellIndex(cell: { page?: number; row?: number; column?: number; index?: number }, columns: number, fallback: number): number {
  if (Number.isInteger(cell.index) && Number(cell.index) >= 0) return Number(cell.index);
  const page = Number.isInteger(cell.page) ? Math.max(0, Number(cell.page)) : 0;
  const row = Number.isInteger(cell.row) ? Math.max(0, Number(cell.row)) : 0;
  const column = Number.isInteger(cell.column) ? Math.max(0, Number(cell.column)) : 0;
  return page * 10000 + row * columns + column || fallback;
}

function layoutItemKind(id: string): "app" | "folder" | "system" {
  if (id.startsWith("app:")) return "app";
  if (id.startsWith("folder:")) return "folder";
  return "system";
}

function compareLayoutItems(left: SyncV2LayoutItem, right: SyncV2LayoutItem): number {
  return left.order - right.order || left.id.localeCompare(right.id);
}

function layoutItemChanged(left: SyncV2LayoutItem, right: Omit<SyncV2LayoutItem, "rev">): boolean {
  return left.kind !== right.kind ||
    left.appId !== right.appId ||
    left.title !== right.title ||
    left.container !== right.container ||
    left.order !== right.order;
}

function bookmarkFieldsChanged(left: SyncV2Bookmark, right: BookmarkRecord): boolean {
  return left.title !== right.title || (left.iconDataUrl || null) !== (right.iconDataUrl || null);
}

function appFieldsChanged(left: SyncV2App, right: WebAppRecord): boolean {
  return left.name !== right.name ||
    left.startUrl !== right.startUrl ||
    left.scopeUrl !== right.scopeUrl ||
    left.themeColor !== right.themeColor ||
    left.displayMode !== right.displayMode ||
    (left.iconDataUrl || null) !== (right.iconDataUrl || null) ||
    (left.iconSource || null) !== (right.iconSource || null);
}

export function normalizeBookmarkKey(value: string): string {
  try {
    const url = new URL(value.trim());
    url.hash = "";
    return url.toString();
  } catch {
    return value.trim();
  }
}

export function identityKeyForUrl(value: string): string {
  return normalizeBookmarkKey(value);
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
