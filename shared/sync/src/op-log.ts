import type { BookmarkRecord, WebAppRecord } from "./index";
import type {
  BookmarksJson,
  BookmarkSyncRecord,
  LauncherCell,
  LauncherFolder,
  LauncherJson,
  LauncherPage,
  WebAppsJson,
  WebAppSyncRecord,
} from "./sync-json-types";
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
  rev: Revision;
};

export type SyncV2Tombstone = {
  deletedAt: string;
  rev: Revision;
};

export type SyncV2LayoutPlacement = {
  id: string;
  kind: "app" | "folder" | "system";
  appId?: string;
  title?: string;
  container: string | null;
  index: number;
};

export type SyncV2State = {
  schemaVersion: 2;
  bookmarks: Record<string, SyncV2Bookmark>;
  bookmarkTombstones: Record<string, SyncV2Tombstone>;
  apps: Record<string, SyncV2App>;
  appTombstones: Record<string, SyncV2Tombstone>;
  layout: LauncherJson;
};

export type SyncV2Operation =
  | SyncV2BaseOperation<"bookmark.upsert"> & { bookmark: BookmarkRecord }
  | SyncV2BaseOperation<"bookmark.delete"> & BookmarkDeleteInput
  | SyncV2BaseOperation<"app.upsert"> & { app: Partial<WebAppRecord> & { id: string; name: string; startUrl: string } }
  | SyncV2BaseOperation<"app.delete"> & { appId: string }
  | SyncV2BaseOperation<"layout.replace"> & { layout: LauncherLayoutLike }
  | SyncV2BaseOperation<"layout.place"> & { item: SyncV2LayoutPlacement }
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
  | { type: "app.upsert"; app: Partial<WebAppRecord> & { id: string; name: string; startUrl: string } }
  | { type: "app.delete"; appId: string }
  | { type: "layout.replace"; layout: LauncherLayoutLike }
  | { type: "layout.place"; item: SyncV2LayoutPlacement }
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
};

export type LauncherLayoutSnapshot = LauncherLayoutLike | LauncherJson;

type SnapshotRecordCollection<T> = Array<T> | Record<string, T>;

export type SyncV2LocalSnapshot = {
  bookmarks?: SnapshotRecordCollection<BookmarkRecord & { rev?: Partial<Revision> }>;
  webApps?: SnapshotRecordCollection<WebAppRecord & { rev?: Partial<Revision> }>;
  layout?: LauncherLayoutSnapshot | null;
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
};

type BookmarkDeleteInput = {
  url?: string;
  title?: string;
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
      rev: { counter: 0, deviceId: "" },
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
    layout: normalizeLauncherJson(state.layout),
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
    if (normalized) bookmarks[bookmarkRecordKey(normalized)] = normalized;
  });
  clean.bookmarks = bookmarks;
  clean.bookmarkTombstones = normalizeBookmarkTombstones(clean.bookmarkTombstones, clean.bookmarks);
  applyBookmarkTombstones(clean.bookmarks, clean.bookmarkTombstones);
  dedupeBookmarkUrlKeys(clean);

  const apps: Record<string, SyncV2App> = {};
  Object.entries(clean.apps).forEach(([fallbackId, record]) => {
    const normalized = normalizeStoredWebAppRecord(record, fallbackId);
    if (normalized) apps[normalized.id] = normalized;
  });
  clean.apps = apps;

  Object.keys(clean.appTombstones).forEach((appId) => {
    delete clean.apps[appId];
  });

  compactStateTombstones(clean);
  clean.layout = sanitizeLauncherJson(clean.layout, clean.apps, clean.appTombstones);

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
  return ac === bc ? 0 : (ac < bc ? -1 : 1);
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
  const inputId = input.id?.trim() || "";
  const existing = inputId ? store.state.apps[inputId] : undefined;
  const id = existing?.id || inputId || crypto.randomUUID();
  if (store.state.appTombstones[id]) throw new Error("Deleted WebApp UUID cannot be reused.");
  const now = Date.now();
  const hasIconDataUrl = Object.prototype.hasOwnProperty.call(input, "iconDataUrl");
  const app: WebAppRecord = {
    id,
    name: input.name.trim() || input.startUrl,
    startUrl: input.startUrl,
    themeColor: input.themeColor ?? existing?.themeColor ?? 0xff126d6a,
    displayMode: input.displayMode || existing?.displayMode || "standalone",
    createdAt: existing?.createdAt || input.createdAt || now,
    lastOpenedAt: input.lastOpenedAt || existing?.lastOpenedAt || now,
    updatedAt: now,
    deletedAt: null,
    iconDataUrl: hasIconDataUrl ? input.iconDataUrl ?? null : existing?.iconDataUrl ?? null,
    iconSource: input.iconSource || (hasIconDataUrl ? (input.iconDataUrl ? "custom" : "title") : existing?.iconSource) || "title",
  };
  let next = appendOperation(store, { type: "app.upsert", app });
  const itemId = `app:${id}`;
  if (placement && !launcherContainsItem(next.state.layout, itemId)) {
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
  if (snapshot.bookmarks) next = appendBookmarkSnapshotOperations(next, snapshot.bookmarks);
  if (snapshot.webApps) next = appendWebAppSnapshotOperations(next, snapshot.webApps);
  if (snapshot.layout) next = appendLayoutSnapshotOperations(next, snapshot.layout);
  return next;
}

