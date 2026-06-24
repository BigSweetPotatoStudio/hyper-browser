import type { BookmarkRecord, WebAppRecord } from "./index";
import { WebDavClient, WebDavConflictError, type WebDavSettings } from "./webdav";

export const SYNC_V2_SCHEMA_VERSION = 2;
const BOOKMARKS_FILE = "bookmarks.json";
const WEBAPPS_FILE = "webapps.json";
const LAUNCHER_FILE = "launcher.json";
const MANIFEST_FILE = "manifest.json";
const TOMBSTONE_COMPACT_TRIGGER = 1500;
const TOMBSTONE_COMPACT_TARGET = 500;
const TOMBSTONE_MIN_RETENTION_MS = 0;

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
  index: number;
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
  | SyncV2BaseOperation<"bookmark.delete"> & BookmarkDeleteInput
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
  | ({ type: "bookmark.delete" } & BookmarkDeleteInput)
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
  bookmarkSnapshotMode?: "flat" | "tree";
  webApps?: WebAppRecord[];
  layout?: LauncherLayoutLike | null;
};

export type SyncV2Result = {
  state: SyncV2State;
  stateChanged: boolean;
  launcherChanged: boolean;
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

type BookmarkDeleteInput = {
  bookmarkId: string;
  identityKey?: string;
  kind?: BookmarkRecord["kind"];
  url?: string;
  title?: string;
  parentId?: string | null;
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
  return sanitizeState({
    schemaVersion: SYNC_V2_SCHEMA_VERSION,
    bookmarks: state.bookmarks || {},
    bookmarkTombstones: state.bookmarkTombstones || {},
    apps: state.apps || {},
    appTombstones: state.appTombstones || {},
    layout: {
      items: readLayoutItemMap(state.layout?.items),
      itemTombstones: state.layout?.itemTombstones || {},
    },
  });
}

export function canonicalJson(value: unknown): string {
  return JSON.stringify(canonicalize(value));
}

function sanitizeState(state: SyncV2State): SyncV2State {
  const clean = cloneJson(state);

  const bookmarks: Record<string, SyncV2Bookmark> = {};
  Object.entries(clean.bookmarks).forEach(([fallbackId, record]) => {
    const normalized = normalizeStoredBookmarkRecord(record, fallbackId);
    if (normalized) bookmarks[normalized.id] = normalized;
  });
  clean.bookmarks = bookmarks;
  clean.bookmarkTombstones = normalizeBookmarkTombstones(clean.bookmarkTombstones, clean.bookmarks);
  applyBookmarkTombstones(clean.bookmarks, clean.bookmarkTombstones);
  dedupeBookmarkIdentityKeys(clean);

  Object.keys(clean.appTombstones).forEach((appId) => {
    delete clean.apps[appId];
    delete clean.layout.items[`app:${appId}`];
  });

  Object.keys(clean.layout.itemTombstones).forEach((itemId) => {
    if (itemId.startsWith("app:")) delete clean.layout.itemTombstones[itemId];
  });

  Object.entries(clean.layout.items).forEach(([itemId, item]) => {
    if (item.container === null) {
      delete clean.layout.items[itemId];
      return;
    }
    if (item.kind !== "app") return;
    const appId = item.appId || (item.id.startsWith("app:") ? item.id.slice(4) : "");
    if (!appId || clean.appTombstones[appId] || !clean.apps[appId]) {
      delete clean.layout.items[itemId];
    }
  });

  compactStateTombstones(clean);

  return clean;
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

export function appendWebAppUpsert(store: SyncV2Store, input: WebAppInput, placement?: { container: string | null; index: number }): { store: SyncV2Store; app: SyncV2App } {
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
  if (placement && !next.state.layout.items[itemId]) {
    next = appendOperation(next, {
      type: "layout.place",
      item: {
        id: itemId,
        kind: "app",
        appId: id,
        container: placement.container,
        index: placement.index,
      },
    });
  }
  return { store: next, app: next.state.apps[id] };
}

export function appendWebAppDelete(store: SyncV2Store, appId: string): SyncV2Store {
  const id = appId.trim();
  if (!id) return store;
  return appendOperation(store, { type: "app.delete", appId: id });
}

export function appendLocalSnapshotOperations(store: SyncV2Store, snapshot: SyncV2LocalSnapshot): SyncV2Store {
  let next = cloneJson(store);
  if (snapshot.bookmarks) next = appendBookmarkSnapshotOperations(next, snapshot.bookmarks, snapshot.bookmarkSnapshotMode || "flat");
  if (snapshot.webApps) next = appendWebAppSnapshotOperations(next, snapshot.webApps);
  if (snapshot.layout) next = appendLayoutSnapshotOperations(next, snapshot.layout);
  return next;
}

function appendBookmarkSnapshotOperations(store: SyncV2Store, bookmarks: BookmarkRecord[], mode: "flat" | "tree"): SyncV2Store {
  let next = store;
  const local = new Map<string, BookmarkRecord>();
  const localKeys = new Set<string>();
  bookmarks.forEach((bookmark) => {
    const record = normalizeBookmarkRecordInput(bookmark, next.state, next.deviceId);
    if (!record) return;
    if (record.deletedAt != null) {
      next = appendOperation(next, { type: "bookmark.delete", ...bookmarkDeleteInput(record) });
      return;
    }
    local.set(record.id, record);
    localKeys.add(bookmarkTombstoneKey(record));
  });
  if (mode === "tree") {
    const remoteActive = activeBookmarksFromState(store.state);
    if (local.size === 0 && remoteActive.length > 0) return next;
    remoteActive.forEach((bookmark) => {
      if (!local.has(bookmark.id) && !localKeys.has(bookmarkTombstoneKey(bookmark))) {
        next = appendOperation(next, { type: "bookmark.delete", ...bookmarkDeleteInput(bookmark) });
      }
    });
  }
  local.forEach((bookmark, id) => {
    const current = next.state.bookmarks[id];
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
    if (!placements.has(item.id) && item.kind !== "app" && !next.state.layout.itemTombstones[item.id]) {
      next = appendOperation(next, { type: "layout.deleteItem", itemId: item.id });
    }
  });
  return next;
}

export function activeBookmarksFromState(state: SyncV2State): BookmarkRecord[] {
  return Object.values(ensureState(state).bookmarks)
    .sort(compareBookmarkRecords)
    .map(({ rev: _rev, ...bookmark }) => bookmark as BookmarkRecord);
}

export function findBookmarkByUrlInState(state: SyncV2State, url: string): BookmarkRecord | null {
  const identityKey = identityKeyForUrl(url);
  if (!identityKey) return null;
  const match = Object.values(ensureState(state).bookmarks)
    .filter((bookmark) => bookmark.kind !== "folder" && identityKeyForUrl(bookmark.url) === identityKey)
    .sort((left, right) => compareRevision(right.rev, left.rev))[0];
  if (!match) return null;
  const { rev: _rev, ...bookmark } = match;
  return bookmark as BookmarkRecord;
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
  const cells = desktop.map((item, index) => desktopCellFromLayoutItem(item, index, fallbackGridColumns));
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
    let stateChanged = false;
    let launcherChanged = false;
    let uploadedOperationCount = 0;
    let pendingOperationCount = 0;

    await options.withLocalLock(async () => {
      let store = ensureStore(await options.loadStore(), options.settings.deviceId);
      const previousState = store.state;
      store = {
        ...store,
        counter: Math.max(store.counter, maxStateCounter(remote.state)),
        state: mergeState(store.state, remote.state),
      };
      if (options.loadLocalSnapshot) {
        store = appendLocalSnapshotOperations(store, await options.loadLocalSnapshot());
      }
      uploadedOperationCount = store.outbox.length;
      mergedState = mergeState(store.state, remote.state);
      stateChanged = canonicalJson(previousState) !== canonicalJson(mergedState);
      launcherChanged = launcherStateSignature(previousState) !== launcherStateSignature(mergedState);
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
        stateChanged,
        launcherChanged,
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
      stateChanged,
      launcherChanged,
      uploadedOperationCount,
      remoteOperationCount: countRemoteRecords(remote.state),
      pendingOperationCount,
      syncedAt: Date.now(),
    };
  }

  throw new Error("WebDAV sync conflict retry limit reached.");
}

function launcherStateSignature(state: SyncV2State): string {
  const clean = ensureState(state);
  return canonicalJson({
    apps: clean.apps,
    appTombstones: clean.appTombstones,
    layout: clean.layout,
  });
}

function mergeState(leftState: SyncV2State, rightState: SyncV2State): SyncV2State {
  const left = ensureState(leftState);
  const right = ensureState(rightState);
  const bookmarkTombstones = mergeLwwMap(left.bookmarkTombstones, right.bookmarkTombstones);
  const bookmarks = mergeLwwMap(left.bookmarks, right.bookmarks);
  applyBookmarkTombstones(bookmarks, bookmarkTombstones);

  const appTombstones = mergeLwwMap(left.appTombstones, right.appTombstones);
  const apps = mergeLwwMap(left.apps, right.apps);
  Object.keys(appTombstones).forEach((appId) => {
    delete apps[appId];
  });

  const itemTombstones = mergeLwwMap(left.layout.itemTombstones, right.layout.itemTombstones);
  Object.keys(itemTombstones).forEach((itemId) => {
    if (itemId.startsWith("app:")) delete itemTombstones[itemId];
  });
  const items = mergeLwwMap(left.layout.items, right.layout.items);
  Object.keys(itemTombstones).forEach((itemId) => {
    delete items[itemId];
  });

  return sanitizeState({
    schemaVersion: SYNC_V2_SCHEMA_VERSION,
    bookmarks,
    bookmarkTombstones,
    apps,
    appTombstones,
    layout: {
      items,
      itemTombstones,
    },
  });
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

function compactStateTombstones(state: SyncV2State): void {
  state.bookmarkTombstones = compactTombstones(state.bookmarkTombstones);
  state.appTombstones = compactTombstones(state.appTombstones);
  state.layout.itemTombstones = compactTombstones(state.layout.itemTombstones);
}

function compactTombstones(tombstones: Record<string, SyncV2Tombstone>): Record<string, SyncV2Tombstone> {
  const entries = Object.entries(tombstones || {}).filter(([, tombstone]) => tombstone?.rev);
  if (entries.length <= TOMBSTONE_COMPACT_TRIGGER) return tombstones;

  const now = Date.now();
  const retained: Array<[string, SyncV2Tombstone]> = [];
  const compactable: Array<[string, SyncV2Tombstone]> = [];
  entries.forEach((entry) => {
    const deletedAt = tombstoneDeletedAtMs(entry[1]);
    if (TOMBSTONE_MIN_RETENTION_MS > 0 && deletedAt > 0 && now - deletedAt <= TOMBSTONE_MIN_RETENTION_MS) {
      retained.push(entry);
    } else {
      compactable.push(entry);
    }
  });

  const compactableKeepCount = Math.max(0, TOMBSTONE_COMPACT_TARGET - retained.length);
  const kept = [
    ...retained,
    ...compactable.sort(compareTombstoneEntriesNewestFirst).slice(0, compactableKeepCount),
  ];
  return Object.fromEntries(kept.sort(([left], [right]) => left.localeCompare(right)));
}

function compareTombstoneEntriesNewestFirst(
  left: [string, SyncV2Tombstone],
  right: [string, SyncV2Tombstone],
): number {
  const timeDelta = tombstoneDeletedAtMs(right[1]) - tombstoneDeletedAtMs(left[1]);
  if (timeDelta !== 0) return timeDelta;
  const revDelta = compareRevision(right[1].rev, left[1].rev);
  if (revDelta !== 0) return revDelta;
  return left[0].localeCompare(right[0]);
}

function tombstoneDeletedAtMs(tombstone: SyncV2Tombstone): number {
  const value = Date.parse(tombstone.deletedAt || "");
  return Number.isFinite(value) ? value : 0;
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
  mergeLwwMapInto(state.bookmarks, readBookmarkRecordMap(root.bookmarks));
  mergeLwwMapInto(state.bookmarkTombstones, readBookmarkTombstoneMap(root.bookmarkTombstones || root.tombstones));
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
    mergeLwwMapInto(state.layout.items, readLayoutItemMap(clean.items));
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

function readBookmarkRecordMap(value: unknown): Record<string, SyncV2Bookmark> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const result: Record<string, SyncV2Bookmark> = {};
  Object.entries(value as Record<string, unknown>).forEach(([key, raw]) => {
    const record = normalizeStoredBookmarkRecord(raw, key);
    if (record) result[record.id] = record;
  });
  return result;
}

function readBookmarkTombstoneMap(value: unknown): Record<string, SyncV2Tombstone> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const result: Record<string, SyncV2Tombstone> = {};
  Object.entries(value as Record<string, unknown>).forEach(([key, raw]) => {
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return;
    const tombstone = raw as SyncV2Tombstone;
    if (!tombstone.rev) return;
    result[bookmarkIdFromStorageKey(key)] = cloneJson(tombstone);
  });
  return result;
}

function readLayoutItemMap(value: unknown): Record<string, SyncV2LayoutItem> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const result: Record<string, SyncV2LayoutItem> = {};
  Object.entries(value as Record<string, unknown>).forEach(([key, raw]) => {
    const item = normalizeLayoutItemRecord(raw, key);
    if (item) result[item.id] = item;
  });
  return result;
}

function legacyBookmarkRecords(value: unknown): BookmarkRecord[] {
  if (!Array.isArray(value)) return [];
  return value
    .filter((item): item is Partial<BookmarkRecord> => !!item && typeof item === "object")
    .map((item) => normalizeLegacyBookmarkRecord(item))
    .filter((item): item is BookmarkRecord => !!item);
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
  const bookmark = normalizeBookmarkRecordInput(record, state, record.sourceDeviceId || "legacy");
  if (!bookmark) return;
  const rev = revisionFromLegacy(record);
  if (bookmark.deletedAt != null) {
    state.bookmarkTombstones[bookmark.id] = { deletedAt: new Date(bookmark.deletedAt).toISOString(), rev };
    delete state.bookmarks[bookmark.id];
    return;
  }
  state.bookmarks[bookmark.id] = { ...bookmark, deletedAt: null, rev };
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
    const bookmark = normalizeBookmarkRecordInput(operation.bookmark, state, operation.deviceId);
    if (!bookmark) return;
    const tombstoneKey = bookmarkTombstoneKey(bookmark);
    const tombstone = pickLww(state.bookmarkTombstones[tombstoneKey], state.bookmarkTombstones[bookmark.id]);
    if (tombstone && compareRevision(tombstone.rev, rev) >= 0) return;
    const current = state.bookmarks[bookmark.id];
    if (current && compareRevision(current.rev, rev) > 0) return;
    state.bookmarks[bookmark.id] = {
      ...bookmark,
      updatedAt: bookmark.updatedAt || operation.createdAt,
      deletedAt: null,
      sourceDeviceId: bookmark.sourceDeviceId || operation.deviceId,
      rev,
    };
    delete state.bookmarkTombstones[tombstoneKey];
    delete state.bookmarkTombstones[bookmark.id];
    dedupeBookmarkIdentityKeys(state);
    return;
  }
  if (operation.type === "bookmark.delete") {
    const key = bookmarkDeleteOperationKey(state, operation);
    if (!key) return;
    const matches = bookmarkRecordsForTombstoneKey(state.bookmarks, key, operation.bookmarkId);
    if (matches.some((current) => compareRevision(current.rev, rev) > 0)) return;
    const tombstone = state.bookmarkTombstones[key];
    if (tombstone && compareRevision(tombstone.rev, rev) >= 0) return;
    matches.forEach((bookmark) => {
      delete state.bookmarks[bookmark.id];
    });
    state.bookmarkTombstones[key] = { deletedAt: new Date(operation.createdAt).toISOString(), rev };
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
    delete state.layout.items[`app:${id}`];
    delete state.layout.itemTombstones[`app:${id}`];
    return;
  }
  if (operation.type === "layout.place") {
    const item = normalizeLayoutItemInput(operation.item);
    if (!item) return;
    if (!item.id.startsWith("app:") && state.layout.itemTombstones[item.id]) return;
    if (item.kind === "app" && (!item.appId || !state.apps[item.appId] || state.appTombstones[item.appId])) return;
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
    delete state.layout.items[itemId];
    if (!itemId.startsWith("app:")) {
      state.layout.itemTombstones[itemId] = { deletedAt: new Date(operation.createdAt).toISOString(), rev };
    }
    return;
  }
  if (operation.type === "layout.deleteItem") {
    const itemId = operation.itemId.trim();
    if (!itemId) return;
    delete state.layout.items[itemId];
    if (itemId.startsWith("app:")) {
      delete state.layout.itemTombstones[itemId];
      return;
    }
    if (!state.layout.itemTombstones[itemId] || compareRevision(state.layout.itemTombstones[itemId].rev, rev) < 0) {
      state.layout.itemTombstones[itemId] = { deletedAt: new Date(operation.createdAt).toISOString(), rev };
    }
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
      index: stableCellIndex(cell, columns, index),
    });
  });
  (layout.dock || []).forEach((id, index) => {
    if (!id) return;
    result.set(id, {
      id,
      kind: layoutItemKind(id),
      ...(id.startsWith("app:") ? { appId: id.slice(4) } : {}),
      container: "dock",
      index,
    });
  });
  (layout.folders || []).forEach((folder) => {
    if (!folder.id || !result.has(folder.id)) return;
    const current = result.get(folder.id);
    result.set(folder.id, {
      ...current!,
      title: folder.title,
    });
    (folder.childIds || []).forEach((id, index) => {
      if (!id) return;
      result.set(id, {
        id,
        kind: layoutItemKind(id),
        ...(id.startsWith("app:") ? { appId: id.slice(4) } : {}),
        container: folder.id,
        index,
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

function desktopCellFromLayoutItem(item: SyncV2LayoutItem, fallbackIndex: number, fallbackGridColumns: number): NonNullable<LauncherLayoutLike["cells"]>[number] {
  const columns = Math.max(1, Math.floor(fallbackGridColumns || 4));
  const index = Number.isFinite(item.index) ? Math.max(0, Math.floor(item.index)) : fallbackIndex;
  return {
    id: item.id,
    page: 0,
    row: Math.floor(index / columns),
    column: index % columns,
    index,
  };
}

function normalizeLayoutItemRecord(raw: unknown, fallbackId?: string): SyncV2LayoutItem | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const rev = (raw as { rev?: Revision }).rev;
  if (!rev) return null;
  const item = normalizeLayoutItemInput(raw, fallbackId);
  return item ? { ...item, rev } : null;
}

function normalizeLayoutItemInput(raw: unknown, fallbackId?: string): Omit<SyncV2LayoutItem, "rev"> | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const source = raw as Record<string, unknown>;
  const id = String(source.id || fallbackId || "").trim();
  if (!id) return null;
  const container = source.container == null ? "" : String(source.container).trim();
  if (!container) return null;
  const kind = source.kind === "app" || source.kind === "folder" || source.kind === "system"
    ? source.kind
    : layoutItemKind(id);
  const appId = typeof source.appId === "string" && source.appId.trim()
    ? source.appId.trim()
    : (id.startsWith("app:") ? id.slice(4) : undefined);
  return {
    id,
    kind,
    ...(kind === "app" && appId ? { appId } : {}),
    ...(typeof source.title === "string" ? { title: source.title } : {}),
    container,
    index: normalizeLayoutIndex(source.index, source.order),
  };
}

function normalizeLayoutIndex(indexValue: unknown, legacyOrderValue: unknown, fallback = 0): number {
  const index = Number(indexValue);
  if (Number.isFinite(index)) return Math.max(0, Math.floor(index));
  const legacyOrder = Number(legacyOrderValue);
  if (Number.isFinite(legacyOrder)) return Math.max(0, Math.floor(legacyOrder / 1000));
  return fallback;
}

function layoutItemKind(id: string): "app" | "folder" | "system" {
  if (id.startsWith("app:")) return "app";
  if (id.startsWith("folder:")) return "folder";
  return "system";
}

function normalizeLegacyBookmarkRecord(item: Partial<BookmarkRecord>): BookmarkRecord | null {
  const url = normalizeBookmarkKey(String(item.url || "").trim());
  if (!url) return null;
  const updatedAt = Number(item.updatedAt) || Number(item.createdAt) || 0;
  const createdAt = Number(item.createdAt) || updatedAt;
  const deletedAt = item.deletedAt == null ? null : Number(item.deletedAt) || Date.parse(String(item.deletedAt)) || 0;
  const identityKey = identityKeyForUrl(url);
  return {
    id: String(item.id || "").trim() || stableUuidFromString(`bookmark:${identityKey}`),
    kind: "bookmark",
    identityKey,
    parentId: typeof item.parentId === "string" && item.parentId.trim() ? item.parentId.trim() : null,
    index: normalizeOptionalIndex(item.index),
    url,
    title: String(item.title || item.url || ""),
    createdAt,
    updatedAt,
    deletedAt,
    sourceDeviceId: String(item.sourceDeviceId || "legacy"),
    iconDataUrl: typeof item.iconDataUrl === "string" ? item.iconDataUrl : null,
  };
}

function normalizeStoredBookmarkRecord(raw: unknown, fallbackId = ""): SyncV2Bookmark | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const source = raw as Partial<SyncV2Bookmark>;
  if (!source.rev) return null;
  const record = normalizeBookmarkRecordInput({
    ...source,
    id: String(source.id || bookmarkIdFromStorageKey(fallbackId)).trim(),
  }, undefined, source.sourceDeviceId || source.rev.deviceId);
  return record ? { ...record, rev: source.rev } : null;
}

function normalizeBookmarkRecordInput(input: Partial<BookmarkRecord>, state?: SyncV2State, deviceId = ""): BookmarkRecord | null {
  const kind = input.kind === "folder" ? "folder" : "bookmark";
  const url = kind === "folder" ? "" : normalizeBookmarkKey(String(input.url || "").trim());
  if (kind === "bookmark" && !url) return null;
  const title = String(input.title || url || "Folder").trim() || url || "Folder";
  const parentId = cleanOptionalString(input.parentId) || null;
  const index = normalizeOptionalIndex(input.index);
  const identityKey = kind === "bookmark"
    ? normalizeBookmarkIdentityKey(input.identityKey, url)
    : normalizeFolderIdentityKey(input.identityKey, parentId, title);
  const existing = state ? findExistingBookmarkRecord(state, kind, identityKey, url) : null;
  const directId = cleanOptionalString(input.id);
  const id = directId || existing?.id || (state ? crypto.randomUUID() : stableUuidFromString(`${kind}:${identityKey || title}`));
  return {
    id,
    kind,
    identityKey,
    parentId,
    index,
    url,
    title,
    createdAt: Number(input.createdAt) || Number(input.updatedAt) || Date.now(),
    updatedAt: Number(input.updatedAt) || Number(input.createdAt) || Date.now(),
    deletedAt: input.deletedAt == null ? null : Number(input.deletedAt) || Date.parse(String(input.deletedAt)) || 0,
    sourceDeviceId: cleanOptionalString(input.sourceDeviceId) || deviceId || "",
    iconDataUrl: cleanOptionalString(input.iconDataUrl) || null,
  };
}

function normalizeBookmarkTombstones(
  tombstones: Record<string, SyncV2Tombstone>,
  bookmarks: Record<string, SyncV2Bookmark> = {},
): Record<string, SyncV2Tombstone> {
  const result: Record<string, SyncV2Tombstone> = {};
  Object.entries(tombstones || {}).forEach(([key, tombstone]) => {
    if (!tombstone?.rev) return;
    const normalizedKey = normalizeBookmarkTombstoneKey(key, bookmarks);
    if (!normalizedKey) return;
    const current = result[normalizedKey];
    if (!current || compareRevision(tombstone.rev, current.rev) > 0) {
      result[normalizedKey] = tombstone;
    }
  });
  return result;
}

function bookmarkIdFromStorageKey(key: string): string {
  const value = String(key || "").trim();
  if (!value) return "";
  return looksLikeUrlKey(value) ? stableUuidFromString(`bookmark:${identityKeyForUrl(value)}`) : value;
}

function normalizeBookmarkTombstoneKey(key: string, bookmarks: Record<string, SyncV2Bookmark>): string {
  const value = String(key || "").trim();
  if (!value) return "";
  const bookmark = bookmarks[value];
  if (bookmark) return bookmarkTombstoneKey(bookmark);
  return looksLikeUrlKey(value) ? identityKeyForUrl(value) : value;
}

function applyBookmarkTombstones(
  bookmarks: Record<string, SyncV2Bookmark>,
  tombstones: Record<string, SyncV2Tombstone>,
): void {
  Object.keys(tombstones).forEach((key) => {
    bookmarkRecordsForTombstoneKey(bookmarks, key).forEach((bookmark) => {
      delete bookmarks[bookmark.id];
    });
  });
}

function looksLikeUrlKey(value: string): boolean {
  return /^[a-z][a-z0-9+.-]*:\/\//i.test(value);
}

function findExistingBookmarkRecord(state: SyncV2State, kind: BookmarkRecord["kind"], identityKey: string, url: string): SyncV2Bookmark | undefined {
  if (kind === "bookmark" && identityKey) {
    return Object.values(state.bookmarks).find((bookmark) => bookmark.kind !== "folder" && bookmark.identityKey === identityKey);
  }
  if (kind === "folder" && identityKey) {
    return Object.values(state.bookmarks).find((bookmark) => bookmark.kind === "folder" && bookmark.identityKey === identityKey);
  }
  return url ? Object.values(state.bookmarks).find((bookmark) => bookmark.url === url) : undefined;
}

function dedupeBookmarkIdentityKeys(state: SyncV2State): void {
  dedupeFolderIdentityKeys(state);

  const selected = new Map<string, SyncV2Bookmark>();
  Object.values(state.bookmarks).forEach((bookmark) => {
    if (bookmark.kind === "folder" || !bookmark.identityKey) return;
    const current = selected.get(bookmark.identityKey);
    if (!current || compareRevision(bookmark.rev, current.rev) > 0 || (compareRevision(bookmark.rev, current.rev) === 0 && bookmark.id > current.id)) {
      selected.set(bookmark.identityKey, bookmark);
    }
  });
  Object.entries(state.bookmarks).forEach(([id, bookmark]) => {
    if (bookmark.kind !== "folder" && bookmark.identityKey && selected.get(bookmark.identityKey)?.id !== id) {
      delete state.bookmarks[id];
    }
  });
}

function dedupeFolderIdentityKeys(state: SyncV2State): void {
  let changed = false;
  do {
    changed = false;
    const selected = new Map<string, SyncV2Bookmark>();
    Object.values(state.bookmarks).forEach((bookmark) => {
      if (bookmark.kind !== "folder") return;
      const key = folderIdentityKey(bookmark.parentId || null, bookmark.title);
      bookmark.identityKey = key;
      const current = selected.get(key);
      if (!current || compareFolderDedupeCandidate(state, bookmark, current) > 0) {
        selected.set(key, bookmark);
      }
    });

    Object.entries(state.bookmarks).forEach(([id, bookmark]) => {
      if (bookmark.kind !== "folder") return;
      const keep = selected.get(folderIdentityKey(bookmark.parentId || null, bookmark.title));
      if (!keep || keep.id === id) return;
      reparentBookmarkChildren(state, id, keep.id);
      delete state.bookmarks[id];
      changed = true;
    });
  } while (changed);
}

function compareFolderDedupeCandidate(state: SyncV2State, left: SyncV2Bookmark, right: SyncV2Bookmark): number {
  const childDelta = bookmarkDescendantCount(state, left.id) - bookmarkDescendantCount(state, right.id);
  if (childDelta !== 0) return childDelta;
  const revDelta = compareRevision(left.rev, right.rev);
  if (revDelta !== 0) return revDelta;
  const leftIndex = left.index ?? Number.MAX_SAFE_INTEGER;
  const rightIndex = right.index ?? Number.MAX_SAFE_INTEGER;
  if (leftIndex !== rightIndex) return rightIndex - leftIndex;
  return left.id.localeCompare(right.id);
}

function bookmarkDescendantCount(state: SyncV2State, folderId: string): number {
  let count = 0;
  const visit = (parentId: string): void => {
    Object.values(state.bookmarks).forEach((bookmark) => {
      if (bookmark.parentId !== parentId) return;
      count += 1;
      if (bookmark.kind === "folder") visit(bookmark.id);
    });
  };
  visit(folderId);
  return count;
}

function reparentBookmarkChildren(state: SyncV2State, fromFolderId: string, toFolderId: string): void {
  Object.values(state.bookmarks).forEach((bookmark) => {
    if (bookmark.parentId === fromFolderId) {
      bookmark.parentId = toFolderId;
    }
  });
}

function bookmarkDeleteInput(bookmark: BookmarkRecord): BookmarkDeleteInput {
  return {
    bookmarkId: bookmark.id,
    identityKey: bookmarkTombstoneKey(bookmark),
    kind: bookmark.kind,
    url: bookmark.url,
    title: bookmark.title,
    parentId: bookmark.parentId ?? null,
  };
}

function bookmarkDeleteOperationKey(state: SyncV2State, operation: SyncV2Operation): string {
  const legacy = operation as unknown as Record<string, unknown>;
  const identityKey = cleanOptionalString(legacy.identityKey);
  if (identityKey) {
    const kind = legacy.kind === "folder" ? "folder" : "bookmark";
    return kind === "folder"
      ? normalizeFolderIdentityKey(identityKey, cleanOptionalString(legacy.parentId) || null, String(legacy.title || "Folder"))
      : normalizeBookmarkIdentityKey(identityKey, String(legacy.url || ""));
  }
  const bookmarkId = typeof legacy.bookmarkId === "string"
    ? legacy.bookmarkId.trim()
    : "";
  if (bookmarkId && state.bookmarks[bookmarkId]) return bookmarkTombstoneKey(state.bookmarks[bookmarkId]);
  if (bookmarkId) return bookmarkId;
  const url = typeof legacy.url === "string"
    ? legacy.url.trim()
    : "";
  if (!url) return "";
  const urlKey = identityKeyForUrl(url);
  return Object.values(state.bookmarks).find((bookmark) => bookmark.kind !== "folder" && identityKeyForUrl(bookmark.url) === urlKey)
    ?.identityKey || urlKey;
}

function bookmarkTombstoneKey(bookmark: Pick<BookmarkRecord, "id" | "kind" | "identityKey" | "url" | "parentId" | "title">): string {
  if (bookmark.identityKey) return bookmark.identityKey;
  return bookmark.kind === "folder"
    ? folderIdentityKey(bookmark.parentId || null, bookmark.title)
    : identityKeyForUrl(bookmark.url);
}

function bookmarkRecordsForTombstoneKey(
  bookmarks: Record<string, SyncV2Bookmark>,
  key: string,
  fallbackId = "",
): SyncV2Bookmark[] {
  const cleanKey = String(key || "").trim();
  const cleanFallbackId = String(fallbackId || "").trim();
  if (!cleanKey && !cleanFallbackId) return [];
  return Object.values(bookmarks).filter((bookmark) =>
    (cleanFallbackId && bookmark.id === cleanFallbackId) ||
    (cleanKey && bookmarkTombstoneKey(bookmark) === cleanKey) ||
    (cleanKey && bookmark.identityKey === cleanKey) ||
    (looksLikeUrlKey(cleanKey) && bookmark.kind !== "folder" && identityKeyForUrl(bookmark.url) === identityKeyForUrl(cleanKey))
  );
}

function folderIdentityKey(parentId: string | null, title: string): string {
  return `folder:${parentId || "root"}:${title.trim().toLowerCase()}`;
}

function normalizeBookmarkIdentityKey(value: unknown, fallbackUrl: string): string {
  const key = cleanOptionalString(value);
  if (key.startsWith("bookmark-location:")) return key;
  return identityKeyForUrl(key || fallbackUrl);
}

function normalizeFolderIdentityKey(value: unknown, parentId: string | null, title: string): string {
  const key = cleanOptionalString(value);
  if (key.startsWith("folder-path:")) return key;
  return folderIdentityKey(parentId, title);
}

function normalizeOptionalIndex(value: unknown): number | undefined {
  const index = Number(value);
  return Number.isFinite(index) && index >= 0 ? Math.floor(index) : undefined;
}

function cleanOptionalString(value: unknown): string {
  return typeof value === "string" && value.trim() && value.trim() !== "null" && value.trim() !== "undefined"
    ? value.trim()
    : "";
}

function compareBookmarkRecords(left: SyncV2Bookmark, right: SyncV2Bookmark): number {
  const leftParent = left.parentId || "";
  const rightParent = right.parentId || "";
  return leftParent.localeCompare(rightParent) ||
    (left.index ?? 0) - (right.index ?? 0) ||
    (left.kind === right.kind ? 0 : left.kind === "folder" ? -1 : 1) ||
    left.title.localeCompare(right.title) ||
    left.id.localeCompare(right.id);
}

function stableUuidFromString(value: string): string {
  let a = 0x811c9dc5;
  let b = 0x9e3779b9;
  let c = 0x85ebca6b;
  let d = 0xc2b2ae35;
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    a = Math.imul(a ^ code, 0x01000193);
    b = Math.imul(b ^ code, 0x85ebca6b);
    c = Math.imul(c ^ code, 0xc2b2ae35);
    d = Math.imul(d ^ code, 0x27d4eb2d);
  }
  const hex = [a, b, c, d].map((part) => (part >>> 0).toString(16).padStart(8, "0")).join("");
  const variant = ((Number.parseInt(hex[16], 16) & 0x3) | 0x8).toString(16);
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-5${hex.slice(13, 16)}-${variant}${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

function compareLayoutItems(left: SyncV2LayoutItem, right: SyncV2LayoutItem): number {
  return left.index - right.index || left.id.localeCompare(right.id);
}

function layoutItemChanged(left: SyncV2LayoutItem, right: Omit<SyncV2LayoutItem, "rev">): boolean {
  return left.kind !== right.kind ||
    left.appId !== right.appId ||
    left.title !== right.title ||
    left.container !== right.container ||
    left.index !== right.index;
}

function bookmarkFieldsChanged(left: SyncV2Bookmark, right: BookmarkRecord): boolean {
  return left.kind !== right.kind ||
    left.identityKey !== right.identityKey ||
    left.parentId !== right.parentId ||
    left.index !== right.index ||
    left.url !== right.url ||
    left.title !== right.title ||
    (left.iconDataUrl || null) !== (right.iconDataUrl || null);
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
