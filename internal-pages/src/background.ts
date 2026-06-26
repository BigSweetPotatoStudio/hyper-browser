import { createSyncBackgroundController } from "@hyper-sync/background";
import { createHyperBackgroundCommandHandler } from "@hyper-sync/hyper-background";
import { canonicalJson } from "@hyper-sync/op-log";
import { createSyncStateService, type SyncStateRunOptions } from "@hyper-sync/state-sync";
import type { BookmarkRecord, SyncSettings, WebAppRecord } from "@hyper-sync";
import type { LauncherJson, SyncJsonByFileName, SyncJsonFileName } from "@hyper-sync/sync-json-types";
import type { BrowserSettings, WebDavSyncResult, WebDavSyncSettings } from "./hyper-browser";

const NATIVE_APP = "hyperBrowser";
const BACKGROUND_TARGET = "hyper.internal.background";
const AUTO_SYNC_DEBOUNCE_MS = 1800;
const REMOTE_SYNC_ALARM = "hyper-browser-android-remote-sync";
const REMOTE_SYNC_ALARM_MINUTES = 1;
const NATIVE_COMMAND_PORT_TARGET = "hyper.internal.nativeCommandPort";
const ANDROID_SYNC_CLIENT_VERSION = "3";

const internalPageMessageTypes = new Set([
  "data.home",
  "data.search",
  "data.bookmarks",
  "data.history",
  "data.apps",
  "data.settings",
  "data.launcherLayout",
  "launcher.layout.save",
  "search.submit",
  "settings.searchEngine.update",
  "settings.toolbarPosition.update",
  "settings.websiteDisplayMode.update",
  "settings.backgroundVideoEnhancement.update",
  "settings.openNewTabsInCurrentTab.update",
  "settings.locale.update",
  "settings.privacy.update",
  "settings.batteryOptimizationState",
  "settings.openBatteryOptimization",
  "sync.webdav.update",
  "sync.localFile.read",
  "sync.localFile.save",
  "backup.export",
  "backup.import",
  "update.check",
  "update.skip",
  "update.clearSkip",
  "update.downloadState",
  "update.install",
  "bookmarks.open",
  "history.open",
  "history.remove",
  "history.clear",
  "apps.open",
  "apps.openStandalone",
  "apps.pin",
  "apps.icon.choose",
  "panel.extensions",
]);

const contentScriptMessageTypes = new Set([
  "pullRefresh.touch",
  "media.keepAlive.start",
  "media.keepAlive.pause",
  "media.keepAlive.stop",
  "settings.backgroundVideoEnhancement.enabled",
]);

let fallbackRemoteCheckTimer: ReturnType<typeof setInterval> | null = null;
let commandQueue: Promise<unknown> = Promise.resolve();