function appendBookmarkSnapshotOperations(
  store: SyncV2Store,
  bookmarks: SnapshotRecordCollection<BookmarkRecord & { rev?: Partial<Revision> }>,
): SyncV2Store {
  let next = store;
  const local = new Map<string, BookmarkRecord>();
  snapshotRecordValues(bookmarks).forEach((bookmark) => {
    const record = normalizeBookmarkRecordInput(bookmark, next.state);
    if (!record) return;
    if (record.deletedAt != null) {
      next = appendOperation(next, { type: "bookmark.delete", ...bookmarkDeleteInput(record) });
      return;
    }
    local.set(bookmarkRecordKey(record), record);
  });
  local.forEach((bookmark, key) => {
    const current = next.state.bookmarks[key];
    if (!current || bookmarkFieldsChanged(current, bookmark)) {
      next = appendOperation(next, { type: "bookmark.upsert", bookmark });
    }
  });
  activeBookmarksFromState(next.state).forEach((bookmark) => {
    const key = bookmarkRecordKey(bookmark);
    if (key && !local.has(key)) {
      next = appendOperation(next, { type: "bookmark.delete", ...bookmarkDeleteInput(bookmark) });
    }
  });
  return next;
}

function appendWebAppSnapshotOperations(
  store: SyncV2Store,
  webApps: SnapshotRecordCollection<WebAppRecord & { rev?: Partial<Revision> }>,
): SyncV2Store {
  let next = store;
  const local = new Map<string, WebAppRecord & { rev?: Partial<Revision> }>();
  snapshotRecordValues(webApps).forEach((app) => {
    const id = app.id?.trim();
    if (id) local.set(id, { ...app, id });
  });
  local.forEach((app, id) => {
    const current = next.state.apps[id];
    const tombstone = next.state.appTombstones[id];
    if (app.deletedAt != null) {
      if (current && snapshotCanOverrideRevision(app, current)) {
        next = appendWebAppDelete(next, id);
      }
      return;
    }
    if (current && !snapshotCanOverrideRevision(app, current)) return;
    if (!current && tombstone && !snapshotCanOverrideRevision(app, tombstone)) return;
    if (!current || appFieldsChanged(current, app)) {
      next = appendOperation(next, {
        type: "app.upsert",
        app: {
          ...app,
          deletedAt: null,
        },
      });
    }
  });
  return next;
}

function snapshotRecordValues<T>(records: SnapshotRecordCollection<T>): T[] {
  return Array.isArray(records) ? records : Object.values(records || {});
}

function snapshotCanOverrideRevision(snapshot: { rev?: Partial<Revision> }, target: { rev?: Partial<Revision> }): boolean {
  return compareRevision(normalizeRevision(snapshot.rev), normalizeRevision(target.rev)) >= 0;
}

export function appendLayoutSnapshotOperations(store: SyncV2Store, layout: LauncherLayoutSnapshot): SyncV2Store {
  const snapshot = launcherLayoutSnapshot(layout);
  if (!layoutDocumentChanged(layoutFromState(store.state), snapshot.layout)) return store;
  if (snapshot.rev.counter > 0 && compareRevision(snapshot.rev, store.state.layout.rev) <= 0) return store;
  return appendOperation(store, { type: "layout.replace", layout: snapshot.layout });
}

function launcherLayoutSnapshot(layout: LauncherLayoutSnapshot): { layout: LauncherLayoutLike; rev: Revision } {
  if (isLauncherJsonInput(layout)) {
    const clean = normalizeLauncherJson(layout);
    return {
      layout: launcherJsonToUiLayout(clean),
      rev: clean.rev,
    };
  }
  return {
    layout,
    rev: { counter: 0, deviceId: "" },
  };
}

function isLauncherJsonInput(value: unknown): value is LauncherJson {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const source = value as Partial<LauncherJson>;
  return !!source.rev ||
    Array.isArray(source.pages) ||
    (Array.isArray(source.dock) && source.dock.some((item) => !!item && typeof item === "object")) ||
    (Array.isArray(source.folders) && source.folders.some((item) =>
      !!item &&
      typeof item === "object" &&
      Array.isArray((item as { cells?: unknown }).cells)
    ));
}

export function activeBookmarksFromState(state: SyncV2State): BookmarkRecord[] {
  return Object.values(ensureState(state).bookmarks)
    .sort(compareBookmarkRecords)
    .map((raw) => {
      const { rev: _rev, ...bookmark } = raw;
      return bookmark as BookmarkRecord;
    });
}

export function findBookmarkByUrlInState(state: SyncV2State, url: string): BookmarkRecord | null {
  const key = bookmarkUrlKey(url);
  if (!key) return null;
  const clean = ensureState(state);
  const match = clean.bookmarks[key] || Object.values(clean.bookmarks)
    .filter((bookmark) => bookmarkRecordKey(bookmark) === key)
    .sort((left, right) => compareRevision(right.rev, left.rev))[0];
  if (!match) return null;
  const { rev: _rev, ...bookmark } = match;
  return bookmark as BookmarkRecord;
}

export function activeWebAppsFromState(state: SyncV2State): WebAppRecord[] {
  return Object.values(ensureState(state).apps)
    .map((raw) => {
      const { rev: _rev, ...app } = raw;
      return app as WebAppRecord;
    });
}

export function findWebAppsByUrlInState(state: SyncV2State, url: string): WebAppRecord[] {
  const key = identityKeyForUrl(url.trim());
  if (!key) return [];
  return activeWebAppsFromState(state).filter((app) =>
    identityKeyForUrl(app.startUrl || "") === key
  );
}

