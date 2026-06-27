import React, { useCallback, useMemo } from "react";
import { createRoot } from "react-dom/client";
import { browser } from "wxt/browser";
import {
  DEFAULT_LAUNCHER_DOCK_ENTRY_IDS,
  LauncherPage,
  defaultLauncherLabels,
  defaultLauncherSystemEntries,
  type LauncherPlatform,
} from "@hyper-launcher";
import {
  defaultLauncherWebDavSyncLabels,
  useLauncherWebDavSync,
  type LauncherWebDavSyncEvent,
  type LauncherWebDavSyncOptions,
} from "@hyper-sync/launcher-webdav-sync";
import { formatSyncResult, isSyncResultLike } from "@hyper-sync/sync-result";
import type { SyncSettingsDialogValues } from "@hyper-sync/settings-dialog";
import { DEFAULT_DEVICE_NAME } from "../identity";
import { getDefaultSettings, loadSettings, saveSettings } from "../storage";
import { DEPRECATED_ENTRY_IDS, launcherLayoutStorage } from "../launcher-layout";
import "../styles.css";
import type { SyncResult, SyncSettings, WebAppRecord } from "../types";
import { sendCommand } from "./bridge";

const chromiumSystemUrls: Record<string, string> = {
  bookmarks: "chrome://bookmarks/",
  history: "chrome://history/",
  extensions: "chrome://extensions/",
};
const firefoxSystemUrls: Record<string, string> = {
  bookmarks: "about:bookmarks",
  history: "about:history",
  extensions: "about:addons",
};

function CompanionHomePage() {
  const launcherVariant = new URLSearchParams(window.location.search).get("variant") === "mobile" ? "mobile" : "desktop";

  const platform = useMemo<LauncherPlatform>(() => ({
    systemEntries: defaultLauncherSystemEntries,
    defaultDockEntryIds: DEFAULT_LAUNCHER_DOCK_ENTRY_IDS,
    deprecatedEntryIds: DEPRECATED_ENTRY_IDS,
    loadApps: () => sendCommand<WebAppRecord[]>("webapps.list"),
    openApp: (app) => {
      openCurrentTab(app.startUrl);
    },
    openSystem: (action) => {
      openCurrentTab(systemUrl(action));
    },
    deleteApp: (app) => sendCommand<WebAppRecord[]>("webapps.delete", { id: app.id }),
    saveApp: (app, changes) => sendCommand<WebAppRecord[]>("webapps.save", {
      ...app,
      name: changes.name,
      startUrl: changes.startUrl,
      iconDataUrl: Object.prototype.hasOwnProperty.call(changes, "iconDataUrl") ? changes.iconDataUrl ?? null : app.iconDataUrl,
      iconSource: changes.iconSource || app.iconSource,
    }),
    updateAppIcon: (app, iconDataUrl) => sendCommand<WebAppRecord[]>("webapps.save", { ...app, iconDataUrl }),
  }), []);

  const saveSyncSettings = useCallback(async (values: SyncSettingsDialogValues): Promise<SyncSettings> => {
    const currentSettings = await loadSettings().catch(() => getDefaultSettings());
    const nextSettings = dialogValuesToSettings(values, currentSettings);
    await saveSettings(nextSettings);
    return nextSettings;
  }, []);

  const subscribeSyncEvents = useCallback((listener: (event: LauncherWebDavSyncEvent<SyncResult>) => void) => {
    const onMessage = (message: { type?: string; syncResult?: unknown }) => {
      if (message?.type !== "remote.synced" && message?.type !== "launcher.changed") return;
      listener({
        type: message.type,
        syncResult: isSyncResultLike(message.syncResult) ? message.syncResult as SyncResult : null,
      });
    };
    browser.runtime.onMessage.addListener(onMessage);
    return () => browser.runtime.onMessage.removeListener(onMessage);
  }, []);

  const webDavOptions = useMemo<LauncherWebDavSyncOptions<SyncSettings, SyncResult>>(() => ({
    labels: defaultLauncherWebDavSyncLabels,
    loadSettings,
    saveSettings: saveSyncSettings,
    isConfigured: (settings) => !!settings.webDavUrl.trim(),
    settingsToDialogValues,
    normalizeDialogValues,
    runSync: (options) => sendCommand<SyncResult>(
      "sync.run",
      options?.mode ? { mode: options.mode } : undefined,
    ),
    scheduleSyncSoon: () => sendCommand("sync.soon"),
    summarizeResult: formatSyncResult,
    subscribeSyncEvents,
  }), [saveSyncSettings, subscribeSyncEvents]);
  const webDavSync = useLauncherWebDavSync(webDavOptions);

  return (
    <>
      <LauncherPage
        labels={defaultLauncherLabels}
        platform={platform}
        layoutStorage={launcherLayoutStorage}
        refreshToken={webDavSync.refreshToken}
        previewLayoutMode="original"
        onLayoutChanged={webDavSync.onLayoutChanged}
        topActions={webDavSync.topActions}
        variant={launcherVariant}
      />
      {webDavSync.settingsDialog}
    </>
  );
}

function settingsToDialogValues(settings: SyncSettings): SyncSettingsDialogValues {
  return {
    webDavUrl: settings.webDavUrl,
    username: settings.username,
    password: settings.password,
    folderTitle: settings.folderTitle,
    deviceName: settings.deviceName,
    deviceId: settings.deviceId,
  };
}

function dialogValuesToSettings(values: SyncSettingsDialogValues, current: SyncSettings): SyncSettings {
  return normalizeSettings({
    ...current,
    webDavUrl: values.webDavUrl,
    username: values.username,
    password: values.password,
    folderTitle: values.folderTitle || current.folderTitle,
    deviceName: DEFAULT_DEVICE_NAME,
  });
}

function normalizeDialogValues(values: SyncSettingsDialogValues): SyncSettingsDialogValues {
  return {
    ...values,
    webDavUrl: values.webDavUrl.trim(),
    username: values.username.trim(),
    folderTitle: values.folderTitle?.trim() || "Hyper Browser",
    deviceName: DEFAULT_DEVICE_NAME,
  };
}

function normalizeSettings(settings: SyncSettings): SyncSettings {
  return {
    ...settings,
    webDavUrl: settings.webDavUrl.trim(),
    username: settings.username.trim(),
    folderTitle: settings.folderTitle.trim() || "Hyper Browser",
    deviceName: DEFAULT_DEVICE_NAME,
  };
}

function openCurrentTab(url: string): void {
  browser.tabs.update({ url }).catch((error) => {
    console.warn("Unable to open in current tab.", error);
  });
}

function systemUrl(action: string): string {
  const urls = import.meta.env.FIREFOX ? firefoxSystemUrls : chromiumSystemUrls;
  return urls[action] || (import.meta.env.FIREFOX ? "about:blank" : "chrome://newtab/");
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <CompanionHomePage />
  </React.StrictMode>
);
