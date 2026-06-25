import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { LauncherPage, LauncherSyncActions, type LauncherPlatform, type LauncherSyncState, type LauncherSystemEntry } from "@hyper-launcher";
import { shouldRefreshLauncherAfterSync } from "@hyper-sync";
import { SyncSettingsDialog, type SyncSettingsDialogResult, type SyncSettingsDialogValues } from "@hyper-sync/settings-dialog";
import "../hyper-browser";
import type { BrowserSettings, WebAppItem, WebDavSyncResult } from "../hyper-browser";
import "../styles.css";
import { t } from "../i18n";
import { createLauncherLayoutStorage, waitForLauncherLayoutSaves } from "../launcher-layout-storage";
import { isPlainObject, sendBackgroundCommand } from "../background-command";

const DEFAULT_DOCK_ENTRY_IDS = ["system:bookmarks", "system:history", "system:extensions"];
const AUTO_SYNC_DEBOUNCE_MS = 1800;

function HomePage() {
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsConfigured, setSettingsConfigured] = useState(false);
  const [syncState, setSyncState] = useState<LauncherSyncState>("idle");
  const [syncMessage, setSyncMessage] = useState("");
  const [layoutRevision, setLayoutRevision] = useState(0);
  const autoSyncTimer = useRef<number | null>(null);

  useEffect(() => {
    window.hyperBrowser.requestSettingsData()
      .then((settings) => setSettingsConfigured(isWebDavConfigured(settings)))
      .catch(() => undefined);
  }, []);

  const storage = useMemo(() => createLauncherLayoutStorage(), []);

  const loadSyncSettings = useCallback(async () => {
    return settingsToDialogValues(await window.hyperBrowser.requestSettingsData());
  }, []);

  const syncSettings = useCallback(async (values: SyncSettingsDialogValues): Promise<SyncSettingsDialogResult> => {
    if (!isHttpUrl(values.webDavUrl.trim())) {
      throw new Error(t("settings.webDavUrlRequired"));
    }
    try {
      const savedSettings = await window.hyperBrowser.updateWebDavSyncSettings({
        webDavSyncEnabled: values.webDavUrl.trim().length > 0,
        webDavSyncUrl: values.webDavUrl.trim(),
        webDavSyncUsername: values.username.trim(),
        webDavSyncPassword: values.password,
        webDavSyncDeviceName: values.deviceName.trim(),
      });
      setSettingsConfigured(isWebDavConfigured(savedSettings));
      const result = await waitForLauncherLayoutSaves().then(() => sendBackgroundCommand<WebDavSyncResult>("sync.run"));
      if (result.settings) setSettingsConfigured(isWebDavConfigured(result.settings));
      setSyncState("success");
      setSyncMessage(webDavSyncSummary(result));
      return {
        values: settingsToDialogValues(result.settings || savedSettings),
        message: webDavSyncSummary(result),
      };
    } catch (syncError) {
      setSyncState("error");
      setSyncMessage(syncError instanceof Error ? syncError.message : t("settings.webDavSyncFailed"));
      throw syncError;
    }
  }, []);

  const scheduleAutoSync = useCallback(() => {
    if (autoSyncTimer.current !== null) {
      window.clearTimeout(autoSyncTimer.current);
    }
    autoSyncTimer.current = window.setTimeout(() => {
      autoSyncTimer.current = null;
      waitForLauncherLayoutSaves()
        .then(() => sendBackgroundCommand("sync.soon"))
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
    const onMessage = (message: unknown) => {
      if (!isPlainObject(message)) return;
      if (message.type !== "remote.synced" && message.type !== "launcher.changed") return;
      const syncResult = isPlainObject(message.syncResult) ? message.syncResult as WebDavSyncResult : null;
      if (syncResult) {
        setSyncState("success");
        setSyncMessage(webDavSyncSummary(syncResult));
        if (shouldRefreshLauncherAfterSync(syncResult)) setLayoutRevision((current) => current + 1);
        return;
      }
      if (message.type === "launcher.changed") setLayoutRevision((current) => current + 1);
    };
    browser?.runtime?.onMessage?.addListener(onMessage);
    return () => browser?.runtime?.onMessage?.removeListener(onMessage);
  }, []);

  const systemEntries = useMemo<LauncherSystemEntry[]>(() => ([
    { id: "system:bookmarks", kind: "system", title: t("home.bookmarks"), mark: "B", color: "#0d7f66", systemIcon: "bookmarks", action: "bookmarks" },
    { id: "system:history", kind: "system", title: t("home.history"), mark: "H", color: "#f4a900", systemIcon: "history", action: "history" },
    { id: "system:extensions", kind: "system", title: t("home.extensions"), mark: "Ex", color: "#d73547", systemIcon: "extensions", action: "extensions" },
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
      const items = await sendBackgroundCommand<WebAppItem[]>("webapps.delete", { id: app.id });
      return items;
    },
    saveApp: async (app, changes) => {
      const items = await sendBackgroundCommand<WebAppItem[]>("webapps.save", {
        ...app,
        ...changes,
        id: app.id,
      });
      return items;
    },
    chooseAppIcon: (app) => window.hyperBrowser.chooseAppIcon(app.id),
    pinApp: (app) => {
      window.hyperBrowser.pinApp(app.id);
    },
    updateAppIcon: async (app, iconDataUrl) => {
      const items = await sendBackgroundCommand<WebAppItem[]>("webapps.save", {
        ...app,
        id: app.id,
        iconDataUrl,
        iconSource: iconDataUrl ? "custom" : "site",
      });
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
          setSettingsOpen(true);
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
        setSyncState("success");
        setSyncMessage(webDavSyncSummary(result));
      })
      .catch((error) => {
        setSyncState("error");
        setSyncMessage(error instanceof Error ? error.message : t("settings.webDavSyncFailed"));
      });
  }

  return (
    <>
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
          onOpenSettings={() => setSettingsOpen(true)}
          onSync={runSync}
        />
      )}
      variant="mobile"
    />
      {settingsOpen && (
        <SyncSettingsDialog
          labels={androidSyncSettingsLabels}
          loadValues={loadSyncSettings}
          normalizeValues={normalizeDialogValues}
          syncValues={syncSettings}
          showFolderTitle={false}
          onClose={() => setSettingsOpen(false)}
        />
      )}
    </>
  );
}

