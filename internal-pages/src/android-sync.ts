import { createSyncStateBackgroundAdapter, type BackgroundAdapter } from "@hyper-sync/background-adapter";
import type { SyncBackgroundRunOptions } from "@hyper-sync/background";
import { canonicalJson } from "@hyper-sync/op-log";
import { createSyncStateService } from "@hyper-sync/state-sync";
import type { SyncSettings } from "@hyper-sync";
import type { SyncJsonByFileName, SyncJsonFileName } from "@hyper-sync/sync-json-types";
import type { BrowserSettings, WebDavSyncResult, WebDavSyncSettings } from "./hyper-browser";

const NATIVE_APP = "hyperBrowser";
const ANDROID_SYNC_CLIENT_VERSION = "3";

type AndroidBackgroundAdapterOptions = {
  sync: BackgroundAdapter<WebDavSyncResult>["sync"];
  notifyLauncherChanged?: BackgroundAdapter<WebDavSyncResult>["notifyLauncherChanged"];
};

const stateSync = createSyncStateService({
  loadSettings: requestSettingsData,
  ensureSettings: ensureAndroidDeviceId,
  toSyncSettings: androidSyncSettings,
  syncFiles: {
    readFile: readAndroidSyncFile,
    saveFile: saveAndroidSyncFile,
  },
  buildResult: ({ base, settings }) => ({
    ...base,
    deviceId: settings.webDavSyncDeviceId,
    settings,
  }),
});

export const androidSync = {
  syncNow,
  syncIfEnabled,
  createBackgroundAdapter,
};

async function syncNow(input?: SyncBackgroundRunOptions): Promise<WebDavSyncResult> {
  return stateSync.syncNow(input);
}

async function syncIfEnabled(): Promise<WebDavSyncResult | null> {
  const settings = await requestSettingsData();
  if (!settings.webDavSyncEnabled || !settings.webDavSyncUrl.trim()) return null;
  return stateSync.syncNow({ settings });
}

function createBackgroundAdapter(options: AndroidBackgroundAdapterOptions): BackgroundAdapter<WebDavSyncResult> {
  return createSyncStateBackgroundAdapter({
    sync: options.sync,
    state: stateSync,
    notifyLauncherChanged: options.notifyLauncherChanged,
    shouldScheduleAfterMutation: (type) =>
      type.startsWith("launcher.layout.") ||
      type.startsWith("bookmarks.") ||
      type.startsWith("webapps."),
  });
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

function androidSyncSettings(settings: BrowserSettings): SyncSettings {
  return {
    webDavUrl: settings.webDavSyncUrl,
    username: settings.webDavSyncUsername,
    password: settings.webDavSyncPassword,
    folderTitle: "Hyper Browser",
    folderId: "",
    deviceName: settings.webDavSyncDeviceName,
    deviceId: settings.webDavSyncDeviceId,
  };
}

async function requestSettingsData(): Promise<BrowserSettings> {
  return requestNativeObject<BrowserSettings>("data.settings");
}

async function readAndroidSyncFile(path: SyncJsonFileName): Promise<unknown | null> {
  const response = await requestNativeObject<{ content?: string | object | null }>("sync.localFile.read", {
    syncClientVersion: ANDROID_SYNC_CLIENT_VERSION,
    path,
  });
  const content = response.content;
  if (typeof content !== "string") return content ?? null;
  if (!content.trim()) return null;
  return JSON.parse(content) as unknown;
}

async function saveAndroidSyncFile(path: SyncJsonFileName, data: SyncJsonByFileName[SyncJsonFileName]): Promise<void> {
  await requestNativeObject("sync.localFile.save", {
    syncClientVersion: ANDROID_SYNC_CLIENT_VERSION,
    path,
    content: canonicalJson(data),
  });
}

async function updateWebDavSyncSettings(settings: WebDavSyncSettings): Promise<BrowserSettings> {
  return requestNativeObject<BrowserSettings>("sync.webdav.update", {
    enabled: settings.webDavSyncEnabled ? "true" : "false",
    url: settings.webDavSyncUrl,
    username: settings.webDavSyncUsername,
    password: settings.webDavSyncPassword,
    deviceName: settings.webDavSyncDeviceName,
  });
}

async function requestNativeObject<T = unknown>(type: string, payload: Record<string, unknown> = {}): Promise<T> {
  const sendNativeMessage = browser?.runtime?.sendNativeMessage;
  if (!sendNativeMessage) throw new Error("Hyper native bridge unavailable.");
  const response = parseBridgeResponse(await sendNativeMessage(NATIVE_APP, { type, payload }));
  if (!response || response.ok !== true) {
    throw new Error(typeof response?.error === "string" ? response.error : "Hyper native bridge request failed.");
  }
  return response.data as T;
}

function parseBridgeResponse(response: unknown): { ok?: boolean; error?: unknown; data?: unknown; itemsJson?: string } {
  return typeof response === "string"
    ? JSON.parse(response) as { ok?: boolean; error?: unknown; data?: unknown; itemsJson?: string }
    : response as { ok?: boolean; error?: unknown; data?: unknown; itemsJson?: string };
}
