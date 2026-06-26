import type {
  BookmarkRecord,
  BookmarksJson,
  BookmarkSyncRecord,
  LauncherCell,
  LauncherFolder,
  LauncherJson,
  LauncherPage,
  SyncRevision,
  SyncJsonByFileName,
  SyncJsonFileName,
  SyncTombstone,
  SyncV2State,
  WebAppRecord,
  WebAppsJson,
  WebAppSyncRecord,
} from "./sync-json-types";
import { WebDavClient, WebDavConflictError, type WebDavSettings } from "./webdav";

export type {
  SyncV2State,
} from "./sync-json-types";

export const SYNC_V2_SCHEMA_VERSION = 2;
const BOOKMARKS_FILE = "bookmarks.json";
const WEBAPPS_FILE = "webapps.json";
const LAUNCHER_FILE = "launcher.json";
const MANIFEST_FILE = "manifest.json";
export const SYNC_STATE_FILE_NAMES = [BOOKMARKS_FILE, WEBAPPS_FILE, LAUNCHER_FILE] as const satisfies readonly SyncJsonFileName[];
const TOMBSTONE_COMPACT_TRIGGER = 1500;
const TOMBSTONE_COMPACT_TARGET = 500;
const TOMBSTONE_MIN_RETENTION_MS = 0;

export type SyncV2Operation =
  | SyncV2BaseOperation<"bookmark.upsert"> & { bookmark: BookmarkRecord }
  | SyncV2BaseOperation<"bookmark.delete"> & BookmarkDeleteInput
  | SyncV2BaseOperation<"app.upsert"> & { app: Partial<WebAppRecord> & { id: string; name: string; startUrl: string } }
  | SyncV2BaseOperation<"app.delete"> & { appId: string }
  | SyncV2BaseOperation<"layout.replace"> & { layout: LauncherJson };

type BookmarkDeleteOperation = Extract<SyncV2Operation, { type: "bookmark.delete" }>;

type SyncV2BaseOperation<T extends string> = {
  schemaVersion: 2;
  opId: string;
  deviceId: string;
  createdAt: number;
  type: T;
};

type SyncV2OperationInput =
  | { type: "bookmark.upsert"; bookmark: BookmarkRecord }
  | ({ type: "bookmark.delete" } & BookmarkDeleteInput)
  | { type: "app.upsert"; app: Partial<WebAppRecord> & { id: string; name: string; startUrl: string } }
  | { type: "app.delete"; appId: string }
  | { type: "layout.replace"; layout: LauncherJson };

export type SyncV2Mutation = {
  deviceId: string;
  state: SyncV2State;
};

type SnapshotRecordCollection<T> = Array<T> | Record<string, T>;

export type SyncV2LocalSnapshot = {
  bookmarks?: SnapshotRecordCollection<BookmarkRecord & { rev?: Partial<SyncRevision> }>;
  webApps?: SnapshotRecordCollection<WebAppRecord & { rev?: Partial<SyncRevision> }>;
  layout?: LauncherJson | null;
};

type SnapshotAppendOptions = {
  forceLayout?: boolean;
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

export type SyncV2Mode = "merge" | "pullRemote" | "pushLocal";

export type SyncStateFileStorage = {
  readFile: (path: SyncJsonFileName) => Promise<unknown | null | undefined>;
  saveFile: (path: SyncJsonFileName, data: SyncJsonByFileName[SyncJsonFileName]) => Promise<void>;
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

type SanitizeStateOptions = {
  pruneLauncherAppRefs?: boolean;
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
      rev: { updatedAt: 0, deviceId: "" },
    },
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

function ensureMergeInputState(value: unknown): SyncV2State {
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
  }, { pruneLauncherAppRefs: false });
}

export function canonicalJson(value: unknown): string {
  return JSON.stringify(canonicalize(value));
}

export async function readSyncStateFromFiles(files: Pick<SyncStateFileStorage, "readFile">): Promise<SyncV2State> {
  const [bookmarks, webApps, launcher] = await Promise.all([
    files.readFile(BOOKMARKS_FILE),
    files.readFile(WEBAPPS_FILE),
    files.readFile(LAUNCHER_FILE),
  ]);
  const state = createEmptyState();
  mergeBookmarkFileIntoState(state, parseJsonFileValue(bookmarks));
  mergeWebAppFileIntoState(state, parseJsonFileValue(webApps));
  mergeLauncherFileIntoState(state, parseJsonFileValue(launcher));
  return ensureState(state);
}