function isWebDavConfigured(settings: BrowserSettings): boolean {
  return settings.webDavSyncUrl.trim().length > 0;
}

function settingsToDialogValues(settings: BrowserSettings): SyncSettingsDialogValues {
  return {
    webDavUrl: settings.webDavSyncUrl,
    username: settings.webDavSyncUsername,
    password: settings.webDavSyncPassword,
    deviceName: settings.webDavSyncDeviceName,
    deviceId: settings.webDavSyncDeviceId,
  };
}

function normalizeDialogValues(values: SyncSettingsDialogValues): SyncSettingsDialogValues {
  return {
    ...values,
    webDavUrl: values.webDavUrl.trim(),
    username: values.username.trim(),
    deviceName: values.deviceName.trim() || "Android",
  };
}

function isHttpUrl(value: string) {
  try {
    const protocol = new URL(value).protocol;
    return protocol === "https:" || protocol === "http:";
  } catch {
    return false;
  }
}

const androidSyncSettingsLabels = {
  title: t("settings.title"),
  close: t("common.close"),
  webDavAddress: t("settings.webDavUrl"),
  username: t("settings.webDavUsername"),
  password: t("settings.webDavPassword"),
  folderTitle: "Sync folder title",
  deviceName: t("settings.webDavDeviceName"),
  help: t("settings.webDavHelp"),
  sync: t("settings.webDavSyncNow"),
  syncing: t("settings.webDavSyncingShort"),
  loadFailed: t("settings.unavailable"),
  syncFailed: t("settings.webDavSyncFailed"),
  deviceId: (deviceId: string) => t("settings.webDavDeviceId", { deviceId }),
};

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
