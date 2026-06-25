import {
  activeBookmarksFromState,
  activeWebAppsFromState,
  appendLocalSnapshotOperations,
  appendOperation,
  appendWebAppDelete,
  appendWebAppUpsert,
  canonicalJson,
  createEmptyStore,
  ensureStore,
  findBookmarkByUrlInState,
  identityKeyForUrl,
  readSyncStateFromFiles,
  saveSyncStateToFiles,
  syncV2,
  type SyncStateFileStorage,
  type SyncV2Result,
  type SyncV2State,
  type SyncV2Store,
} from "@hyper-sync/op-log";
import type { BookmarkRecord, WebAppRecord } from "@hyper-sync";
import type { LauncherJson, SyncJsonByFileName, SyncJsonFileName } from "@hyper-sync/sync-json-types";
import type { BrowserSettings, WebDavSyncResult, WebDavSyncSettings } from "./hyper-browser";

const ANDROID_SYNC_V2_STORAGE_KEY = "hyper-browser-sync-v2-store";
const NATIVE_APP = "hyperBrowser";
const ANDROID_SYNC_CLIENT_VERSION = "3";

type BridgePayload = Record<string, string>;

type AndroidBookmarkSaveInput = Partial<BookmarkRecord> & {
  oldUrl?: string;
};

type AndroidWebAppDeleteInput = string | Partial<WebAppRecord> | null | undefined;

type BridgeResponse = {
  ok?: boolean;
  error?: string;
  data?: unknown;
  itemsJson?: string;
};

let localLock: Promise<void> = Promise.resolve();

const androidSyncFiles: SyncStateFileStorage = {
  readFile: readAndroidSyncFile,
  saveFile: saveAndroidSyncFile,
};

export async function runAndroidWebDavSync(baseSettings?: BrowserSettings): Promise<WebDavSyncResult> {
  const initialSettings = baseSettings || await requestSettingsData();
  if (!initialSettings.webDavSyncUrl.trim()) throw new Error("WebDAV URL is required.");
  const settings = await ensureAndroidDeviceId(initialSettings);
  const before = await readLocalState();
  const result = await syncV2({
    settings: webDavSettings(settings),
    loadStore: async () => readStoredStore(settings.webDavSyncDeviceId),
    saveStore: (store) => writeStoredStore(store, settings.webDavSyncDeviceId),
    loadState: () => readLocalState(),
    saveState: (state) => applyAndroidState(state),
    withLocalLock,
  });
  return syncResultFromState(result.state, before, settings, result);
}

export async function runAndroidWebDavSyncIfEnabled(): Promise<WebDavSyncResult | null> {
  const settings = await requestSettingsData();
  if (!settings.webDavSyncEnabled || !settings.webDavSyncUrl.trim()) return null;
  return runAndroidWebDavSync(settings);
}

export async function saveAndroidLauncherLayoutToSync(layout: LauncherJson): Promise<void> {
  const settings = await ensureAndroidDeviceId(await requestSettingsData());
  await withLocalLock(async () => {
    const current = await readStoredStore(settings.webDavSyncDeviceId);
    const currentState = await readLocalState();
    const next = appendLocalSnapshotOperations(current, currentState, { layout }, { forceLayout: true });
    if (canonicalJson(current) !== canonicalJson(next.store) || canonicalJson(currentState) !== canonicalJson(next.state)) {
      await writeStoredStore(next.store, settings.webDavSyncDeviceId);
      await applyAndroidState(next.state);
    }
  });
}

export async function saveAndroidBookmarkToSync(input: AndroidBookmarkSaveInput): Promise<BookmarkRecord[]> {
  const settings = await ensureAndroidDeviceId(await requestSettingsData());
  let nextState: SyncV2State | null = null;
  await withLocalLock(async () => {
    let store = await readStoredStore(settings.webDavSyncDeviceId);
    let state = await readLocalState();
    const url = identityKeyForUrl(input.url || "");
    if (!url) throw new Error("Bookmark URL is required.");
    const oldUrl = identityKeyForUrl(input.oldUrl || "");
    const existing = findBookmarkByUrlInState(state, url) ||
      (oldUrl ? findBookmarkByUrlInState(state, oldUrl) : null);
    if (oldUrl && oldUrl !== url && findBookmarkByUrlInState(state, oldUrl)) {
      const next = appendOperation(store, state, {
        type: "bookmark.delete",
        url: oldUrl,
        title: existing?.title,
      });
      store = next.store;
      state = next.state;
    }
    const now = Date.now();
    const next = appendOperation(store, state, {
      type: "bookmark.upsert",
      bookmark: {
        url,
        title: (input.title || existing?.title || url).trim() || url,
        createdAt: existing?.createdAt || input.createdAt || now,
        updatedAt: now,
        iconDataUrl: input.iconDataUrl ?? existing?.iconDataUrl ?? null,
      },
    });
    store = next.store;
    state = next.state;
    await writeStoredStore(store, settings.webDavSyncDeviceId);
    await applyAndroidState(state);
    nextState = state;
  });
  return activeBookmarksFromState(nextState || await readLocalState());
}

