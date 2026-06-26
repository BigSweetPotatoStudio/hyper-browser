import { createSyncBackgroundController } from "@hyper-sync/background";
import { createBackgroundCommandHandler } from "@hyper-sync/background-adapter";
import { androidSync } from "./android-sync";
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

const syncBackground = createSyncBackgroundController({
  debounceMs: AUTO_SYNC_DEBOUNCE_MS,
  syncNow: androidSync.syncNow,
  syncIfEnabled: androidSync.syncIfEnabled,
  notifyLauncherChanged,
  notifySyncResult,
  onError: (_scope, error) => console.warn("Launcher sync failed.", error),
});

const backgroundAdapter = androidSync.createBackgroundAdapter({
  sync: syncBackground,
  notifyLauncherChanged,
});

const hyperCommands = createBackgroundCommandHandler(backgroundAdapter);

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

function notifySyncResult(updatedAt: number, syncResult: WebDavSyncResult): void {
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
