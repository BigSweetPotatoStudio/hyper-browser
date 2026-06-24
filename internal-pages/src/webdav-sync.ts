import {
  activeBookmarksFromState,
  activeWebAppsFromState,
  canonicalJson,
  createEmptyStore,
  ensureStore,
  layoutFromState,
  syncV2,
  type SyncV2LocalSnapshot,
  type SyncV2Result,
  type SyncV2State,
  type SyncV2Store,
} from "@hyper-sync/op-log";
import type { BookmarkRecord, WebAppRecord } from "@hyper-sync";
import type { BrowserSettings, WebDavLocalSyncData, WebDavSyncResult, WebDavSyncSettings } from "./hyper-browser";
import { waitForLauncherLayoutSaves } from "./launcher-layout-storage";

const ANDROID_SYNC_V2_STORAGE_KEY = "hyper-browser-sync-v2-store";
const NATIVE_APP = "hyperBrowser";

type BridgePayload = Record<string, string>;

type BridgeResponse = {
  ok?: boolean;
  error?: string;
  data?: unknown;
  itemsJson?: string;
};

let localLock: Promise<void> = Promise.resolve();

export async function runAndroidWebDavSync(baseSettings?: BrowserSettings): Promise<WebDavSyncResult> {
  const initialSettings = baseSettings || await requestSettingsData();
  if (!initialSettings.webDavSyncUrl.trim()) throw new Error("WebDAV URL is required.");
  const settings = await ensureAndroidDeviceId(initialSettings);
  const before = (await readStoredStore(settings.webDavSyncDeviceId)).state;
  const result = await syncV2({
    settings: webDavSettings(settings),
    loadStore: async () => readStoredStore(settings.webDavSyncDeviceId),
    saveStore: (store) => writeStoredStore(store, settings.webDavSyncDeviceId),
    loadLocalSnapshot: () => loadLocalSnapshot(),
    applyState: (state) => applyAndroidState(state),
    withLocalLock,
  });
  return syncResultFromState(result.state, before, settings, result);
}

export async function runAndroidWebDavSyncIfEnabled(): Promise<WebDavSyncResult | null> {
  const settings = await requestSettingsData();
  if (!settings.webDavSyncEnabled || !settings.webDavSyncUrl.trim()) return null;
  return runAndroidWebDavSync(settings);
}

async function loadLocalSnapshot(): Promise<SyncV2LocalSnapshot> {
  const localData = await requestWebDavLocalData();
  await waitForLauncherLayoutSaves();
  const layout = await requestLauncherLayout();
  return {
    bookmarks: activeLocalBookmarks(localData),
    webApps: activeLocalWebApps(localData),
    layout,
  };
}

async function applyAndroidState(state: SyncV2State): Promise<void> {
  const clean = state;
  await applyWebDavSyncRecords({
    bookmarks: activeBookmarksFromState(clean),
    webApps: activeWebAppsFromState(clean),
  });
  const nextLayout = layoutFromState(clean);
  const currentLayout = await requestLauncherLayout().catch(() => null);
  if (canonicalLauncherLayout(currentLayout) !== canonicalLauncherLayout(nextLayout)) {
    await saveLauncherLayout(nextLayout);
  }
}

function canonicalLauncherLayout(layout: unknown): string {
  if (!layout || typeof layout !== "object" || Array.isArray(layout)) return "";
  const { updatedAt: _updatedAt, ...rest } = layout as Record<string, unknown>;
  return canonicalJson(rest);
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

function activeLocalBookmarks(localData: WebDavLocalSyncData) {
  return (localData.bookmarks || []).filter((bookmark) => bookmark.deletedAt == null);
}

function activeLocalWebApps(localData: WebDavLocalSyncData) {
  return (localData.webApps || []).filter((app) => app.deletedAt == null);
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
    removedBookmarkCount: [...previousBookmarks].filter((url) => !state.bookmarks[url]).length,
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

async function requestLauncherLayout(): Promise<object | null> {
  const result = await requestObject<{ layout?: object | null }>("data.launcherLayout");
  return result.layout && typeof result.layout === "object" ? result.layout : null;
}

async function saveLauncherLayout(layout: object): Promise<void> {
  await sendNativeBridge("launcher.layout.save", { layout: JSON.stringify(layout) });
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

async function requestWebDavLocalData(): Promise<WebDavLocalSyncData> {
  return requestObject<WebDavLocalSyncData>("sync.webdav.localData");
}

async function applyWebDavSyncRecords(records: { bookmarks: BookmarkRecord[] | object; webApps: WebAppRecord[] | object }): Promise<WebDavSyncResult> {
  return requestObject<WebDavSyncResult>("sync.webdav.applyRecords", {
    bookmarks: JSON.stringify(records.bookmarks),
    webApps: JSON.stringify(records.webApps),
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