export function layoutFromState(state: SyncV2State): LauncherLayoutLike {
  return launcherJsonToUiLayout(ensureState(state).layout);
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
      const pendingOutbox = store.outbox;
      const previousState = store.state;
      store = {
        ...store,
        state: mergeState(store.state, remote.state),
        outbox: [],
      };
      if (pendingOutbox.length > 0) {
        store = rebasePendingOperations(store, pendingOutbox);
      }
      if (options.loadLocalSnapshot) {
        store = appendLocalSnapshotOperations(store, await options.loadLocalSnapshot());
      }
      uploadedOperationCount = store.outbox.length;
      mergedState = mergeState(store.state, remote.state);
      stateChanged = canonicalJson(previousState) !== canonicalJson(mergedState);
      launcherChanged = launcherStateSignature(previousState) !== launcherStateSignature(mergedState);
      store = {
        ...store,
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

function rebasePendingOperations(store: SyncV2Store, operations: SyncV2Operation[]): SyncV2Store {
  const next = {
    ...cloneJson(store),
    outbox: [] as SyncV2Operation[],
  };
  uniqueOperations(operations).sort(compareOperations).forEach((operation) => {
    next.counter += 1;
    const rebased = {
      ...operation,
      deviceId: next.deviceId,
      counter: next.counter,
    } as SyncV2Operation;
    applyOperationInPlace(next.state, rebased);
    next.outbox.push(rebased);
  });
  return next;
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

  const layout = pickLauncherJson(left.layout, right.layout);

  return sanitizeState({
    schemaVersion: SYNC_V2_SCHEMA_VERSION,
    bookmarks,
    bookmarkTombstones,
    apps,
    appTombstones,
    layout,
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

function pickLauncherJson(left: LauncherJson, right: LauncherJson): LauncherJson {
  const order = compareLauncherJson(left, right);
  if (order > 0) return cloneJson(left);
  if (order < 0) return cloneJson(right);
  return canonicalJson(left) >= canonicalJson(right) ? cloneJson(left) : cloneJson(right);
}

function compareLauncherJson(left: LauncherJson, right: LauncherJson): number {
  return compareRevision(left.rev, right.rev);
}

function layoutDocumentChanged(left: LauncherLayoutLike, right: LauncherLayoutLike): boolean {
  return canonicalJson(layoutDocumentSignature(left)) !== canonicalJson(layoutDocumentSignature(right));
}

function layoutDocumentSignature(layout: LauncherLayoutLike): unknown {
  return {
    cells: (layout.cells || []).map((cell) => ({
      id: cell.id,
      page: Number.isInteger(cell.page) && Number(cell.page) >= 0 ? Number(cell.page) : 0,
      index: Number.isInteger(cell.index) && Number(cell.index) >= 0 ? Number(cell.index) : -1,
    })),
    dock: layout.dock || [],
    folders: layout.folders || [],
  };
}

function launcherJsonFromUiLayout(
  layout: LauncherLayoutLike,
  rev: Revision,
): LauncherJson {
  const pages = pagesFromUiCells(layout.cells || []);
  const dock = (layout.dock || [])
    .map((id, index) => launcherCell(id, index))
    .filter((cell): cell is LauncherCell => !!cell);
  const folders = (layout.folders || [])
    .map((folder): LauncherFolder | null => {
      const id = cleanOptionalString(folder.id);
      if (!id) return null;
      return {
        id,
        ...(typeof folder.title === "string" ? { title: folder.title } : {}),
        cells: (folder.childIds || [])
          .map((childId, index) => launcherCell(childId, index))
          .filter((cell): cell is LauncherCell => !!cell),
      };
    })
    .filter((folder): folder is LauncherFolder => !!folder);
  return {
    ...(pages.length > 0 ? { pages } : {}),
    ...(dock.length > 0 ? { dock } : {}),
    ...(folders.length > 0 ? { folders } : {}),
    rev,
  };
}

function pagesFromUiCells(cells: NonNullable<LauncherLayoutLike["cells"]>): LauncherPage[] {
  const pages = new Map<number, LauncherCell[]>();
  cells.forEach((cell, fallbackIndex) => {
    const page = Number.isInteger(cell.page) && Number(cell.page) >= 0 ? Number(cell.page) : 0;
    const next = launcherCell(cell.id, cell.index, fallbackIndex);
    if (!next) return;
    pages.set(page, [...(pages.get(page) || []), next]);
  });
  return [...pages.entries()]
    .sort(([left], [right]) => left - right)
    .map(([, cells]) => ({ cells: sortLauncherCells(cells) }));
}

function launcherJsonToUiLayout(layout: LauncherJson): LauncherLayoutLike {
  const clean = normalizeLauncherJson(layout);
  const cells = (clean.pages || []).flatMap((page, pageIndex) =>
    sortLauncherCells(page.cells || []).map((cell) => ({
      id: cell.id,
      page: pageIndex,
      row: 0,
      column: 0,
      index: cell.index,
    }))
  );
  return {
    version: 4,
    cells,
    dock: sortLauncherCells(clean.dock || []).map((cell) => cell.id),
    folders: (clean.folders || []).map((folder) => ({
      id: folder.id,
      title: folder.title || "Folder",
      childIds: sortLauncherCells(folder.cells || []).map((cell) => cell.id),
    })),
  };
}

function normalizeLauncherJson(value: unknown): LauncherJson {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return { rev: { counter: 0, deviceId: "" } };
  }
  const source = value as Partial<LauncherJson>;
  return {
    ...(Array.isArray(source.pages) ? { pages: source.pages.map(normalizeLauncherPage).filter((page) => (page.cells || []).length > 0) } : {}),
    ...(Array.isArray(source.dock) ? { dock: normalizeLauncherCells(source.dock).slice(0, 4) } : {}),
    ...(Array.isArray(source.folders) ? { folders: source.folders.map(normalizeLauncherFolder).filter((folder): folder is LauncherFolder => !!folder) } : {}),
    rev: normalizeRevision(source.rev),
  };
}

function sanitizeLauncherJson(
  value: unknown,
  apps: Record<string, SyncV2App> = {},
  appTombstones: Record<string, SyncV2Tombstone> = {},
): LauncherJson {
  const layout = normalizeLauncherJson(value);
  const used = new Set<string>();
  const keepCell = (cell: LauncherCell): LauncherCell | null => {
    if (!launcherItemAvailable(cell.id, apps, appTombstones)) return null;
    if (used.has(cell.id)) return null;
    used.add(cell.id);
    return cell;
  };
  const pages = (layout.pages || [])
    .map((page) => ({ cells: sortLauncherCells((page.cells || []).map(keepCell).filter((cell): cell is LauncherCell => !!cell)) }))
    .filter((page) => page.cells.length > 0);
  const dock = sortLauncherCells((layout.dock || []).map(keepCell).filter((cell): cell is LauncherCell => !!cell)).slice(0, 4);
  const folders = (layout.folders || [])
    .map((folder): LauncherFolder | null => {
      const folderIsPlaced = used.has(folder.id) || launcherContainsCell(pages, folder.id) || dock.some((cell) => cell.id === folder.id);
      const cells = sortLauncherCells((folder.cells || []).map(keepCell).filter((cell): cell is LauncherCell => !!cell));
      if (!folderIsPlaced && cells.length === 0) return null;
      return { id: folder.id, ...(folder.title ? { title: folder.title } : {}), ...(cells.length > 0 ? { cells } : {}) };
    })
    .filter((folder): folder is LauncherFolder => !!folder);
  return {
    ...(pages.length > 0 ? { pages } : {}),
    ...(dock.length > 0 ? { dock } : {}),
    ...(folders.length > 0 ? { folders } : {}),
    rev: layout.rev,
  };
}

function normalizeLauncherPage(page: unknown): LauncherPage {
  if (!page || typeof page !== "object" || Array.isArray(page)) return {};
  return { cells: normalizeLauncherCells((page as Partial<LauncherPage>).cells) };
}

function normalizeLauncherFolder(folder: unknown): LauncherFolder | null {
  if (!folder || typeof folder !== "object" || Array.isArray(folder)) return null;
  const source = folder as Partial<LauncherFolder>;
  const id = cleanOptionalString(source.id);
  if (!id) return null;
  return {
    id,
    ...(typeof source.title === "string" ? { title: source.title } : {}),
    cells: normalizeLauncherCells(source.cells),
  };
}

function normalizeLauncherCells(cells: unknown): LauncherCell[] {
  if (!Array.isArray(cells)) return [];
  return sortLauncherCells(cells
    .map((cell, fallbackIndex) => {
      if (typeof cell === "string") return launcherCell(cell, fallbackIndex);
      if (!cell || typeof cell !== "object" || Array.isArray(cell)) return null;
      const source = cell as Partial<LauncherCell>;
      return launcherCell(source.id, source.index, fallbackIndex);
    })
    .filter((cell): cell is LauncherCell => !!cell));
}

function launcherCell(idValue: unknown, indexValue: unknown, fallbackIndex = 0): LauncherCell | null {
  const id = cleanOptionalString(idValue);
  if (!id) return null;
  const index = Number(indexValue);
  return {
    id,
    index: Number.isFinite(index) && index >= 0 ? Math.floor(index) : fallbackIndex,
  };
}

function sortLauncherCells(cells: LauncherCell[]): LauncherCell[] {
  return [...cells].sort((left, right) => left.index - right.index || left.id.localeCompare(right.id));
}

function launcherItemAvailable(id: string, apps: Record<string, SyncV2App>, appTombstones: Record<string, SyncV2Tombstone>): boolean {
  if (!id.startsWith("app:")) return true;
  const appId = id.slice(4);
  return !!appId && !!apps[appId] && !appTombstones[appId];
}

function launcherContainsCell(pages: LauncherPage[], id: string): boolean {
  return pages.some((page) => (page.cells || []).some((cell) => cell.id === id));
}

function launcherContainsItem(layout: LauncherJson, idValue: unknown): boolean {
  const id = cleanOptionalString(idValue);
  if (!id) return false;
  const clean = normalizeLauncherJson(layout);
  return launcherContainsCell(clean.pages || [], id) ||
    (clean.dock || []).some((cell) => cell.id === id) ||
    (clean.folders || []).some((folder) =>
      folder.id === id || (folder.cells || []).some((cell) => cell.id === id)
    );
}

function placeLauncherItem(layout: LauncherJson, item: SyncV2LayoutPlacement, rev: Revision): LauncherJson {
  const withoutItem = removeLauncherItem(layout, item.id, rev);
  const next = normalizeLauncherJson(withoutItem);
  const cell = launcherCell(item.id, item.index);
  if (!cell) return { ...next, rev };

  const container = cleanOptionalString(item.container);
  if (container === "dock") {
    next.dock = sortLauncherCells([...(next.dock || []), cell]).slice(0, 4);
  } else {
    const folderId = launcherFolderIdFromContainer(container);
    if (folderId) {
      const folder = (next.folders || []).find((candidate) => candidate.id === folderId) || {
        id: folderId,
        title: "Folder",
        cells: [],
      };
      folder.cells = sortLauncherCells([...(folder.cells || []), cell]);
      if (!next.folders?.some((candidate) => candidate.id === folder.id)) {
        next.folders = [...(next.folders || []), folder];
      }
    } else {
      const pageIndex = launcherPageIndexFromContainer(container);
      const pages = [...(next.pages || [])];
      while (pages.length <= pageIndex) pages.push({ cells: [] });
      pages[pageIndex] = {
        cells: sortLauncherCells([...(pages[pageIndex].cells || []), cell]),
      };
      next.pages = pages;
    }
  }

  if (item.kind === "folder") {
    const folder = (next.folders || []).find((candidate) => candidate.id === item.id);
    if (folder) {
      if (item.title) folder.title = item.title;
    } else {
      next.folders = [...(next.folders || []), {
        id: item.id,
        ...(item.title ? { title: item.title } : {}),
        cells: [],
      }];
    }
  }

  return normalizeLauncherJson({ ...next, rev });
}

function removeLauncherItem(layout: LauncherJson, idValue: unknown, rev: Revision): LauncherJson {
  const id = cleanOptionalString(idValue);
  const clean = normalizeLauncherJson(layout);
  if (!id) return { ...clean, rev };
  return normalizeLauncherJson({
    pages: (clean.pages || [])
      .map((page) => ({ cells: (page.cells || []).filter((cell) => cell.id !== id) }))
      .filter((page) => page.cells.length > 0),
    dock: (clean.dock || []).filter((cell) => cell.id !== id),
    folders: (clean.folders || [])
      .filter((folder) => folder.id !== id)
      .map((folder) => ({
        id: folder.id,
        ...(folder.title ? { title: folder.title } : {}),
        cells: (folder.cells || []).filter((cell) => cell.id !== id),
      }))
      .filter((folder) => folder.cells.length > 0 || launcherFolderIsPlaced(clean, folder.id)),
    rev,
  });
}

function launcherFolderIsPlaced(layout: LauncherJson, folderId: string): boolean {
  return launcherContainsCell(layout.pages || [], folderId) ||
    (layout.dock || []).some((cell) => cell.id === folderId);
}

function launcherFolderIdFromContainer(container: string): string {
  if (!container) return "";
  if (container.startsWith("folder:")) return container;
  if (container !== "dock" && !container.startsWith("desktop:") && !container.startsWith("page:")) return container;
  return "";
}

function launcherPageIndexFromContainer(container: string): number {
  const match = /^(?:desktop|page):(\d+)$/i.exec(container);
  if (!match) return 0;
  return normalizeLayoutIndex(match[1]);
}

function launcherCellCount(layout: LauncherJson): number {
  const clean = normalizeLauncherJson(layout);
  return (clean.pages || []).reduce((count, page) => count + (page.cells || []).length, 0) +
    (clean.dock || []).length +
    (clean.folders || []).reduce((count, folder) => count + 1 + (folder.cells || []).length, 0);
}

function normalizeRevision(value: unknown, fallbackDeviceId: unknown = ""): Revision {
  const fallback = cleanOptionalString(fallbackDeviceId);
  if (!value || typeof value !== "object" || Array.isArray(value)) return { counter: 0, deviceId: fallback };
  const source = value as Partial<Revision>;
  return {
    counter: Number.isSafeInteger(source.counter) ? Math.max(0, Number(source.counter)) : 0,
    deviceId: cleanOptionalString(source.deviceId) || fallback,
  };
}

function normalizeTimestamp(value: unknown): number {
  const timestamp = Number(value);
  return Number.isFinite(timestamp) && timestamp > 0 ? Math.floor(timestamp) : 0;
}

function compactStateTombstones(state: SyncV2State): void {
  state.bookmarkTombstones = compactTombstones(state.bookmarkTombstones);
  state.appTombstones = compactTombstones(state.appTombstones);
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
      bookmarks: bookmarksJsonRecords(clean.bookmarks),
      bookmarkTombstones: clean.bookmarkTombstones,
    } satisfies BookmarksJson,
    [WEBAPPS_FILE]: {
      schemaVersion: SYNC_V2_SCHEMA_VERSION,
      apps: webAppsJsonRecords(clean.apps),
      appTombstones: clean.appTombstones,
    } satisfies WebAppsJson,
    [LAUNCHER_FILE]: clean.layout,
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
        launcherPages: clean.layout.pages?.length || 0,
        launcherFolders: clean.layout.folders?.length || 0,
      },
    },
  };
}

