import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { LauncherPage, LauncherSyncActions, type LauncherPlatform, type LauncherSyncState, type LauncherSystemEntry } from "@hyper-launcher";
import { syncLauncherLayout } from "@hyper-launcher/webdav-layout";
import "../hyper-browser";
import type { BrowserSettings, WebDavSyncResult } from "../hyper-browser";
import "../styles.css";
import { t } from "../i18n";
import { createLauncherLayoutStorage } from "../launcher-layout-storage";

const DEFAULT_DOCK_ENTRY_IDS = ["system:bookmarks", "system:history", "system:extensions"];
const AUTO_SYNC_DEBOUNCE_MS = 1800;
const REMOTE_POLL_MS = 30000;

function HomePage() {
  const [settingsConfigured, setSettingsConfigured] = useState(false);
  const [syncState, setSyncState] = useState<LauncherSyncState>("idle");
  const [syncMessage, setSyncMessage] = useState("");
  const [layoutRevision, setLayoutRevision] = useState(0);
  const autoSyncTimer = useRef<number | null>(null);
  const autoSyncRunning = useRef(false);
  const autoSyncPending = useRef(false);
  const lastSeenRemoteUpdatedAt = useRef(0);
  const remoteCheckRunning = useRef(false);

  useEffect(() => {
    window.hyperBrowser.requestSettingsData()
      .then((settings) => setSettingsConfigured(isWebDavConfigured(settings)))
      .catch(() => undefined);
  }, []);

  const storage = useMemo(() => createLauncherLayoutStorage(), []);

  const runAutoSync = useCallback(async (options: { refreshLauncher?: boolean } = {}) => {
    if (autoSyncRunning.current) {
      autoSyncPending.current = true;
      return;
    }
    autoSyncRunning.current = true;
    try {
      do {
        autoSyncPending.current = false;
        const settings = await window.hyperBrowser.requestSettingsData();
        if (!settings.webDavSyncEnabled || !isWebDavConfigured(settings)) return;
        const result = await window.hyperBrowser.runWebDavSync();
        const layoutResult = await syncLauncherLayout(storage, {
          webDavUrl: settings.webDavSyncUrl,
          username: settings.webDavSyncUsername,
          password: settings.webDavSyncPassword,
          deviceId: settings.webDavSyncDeviceId,
          deviceName: settings.webDavSyncDeviceName || "Hyper Browser Android",
          clientName: "hyper-browser-android",
        }, await launcherSyncOptions());
        const importedWebApps = result.importedWebAppCount + result.removedWebAppCount > 0;
        if (layoutResult.direction === "pull" || importedWebApps || options.refreshLauncher) {
          setLayoutRevision((current) => current + 1);
        }
        setSyncState("success");
        setSyncMessage(webDavSyncSummary(result));
      } while (autoSyncPending.current);
    } catch (error) {
      setSyncState("error");
      setSyncMessage(error instanceof Error ? error.message : t("settings.webDavSyncFailed"));
    } finally {
      autoSyncRunning.current = false;
    }
  }, [storage]);

  const checkRemoteChanges = useCallback(async () => {
    if (remoteCheckRunning.current || document.visibilityState !== "visible") return;
    remoteCheckRunning.current = true;
    try {
      const settings = await window.hyperBrowser.requestSettingsData();
      if (!settings.webDavSyncEnabled || !isWebDavConfigured(settings)) return;
      const remoteCheck = await window.hyperBrowser.checkWebDavRemoteChanges(lastSeenRemoteUpdatedAt.current);
      if (remoteCheck.updatedAt > 0) lastSeenRemoteUpdatedAt.current = remoteCheck.updatedAt;
      const layoutResult = await syncLauncherLayout(storage, {
        webDavUrl: settings.webDavSyncUrl,
        username: settings.webDavSyncUsername,
        password: settings.webDavSyncPassword,
        deviceId: settings.webDavSyncDeviceId,
        deviceName: settings.webDavSyncDeviceName || "Hyper Browser Android",
        clientName: "hyper-browser-android",
      }, await launcherSyncOptions());
      const syncResult = remoteCheck.syncResult || null;
      if (syncResult) {
        setSyncState("success");
        setSyncMessage(webDavSyncSummary(syncResult));
      }
      if (layoutResult.direction === "pull" || remoteCheck.changed) setLayoutRevision((current) => current + 1);
    } catch (error) {
      console.warn("Remote sync check failed.", error);
    } finally {
      remoteCheckRunning.current = false;
    }
  }, [storage]);

  const scheduleAutoSync = useCallback(() => {
    if (autoSyncTimer.current !== null) {
      window.clearTimeout(autoSyncTimer.current);
    }
    autoSyncTimer.current = window.setTimeout(() => {
      autoSyncTimer.current = null;
      runAutoSync();
    }, AUTO_SYNC_DEBOUNCE_MS);
  }, [runAutoSync]);

  useEffect(() => () => {
    if (autoSyncTimer.current !== null) {
      window.clearTimeout(autoSyncTimer.current);
      autoSyncTimer.current = null;
    }
  }, []);

  useEffect(() => {
    checkRemoteChanges();
    const interval = window.setInterval(() => {
      checkRemoteChanges();
    }, REMOTE_POLL_MS);
    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") checkRemoteChanges();
    };
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => {
      window.clearInterval(interval);
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, [checkRemoteChanges]);

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
      return window.hyperBrowser.deleteApp(app.id);
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
    dockFull: t("home.dockFull"),
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
          }, await launcherSyncOptions());
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
      labels={labels}
      platform={platform}
      storage={storage}
      refreshToken={layoutRevision}
      onLayoutChanged={scheduleAutoSync}
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

async function launcherSyncOptions(): Promise<{ availableEntryIds: string[] }> {
  const apps = await window.hyperBrowser.requestAppsData().catch(() => []);
  return { availableEntryIds: apps.map((app) => app.id) };
}

function webDavSyncSummary(result: WebDavSyncResult): string {
  return t("settings.webDavSyncComplete", {
    bookmarks: result.bookmarkCount,
    webApps: result.webAppCount,
    deleted: result.deletedBookmarkCount + result.deletedWebAppCount,
  });
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <HomePage />
  </React.StrictMode>
);
