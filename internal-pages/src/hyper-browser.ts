type BridgeResponse = {
  ok: boolean;
  error?: string;
  itemsJson?: string;
  data?: unknown;
};

type BookmarkItem = {
  title?: string;
  url: string;
  iconDataUrl?: string | null;
};

type HistoryItem = {
  title?: string;
  url: string;
  visitedAt?: string;
  iconDataUrl?: string | null;
};

type WebAppItem = {
  id: string;
  name: string;
  startUrl: string;
  scopeUrl: string;
  iconPath?: string | null;
  iconDataUrl?: string | null;
  siteIconDataUrl?: string | null;
  iconSource?: "custom" | "site" | "title";
  themeColor: number;
  displayMode: string;
  createdAt: number;
  lastOpenedAt: number;
};

type SearchSuggestionItem = {
  id?: string;
  title?: string;
  url: string;
  source: "bookmark" | "history" | "app";
};

type BrowserSettings = {
  searchEngineId: "google" | "bing" | "custom";
  searchEngineName: string;
  customSearchUrl: string;
  toolbarPosition: "top" | "bottom";
  backgroundVideoEnhancementEnabled: boolean;
  openNewTabsInCurrentTab: boolean;
  dohEnabled: boolean;
  dohProviderUrl: string;
  httpsOnlyEnabled: boolean;
  privacyProtectionLevel: "none" | "standard" | "strict";
  localePreference: "default" | "zh" | "en";
  webDavSyncEnabled: boolean;
  webDavSyncUrl: string;
  webDavSyncUsername: string;
  webDavSyncPassword: string;
  webDavSyncDeviceName: string;
  webDavSyncDeviceId: string;
};

type WebDavSyncSettings = Pick<BrowserSettings,
  "webDavSyncEnabled" |
  "webDavSyncUrl" |
  "webDavSyncUsername" |
  "webDavSyncPassword" |
  "webDavSyncDeviceName"
>;

type WebDavSyncResult = {
  bookmarkCount: number;
  webAppCount: number;
  deletedBookmarkCount: number;
  deletedWebAppCount: number;
  importedBookmarkCount: number;
  removedBookmarkCount: number;
  importedWebAppCount: number;
  removedWebAppCount: number;
  syncedAt: number;
  deviceId: string;
  attemptCount: number;
  settings?: BrowserSettings;
};

type UpdateAsset = {
  abi: string;
  url: string;
  sha256?: string;
  sizeBytes?: number;
};

type AvailableUpdate = {
  versionCode: number;
  versionName: string;
  notes?: string;
  releaseUrl?: string;
  asset: UpdateAsset;
};

type UpdateCheckResult = {
  status: "available" | "skipped" | "upToDate" | "unsupported" | "error";
  currentVersionCode: number;
  currentVersionName: string;
  skippedVersionCode: number;
  message?: string;
  update?: AvailableUpdate;
};

type UpdateDownloadState = {
  status: "idle" | "preparing" | "permissionRequired" | "downloading" | "verifying" | "ready" | "error";
  versionCode: number;
  versionName: string;
  bytesDownloaded: number;
  totalBytes: number;
  message?: string;
};

type BatteryOptimizationState = {
  ignoringBatteryOptimizations: boolean;
  opened?: boolean;
};

type BackupActionResult = {
  message?: string;
};

type HyperBridgeMessageType =
  | "data.home"
  | "data.search"
  | "data.bookmarks"
  | "data.history"
  | "data.apps"
  | "data.settings"
  | "search.submit"
  | "settings.searchEngine.update"
  | "settings.toolbarPosition.update"
  | "settings.backgroundVideoEnhancement.update"
  | "settings.openNewTabsInCurrentTab.update"
  | "settings.locale.update"
  | "settings.privacy.update"
  | "settings.batteryOptimizationState"
  | "settings.openBatteryOptimization"
  | "sync.webdav.update"
  | "sync.webdav.run"
  | "backup.export"
  | "backup.import"
  | "update.check"
  | "update.skip"
  | "update.clearSkip"
  | "update.downloadState"
  | "update.install"
  | "bookmarks.open"
  | "bookmarks.remove"
  | "bookmarks.edit"
  | "history.open"
  | "history.remove"
  | "history.clear"
  | "apps.open"
  | "apps.pin"
  | "apps.edit"
  | "apps.update"
  | "apps.icon.choose"
  | "apps.icon.update"
  | "apps.delete"
  | "panel.extensions";

type BridgePayload = Record<string, string>;

declare global {
  const browser: {
    runtime?: {
      sendMessage?: (message: unknown) => Promise<unknown>;
    };
  } | undefined;

  interface Window {
    hyperBrowser: HyperBrowserApi;
  }
}

