import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { browser } from "wxt/browser";
import { LauncherPage, LauncherSyncActions, type LauncherPlatform, type LauncherSyncState, type LauncherSystemEntry } from "@hyper-launcher";
import { shouldRefreshLauncherAfterSync } from "@hyper-sync";
import { SyncSettingsDialog, type SyncSettingsDialogResult, type SyncSettingsDialogValues } from "@hyper-sync/settings-dialog";
import { DEFAULT_DEVICE_NAME } from "../identity";
import { getDefaultSettings, loadSettings, saveSettings } from "../storage";
import { DEFAULT_DOCK_ENTRY_IDS, DEPRECATED_ENTRY_IDS, launcherLayoutStorage } from "../launcher-layout";
import "../styles.css";
import type { SyncResult, SyncSettings, WebAppRecord } from "../types";
import { sendCommand } from "./bridge";

const systemEntries: LauncherSystemEntry[] = [
  { id: "system:bookmarks", kind: "system", title: "Bookmarks", mark: "B", color: "#34a853", action: "bookmarks" },
  { id: "system:history", kind: "system", title: "History", mark: "H", color: "#fbbc04", action: "history" },
  { id: "system:extensions", kind: "system", title: "Extensions", mark: "Ex", color: "#ea4335", action: "extensions" },
];
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
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsConfigured, setSettingsConfigured] = useState(false);
  const [syncState, setSyncState] = useState<LauncherSyncState>("idle");
  const [syncMessage, setSyncMessage] = useState("");
  const [layoutRevision, setLayoutRevision] = useState(0);
  const syncRunning = useRef(false);

  useEffect(() => {
    loadSettings()
      .then((settings) => setSettingsConfigured(!!settings.webDavUrl.trim()))
      .catch(() => undefined);
  }, []);

  const platform = useMemo<LauncherPlatform>(() => ({
    systemEntries,
    defaultDockEntryIds: DEFAULT_DOCK_ENTRY_IDS,
    deprecatedEntryIds: DEPRECATED_ENTRY_IDS,
    loadApps: () => sendCommand<WebAppRecord[]>("webapps.list"),
    openApp: (app) => {
      openTab(app.startUrl);
    },
    openSystem: (action) => {
      openTab(systemUrl(action));
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

  const runSync = useCallback(async (options: { refreshLauncher?: boolean } = {}) => {
    if (syncRunning.current) return;
    syncRunning.current = true;
    setSyncState("syncing");
    setSyncMessage("Syncing...");
    try {
      const settings = await loadSettings();
      const configured = !!settings.webDavUrl.trim();
      setSettingsConfigured(configured);
      if (!configured) {
        setSyncState("needs-settings");
        setSyncMessage("Set WebDAV URL first");
        setSettingsOpen(true);
        return;
      }
      const result = await sendCommand<SyncResult>("sync.run");
      setSyncState("success");
      setSyncMessage(syncResultMessage(result));
      if (options.refreshLauncher) setLayoutRevision((current) => current + 1);
    } catch (syncError) {
      const text = syncError instanceof Error ? syncError.message : "Sync failed.";
      setSyncState(isWebDavConfigError(syncError) ? "needs-settings" : "error");
      setSyncMessage(text);
      if (isWebDavConfigError(syncError)) setSettingsOpen(true);
    } finally {
      syncRunning.current = false;
    }
  }, []);

  const loadSyncSettings = useCallback(async () => settingsToDialogValues(await loadSettings()), []);

  const syncSettings = useCallback(async (values: SyncSettingsDialogValues): Promise<SyncSettingsDialogResult> => {
    try {
      const currentSettings = await loadSettings().catch(() => getDefaultSettings());
      const nextSettings = dialogValuesToSettings(values, currentSettings);
      await saveSettings(nextSettings);
      setSettingsConfigured(!!nextSettings.webDavUrl.trim());
      const result = await sendCommand<SyncResult>("sync.run");
      setSyncState("success");
      setSyncMessage(syncResultMessage(result));
      return {
        values: settingsToDialogValues(await loadSettings()),
        message: syncResultMessage(result),
      };
    } catch (syncError) {
      const text = syncError instanceof Error ? syncError.message : "Sync failed.";
      setSyncState(isWebDavConfigError(text) ? "needs-settings" : "error");
      setSyncMessage(text);
      throw syncError;
    }
  }, []);

  useEffect(() => {
    const onMessage = (message: { type?: string; syncResult?: unknown }) => {
      if (message?.type !== "remote.synced" && message?.type !== "launcher.changed") return;
      if (isSyncResult(message.syncResult)) {
        setSyncState("success");
        setSyncMessage(syncResultMessage(message.syncResult));
        if (shouldRefreshLauncherAfterSync(message.syncResult)) {
          setLayoutRevision((current) => current + 1);
        }
        return;
      }
      if (message.type === "launcher.changed") {
        setLayoutRevision((current) => current + 1);
      }
    };
    browser.runtime.onMessage.addListener(onMessage);
    return () => browser.runtime.onMessage.removeListener(onMessage);
  }, []);

  return (
    <>
      <LauncherPage
        platform={platform}
        layoutStorage={launcherLayoutStorage}
        refreshToken={layoutRevision}
        previewLayoutMode="original"
        onLayoutChanged={() => {
          sendCommand("sync.soon").catch(() => undefined);
        }}
        topActions={(
          <LauncherSyncActions
            labels={{
              settings: "Settings",
              sync: "Sync",
              syncing: "Syncing",
              syncTitle: "Sync",
              setupTitle: "Set up WebDAV before syncing",
            }}
            message={syncMessage}
            settingsConfigured={settingsConfigured}
            state={syncState}
            onOpenSettings={() => setSettingsOpen(true)}
            onSync={() => runSync()}
          />
        )}
        variant={launcherVariant}
      />
      {settingsOpen && (
        <SyncSettingsDialog
          labels={companionSyncSettingsLabels}
          loadValues={loadSyncSettings}
          normalizeValues={normalizeDialogValues}
          syncValues={syncSettings}
          onClose={() => setSettingsOpen(false)}
        />
      )}
    </>
  );
}

const companionSyncSettingsLabels = {
  title: "Settings",
  close: "Close",
  webDavAddress: "WebDAV address",
  username: "Username",
  password: "Password or app token",
  folderTitle: "Sync folder title",
  deviceName: "Device name",
  help: "Remote data is stored under HyperBrowserSync/bookmarks.json, webapps.json, launcher.json, and manifest.json.",
  sync: "Sync",
  syncing: "Syncing...",
  loadFailed: "Unable to load settings.",
  syncFailed: "Sync failed.",
  deviceId: (deviceId: string) => `Device ID: ${deviceId}`,
};

function isWebDavConfigError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error || "");
  return /webdav url is required/i.test(message);
}

function syncResultMessage(result: SyncResult): string {
  const deleted = result.deletedBookmarkCount + result.deletedWebAppCount;
  const tombstones = deleted > 0 ? `, ${deleted} tombstones` : "";
  const pending = result.pendingOperationCount > 0 ? `, ${result.pendingOperationCount} pending changes` : "";
  return `Synced ${result.bookmarkCount} bookmarks and ${result.webAppCount} WebApps${tombstones}${pending}`;
}

function isSyncResult(value: unknown): value is SyncResult {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<SyncResult>;
  return typeof candidate.bookmarkCount === "number" &&
    typeof candidate.webAppCount === "number" &&
    typeof candidate.deletedBookmarkCount === "number" &&
    typeof candidate.deletedWebAppCount === "number";
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
    deviceName: values.deviceName,
  });
}

function normalizeDialogValues(values: SyncSettingsDialogValues): SyncSettingsDialogValues {
  return {
    ...values,
    webDavUrl: values.webDavUrl.trim(),
    username: values.username.trim(),
    folderTitle: values.folderTitle?.trim() || "Hyper Browser",
    deviceName: values.deviceName.trim() || DEFAULT_DEVICE_NAME,
  };
}

function normalizeSettings(settings: SyncSettings): SyncSettings {
  return {
    ...settings,
    webDavUrl: settings.webDavUrl.trim(),
    username: settings.username.trim(),
    folderTitle: settings.folderTitle.trim() || "Hyper Browser",
    deviceName: settings.deviceName.trim() || DEFAULT_DEVICE_NAME,
  };
}

function openTab(url: string): void {
  browser.tabs.create({ url }).catch((error) => {
    console.warn("Unable to open tab.", error);
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
