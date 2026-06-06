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
  themeColor: number;
  displayMode: string;
  createdAt: number;
  lastOpenedAt: number;
};

type SearchSuggestionItem = {
  title?: string;
  url: string;
  source: "bookmark" | "history";
};

type BrowserSettings = {
  searchEngineId: "google" | "bing" | "custom";
  searchEngineName: string;
  customSearchUrl: string;
  toolbarPosition: "top" | "bottom";
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
  requestBatteryOptimizationState(): Promise<BatteryOptimizationState>;
  openBatteryOptimizationSettings(): Promise<BatteryOptimizationState>;
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
  editApp(id: string, name: string, startUrl: string): void;
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

function send(type: string, payload?: Record<string, string>): Promise<BridgeResponse> {
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

function command(type: string, params?: Record<string, string>) {
  send(type, params).catch((error) => console.error(error));
}

function requestData<T>(type: string): Promise<T[]> {
  return send(type).then((response) => {
    if (!response.itemsJson) return [];
    const items = JSON.parse(response.itemsJson) as unknown;
    return Array.isArray(items) ? items as T[] : [];
  });
}

function requestObject<T>(type: string): Promise<T> {
  return send(type).then((response) => response.data as T);
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
  requestBatteryOptimizationState() {
    return requestObject<BatteryOptimizationState>("settings.batteryOptimizationState");
  },
  openBatteryOptimizationSettings() {
    return requestObject<BatteryOptimizationState>("settings.openBatteryOptimization");
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
  editApp(id, name, startUrl) {
    command("apps.edit", { id, name, startUrl });
  },
  deleteApp(id) {
    command("apps.delete", { id });
  }
};

export type { AvailableUpdate, BatteryOptimizationState, BookmarkItem, BrowserSettings, HistoryItem, SearchSuggestionItem, UpdateCheckResult, UpdateDownloadState, WebAppItem };
