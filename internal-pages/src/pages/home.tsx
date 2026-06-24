import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { LauncherPage, LauncherSyncActions, type LauncherPlatform, type LauncherSyncState, type LauncherSystemEntry } from "@hyper-launcher";
import { shouldRefreshLauncherAfterSync, shouldUpdateSyncStatusAfterRemoteCheck } from "@hyper-sync";
import "../hyper-browser";
import type { BrowserSettings, WebDavSyncResult } from "../hyper-browser";
import "../styles.css";
import { t } from "../i18n";
import { createLauncherLayoutStorage, waitForLauncherLayoutSaves } from "../launcher-layout-storage";
import { isPlainObject, sendBackgroundCommand, type RemoteCheckResult } from "../background-command";

const DEFAULT_DOCK_ENTRY_IDS = ["system:bookmarks", "system:history", "system:extensions"];
const AUTO_SYNC_DEBOUNCE_MS = 1800;

function HomePage() {
  const [settingsConfigured, setSettingsConfigured] = useState(false);
  const [syncState, setSyncState] = useState<LauncherSyncState>("idle");
  const [syncMessage, setSyncMessage] = useState("");
  const [layoutRevision, setLayoutRevision] = useState(0);
  const autoSyncTimer = useRef<number | null>(null);
  const remoteCheckRunning = useRef(false);

  useEffect(() => {
    window.hyperBrowser.requestSettingsData()
      .then((settings) => setSettingsConfigured(isWebDavConfigured(settings)))
      .catch(() => undefined);
  }, []);

  const storage = useMemo(() => createLauncherLayoutStorage(), []);

  const checkRemoteChanges = useCallback(async () => {
    if (remoteCheckRunning.current || document.visibilityState !== "visible") return;
    remoteCheckRunning.current = true;
    try {
      const remoteCheck = await sendBackgroundCommand<RemoteCheckResult>("remote.check");
      const syncResult = remoteCheck.syncResult || null;
      if (syncResult && shouldUpdateSyncStatusAfterRemoteCheck(remoteCheck)) {
        setSyncState("success");
        setSyncMessage(webDavSyncSummary(syncResult));
      }
      if (shouldRefreshLauncherAfterSync(remoteCheck)) setLayoutRevision((current) => current + 1);
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
      waitForLauncherLayoutSaves()
        .then(() => sendBackgroundCommand("launcher.syncSoon"))
        .catch((error) => console.warn("Unable to schedule launcher sync.", error));
    }, AUTO_SYNC_DEBOUNCE_MS);
  }, []);

  useEffect(() => () => {
    if (autoSyncTimer.current !== null) {
      window.clearTimeout(autoSyncTimer.current);
      autoSyncTimer.current = null;
    }
  }, []);

  useEffect(() => {
    checkRemoteChanges();
    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") checkRemoteChanges();
    };
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => {
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, [checkRemoteChanges]);

  useEffect(() => {
    const onMessage = (message: unknown) => {
      if (!isPlainObject(message)) return;
      if (message.type !== "remote.synced" && message.type !== "launcher.changed") return;
      const syncResult = isPlainObject(message.syncResult) ? message.syncResult as WebDavSyncResult : null;
      if (syncResult) {
        setSyncState("success");
        setSyncMessage(webDavSyncSummary(syncResult));
      }
      setLayoutRevision((current) => current + 1);
    };
    browser?.runtime?.onMessage?.addListener(onMessage);
    return () => browser?.runtime?.onMessage?.removeListener(onMessage);
  }, []);

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
    openStandaloneApp: (app) => {
      window.hyperBrowser.openStandaloneApp(app.id);
    },
    openSystem: (action) => {
      if (action === "bookmarks") window.hyperBrowser.showBookmarks();
      if (action === "history") window.hyperBrowser.showHistory();
      if (action === "extensions") window.hyperBrowser.showExtensions();
    },
    deleteApp: async (app) => {
      const items = await window.hyperBrowser.deleteApp(app.id);
      scheduleAutoSync();
      return items;
    },
    saveApp: async (app, changes) => {
      const items = await window.hyperBrowser.updateApp(app.id, changes.name, changes.startUrl, changes.iconDataUrl);
      scheduleAutoSync();
      return items;
    },
    chooseAppIcon: (app) => window.hyperBrowser.chooseAppIcon(app.id),
    pinApp: (app) => {
      window.hyperBrowser.pinApp(app.id);
    },
    updateAppIcon: async (app, iconDataUrl) => {
      const items = await window.hyperBrowser.updateAppIcon(app.id, iconDataUrl);
      scheduleAutoSync();
      return items;
    },
  }), [scheduleAutoSync, systemEntries]);

  const labels = useMemo(() => ({
    loading: t("apps.loading"),
    emptyDesktop: t("apps.empty"),
    loadAppsError: t("apps.failed"),
    loadLayoutError: t("apps.failed"),
    folder: t("home.folder"),
    folderEmpty: t("home.folderEmpty"),
    openStandaloneApp: t("apps.openStandalone"),
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
        return waitForLauncherLayoutSaves().then(() => sendBackgroundCommand<WebDavSyncResult>("sync.run"));
      })
      .then(async (result) => {
        if (!result) return;
        const settings = result.settings;
        if (settings) {
          setSettingsConfigured(isWebDavConfigured(settings));
        }
        if (shouldRefreshLauncherAfterSync(result)) setLayoutRevision((current) => current + 1);
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
        layoutStorage={storage}
        refreshToken={layoutRevision}
        previewLayoutMode="compact"
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

function webDavSyncSummary(result: WebDavSyncResult): string {
  const summary = t("settings.webDavSyncComplete", {
    bookmarks: result.bookmarkCount,
    webApps: result.webAppCount,
    deleted: result.deletedBookmarkCount + result.deletedWebAppCount,
  });
  return result.pendingOperationCount > 0
    ? `${summary}${t("settings.webDavSyncPending", { pending: result.pendingOperationCount })}`
    : summary;
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <HomePage />
  </React.StrictMode>
);