type HyperBrowserApi = {
  open(input: string): void;
  showBookmarks(): void;
  showHistory(): void;
  showApps(): void;
  showSettings(): void;
  showExtensions(): void;
  requestHomeData(): Promise<HistoryItem[]>;
  requestAppsData(): Promise<WebAppItem[]>;
  requestSearchData(): Promise<SearchSuggestionItem[]>;
  requestSettingsData(): Promise<BrowserSettings>;
  updateSearchEngine(searchEngineId: BrowserSettings["searchEngineId"], customSearchUrl?: string): Promise<BrowserSettings>;
  updateToolbarPosition(toolbarPosition: BrowserSettings["toolbarPosition"]): Promise<BrowserSettings>;
  updateBackgroundVideoEnhancement(enabled: boolean): Promise<BrowserSettings>;
  updateOpenNewTabsInCurrentTab(enabled: boolean): Promise<BrowserSettings>;
  updateLocalePreference(localePreference: BrowserSettings["localePreference"]): Promise<BrowserSettings>;
  updatePrivacySettings(settings: Pick<BrowserSettings, "dohEnabled" | "dohProviderUrl" | "httpsOnlyEnabled" | "privacyProtectionLevel">): Promise<BrowserSettings>;
  requestBatteryOptimizationState(): Promise<BatteryOptimizationState>;
  openBatteryOptimizationSettings(): Promise<BatteryOptimizationState>;
  updateWebDavSyncSettings(settings: WebDavSyncSettings): Promise<BrowserSettings>;
  runWebDavSync(): Promise<WebDavSyncResult>;
  exportBackup(): Promise<BackupActionResult>;
  importBackup(): Promise<BackupActionResult>;
  checkUpdate(ignoreSkipped?: boolean): Promise<UpdateCheckResult>;
  installUpdate(versionCode: number): Promise<UpdateDownloadState>;
  requestUpdateDownloadState(): Promise<UpdateDownloadState>;
  skipUpdate(versionCode: number): Promise<void>;
  clearSkippedUpdate(): Promise<void>;
  requestBookmarksData(): Promise<BookmarkItem[]>;
  requestHistoryData(): Promise<HistoryItem[]>;
  openBookmark(url: string): void;
  removeBookmark(url: string): void;
  editBookmark(oldUrl: string, title: string, url: string): void;
  openHistory(url: string): void;
  removeHistory(url: string): void;
  clearHistory(): void;
  openApp(id: string): void;
  pinApp(id: string): void;
  editApp(id: string): Promise<WebAppItem[]>;
  updateApp(id: string, name: string, startUrl: string, iconDataUrl?: string | null): Promise<WebAppItem[]>;
  chooseAppIcon(id: string): Promise<string | null>;
  updateAppIcon(id: string, iconDataUrl: string | null): Promise<WebAppItem[]>;
  deleteApp(id: string): void;
};

const nativeApp = "hyperBrowser";

function canUseBridge(): boolean {
  return typeof browser !== "undefined" &&
    !!browser.runtime &&
    typeof browser.runtime.sendMessage === "function";
}

function parseResponse(response: unknown): BridgeResponse {
  if (typeof response === "string") {
    return JSON.parse(response) as BridgeResponse;
  }
  return response as BridgeResponse;
}

function send(type: HyperBridgeMessageType, payload?: BridgePayload): Promise<BridgeResponse> {
  const sendMessage = browser?.runtime?.sendMessage;
  if (!canUseBridge() || !sendMessage) return Promise.reject(new Error("Hyper bridge unavailable."));
  return sendMessage({ nativeApp, type, payload: payload || {} })
    .then(parseResponse)
    .then((response) => {
      if (!response || response.ok !== true) {
        throw new Error(response?.error || "Hyper bridge request failed.");
      }
      return response;
    });
}

function command(type: HyperBridgeMessageType, params?: BridgePayload) {
  send(type, params).catch((error) => console.error(error));
}

function itemsFromResponse<T>(response: BridgeResponse): T[] {
  if (!response.itemsJson) return [];
  const items = JSON.parse(response.itemsJson) as unknown;
  return Array.isArray(items) ? items as T[] : [];
}

function requestItems<T>(type: HyperBridgeMessageType, payload?: BridgePayload): Promise<T[]> {
  return send(type, payload).then(itemsFromResponse<T>);
}

function requestData<T>(type: HyperBridgeMessageType): Promise<T[]> {
  return requestItems<T>(type);
}

function requestObject<T>(type: HyperBridgeMessageType, payload?: BridgePayload): Promise<T> {
  return send(type, payload).then((response) => response.data as T);
}

