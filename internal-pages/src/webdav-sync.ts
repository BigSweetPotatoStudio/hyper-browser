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
import type { BrowserSettings, WebDavLocalSyncData, WebDavSyncResult } from "./hyper-browser";
import { waitForLauncherLayoutSaves } from "./launcher-layout-storage";

const ANDROID_SYNC_V2_STORAGE_KEY = "hyper-browser-sync-v2-store";

let localLock: Promise<void> = Promise.resolve();

export async function runAndroidWebDavSync(baseSettings?: BrowserSettings): Promise<WebDavSyncResult> {
  const initialSettings = baseSettings || await window.hyperBrowser.requestSettingsData();
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
  const settings = await window.hyperBrowser.requestSettingsData();
  if (!settings.webDavSyncEnabled || !settings.webDavSyncUrl.trim()) return null;
  return runAndroidWebDavSync(settings);
}

export async function checkAndroidWebDavRemoteChanges(): Promise<{
  changed: boolean;
  stateChanged: boolean;
  launcherChanged: boolean;
  synced: boolean;
  updatedAt: number;
  syncResult?: WebDavSyncResult;
}> {
  const settings = await window.hyperBrowser.requestSettingsData();
  if (!settings.webDavSyncEnabled || !settings.webDavSyncUrl.trim()) {
    return { changed: false, stateChanged: false, launcherChanged: false, synced: false, updatedAt: 0 };
  }
  const syncResult = await runAndroidWebDavSync(settings);
  return {
    changed: syncResult.launcherChanged,
    stateChanged: syncResult.stateChanged,
    launcherChanged: syncResult.launcherChanged,
    synced: true,
    updatedAt: Date.now(),
    syncResult,
  };
}

async function loadLocalSnapshot(): Promise<SyncV2LocalSnapshot> {
  const localData = await window.hyperBrowser.requestWebDavLocalData();
  await waitForLauncherLayoutSaves();
  const layout = await window.hyperBrowser.requestLauncherLayout();
  return {
    bookmarks: activeLocalBookmarks(localData),
    webApps: activeLocalWebApps(localData),
    layout,
  };
}

async function applyAndroidState(state: SyncV2State): Promise<void> {
  const clean = state;
  await window.hyperBrowser.applyWebDavSyncRecords({
    bookmarks: activeBookmarksFromState(clean),
    webApps: activeWebAppsFromState(clean),
  });
  const nextLayout = layoutFromState(clean);
  const currentLayout = await window.hyperBrowser.requestLauncherLayout().catch(() => null);
  if (canonicalLauncherLayout(currentLayout) !== canonicalLauncherLayout(nextLayout)) {
    await window.hyperBrowser.saveLauncherLayout(nextLayout);
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
  return window.hyperBrowser.updateWebDavSyncSettings({
    webDavSyncEnabled: settings.webDavSyncEnabled,
    webDavSyncUrl: settings.webDavSyncUrl,
    webDavSyncUsername: settings.webDavSyncUsername,
    webDavSyncPassword: settings.webDavSyncPassword,
    webDavSyncDeviceName: settings.webDavSyncDeviceName,
  });
}

function readStoredStore(deviceId: string): SyncV2Store {
  const raw = window.localStorage.getItem(ANDROID_SYNC_V2_STORAGE_KEY);
  if (!raw) return createEmptyStore(deviceId);
  try {
    return ensureStore(JSON.parse(raw), deviceId);
  } catch {
    return createEmptyStore(deviceId);
  }
}

async function writeStoredStore(store: SyncV2Store, deviceId: string): Promise<void> {
  window.localStorage.setItem(ANDROID_SYNC_V2_STORAGE_KEY, canonicalJson(ensureStore(store, deviceId)));
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