function bookmarksJsonRecords(bookmarks: Record<string, SyncV2Bookmark>): Record<string, BookmarkSyncRecord> {
  const result: Record<string, BookmarkSyncRecord> = {};
  Object.entries(bookmarks).forEach(([key, bookmark]) => {
    result[key] = {
      url: bookmark.url,
      title: bookmark.title,
      createdAt: bookmark.createdAt,
      updatedAt: bookmark.updatedAt,
      ...(bookmark.iconDataUrl ? { iconDataUrl: bookmark.iconDataUrl } : {}),
      rev: bookmark.rev,
    };
  });
  return result;
}

function webAppsJsonRecords(apps: Record<string, SyncV2App>): Record<string, WebAppSyncRecord> {
  const result: Record<string, WebAppSyncRecord> = {};
  Object.entries(apps).forEach(([key, app]) => {
    result[key] = {
      id: app.id,
      name: app.name,
      startUrl: app.startUrl,
      themeColor: app.themeColor,
      displayMode: app.displayMode,
      createdAt: app.createdAt,
      lastOpenedAt: app.lastOpenedAt,
      updatedAt: app.updatedAt,
      ...(app.iconDataUrl ? { iconDataUrl: app.iconDataUrl } : {}),
      ...(app.iconSource ? { iconSource: app.iconSource } : {}),
      rev: app.rev,
    };
  });
  return result;
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
  if (!value || typeof value !== "object" || Array.isArray(value)) return;
  const root = value as Record<string, unknown>;
  mergeLwwMapInto(state.bookmarks, readBookmarkRecordMap(root.bookmarks));
  mergeLwwMapInto(state.bookmarkTombstones, readBookmarkTombstoneMap(root.bookmarkTombstones));
}