window.hyperBrowser = {
  open(input) {
    command("search.submit", { query: input });
  },
  showBookmarks() {
    window.location.href = "hyper://bookmarks";
  },
  showHistory() {
    window.location.href = "hyper://history";
  },
  showApps() {
    window.location.href = "hyper://apps";
  },
  showSettings() {
    window.location.href = "hyper://settings";
  },
  showExtensions() {
    command("panel.extensions");
  },
  requestHomeData() {
    return requestData<HistoryItem>("data.home");
  },
  requestAppsData() {
    return requestData<WebAppItem>("data.apps");
  },
  requestSearchData() {
    return requestData<SearchSuggestionItem>("data.search");
  },
  requestSettingsData() {
    return requestObject<BrowserSettings>("data.settings");
  },
  updateSearchEngine(searchEngineId, customSearchUrl = "") {
    return send("settings.searchEngine.update", { searchEngineId, customSearchUrl })
      .then((response) => response.data as BrowserSettings);
  },
  updateToolbarPosition(toolbarPosition) {
    return send("settings.toolbarPosition.update", { toolbarPosition })
      .then((response) => response.data as BrowserSettings);
  },
  updateBackgroundVideoEnhancement(enabled) {
    return send("settings.backgroundVideoEnhancement.update", { enabled: enabled ? "true" : "false" })
      .then((response) => response.data as BrowserSettings);
  },
  updateOpenNewTabsInCurrentTab(enabled) {
    return send("settings.openNewTabsInCurrentTab.update", { enabled: enabled ? "true" : "false" })
      .then((response) => response.data as BrowserSettings);
  },
  updateLocalePreference(localePreference) {
    return send("settings.locale.update", { localePreference })
      .then((response) => response.data as BrowserSettings);
  },
  updatePrivacySettings(settings) {
    return send("settings.privacy.update", {
      dohEnabled: settings.dohEnabled ? "true" : "false",
      dohProviderUrl: settings.dohProviderUrl,
      httpsOnlyEnabled: settings.httpsOnlyEnabled ? "true" : "false",
      privacyProtectionLevel: settings.privacyProtectionLevel,
    }).then((response) => response.data as BrowserSettings);
  },
  requestBatteryOptimizationState() {
    return requestObject<BatteryOptimizationState>("settings.batteryOptimizationState");
  },
  openBatteryOptimizationSettings() {
    return requestObject<BatteryOptimizationState>("settings.openBatteryOptimization");
  },
  updateWebDavSyncSettings(settings) {
    return send("sync.webdav.update", {
      enabled: settings.webDavSyncEnabled ? "true" : "false",
      url: settings.webDavSyncUrl,
      username: settings.webDavSyncUsername,
      password: settings.webDavSyncPassword,
      deviceName: settings.webDavSyncDeviceName,
    }).then((response) => response.data as BrowserSettings);
  },
  runWebDavSync() {
    return send("sync.webdav.run").then((response) => response.data as WebDavSyncResult);
  },
  exportBackup() {
    return send("backup.export").then((response) => response.data as BackupActionResult);
  },
  importBackup() {
    return send("backup.import").then((response) => response.data as BackupActionResult);
  },
  checkUpdate(ignoreSkipped = false) {
    return send("update.check", { ignoreSkipped: ignoreSkipped ? "true" : "false" })
      .then((response) => response.data as UpdateCheckResult);
  },
  installUpdate(versionCode) {
    return send("update.install", { versionCode: String(versionCode) })
      .then((response) => response.data as UpdateDownloadState);
  },
  requestUpdateDownloadState() {
    return requestObject<UpdateDownloadState>("update.downloadState");
  },
  skipUpdate(versionCode) {
    return send("update.skip", { versionCode: String(versionCode) }).then(() => undefined);
  },
  clearSkippedUpdate() {
    return send("update.clearSkip").then(() => undefined);
  },
  requestBookmarksData() {
    return requestData<BookmarkItem>("data.bookmarks");
  },
  requestHistoryData() {
    return requestData<HistoryItem>("data.history");
  },
  openBookmark(url) {
    command("bookmarks.open", { url });
  },
  removeBookmark(url) {
    command("bookmarks.remove", { url });
  },
  editBookmark(oldUrl, title, url) {
    command("bookmarks.edit", { oldUrl, title, url });
  },
  openHistory(url) {
    command("history.open", { url });
  },
  removeHistory(url) {
    command("history.remove", { url });
  },
  clearHistory() {
    command("history.clear");
  },
  openApp(id) {
    command("apps.open", { id });
  },
  pinApp(id) {
    command("apps.pin", { id });
  },
  editApp(id) {
    return requestItems<WebAppItem>("apps.edit", { id });
  },
  updateApp(id, name, startUrl, iconDataUrl) {
    const payload: BridgePayload = { id, name, startUrl };
    if (iconDataUrl !== undefined) payload.iconDataUrl = iconDataUrl || "";
    return requestItems<WebAppItem>("apps.update", payload);
  },
  chooseAppIcon(id) {
    return requestObject<{ iconDataUrl?: string | null }>("apps.icon.choose", { id })
      .then((result) => result.iconDataUrl || null);
  },
  updateAppIcon(id, iconDataUrl) {
    return requestItems<WebAppItem>("apps.icon.update", { id, iconDataUrl: iconDataUrl || "" });
  },
  deleteApp(id) {
    command("apps.delete", { id });
  }
};

export type { AvailableUpdate, BackupActionResult, BatteryOptimizationState, BookmarkItem, BrowserSettings, HistoryItem, SearchSuggestionItem, UpdateCheckResult, UpdateDownloadState, WebAppItem, WebDavSyncResult, WebDavSyncSettings };
