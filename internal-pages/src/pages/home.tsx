import React, { useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import { LauncherPage, LauncherSyncActions, type LauncherLayout, type LauncherLayoutStorage, type LauncherPlatform, type LauncherSyncState, type LauncherSystemEntry } from "@hyper-launcher";
import { syncLauncherLayout } from "@hyper-launcher/webdav-layout";
import "../hyper-browser";
import type { BrowserSettings, WebDavSyncResult } from "../hyper-browser";
import "../styles.css";
import { t } from "../i18n";

const LAYOUT_STORAGE_KEY = "hyper-home-launcher-layout-v3";
const LEGACY_LAYOUT_STORAGE_KEY = "hyper-home-launcher-layout-v1";
const DEFAULT_DOCK_ENTRY_IDS = ["system:bookmarks", "system:history", "system:extensions"];

function HomePage() {
  const [settingsConfigured, setSettingsConfigured] = useState(false);
  const [syncState, setSyncState] = useState<LauncherSyncState>("idle");
  const [syncMessage, setSyncMessage] = useState("");
  const [layoutRevision, setLayoutRevision] = useState(0);

  useEffect(() => {
    window.hyperBrowser.requestSettingsData()
      .then((settings) => setSettingsConfigured(isWebDavConfigured(settings)))
      .catch(() => undefined);
  }, []);

  const storage = useMemo<LauncherLayoutStorage>(() => ({
    async load() {
      return readJson(LAYOUT_STORAGE_KEY) || readJson(LEGACY_LAYOUT_STORAGE_KEY);
    },
    save(layout: LauncherLayout) {
      window.localStorage.setItem(LAYOUT_STORAGE_KEY, JSON.stringify(layout));
    },
  }), []);

  const systemEntries = useMemo<LauncherSystemEntry[]>(() => ([
    { id: "system:bookmarks", kind: "system", title: t("home.bookmarks"), mark: "B", color: "#34a853", action: "bookmarks" },
    { id: "system:history", kind: "system", title: t("home.history"), mark: "H", color: "#fbbc04", action: "history" },
    { id: "system:extensions", kind: "system", title: t("home.extensions"), mark: "Ex", color: "#ea4335", action: "extensions" },
  ]), []);

  const platform = useMemo<LauncherPlatform>(() => ({
    systemEntries,
    defaultDockEntryIds: DEFAULT_DOCK_ENTRY_IDS,
    loadApps: () => window.hyperBrowser.requestAppsData(),
    openApp: (app) => {
      window.hyperBrowser.openApp(app.id);
    },
    openSystem: (action) => {
      if (action === "bookmarks") window.hyperBrowser.showBookmarks();
      if (action === "history") window.hyperBrowser.showHistory();
      if (action === "extensions") window.hyperBrowser.showExtensions();
    },
    deleteApp: (app) => {
      window.hyperBrowser.deleteApp(app.id);
      return window.hyperBrowser.requestAppsData().then((items) => items.filter((item) => item.id !== app.id));
    },
    saveApp: (app, changes) => window.hyperBrowser.updateApp(app.id, changes.name, changes.startUrl, changes.iconDataUrl),
    chooseAppIcon: (app) => window.hyperBrowser.chooseAppIcon(app.id),
    pinApp: (app) => {
      window.hyperBrowser.pinApp(app.id);
    },
    updateAppIcon: (app, iconDataUrl) => window.hyperBrowser.updateAppIcon(app.id, iconDataUrl),
  }), [systemEntries]);

  const labels = useMemo(() => ({
    loading: t("apps.loading"),
    emptyDesktop: t("apps.empty"),
    loadAppsError: t("apps.failed"),
    loadLayoutError: t("apps.failed"),
    folder: t("home.folder"),
    folderEmpty: t("home.folderEmpty"),
    open: t("home.open"),
    editHomeScreen: t("home.editHomeScreen"),
    done: t("home.done"),
    newFolder: t("home.newFolder"),
    renameFolder: t("home.renameFolder"),
    unpackFolder: t("home.unpackFolder"),
    moveToDesktop: t("home.moveToDesktop"),
    moveToDock: t("home.moveToDock"),
    pinApp: t("apps.pin"),
    editApp: t("apps.editTitle"),
    editIcon: t("apps.editIcon"),
    appName: t("common.name"),
    appUrl: t("common.url"),
    deleteApp: t("common.delete"),
    iconTitle: t("apps.editIcon"),
    iconLetter: t("apps.iconLetter"),
    iconBackground: t("apps.iconBackground"),
    iconPreset: t("apps.iconPreset"),
    iconSourceTitle: t("apps.iconSourceTitle"),
    iconSite: t("apps.iconSite"),
    iconTitleFallback: t("apps.iconTitleFallback"),
    iconDefaultLibrary: t("apps.iconDefaultLibrary"),
    iconChooseImage: t("apps.iconChooseImage"),
    iconSelectedImage: t("apps.iconSelectedImage"),
    iconPresetLabels: {
      news: t("apps.iconPresetNews"),
      video: t("apps.iconPresetVideo"),
      music: t("apps.iconPresetMusic"),
      shop: t("apps.iconPresetShop"),
      chat: t("apps.iconPresetChat"),
      docs: t("apps.iconPresetDocs"),
      work: t("apps.iconPresetWork"),
      star: t("apps.iconPresetStar"),
    },
    iconUpload: t("apps.iconUpload"),
    iconUseImage: t("apps.iconUseImage"),
    iconReset: t("apps.iconReset"),
    cancel: t("common.cancel"),
    save: t("common.save"),
    deleteFailed: t("apps.failed"),
    editFailed: t("apps.failed"),
    iconUpdateFailed: t("apps.iconUpdateFailed"),
  }), []);

  function runSync() {
    setSyncState("syncing");
    setSyncMessage(t("settings.webDavSyncingShort"));
    window.hyperBrowser.requestSettingsData()
      .then((settings) => {
        const configured = isWebDavConfigured(settings);
        setSettingsConfigured(configured);
        if (!configured) {
          setSyncState("needs-settings");
          setSyncMessage(t("settings.webDavNeedsSetup"));
          window.hyperBrowser.showSettings();
          return null;
        }
        return window.hyperBrowser.runWebDavSync();
      })
      .then(async (result) => {
        if (!result) return;
        const settings = result.settings;
        if (settings) {
          setSettingsConfigured(isWebDavConfigured(settings));
          const layoutResult = await syncLauncherLayout(storage, {
            webDavUrl: settings.webDavSyncUrl,
            username: settings.webDavSyncUsername,
            password: settings.webDavSyncPassword,
            deviceId: settings.webDavSyncDeviceId,
            deviceName: settings.webDavSyncDeviceName || "Hyper Browser Android",
            clientName: "hyper-browser-android",
          });
          if (layoutResult.direction === "pull") setLayoutRevision((current) => current + 1);
        }
        setSyncState("success");
        setSyncMessage(webDavSyncSummary(result));
      })
      .catch((error) => {
        setSyncState("error");
        setSyncMessage(error instanceof Error ? error.message : t("settings.webDavSyncFailed"));
      });
  }

  return (
    <LauncherPage
      key={layoutRevision}
      labels={labels}
      platform={platform}
      storage={storage}
      topActions={(
        <LauncherSyncActions
          labels={{
            settings: t("settings.title"),
            sync: t("settings.webDavSyncNow"),
            syncing: t("settings.webDavSyncingShort"),
            syncTitle: t("settings.webDavSyncNow"),
            setupTitle: t("settings.webDavNeedsSetup"),
          }}
          message={syncMessage}
          settingsConfigured={settingsConfigured}
          state={syncState}
          onOpenSettings={() => window.hyperBrowser.showSettings()}
          onSync={runSync}
        />
      )}
      variant="mobile"
    />
  );
}

function isWebDavConfigured(settings: BrowserSettings): boolean {
  return settings.webDavSyncUrl.trim().length > 0;
}

function webDavSyncSummary(result: WebDavSyncResult): string {
  return t("settings.webDavSyncComplete", {
    bookmarks: result.bookmarkCount,
    webApps: result.webAppCount,
    deleted: result.deletedBookmarkCount + result.deletedWebAppCount,
  });
}

function readJson(key: string): Record<string, unknown> | null {
  const raw = window.localStorage.getItem(key);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as Record<string, unknown>;
  } catch {
    return null;
  }
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <HomePage />
  </React.StrictMode>
);