function mergeWebAppFileIntoState(state: SyncV2State, value: unknown): void {
  if (!value || typeof value !== "object" || Array.isArray(value)) return;
  const root = value as Record<string, unknown>;
  mergeLwwMapInto(state.apps, readWebAppRecordMap(root.apps));
  mergeLwwMapInto(state.appTombstones, readRecordMap<SyncV2Tombstone>(root.appTombstones));
}

function mergeLauncherFileIntoState(state: SyncV2State, value: unknown): void {
  if (!value || typeof value !== "object") return;
  const layout = readLauncherLayoutState(value);
  if (layout) state.layout = pickLauncherJson(state.layout, layout);
}

function readLauncherLayoutState(value: unknown): LauncherJson | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const layout = normalizeLauncherJson(value);
  return layout.rev.counter > 0 || layout.rev.deviceId ? layout : null;
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
    if (record) result[bookmarkRecordKey(record)] = record;
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
    const cleanKey = String(key || "").trim();
    if (cleanKey) result[cleanKey] = cloneJson(tombstone);
  });
  return result;
}

function readWebAppRecordMap(value: unknown): Record<string, SyncV2App> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const result: Record<string, SyncV2App> = {};
  Object.entries(value as Record<string, unknown>).forEach(([key, raw]) => {
    const record = normalizeStoredWebAppRecord(raw, key);
    if (record) result[record.id] = record;
  });
  return result;
}