export async function saveSyncStateToFiles(files: SyncStateFileStorage, state: SyncV2State): Promise<void> {
  const desired = stateToSyncJsonFiles(state);
  await Promise.all(SYNC_STATE_FILE_NAMES.map(async (path) => {
    const current = parseJsonFileValue(await files.readFile(path));
    if (canonicalJson(current) === canonicalJson(desired[path])) return;
    await files.saveFile(path, desired[path]);
  }));
}

export function stateToSyncJsonFiles(state: SyncV2State): SyncJsonByFileName {
  const remoteFiles = stateToRemoteFiles(state);
  return {
    [BOOKMARKS_FILE]: remoteFiles[BOOKMARKS_FILE] as BookmarksJson,
    [WEBAPPS_FILE]: remoteFiles[WEBAPPS_FILE] as WebAppsJson,
    [LAUNCHER_FILE]: remoteFiles[LAUNCHER_FILE] as LauncherJson,
  };
}

function sanitizeState(state: SyncV2State, options: SanitizeStateOptions = {}): SyncV2State {
  const pruneLauncherAppRefs = options.pruneLauncherAppRefs !== false;
  const clean = cloneJson(state);

  const bookmarks: Record<string, BookmarkSyncRecord> = {};
  Object.entries(clean.bookmarks).forEach(([fallbackId, record]) => {
    const normalized = normalizeStoredBookmarkRecord(record, fallbackId);
    if (normalized) bookmarks[bookmarkRecordKey(normalized)] = normalized;
  });
  clean.bookmarks = bookmarks;
  clean.bookmarkTombstones = normalizeBookmarkTombstones(clean.bookmarkTombstones, clean.bookmarks);
  applyBookmarkTombstones(clean.bookmarks, clean.bookmarkTombstones);
  dedupeBookmarkUrlKeys(clean);

  const apps: Record<string, WebAppSyncRecord> = {};
  Object.entries(clean.apps).forEach(([fallbackId, record]) => {
    const normalized = normalizeStoredWebAppRecord(record, fallbackId);
    if (normalized) apps[normalized.id] = normalized;
  });
  clean.apps = apps;

  Object.keys(clean.appTombstones).forEach((appId) => {
    delete clean.apps[appId];
  });

  compactStateTombstones(clean);
  clean.layout = pruneLauncherAppRefs
    ? sanitizeLauncherJson(clean.layout, clean.apps, clean.appTombstones)
    : normalizeLauncherJson(clean.layout);

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

function parseJsonFileValue(value: unknown): unknown {
  if (typeof value !== "string") return value;
  const trimmed = value.trim();
  if (!trimmed) return null;
  try {
    return JSON.parse(trimmed);
  } catch {
    return null;
  }
}

export function compareRevision(a: Partial<SyncRevision> = {}, b: Partial<SyncRevision> = {}): number {
  const ac = Number.isSafeInteger(a.updatedAt) ? Number(a.updatedAt) : 0;
  const bc = Number.isSafeInteger(b.updatedAt) ? Number(b.updatedAt) : 0;
  return ac === bc ? 0 : (ac < bc ? -1 : 1);
}

export function reduceOperations(operations: SyncV2Operation[]): SyncV2State {
  const state = createEmptyState();
  uniqueOperations(operations).sort(compareOperations).forEach((operation) => {
    applyOperationInPlace(state, operation);
  });
  return state;
}

export function appendOperation(deviceId: string, state: SyncV2State, operation: SyncV2OperationInput): SyncV2Mutation {
  const nextState = ensureState(state);
  const fullOperation = {
    schemaVersion: SYNC_V2_SCHEMA_VERSION,
    opId: crypto.randomUUID(),
    deviceId,
    createdAt: Date.now(),
    ...operation,
  } as SyncV2Operation;
  applyOperationInPlace(nextState, fullOperation);
  return { deviceId, state: nextState };
}

export function appendWebAppUpsert(deviceId: string, state: SyncV2State, input: WebAppInput): SyncV2Mutation & { app: WebAppSyncRecord } {
  const currentState = ensureState(state);
  const inputId = input.id?.trim() || "";
  const existing = inputId ? currentState.apps[inputId] : undefined;
  const id = existing?.id || inputId || crypto.randomUUID();
  if (currentState.appTombstones[id]) throw new Error("Deleted WebApp UUID cannot be reused.");
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
    iconDataUrl: hasIconDataUrl ? input.iconDataUrl ?? null : existing?.iconDataUrl ?? null,
    iconSource: input.iconSource || (hasIconDataUrl ? (input.iconDataUrl ? "custom" : "title") : existing?.iconSource) || "title",
  };
  const next = appendOperation(deviceId, currentState, { type: "app.upsert", app });
  return { ...next, app: next.state.apps[id] };
}