export async function deleteAndroidBookmarkFromSync(input: { url?: string }): Promise<BookmarkRecord[]> {
  const settings = await ensureAndroidDeviceId(await requestSettingsData());
  let nextState: SyncV2State | null = null;
  await withLocalLock(async () => {
    const store = await readStoredStore(settings.webDavSyncDeviceId);
    const state = await readLocalState();
    const url = identityKeyForUrl(input.url || "");
    const bookmark = url ? findBookmarkByUrlInState(state, url) : null;
    if (bookmark) {
      const next = appendOperation(store, state, {
        type: "bookmark.delete",
        url: bookmark.url,
        title: bookmark.title,
      });
      await writeStoredStore(next.store, settings.webDavSyncDeviceId);
      await applyAndroidState(next.state);
      nextState = next.state;
    }
  });
  return activeBookmarksFromState(nextState || await readLocalState());
}

export async function saveAndroidWebAppToSync(input: Partial<WebAppRecord> & { name: string; startUrl: string }): Promise<WebAppRecord[]> {
  const settings = await ensureAndroidDeviceId(await requestSettingsData());
  let nextState: SyncV2State | null = null;
  await withLocalLock(async () => {
    const current = await readStoredStore(settings.webDavSyncDeviceId);
    const currentState = await readLocalState();
    const next = appendWebAppUpsert(current, currentState, input);
    await writeStoredStore(next.store, settings.webDavSyncDeviceId);
    await applyAndroidState(next.state);
    nextState = next.state;
  });
  return activeWebAppsFromState(nextState || await readLocalState());
}

export async function deleteAndroidWebAppFromSync(input: AndroidWebAppDeleteInput): Promise<WebAppRecord[]> {
  const settings = await ensureAndroidDeviceId(await requestSettingsData());
  const id = typeof input === "string" ? input.trim() : input?.id?.trim() || "";
  let nextState: SyncV2State | null = null;
  await withLocalLock(async () => {
    const current = await readStoredStore(settings.webDavSyncDeviceId);
    const currentState = await readLocalState();
    const next = appendWebAppDelete(current, currentState, id);
    if (canonicalJson(current) !== canonicalJson(next.store) || canonicalJson(currentState) !== canonicalJson(next.state)) {
      await writeStoredStore(next.store, settings.webDavSyncDeviceId);
      await applyAndroidState(next.state);
      nextState = next.state;
    }
  });
  return activeWebAppsFromState(nextState || await readLocalState());
}

async function applyAndroidState(state: SyncV2State): Promise<void> {
  await saveSyncStateToFiles(androidSyncFiles, state);
}