function countRemoteRecords(state: SyncV2State): number {
  const clean = ensureState(state);
  return Object.keys(clean.bookmarks).length +
    Object.keys(clean.bookmarkTombstones).length +
    Object.keys(clean.apps).length +
    Object.keys(clean.appTombstones).length +
    launcherCellCount(clean.layout);
}

function applyOperationInPlace(state: SyncV2State, operation: SyncV2Operation): void {
  const rev = operationRevision(operation);
  if (operation.type === "bookmark.upsert") {
    const bookmark = normalizeBookmarkRecordInput(operation.bookmark, state);
    if (!bookmark) return;
    const tombstoneKeys = bookmarkTombstoneKeysForRecord(bookmark);
    const tombstone = tombstoneKeys.reduce<SyncV2Tombstone | undefined>(
      (current, key) => pickLww(current, state.bookmarkTombstones[key]),
      undefined,
    );
    if (tombstone && compareRevision(tombstone.rev, rev) >= 0) return;
    const key = bookmarkRecordKey(bookmark);
    const current = state.bookmarks[key];
    if (current && compareRevision(current.rev, rev) > 0) return;
    const recordRev = revisionWithDevice(rev, current?.rev.deviceId || operation.deviceId);
    state.bookmarks[key] = {
      ...bookmark,
      updatedAt: bookmark.updatedAt || operation.createdAt,
      deletedAt: null,
      rev: recordRev,
    };
    tombstoneKeys.forEach((key) => {
      delete state.bookmarkTombstones[key];
    });
    dedupeBookmarkUrlKeys(state);
    return;
  }
  if (operation.type === "bookmark.delete") {
    const key = bookmarkDeleteOperationKey(state, operation);
    if (!key) return;
    const matches = bookmarkRecordsForTombstoneKey(state.bookmarks, key);
    if (matches.some((current) => compareRevision(current.rev, rev) > 0)) return;
    const tombstone = state.bookmarkTombstones[key];
    if (tombstone && compareRevision(tombstone.rev, rev) >= 0) return;
    const tombstoneRev = revisionWithDevice(rev, matches[0]?.rev.deviceId || tombstone?.rev.deviceId || operation.deviceId);
    matches.forEach((bookmark) => {
      delete state.bookmarks[bookmarkRecordKey(bookmark)];
    });
    state.bookmarkTombstones[key] = { deletedAt: new Date(operation.createdAt).toISOString(), rev: tombstoneRev };
    return;
  }
  if (operation.type === "app.upsert") {
    const id = operation.app.id.trim();
    if (!id || state.appTombstones[id]) return;
    const current = state.apps[id];
    if (current && compareRevision(current.rev, rev) > 0) return;
    const recordRev = revisionWithDevice(rev, current?.rev.deviceId || operation.deviceId);
    state.apps[id] = {
      id,
      name: operation.app.name.trim() || operation.app.startUrl,
      startUrl: operation.app.startUrl,
      themeColor: operation.app.themeColor ?? current?.themeColor ?? 0xff126d6a,
      displayMode: operation.app.displayMode || current?.displayMode || "standalone",
      createdAt: operation.app.createdAt || current?.createdAt || operation.createdAt,
      lastOpenedAt: operation.app.lastOpenedAt || current?.lastOpenedAt || operation.createdAt,
      updatedAt: operation.app.updatedAt || operation.createdAt,
      deletedAt: null,
      iconDataUrl: operation.app.iconDataUrl ?? current?.iconDataUrl ?? null,
      iconSource: operation.app.iconSource || current?.iconSource || (operation.app.iconDataUrl ? "custom" : "title"),
      rev: recordRev,
    };
    return;
  }
  if (operation.type === "app.delete") {
    const id = operation.appId.trim();
    if (!id) return;
    const current = state.apps[id];
    const tombstone = state.appTombstones[id];
    const tombstoneRev = revisionWithDevice(rev, current?.rev.deviceId || tombstone?.rev.deviceId || operation.deviceId);
    if (!tombstone || compareRevision(tombstone.rev, tombstoneRev) < 0) {
      state.appTombstones[id] = { deletedAt: new Date(operation.createdAt).toISOString(), rev: tombstoneRev };
    }
    delete state.apps[id];
    state.layout = removeLauncherItem(state.layout, `app:${id}`, tombstoneRev);
    return;
  }
  if (operation.type === "layout.replace") {
    const nextLayout = launcherJsonFromUiLayout(
      operation.layout,
      rev,
    );
    if (compareLauncherJson(state.layout, nextLayout) > 0) return;
    state.layout = nextLayout;
    return;
  }
  if (operation.type === "layout.place") {
    if (compareRevision(state.layout.rev, rev) > 0) return;
    const item = normalizeLayoutItemInput(operation.item);
    if (!item) return;
    if (item.kind === "app" && (!item.appId || !state.apps[item.appId] || state.appTombstones[item.appId])) return;
    state.layout = placeLauncherItem(state.layout, item, rev);
    return;
  }
  if (operation.type === "layout.hide") {
    if (compareRevision(state.layout.rev, rev) > 0) return;
    const itemId = operation.itemId.trim();
    if (!itemId) return;
    state.layout = removeLauncherItem(state.layout, itemId, rev);
    return;
  }
  if (operation.type === "layout.deleteItem") {
    if (compareRevision(state.layout.rev, rev) > 0) return;
    const itemId = operation.itemId.trim();
    if (!itemId) return;
    state.layout = removeLauncherItem(state.layout, itemId, rev);
  }
}