export function appendWebAppDelete(deviceId: string, state: SyncV2State, appId: string): SyncV2Mutation {
  const id = appId.trim();
  if (!id) return { deviceId, state: ensureState(state) };
  return appendOperation(deviceId, state, { type: "app.delete", appId: id });
}

export function appendLocalSnapshotOperations(
  deviceId: string,
  state: SyncV2State,
  snapshot: SyncV2LocalSnapshot,
  options: SnapshotAppendOptions = {},
): SyncV2Mutation {
  let next: SyncV2Mutation = { deviceId, state: ensureState(state) };
  if (snapshot.bookmarks) next = appendBookmarkSnapshotOperations(next, snapshot.bookmarks);
  if (snapshot.webApps) next = appendWebAppSnapshotOperations(next, snapshot.webApps);
  if (snapshot.layout) next = appendLayoutSnapshotOperations(next.deviceId, next.state, snapshot.layout, { force: options.forceLayout });
  return next;
}

function appendBookmarkSnapshotOperations(
  mutation: SyncV2Mutation,
  bookmarks: SnapshotRecordCollection<BookmarkRecord & { rev?: Partial<SyncRevision> }>,
): SyncV2Mutation {
  let next = mutation;
  const local = new Map<string, BookmarkRecord>();
  snapshotRecordValues(bookmarks).forEach((bookmark) => {
    const record = normalizeBookmarkRecordInput(bookmark, next.state);
    if (!record) return;
    local.set(bookmarkRecordKey(record), record);
  });
  local.forEach((bookmark, key) => {
    const current = next.state.bookmarks[key];
    if (!current || bookmarkFieldsChanged(current, bookmark)) {
      next = appendOperation(next.deviceId, next.state, { type: "bookmark.upsert", bookmark });
    }
  });
  activeBookmarksFromState(next.state).forEach((bookmark) => {
    const key = bookmarkRecordKey(bookmark);
    if (key && !local.has(key)) {
      next = appendOperation(next.deviceId, next.state, { type: "bookmark.delete", ...bookmarkDeleteInput(bookmark) });
    }
  });
  return next;
}

function appendWebAppSnapshotOperations(
  mutation: SyncV2Mutation,
  webApps: SnapshotRecordCollection<WebAppRecord & { rev?: Partial<SyncRevision> }>,
): SyncV2Mutation {
  let next = mutation;
  const local = new Map<string, WebAppRecord & { rev?: Partial<SyncRevision> }>();
  snapshotRecordValues(webApps).forEach((app) => {
    const id = app.id?.trim();
    if (id) local.set(id, { ...app, id });
  });
  local.forEach((app, id) => {
    const current = next.state.apps[id];
    const tombstone = next.state.appTombstones[id];
    if (current && !snapshotCanOverrideRevision(app, current)) return;
    if (!current && tombstone && !snapshotCanOverrideRevision(app, tombstone)) return;
    if (!current || appFieldsChanged(current, app)) {
      next = appendOperation(next.deviceId, next.state, {
        type: "app.upsert",
        app,
      });
    }
  });
  return next;
}

function snapshotRecordValues<T>(records: SnapshotRecordCollection<T>): T[] {
  return Array.isArray(records) ? records : Object.values(records || {});
}

function snapshotCanOverrideRevision(snapshot: { rev?: Partial<SyncRevision> }, target: { rev?: Partial<SyncRevision> }): boolean {
  return compareRevision(normalizeRevision(snapshot.rev), normalizeRevision(target.rev)) >= 0;
}