async function readLocalState(): Promise<SyncV2State> {
  return readSyncStateFromFiles(androidSyncFiles);
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

async function ensureAndroidDeviceId(settings: BrowserSettings): Promise<BrowserSettings> {
  if (settings.webDavSyncDeviceId) return settings;
  return updateWebDavSyncSettings({
    webDavSyncEnabled: settings.webDavSyncEnabled,
    webDavSyncUrl: settings.webDavSyncUrl,
    webDavSyncUsername: settings.webDavSyncUsername,
    webDavSyncPassword: settings.webDavSyncPassword,
    webDavSyncDeviceName: settings.webDavSyncDeviceName,
  });
}

async function readStoredStore(deviceId: string): Promise<SyncV2Store> {
  const raw = await readStoredValue();
  if (raw === null || raw === undefined || raw === "") return createEmptyStore(deviceId);
  try {
    const parsed = typeof raw === "string" ? JSON.parse(raw) : raw;
    const store = ensureStore(parsed, deviceId);
    if (canonicalJson(parsed) !== canonicalJson(store)) {
      await writeStoredStore(store, deviceId);
    }
    return store;
  } catch {
    return createEmptyStore(deviceId);
  }
}

async function writeStoredStore(store: SyncV2Store, deviceId: string): Promise<void> {
  await writeStoredValue(canonicalJson(ensureStore(store, deviceId)));
}

async function readStoredValue(): Promise<unknown> {
  const storage = browser?.storage?.local;
  if (storage?.get) {
    const result = await storage.get(ANDROID_SYNC_V2_STORAGE_KEY);
    if (Object.prototype.hasOwnProperty.call(result, ANDROID_SYNC_V2_STORAGE_KEY)) {
      return result[ANDROID_SYNC_V2_STORAGE_KEY];
    }
    const legacy = readLegacyLocalStorage();
    if (legacy !== null) {
      await storage.set({ [ANDROID_SYNC_V2_STORAGE_KEY]: legacy });
      return legacy;
    }
    return null;
  }
  return readLegacyLocalStorage();
}

async function writeStoredValue(value: string): Promise<void> {
  const storage = browser?.storage?.local;
  if (storage?.set) {
    await storage.set({ [ANDROID_SYNC_V2_STORAGE_KEY]: value });
    return;
  }
  writeLegacyLocalStorage(value);
}

function readLegacyLocalStorage(): string | null {
  try {
    return typeof window !== "undefined" && window.localStorage
      ? window.localStorage.getItem(ANDROID_SYNC_V2_STORAGE_KEY)
      : null;
  } catch {
    return null;
  }
}

function writeLegacyLocalStorage(value: string): void {
  try {
    if (typeof window !== "undefined" && window.localStorage) {
      window.localStorage.setItem(ANDROID_SYNC_V2_STORAGE_KEY, value);
    }
  } catch {
    // Ignore storage fallback failures; the next sync can rebuild from local data.
  }
}

function webDavSettings(settings: BrowserSettings) {
  return {
    webDavUrl: settings.webDavSyncUrl,
    username: settings.webDavSyncUsername,
    password: settings.webDavSyncPassword,
    deviceId: settings.webDavSyncDeviceId,
  };
}

function syncResultFromState(
  state: SyncV2State,
  previous: SyncV2State,
  settings: BrowserSettings,
  sync: SyncV2Result,
): WebDavSyncResult {
  const bookmarks = activeBookmarksFromState(state);
  const webApps = activeWebAppsFromState(state);
  const previousBookmarks = new Set(activeBookmarksFromState(previous).map((bookmark) => bookmark.url));
  const previousWebApps = new Set(activeWebAppsFromState(previous).map((app) => app.id));
  return {
    stateChanged: sync.stateChanged,
    launcherChanged: sync.launcherChanged,
    bookmarkCount: bookmarks.length,
    webAppCount: webApps.length,
    deletedBookmarkCount: Object.keys(state.bookmarkTombstones).length,
    deletedWebAppCount: Object.keys(state.appTombstones).length,
    importedBookmarkCount: bookmarks.filter((bookmark) => !previousBookmarks.has(bookmark.url)).length,
    removedBookmarkCount: [...previousBookmarks].filter((id) => !state.bookmarks[id]).length,
    importedWebAppCount: webApps.filter((app) => !previousWebApps.has(app.id)).length,
    removedWebAppCount: [...previousWebApps].filter((id) => !state.apps[id]).length,
    syncedAt: sync.syncedAt,
    deviceId: settings.webDavSyncDeviceId,
    uploadedOperationCount: sync.uploadedOperationCount,
    remoteOperationCount: sync.remoteOperationCount,
    pendingOperationCount: sync.pendingOperationCount,
    settings,
  };
}

async function requestSettingsData(): Promise<BrowserSettings> {
  return requestObject<BrowserSettings>("data.settings");
}

async function readAndroidSyncFile(path: SyncJsonFileName): Promise<unknown | null> {
  const response = await requestObject<{ content?: string | object | null }>("sync.localFile.read", {
    syncClientVersion: ANDROID_SYNC_CLIENT_VERSION,
    path,
  });
  const content = response.content;
  if (typeof content !== "string") return content ?? null;
  if (!content.trim()) return null;
  return JSON.parse(content) as unknown;
}

async function saveAndroidSyncFile(path: SyncJsonFileName, data: SyncJsonByFileName[SyncJsonFileName]): Promise<void> {
  await sendNativeBridge("sync.localFile.save", {
    syncClientVersion: ANDROID_SYNC_CLIENT_VERSION,
    path,
    content: canonicalJson(data),
  });
}

async function updateWebDavSyncSettings(settings: WebDavSyncSettings): Promise<BrowserSettings> {
  return requestObject<BrowserSettings>("sync.webdav.update", {
    enabled: settings.webDavSyncEnabled ? "true" : "false",
    url: settings.webDavSyncUrl,
    username: settings.webDavSyncUsername,
    password: settings.webDavSyncPassword,
    deviceName: settings.webDavSyncDeviceName,
  });
}

async function requestObject<T>(type: string, payload?: BridgePayload): Promise<T> {
  const response = await sendNativeBridge(type, payload);
  return response.data as T;
}

async function sendNativeBridge(type: string, payload: BridgePayload = {}): Promise<BridgeResponse> {
  const runtime = browser?.runtime;
  if (!runtime) throw new Error("Hyper bridge unavailable.");

  const response = isBackgroundContext() && runtime.sendNativeMessage
    ? await runtime.sendNativeMessage(NATIVE_APP, { type, payload })
    : await sendViaBackground(type, payload);

  const parsed = parseBridgeResponse(response);
  if (!parsed || parsed.ok !== true) {
    throw new Error(parsed?.error || "Hyper bridge request failed.");
  }
  return parsed;
}

async function sendViaBackground(type: string, payload: BridgePayload): Promise<unknown> {
  const sendMessage = browser?.runtime?.sendMessage;
  if (sendMessage) return sendMessage({ nativeApp: NATIVE_APP, type, payload });
  const sendNativeMessage = browser?.runtime?.sendNativeMessage;
  if (sendNativeMessage) return sendNativeMessage(NATIVE_APP, { type, payload });
  throw new Error("Hyper bridge unavailable.");
}

function isBackgroundContext(): boolean {
  try {
    return typeof window !== "undefined" && new URL(window.location.href).pathname.endsWith("/background.html");
  } catch {
    return false;
  }
}

function parseBridgeResponse(response: unknown): BridgeResponse {
  if (typeof response === "string") return JSON.parse(response) as BridgeResponse;
  return response as BridgeResponse;
}