function operationRevision(operation: SyncV2Operation): Revision {
  return { counter: operation.createdAt, deviceId: operation.deviceId };
}

function revisionWithDevice(rev: Revision, deviceId: unknown): Revision {
  return {
    counter: rev.counter,
    deviceId: cleanOptionalString(deviceId) || rev.deviceId,
  };
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
  visit(state.layout);
  return max;
}

function normalizeLayoutItemInput(raw: unknown, fallbackId?: string): SyncV2LayoutPlacement | null {
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
    index: normalizeLayoutIndex(source.index),
  };
}

function normalizeLayoutIndex(indexValue: unknown, fallback = 0): number {
  const index = Number(indexValue);
  if (Number.isFinite(index)) return Math.max(0, Math.floor(index));
  return fallback;
}

function layoutItemKind(id: string): "app" | "folder" | "system" {
  if (id.startsWith("app:")) return "app";
  if (id.startsWith("folder:")) return "folder";
  return "system";
}

function normalizeStoredBookmarkRecord(raw: unknown, fallbackId = ""): SyncV2Bookmark | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const source = raw as Partial<SyncV2Bookmark>;
  if (!source.rev) return null;
  const fallbackUrl = looksLikeUrlKey(fallbackId) ? fallbackId : "";
  const record = normalizeBookmarkRecordInput({
    ...source,
    url: source.url || fallbackUrl,
  });
  return record ? { ...record, rev: normalizeRevision(source.rev) } : null;
}

function normalizeStoredWebAppRecord(raw: unknown, fallbackId = ""): SyncV2App | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const source = raw as Partial<SyncV2App>;
  if (!source.rev) return null;
  const id = cleanOptionalString(source.id) || cleanOptionalString(fallbackId);
  const startUrl = String(source.startUrl || "").trim();
  if (!id || !startUrl) return null;
  const updatedAt = Number(source.updatedAt) || Number(source.createdAt) || Date.now();
  const iconSource = source.iconSource === "custom" || source.iconSource === "site" || source.iconSource === "title"
    ? source.iconSource
    : undefined;
  return {
    id,
    name: String(source.name || startUrl).trim() || startUrl,
    startUrl,
    themeColor: Number(source.themeColor) || 0xff126d6a,
    displayMode: String(source.displayMode || "standalone"),
    createdAt: Number(source.createdAt) || updatedAt,
    lastOpenedAt: Number(source.lastOpenedAt) || updatedAt,
    updatedAt,
    deletedAt: source.deletedAt == null ? null : Number(source.deletedAt) || Date.parse(String(source.deletedAt)) || 0,
    iconDataUrl: cleanOptionalString(source.iconDataUrl) || null,
    ...(iconSource ? { iconSource } : {}),
    rev: normalizeRevision(source.rev),
  };
}