const androidSyncService = createSyncStateService({
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

const syncBackground = createSyncBackgroundController({
  debounceMs: AUTO_SYNC_DEBOUNCE_MS,
  syncNow: runAndroidWebDavSync,
  syncIfEnabled: runAndroidWebDavSyncIfEnabled,
  notifyLauncherChanged,
  notifyRemoteSynced,
  onError: (_scope, error) => console.warn("Launcher sync failed.", error),
});

const hyperCommands = createHyperBackgroundCommandHandler({
  sync: syncBackground,
  listBookmarks,
  findBookmarkByUrl,
  saveBookmark,
  deleteBookmark,
  listWebApps,
  findWebAppsByUrl,
  saveWebApp,
  deleteWebApp,
  loadLauncherLayout: requestLauncherLayout,
  saveLauncherLayout,
  notifyLauncherChanged,
  shouldScheduleAfterMutation: (type) => type.startsWith("launcher.layout.") || type.startsWith("bookmarks.") || type.startsWith("webapps."),
});

function startBackground(): void {
  const runtime = browser?.runtime;
  if (!runtime?.onMessage) return;

  runtime.onMessage.addListener((message, sender) => {
    if (!isPlainObject(message)) return false;
    if (message.target === BACKGROUND_TARGET && typeof message.type === "string") {
      if (!isInternalPageSender(sender)) {
        return Promise.resolve({ ok: false, error: "Rejected Hyper background message." });
      }
      return enqueueBackgroundCommand({ type: message.type, payload: message.payload })
        .then((data) => ({ ok: true, data }))
        .catch((error) => ({ ok: false, error: error instanceof Error ? error.message : String(error) }));
    }
    if (message.nativeApp !== NATIVE_APP) return false;
    return handleNativeBridgeMessage(message, sender);
  });

  ensureRemoteSyncAlarm();
  ensureNativeCommandPort();
  syncBackground.checkRemoteChanges({ notifyPages: true }).catch((error) => console.warn("Remote sync check failed.", error));
}

async function handleBackgroundCommand(message: { type: string; payload?: unknown }): Promise<unknown> {
  const shared = await hyperCommands.handle(message);
  if (shared.handled) return shared.data;
  throw new Error("Unknown background command.");
}

function enqueueBackgroundCommand(message: { type: string; payload?: unknown }): Promise<unknown> {
  const task = commandQueue
    .catch(() => undefined)
    .then(() => handleBackgroundCommand(message));
  commandQueue = task.catch(() => undefined);
  return task;
}

function handleNativeBridgeMessage(message: Record<string, unknown>, sender: unknown): Promise<unknown> {
  if (typeof message.type !== "string") {
    return bridgeError("Invalid Hyper bridge message.");
  }
  if (!isAllowedMessage(message.type, sender)) {
    return bridgeError("Rejected Hyper bridge message.");
  }

  const payload = normalizePayload(message.payload, sender);
  if (!payload) {
    return bridgeError("Invalid Hyper bridge payload.");
  }

  const sendNativeMessage = browser?.runtime?.sendNativeMessage;
  if (!sendNativeMessage) return bridgeError("Hyper native bridge unavailable.");
  return sendNativeMessage(NATIVE_APP, {
    type: message.type,
    payload,
  });
}

function ensureNativeCommandPort(): void {
  const connectNative = browser?.runtime?.connectNative;
  if (!connectNative) return;
  const port = connectNative(NATIVE_APP);
  port.onMessage?.addListener((message: unknown) => {
    if (!isPlainObject(message) || message.target !== NATIVE_COMMAND_PORT_TARGET) return;
    const requestId = typeof message.requestId === "string" ? message.requestId : "";
    const type = typeof message.type === "string" ? message.type : "";
    if (!requestId || !type) return;
    enqueueBackgroundCommand({ type, payload: message.payload })
      .then((data) => port.postMessage({ target: NATIVE_COMMAND_PORT_TARGET, requestId, ok: true, data }))
      .catch((error) => port.postMessage({
        target: NATIVE_COMMAND_PORT_TARGET,
        requestId,
        ok: false,
        error: error instanceof Error ? error.message : String(error),
      }));
  });
  port.onDisconnect?.addListener(() => {
    setTimeout(() => ensureNativeCommandPort(), 1000);
  });
}

async function requestLauncherLayout(): Promise<LauncherJson | null> {
  return androidSyncService.loadLauncherLayout();
}

async function saveLauncherLayout(layout: unknown): Promise<void> {
  if (isLauncherJson(layout)) {
    await androidSyncService.saveLauncherLayout(layout);
  }
}

async function listBookmarks(): Promise<BookmarkRecord[]> {
  return androidSyncService.loadBookmarks();
}

async function findBookmarkByUrl(input: { url: string }): Promise<unknown | null> {
  return androidSyncService.findBookmarkByUrl(input.url);
}

async function saveBookmark(input: unknown): Promise<unknown[]> {
  if (!isPlainObject(input)) throw new Error("Invalid bookmark payload.");
  return androidSyncService.saveBookmark(input);
}

async function deleteBookmark(input: { url?: string }): Promise<unknown[]> {
  return androidSyncService.deleteBookmark(input);
}

async function listWebApps(): Promise<unknown[]> {
  return androidSyncService.loadWebApps();
}

async function findWebAppsByUrl(input: { url: string }): Promise<WebAppRecord[]> {
  return androidSyncService.findWebAppsByUrl(input.url);
}

async function saveWebApp(input: Partial<WebAppRecord> & { name: string; startUrl: string }): Promise<unknown[]> {
  return androidSyncService.saveWebApp(input);
}

async function deleteWebApp(input: unknown): Promise<unknown[]> {
  return androidSyncService.deleteWebApp(input as string | Partial<WebAppRecord> | null | undefined);
}

async function runAndroidWebDavSync(input?: SyncStateRunOptions<BrowserSettings>): Promise<WebDavSyncResult> {
  return androidSyncService.syncNow(input);
}

async function runAndroidWebDavSyncIfEnabled(): Promise<WebDavSyncResult | null> {
  const settings = await requestSettingsData();
  if (!settings.webDavSyncEnabled || !settings.webDavSyncUrl.trim()) return null;
  return androidSyncService.syncNow({ settings });
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

function isLauncherJson(value: unknown): value is LauncherJson {
  return !!value && typeof value === "object" && !Array.isArray(value) && "rev" in value;
}

function parseBridgeResponse(response: unknown): { ok?: boolean; error?: unknown; data?: unknown; itemsJson?: string } {
  return typeof response === "string"
    ? JSON.parse(response) as { ok?: boolean; error?: unknown; data?: unknown; itemsJson?: string }
    : response as { ok?: boolean; error?: unknown; data?: unknown; itemsJson?: string };
}

function ensureRemoteSyncAlarm(): void {
  const alarms = browser?.alarms;
  if (alarms?.create) {
    void alarms.create(REMOTE_SYNC_ALARM, {
      delayInMinutes: REMOTE_SYNC_ALARM_MINUTES,
      periodInMinutes: REMOTE_SYNC_ALARM_MINUTES,
    });
    alarms.onAlarm?.addListener((alarm) => {
      if (alarm.name === REMOTE_SYNC_ALARM) {
        syncBackground.checkRemoteChanges({ notifyPages: true }).catch((error) => console.warn("Remote sync check failed.", error));
      }
    });
    return;
  }

  if (fallbackRemoteCheckTimer !== null) return;
  fallbackRemoteCheckTimer = setInterval(() => {
    syncBackground.checkRemoteChanges({ notifyPages: true }).catch((error) => console.warn("Remote sync check failed.", error));
  }, REMOTE_SYNC_ALARM_MINUTES * 60_000);
}

function notifyRemoteSynced(updatedAt: number, syncResult: WebDavSyncResult): void {
  browser?.runtime?.sendMessage?.({ type: "remote.synced", updatedAt, syncResult }).catch(() => undefined);
}

function notifyLauncherChanged(syncResult?: WebDavSyncResult): void {
  browser?.runtime?.sendMessage?.({ type: "launcher.changed", updatedAt: Date.now(), syncResult }).catch(() => undefined);
}

function bridgeError(error: string): Promise<string> {
  return Promise.resolve(JSON.stringify({ ok: false, error }));
}

function senderUrl(sender: unknown): string {
  return isPlainObject(sender) && typeof sender.url === "string" ? sender.url : "";
}

function isInternalPageSender(sender: unknown): boolean {
  const extensionBaseUrl = browser?.runtime?.getURL?.("") || "";
  const url = senderUrl(sender);
  return !!extensionBaseUrl && url.startsWith(extensionBaseUrl);
}

function isWebContentSender(sender: unknown): boolean {
  const url = senderUrl(sender);
  return url.startsWith("http://") || url.startsWith("https://");
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

function normalizePayload(payload: unknown, sender: unknown): Record<string, string | number | boolean | null> | null {
  if (payload === undefined || payload === null) {
    return { sourceUrl: senderUrl(sender) };
  }
  if (!isPlainObject(payload)) {
    return null;
  }

  const normalized: Record<string, string | number | boolean | null> = {};
  for (const [key, value] of Object.entries(payload)) {
    const valueType = typeof value;
    if (value !== null && valueType !== "string" && valueType !== "number" && valueType !== "boolean") {
      return null;
    }
    normalized[key] = value as string | number | boolean | null;
  }
  normalized.sourceUrl = senderUrl(sender);
  return normalized;
}

function isAllowedMessage(type: string, sender: unknown): boolean {
  if (internalPageMessageTypes.has(type)) return isInternalPageSender(sender);
  if (contentScriptMessageTypes.has(type)) return isWebContentSender(sender);
  return false;
}

startBackground();
