import { createSyncBackgroundController } from "@hyper-sync/background";
import { createHyperBackgroundCommandHandler } from "@hyper-sync/hyper-background";
import { runAndroidWebDavSync, runAndroidWebDavSyncIfEnabled } from "./webdav-sync";
import type { WebDavSyncResult } from "./hyper-browser";

const NATIVE_APP = "hyperBrowser";
const BACKGROUND_TARGET = "hyper.internal.background";
const AUTO_SYNC_DEBOUNCE_MS = 1800;
const REMOTE_SYNC_ALARM = "hyper-browser-android-remote-sync";
const REMOTE_SYNC_ALARM_MINUTES = 1;
const NATIVE_COMMAND_PORT_TARGET = "hyper.internal.nativeCommandPort";

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
  "settings.backgroundVideoEnhancement.update",
  "settings.openNewTabsInCurrentTab.update",
  "settings.locale.update",
  "settings.privacy.update",
  "settings.batteryOptimizationState",
  "settings.openBatteryOptimization",
  "sync.webdav.update",
  "sync.webdav.localData",
  "sync.webdav.applyRecords",
  "backup.export",
  "backup.import",
  "update.check",
  "update.skip",
  "update.clearSkip",
  "update.downloadState",
  "update.install",
  "bookmarks.open",
  "bookmarks.delete",
  "bookmarks.save",
  "history.open",
  "history.remove",
  "history.clear",
  "apps.open",
  "apps.openStandalone",
  "apps.pin",
  "apps.edit",
  "apps.update",
  "apps.icon.choose",
  "apps.icon.update",
  "apps.delete",
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

const syncBackground = createSyncBackgroundController<WebDavSyncResult>({
  debounceMs: AUTO_SYNC_DEBOUNCE_MS,
  syncNow: runAndroidWebDavSync,
  syncIfEnabled: runAndroidWebDavSyncIfEnabled,
  notifyLauncherChanged,
  notifyRemoteSynced,
  onError: (_scope, error) => console.warn("Launcher sync failed.", error),
});

const hyperCommands = createHyperBackgroundCommandHandler<WebDavSyncResult>({
  sync: syncBackground,
  listBookmarks,
  findBookmarkByUrl,
  addBookmark: saveBookmark,
  saveBookmark,
  deleteBookmark,
  loadLauncherLayout: requestLauncherLayout,
  saveLauncherLayout,
  notifyLauncherChanged,
  shouldScheduleAfterMutation: (type) => type.startsWith("launcher.layout.") || type.startsWith("bookmarks."),
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
      return handleBackgroundCommand({ type: message.type, payload: message.payload })
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
    handleBackgroundCommand({ type, payload: message.payload })
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

async function requestLauncherLayout(): Promise<object | null> {
  const response = await requestNativeObject<{ layout?: object | null }>("data.launcherLayout");
  return response.layout && typeof response.layout === "object" ? response.layout : null;
}

async function saveLauncherLayout(layout: unknown): Promise<void> {
  await requestNativeObject("launcher.layout.save", { layout: JSON.stringify(layout || {}) });
}

async function listBookmarks(): Promise<unknown[]> {
  return requestNativeItems("data.bookmarks");
}

async function findBookmarkByUrl(input: { url: string }): Promise<unknown | null> {
  const identityKey = normalizeBookmarkIdentityKey(input.url);
  if (!identityKey) return null;
  const bookmarks = await listBookmarks();
  return bookmarks.find((bookmark) => {
    if (!isPlainObject(bookmark) || bookmark.kind === "folder") return false;
    const candidate = typeof bookmark.identityKey === "string" && bookmark.identityKey.trim()
      ? normalizeBookmarkIdentityKey(bookmark.identityKey)
      : normalizeBookmarkIdentityKey(typeof bookmark.url === "string" ? bookmark.url : "");
    return candidate === identityKey;
  }) || null;
}

async function saveBookmark(input: unknown): Promise<unknown[]> {
  if (!isPlainObject(input)) throw new Error("Invalid bookmark payload.");
  const payload = normalizeBookmarkPayload(input);
  return requestNativeItems("bookmarks.save", payload);
}

async function deleteBookmark(input: { id?: string; url?: string }): Promise<unknown[]> {
  return requestNativeItems("bookmarks.delete", {
    ...(input.id ? { id: input.id } : {}),
    ...(input.url ? { url: input.url } : {}),
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

async function requestNativeItems<T = unknown>(type: string, payload: Record<string, unknown> = {}): Promise<T[]> {
  const sendNativeMessage = browser?.runtime?.sendNativeMessage;
  if (!sendNativeMessage) throw new Error("Hyper native bridge unavailable.");
  const response = parseBridgeResponse(await sendNativeMessage(NATIVE_APP, { type, payload }));
  if (!response || response.ok !== true) {
    throw new Error(typeof response?.error === "string" ? response.error : "Hyper native bridge request failed.");
  }
  if (!response.itemsJson) return [];
  const items = JSON.parse(response.itemsJson) as unknown;
  return Array.isArray(items) ? items as T[] : [];
}

function normalizeBookmarkPayload(input: Record<string, unknown>): Record<string, string> {
  const payload: Record<string, string> = {};
  const id = stringFromUnknown(input.id);
  const oldUrl = stringFromUnknown(input.oldUrl);
  const title = stringFromUnknown(input.title);
  const url = stringFromUnknown(input.url);
  const kind = stringFromUnknown(input.kind) === "folder" ? "folder" : "bookmark";
  const parentId = stringFromUnknown(input.parentId);
  const index = numberFromUnknown(input.index);
  if (id) payload.id = id;
  if (oldUrl) payload.oldUrl = oldUrl;
  if (title) payload.title = title;
  if (url) payload.url = url;
  if (kind) payload.kind = kind;
  if (parentId) payload.parentId = parentId;
  if (index !== null) payload.index = String(index);
  return payload;
}

function normalizeBookmarkIdentityKey(value: string): string {
  try {
    const url = new URL(value.trim());
    url.hash = "";
    return url.toString();
  } catch {
    return value.trim();
  }
}

function stringFromUnknown(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function numberFromUnknown(value: unknown): number | null {
  const number = typeof value === "number" ? value : typeof value === "string" ? Number(value) : NaN;
  return Number.isFinite(number) && number >= 0 ? Math.floor(number) : null;
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
