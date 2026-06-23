const nativeApp = "hyperBrowser";

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
  "bookmarks.remove",
  "bookmarks.edit",
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

function bridgeError(error) {
  return Promise.resolve(JSON.stringify({ ok: false, error }));
}

function senderUrl(sender) {
  return sender && typeof sender.url === "string" ? sender.url : "";
}

function isInternalPageSender(sender) {
  const extensionBaseUrl = browser.runtime.getURL("");
  const url = senderUrl(sender);
  return !!extensionBaseUrl && url.startsWith(extensionBaseUrl);
}

function isWebContentSender(sender) {
  const url = senderUrl(sender);
  return url.startsWith("http://") || url.startsWith("https://");
}

function isPlainObject(value) {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

function normalizePayload(payload, sender) {
  if (payload === undefined || payload === null) {
    return { sourceUrl: senderUrl(sender) };
  }
  if (!isPlainObject(payload)) {
    return null;
  }

  const normalized = {};
  for (const [key, value] of Object.entries(payload)) {
    const valueType = typeof value;
    if (value !== null && valueType !== "string" && valueType !== "number" && valueType !== "boolean") {
      return null;
    }
    normalized[key] = value;
  }
  normalized.sourceUrl = senderUrl(sender);
  return normalized;
}

function isAllowedMessage(type, sender) {
  if (internalPageMessageTypes.has(type)) return isInternalPageSender(sender);
  if (contentScriptMessageTypes.has(type)) return isWebContentSender(sender);
  return false;
}

browser.runtime.onMessage.addListener((message, sender) => {
  if (!isPlainObject(message) || message.nativeApp !== nativeApp || typeof message.type !== "string") {
    return bridgeError("Invalid Hyper bridge message.");
  }
  if (!isAllowedMessage(message.type, sender)) {
    return bridgeError("Rejected Hyper bridge message.");
  }

  const payload = normalizePayload(message.payload, sender);
  if (!payload) {
    return bridgeError("Invalid Hyper bridge payload.");
  }

  return browser.runtime.sendNativeMessage(nativeApp, {
    type: message.type,
    payload,
  });
});