export function appendLayoutSnapshotOperations(
  deviceId: string,
  state: SyncV2State,
  layout: LauncherJson,
  options: { force?: boolean } = {},
): SyncV2Mutation {
  const currentState = ensureState(state);
  const snapshot = normalizeLauncherJson(layout);
  if (!layoutDocumentChanged(currentState.layout, snapshot)) return { deviceId, state: currentState };
  if (!options.force && snapshot.rev.updatedAt > 0 && compareRevision(snapshot.rev, currentState.layout.rev) <= 0) {
    return { deviceId, state: currentState };
  }
  return appendOperation(deviceId, currentState, { type: "layout.replace", layout: snapshot });
}

export function activeBookmarksFromState(state: SyncV2State): BookmarkRecord[] {
  return Object.values(ensureState(state).bookmarks)
    .sort(compareBookmarkRecords)
    .map((raw) => {
      const { rev: _rev, ...bookmark } = raw;
      return bookmark;
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
  return bookmark;
}

export function activeWebAppsFromState(state: SyncV2State): WebAppRecord[] {
  return Object.values(ensureState(state).apps)
    .map((raw) => {
      const { rev: _rev, ...app } = raw;
      return app;
    });
}

export function findWebAppsByUrlInState(state: SyncV2State, url: string): WebAppRecord[] {
  const key = identityKeyForUrl(url.trim());
  if (!key) return [];
  return activeWebAppsFromState(state).filter((app) =>
    identityKeyForUrl(app.startUrl || "") === key
  );
}

export function layoutFromState(state: SyncV2State): LauncherJson {
  return normalizeLauncherJson(ensureState(state).layout);
}

export async function syncV2(options: {
  settings: WebDavSettings & { deviceId: string };
  loadState: () => Promise<SyncV2State>;
  saveState: (state: SyncV2State) => Promise<void>;
  loadLocalSnapshot?: () => Promise<SyncV2LocalSnapshot>;
  withLocalLock: <T>(operation: () => Promise<T>) => Promise<T>;
  mode?: SyncV2Mode;
}): Promise<SyncV2Result> {
  const client = new WebDavClient(options.settings);
  await client.ensureCollections();
  const mode = options.mode || "merge";

  if (mode === "pullRemote") {
    const remote = await readRemoteStateFiles(client);
    let state = ensureState(remote.state);
    let previousState = createEmptyState();
    let stateChanged = false;
    let launcherChanged = false;

    await options.withLocalLock(async () => {
      previousState = await options.loadState();
      state = ensureState(remote.state);
      stateChanged = canonicalJson(previousState) !== canonicalJson(state);
      launcherChanged = launcherStateSignature(previousState) !== launcherStateSignature(state);
      await options.saveState(state);
    });

    return {
      state,
      stateChanged,
      launcherChanged,
      uploadedOperationCount: 0,
      remoteOperationCount: countRemoteRecords(state),
      pendingOperationCount: 0,
      syncedAt: Date.now(),
    };
  }

  for (let attempt = 0; attempt < 8; attempt += 1) {
    const remote = await readRemoteStateFiles(client);
    let mergedState = createEmptyState();
    let stateChanged = false;
    let launcherChanged = false;
    let uploadedOperationCount = 0;
    let pendingOperationCount = 0;

    await options.withLocalLock(async () => {
      const previousState = await options.loadState();
      let workingState = mode === "pushLocal"
        ? ensureState(previousState)
        : mergeSyncStates(previousState, remote.state);
      if (options.loadLocalSnapshot) {
        const next = appendLocalSnapshotOperations(options.settings.deviceId, workingState, await options.loadLocalSnapshot(), {
          forceLayout: mode === "pushLocal",
        });
        workingState = next.state;
      }
      uploadedOperationCount = 0;
      mergedState = mode === "pushLocal"
        ? ensureState(workingState)
        : mergeSyncStates(workingState, remote.state);
      stateChanged = canonicalJson(previousState) !== canonicalJson(mergedState);
      launcherChanged = launcherStateSignature(previousState) !== launcherStateSignature(mergedState);
      pendingOperationCount = 0;
      await options.saveState(mergedState);
    });

    const desiredFiles = stateToRemoteFiles(mergedState);
    const changedFiles = changedRemoteFiles(remote.files, desiredFiles);

    if (changedFiles.length === 0) {
      await options.withLocalLock(async () => {
        const latestState = await options.loadState();
        if (canonicalJson(latestState) !== canonicalJson(mergedState)) {
          pendingOperationCount = 1;
          return;
        }
        pendingOperationCount = 0;
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
      const latestState = await options.loadState();
      if (canonicalJson(latestState) !== canonicalJson(mergedState)) {
        pendingOperationCount = 1;
        return;
      }
      pendingOperationCount = 0;
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

export function mergeSyncStates(leftState: SyncV2State, rightState: SyncV2State): SyncV2State {
  const left = ensureMergeInputState(leftState);
  const right = ensureMergeInputState(rightState);
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

function mergeLwwMap<T extends { rev?: SyncRevision }>(left: Record<string, T> = {}, right: Record<string, T> = {}): Record<string, T> {
  const result: Record<string, T> = {};
  new Set([...Object.keys(left), ...Object.keys(right)]).forEach((key) => {
    const value = pickLww(left[key], right[key]);
    if (value) result[key] = value;
  });
  return result;
}

function pickLww<T extends { rev?: SyncRevision }>(left?: T, right?: T): T | undefined {
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

function layoutDocumentChanged(left: LauncherJson, right: LauncherJson): boolean {
  return canonicalJson(layoutDocumentSignature(left)) !== canonicalJson(layoutDocumentSignature(right));
}

function layoutDocumentSignature(layout: LauncherJson): unknown {
  const clean = normalizeLauncherJson(layout);
  return {
    pages: (clean.pages || []).slice(0, 1).map((page) => ({
      cells: sortLauncherCells(page.cells || []).map((cell) => ({ id: cell.id, index: cell.index })),
    })),
    dock: sortLauncherCells(clean.dock || []).map((cell) => ({ id: cell.id, index: cell.index })),
    folders: (clean.folders || []).map((folder) => ({
      id: folder.id,
      title: folder.title || "",
      cells: sortLauncherCells(folder.cells || []).map((cell) => ({ id: cell.id, index: cell.index })),
    })),
  };
}

function normalizeLauncherJson(value: unknown): LauncherJson {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return { rev: { updatedAt: 0, deviceId: "" } };
  }
  const source = value as Partial<LauncherJson>;
  const firstPage = Array.isArray(source.pages) ? source.pages[0] : undefined;
  const pageCells = normalizeLauncherCells(firstPage?.cells);
  return {
    ...(pageCells.length > 0 ? { pages: [{ cells: pageCells }] } : {}),
    ...(Array.isArray(source.dock) ? { dock: normalizeLauncherCells(source.dock).slice(0, 4) } : {}),
    ...(Array.isArray(source.folders) ? { folders: source.folders.map(normalizeLauncherFolder).filter((folder): folder is LauncherFolder => !!folder) } : {}),
    rev: normalizeRevision(source.rev),
  };
}

function sanitizeLauncherJson(
  value: unknown,
  apps: Record<string, WebAppSyncRecord> = {},
  appTombstones: Record<string, SyncTombstone> = {},
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

function launcherItemAvailable(id: string, apps: Record<string, WebAppSyncRecord>, appTombstones: Record<string, SyncTombstone>): boolean {
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

function removeLauncherItem(layout: LauncherJson, idValue: unknown, rev: SyncRevision): LauncherJson {
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

function launcherCellCount(layout: LauncherJson): number {
  const clean = normalizeLauncherJson(layout);
  return (clean.pages || []).reduce((count, page) => count + (page.cells || []).length, 0) +
    (clean.dock || []).length +
    (clean.folders || []).reduce((count, folder) => count + 1 + (folder.cells || []).length, 0);
}

function normalizeRevision(value: unknown, fallbackDeviceId: unknown = ""): SyncRevision {
  const fallback = cleanOptionalString(fallbackDeviceId);
  if (!value || typeof value !== "object" || Array.isArray(value)) return { updatedAt: 0, deviceId: fallback };
  const source = value as Partial<SyncRevision>;
  return {
    updatedAt: Number.isSafeInteger(source.updatedAt) ? Math.max(0, Number(source.updatedAt)) : 0,
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

function compactTombstones(tombstones: Record<string, SyncTombstone>): Record<string, SyncTombstone> {
  const entries = Object.entries(tombstones || {}).filter(([, tombstone]) => tombstone?.rev);
  if (entries.length <= TOMBSTONE_COMPACT_TRIGGER) return tombstones;

  const now = Date.now();
  const retained: Array<[string, SyncTombstone]> = [];
  const compactable: Array<[string, SyncTombstone]> = [];
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
  left: [string, SyncTombstone],
  right: [string, SyncTombstone],
): number {
  const timeDelta = tombstoneDeletedAtMs(right[1]) - tombstoneDeletedAtMs(left[1]);
  if (timeDelta !== 0) return timeDelta;
  const revDelta = compareRevision(right[1].rev, left[1].rev);
  if (revDelta !== 0) return revDelta;
  return left[0].localeCompare(right[0]);
}

function tombstoneDeletedAtMs(tombstone: SyncTombstone): number {
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
      updatedAt: maxStateUpdatedAt(clean),
      stateUpdatedAt: maxStateUpdatedAt(clean),
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

function bookmarksJsonRecords(bookmarks: Record<string, BookmarkSyncRecord>): Record<string, BookmarkSyncRecord> {
  const result: Record<string, BookmarkSyncRecord> = {};
  Object.entries(bookmarks).forEach(([key, bookmark]) => {
    result[key] = {
      url: bookmark.url,
      title: bookmark.title,
      createdAt: bookmark.createdAt,
      updatedAt: bookmark.updatedAt,
      rev: bookmark.rev,
    };
  });
  return result;
}

function webAppsJsonRecords(apps: Record<string, WebAppSyncRecord>): Record<string, WebAppSyncRecord> {
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
  mergeLwwMapInto(state.appTombstones, readRecordMap<SyncTombstone>(root.appTombstones));
}

function mergeLauncherFileIntoState(state: SyncV2State, value: unknown): void {
  if (!value || typeof value !== "object") return;
  const layout = readLauncherLayoutState(value);
  if (layout) state.layout = pickLauncherJson(state.layout, layout);
}

function readLauncherLayoutState(value: unknown): LauncherJson | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const layout = normalizeLauncherJson(value);
  return layout.rev.updatedAt > 0 || layout.rev.deviceId ? layout : null;
}

function mergeLwwMapInto<T extends { rev?: SyncRevision }>(target: Record<string, T>, source: Record<string, T>): void {
  const merged = mergeLwwMap(target, source);
  Object.keys(target).forEach((key) => {
    delete target[key];
  });
  Object.assign(target, merged);
}

function readRecordMap<T extends { rev?: SyncRevision }>(value: unknown): Record<string, T> {
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

function readBookmarkRecordMap(value: unknown): Record<string, BookmarkSyncRecord> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const result: Record<string, BookmarkSyncRecord> = {};
  Object.entries(value as Record<string, unknown>).forEach(([key, raw]) => {
    const record = normalizeStoredBookmarkRecord(raw, key);
    if (record) result[bookmarkRecordKey(record)] = record;
  });
  return result;
}

function readBookmarkTombstoneMap(value: unknown): Record<string, SyncTombstone> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const result: Record<string, SyncTombstone> = {};
  Object.entries(value as Record<string, unknown>).forEach(([key, raw]) => {
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return;
    const tombstone = raw as SyncTombstone;
    if (!tombstone.rev) return;
    const cleanKey = String(key || "").trim();
    if (cleanKey) result[cleanKey] = cloneJson(tombstone);
  });
  return result;
}

function readWebAppRecordMap(value: unknown): Record<string, WebAppSyncRecord> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const result: Record<string, WebAppSyncRecord> = {};
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
    const tombstone = tombstoneKeys.reduce<SyncTombstone | undefined>(
      (current, key) => pickLww(current, state.bookmarkTombstones[key]),
      undefined,
    );
    if (tombstone && compareRevision(tombstone.rev, rev) >= 0) return;
    const key = bookmarkRecordKey(bookmark);
    const current = state.bookmarks[key];
    if (current && compareRevision(current.rev, rev) > 0) return;
    const recordRev = revisionWithDevice(rev, current?.rev.deviceId || operation.deviceId);
    state.bookmarks[key] = {
      url: bookmark.url,
      title: bookmark.title,
      createdAt: bookmark.createdAt,
      updatedAt: bookmark.updatedAt || operation.createdAt,
      rev: recordRev,
    };
    tombstoneKeys.forEach((key) => {
      delete state.bookmarkTombstones[key];
    });
    dedupeBookmarkUrlKeys(state);
    return;
  }
  if (operation.type === "bookmark.delete") {
    const key = bookmarkDeleteOperationKey(operation);
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
    const nextLayout = normalizeLauncherJson({ ...operation.layout, rev });
    if (compareLauncherJson(state.layout, nextLayout) > 0) return;
    state.layout = nextLayout;
  }
}

function operationRevision(operation: SyncV2Operation): SyncRevision {
  return { updatedAt: operation.createdAt, deviceId: operation.deviceId };
}

function revisionWithDevice(rev: SyncRevision, deviceId: unknown): SyncRevision {
  return {
    updatedAt: rev.updatedAt,
    deviceId: cleanOptionalString(deviceId) || rev.deviceId,
  };
}

function compareOperations(left: SyncV2Operation, right: SyncV2Operation): number {
  return left.createdAt - right.createdAt ||
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
    Number.isSafeInteger(operation.createdAt) &&
    typeof operation.type === "string";
}

function maxStateUpdatedAt(state: SyncV2State): number {
  let max = 0;
  const visit = (record: { rev?: SyncRevision }) => {
    if (Number.isSafeInteger(record.rev?.updatedAt)) max = Math.max(max, Number(record.rev?.updatedAt));
  };
  Object.values(state.bookmarks).forEach(visit);
  Object.values(state.bookmarkTombstones).forEach(visit);
  Object.values(state.apps).forEach(visit);
  Object.values(state.appTombstones).forEach(visit);
  visit(state.layout);
  return max;
}

function normalizeStoredBookmarkRecord(raw: unknown, fallbackId = ""): BookmarkSyncRecord | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const source = raw as Partial<BookmarkSyncRecord>;
  if (!source.rev) return null;
  const fallbackUrl = looksLikeUrlKey(fallbackId) ? fallbackId : "";
  const record = normalizeBookmarkRecordInput({
    ...source,
    url: source.url || fallbackUrl,
  });
  if (!record) return null;
  return {
    url: record.url,
    title: record.title,
    createdAt: record.createdAt,
    updatedAt: record.updatedAt,
    rev: normalizeRevision(source.rev),
  };
}

function normalizeStoredWebAppRecord(raw: unknown, fallbackId = ""): WebAppSyncRecord | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const source = raw as Partial<WebAppSyncRecord>;
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
  };
}

function normalizeBookmarkTombstones(
  tombstones: Record<string, SyncTombstone>,
  bookmarks: Record<string, BookmarkSyncRecord> = {},
): Record<string, SyncTombstone> {
  const result: Record<string, SyncTombstone> = {};
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

function normalizeBookmarkTombstoneKey(key: string, bookmarks: Record<string, BookmarkSyncRecord>): string {
  const value = String(key || "").trim();
  if (!value) return "";
  const bookmark = bookmarks[value];
  if (bookmark) return bookmarkTombstoneKey(bookmark);
  const alias = Object.values(bookmarks).find((candidate) => bookmarkTombstoneKeysForRecord(candidate).includes(value));
  if (alias) return bookmarkTombstoneKey(alias);
  return looksLikeUrlKey(value) ? identityKeyForUrl(value) : "";
}

function applyBookmarkTombstones(
  bookmarks: Record<string, BookmarkSyncRecord>,
  tombstones: Record<string, SyncTombstone>,
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
  const selected = new Map<string, BookmarkSyncRecord>();
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

function bookmarkDeleteOperationKey(operation: BookmarkDeleteOperation): string {
  return bookmarkUrlKey(operation.url?.trim() || "");
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
  bookmarks: Record<string, BookmarkSyncRecord>,
  key: string,
  fallbackId = "",
): BookmarkSyncRecord[] {
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

function compareBookmarkRecords(left: BookmarkSyncRecord, right: BookmarkSyncRecord): number {
  return (right.createdAt || 0) - (left.createdAt || 0) ||
    left.title.localeCompare(right.title) ||
    left.url.localeCompare(right.url);
}

function bookmarkFieldsChanged(left: BookmarkSyncRecord, right: BookmarkRecord): boolean {
  return left.url !== right.url ||
    left.title !== right.title;
}

function appFieldsChanged(left: WebAppSyncRecord, right: WebAppRecord): boolean {
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
    return url.toString();
  } catch {
    return value.trim();
  }
}

export function identityKeyForUrl(value: string): string {
  return normalizeBookmarkKey(value);
}