function normalizeBookmarkRecordInput(input: Partial<BookmarkRecord>, _state?: SyncV2State): BookmarkRecord | null {
  const url = bookmarkUrlKey(String(input.url || "").trim());
  if (!url) return null;
  const title = String(input.title || url).trim() || url;
  return {
    url,
    title,
    createdAt: Number(input.createdAt) || Number(input.updatedAt) || Date.now(),
    updatedAt: Number(input.updatedAt) || Number(input.createdAt) || Date.now(),
    deletedAt: input.deletedAt == null ? null : Number(input.deletedAt) || Date.parse(String(input.deletedAt)) || 0,
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

function normalizeBookmarkTombstoneKey(key: string, bookmarks: Record<string, SyncV2Bookmark>): string {
  const value = String(key || "").trim();
  if (!value) return "";
  const bookmark = bookmarks[value];
  if (bookmark) return bookmarkTombstoneKey(bookmark);
  const alias = Object.values(bookmarks).find((candidate) => bookmarkTombstoneKeysForRecord(candidate).includes(value));
  if (alias) return bookmarkTombstoneKey(alias);
  return looksLikeUrlKey(value) ? identityKeyForUrl(value) : "";
}

function applyBookmarkTombstones(
  bookmarks: Record<string, SyncV2Bookmark>,
  tombstones: Record<string, SyncV2Tombstone>,
): void {
  Object.keys(tombstones).forEach((key) => {
    const tombstone = tombstones[key];
    const matches = bookmarkRecordsForTombstoneKey(bookmarks, key);
    let hasNewerBookmark = false;
    matches.forEach((bookmark) => {
      if (compareRevision(tombstone.rev, bookmark.rev) >= 0) {
        delete bookmarks[bookmarkRecordKey(bookmark)];
      } else {
        hasNewerBookmark = true;
      }
    });
    if (hasNewerBookmark) {
      delete tombstones[key];
    }
  });
}

function looksLikeUrlKey(value: string): boolean {
  return /^[a-z][a-z0-9+.-]*:\/\//i.test(value);
}

function dedupeBookmarkUrlKeys(state: SyncV2State): void {
  const selected = new Map<string, SyncV2Bookmark>();
  Object.values(state.bookmarks).forEach((bookmark) => {
    const key = bookmarkRecordKey(bookmark);
    if (!key) {
      Object.entries(state.bookmarks).forEach(([id, candidate]) => {
        if (candidate === bookmark) delete state.bookmarks[id];
      });
      return;
    }
    const current = selected.get(key);
    if (!current || compareRevision(bookmark.rev, current.rev) > 0) {
      selected.set(key, bookmark);
    }
  });
  Object.entries(state.bookmarks).forEach(([id, bookmark]) => {
    const key = bookmarkRecordKey(bookmark);
    const keep = selected.get(key);
    if (!key || !keep || keep !== bookmark || id !== key) {
      delete state.bookmarks[id];
    }
  });
}

function bookmarkDeleteInput(bookmark: BookmarkRecord): BookmarkDeleteInput {
  return {
    url: bookmark.url,
    title: bookmark.title,
  };
}

function bookmarkDeleteOperationKey(_state: SyncV2State, operation: SyncV2Operation): string {
  const legacy = operation as unknown as Record<string, unknown>;
  const url = typeof legacy.url === "string"
    ? legacy.url.trim()
    : "";
  return bookmarkUrlKey(url);
}

function bookmarkRecordKey(bookmark: Pick<BookmarkRecord, "url">): string {
  return bookmarkUrlKey(bookmark.url);
}

function bookmarkUrlKey(value: string): string {
  const url = normalizeBookmarkKey(value);
  return looksLikeUrlKey(url) ? identityKeyForUrl(url) : "";
}

function bookmarkTombstoneKey(bookmark: Pick<BookmarkRecord, "url" | "title">): string {
  return bookmarkUrlKey(bookmark.url);
}

function bookmarkTombstoneKeysForRecord(bookmark: Pick<BookmarkRecord, "url" | "title">): string[] {
  const keys = new Set<string>();
  const addUrl = (value: string | undefined | null): void => {
    const clean = bookmarkUrlKey(String(value || ""));
    if (clean) keys.add(clean);
  };
  addUrl(bookmark.url);
  return [...keys];
}

function bookmarkRecordsForTombstoneKey(
  bookmarks: Record<string, SyncV2Bookmark>,
  key: string,
  fallbackId = "",
): SyncV2Bookmark[] {
  const cleanKey = String(key || "").trim();
  const cleanFallbackId = String(fallbackId || "").trim();
  if (!cleanKey && !cleanFallbackId) return [];
  const urlKey = bookmarkUrlKey(cleanKey) || bookmarkUrlKey(cleanFallbackId);
  return Object.values(bookmarks).filter((bookmark) =>
    !!urlKey && bookmarkRecordKey(bookmark) === urlKey
  );
}

function cleanOptionalString(value: unknown): string {
  return typeof value === "string" && value.trim() && value.trim() !== "null" && value.trim() !== "undefined"
    ? value.trim()
    : "";
}

function compareBookmarkRecords(left: SyncV2Bookmark, right: SyncV2Bookmark): number {
  return left.title.localeCompare(right.title) ||
    left.url.localeCompare(right.url);
}

function bookmarkFieldsChanged(left: SyncV2Bookmark, right: BookmarkRecord): boolean {
  return left.url !== right.url ||
    left.title !== right.title ||
    (left.iconDataUrl || null) !== (right.iconDataUrl || null);
}

function appFieldsChanged(left: SyncV2App, right: WebAppRecord): boolean {
  return left.name !== right.name ||
    left.startUrl !== right.startUrl ||
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
