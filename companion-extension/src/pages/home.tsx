import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { browser } from "wxt/browser";
import { LauncherPage, LauncherSyncActions, type LauncherPlatform, type LauncherSyncState, type LauncherSystemEntry } from "@hyper-launcher";
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
const REMOTE_POLL_MS = 30000;
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
  const remoteCheckRunning = useRef(false);
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
      if (result.launcherLayout?.changed || options.refreshLauncher) setLayoutRevision((current) => current + 1);
      setSyncState("success");
      setSyncMessage(syncResultMessage(result));
    } catch (syncError) {
      const text = syncError instanceof Error ? syncError.message : "Sync failed.";
      setSyncState(isWebDavConfigError(syncError) ? "needs-settings" : "error");
      setSyncMessage(text);
      if (isWebDavConfigError(syncError)) setSettingsOpen(true);
    } finally {
      syncRunning.current = false;
    }
  }, []);

  const checkRemoteChanges = useCallback(async () => {
    if (remoteCheckRunning.current || document.visibilityState !== "visible") return;
    remoteCheckRunning.current = true;
    try {
      if (syncRunning.current) return;
      const result = await sendCommand<{ changed: boolean; synced: boolean; updatedAt: number }>("remote.check");
      if (result.changed || result.synced) setLayoutRevision((current) => current + 1);
    } catch (error) {
      console.warn("Remote sync check failed.", error);
    } finally {
      remoteCheckRunning.current = false;
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

  useEffect(() => {
    const onMessage = (message: { type?: string }) => {
      if (message?.type === "remote.synced" || message?.type === "launcher.changed") {
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
        onLayoutChanged={() => {
          sendCommand("launcher.syncSoon").catch(() => undefined);
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
        <SettingsDialog
          onSettingsSaved={(settings) => setSettingsConfigured(!!settings.webDavUrl.trim())}
          onSyncError={(message) => {
            setSyncState(isWebDavConfigError(message) ? "needs-settings" : "error");
            setSyncMessage(message);
          }}
          onSyncResult={(result) => {
            setSyncState("success");
            setSyncMessage(syncResultMessage(result));
            if (result.launcherLayout?.changed) setLayoutRevision((current) => current + 1);
          }}
          onClose={() => setSettingsOpen(false)}
        />
      )}
    </>
  );
}

function SettingsDialog(props: {
  onClose: () => void;
  onSettingsSaved: (settings: SyncSettings) => void;
  onSyncError: (message: string) => void;
  onSyncResult: (result: SyncResult) => void;
}) {
  const [settings, setSettings] = useState<SyncSettings>(getDefaultSettings());
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    loadSettings()
      .then((loaded) => {
        if (!cancelled) setSettings(loaded);
      })
      .catch((loadError) => {
        if (!cancelled) setError(loadError instanceof Error ? loadError.message : "Unable to load settings.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  function update<K extends keyof SyncSettings>(key: K, value: SyncSettings[K]) {
    setSettings((current) => ({ ...current, [key]: value }));
    setMessage("");
    setError("");
  }

  function sync() {
    const next = normalizeSettings(settings);
    setBusy(true);
    setMessage("Syncing...");
    setError("");
    saveSettings(next)
      .then(() => sendCommand<SyncResult>("sync.run"))
      .then((result) => {
        setMessage(syncResultMessage(result));
        props.onSyncResult(result);
        return loadSettings();
      })
      .then((loaded) => {
        setSettings(loaded);
        props.onSettingsSaved(loaded);
      })
      .catch((syncError) => {
        const text = syncError instanceof Error ? syncError.message : "Sync failed.";
        setMessage("");
        setError(text);
        props.onSyncError(text);
      })
      .finally(() => setBusy(false));
  }

  return (
    <div className="settings-scrim" onClick={props.onClose}>
      <section className="settings-dialog" role="dialog" aria-modal="true" aria-label="Settings" onClick={(event) => event.stopPropagation()}>
        <header className="settings-dialog-header">
          <h2>Settings</h2>
          <button type="button" onClick={props.onClose}>Close</button>
        </header>
        <div className="grid">
          <label className="field full">
            <span className="label">WebDAV address</span>
            <input className="input" type="url" placeholder="https://example.com/dav" value={settings.webDavUrl} onChange={(event) => update("webDavUrl", event.currentTarget.value)} />
          </label>
          <label className="field">
            <span className="label">Username</span>
            <input className="input" type="text" autoComplete="username" value={settings.username} onChange={(event) => update("username", event.currentTarget.value)} />
          </label>
          <label className="field">
            <span className="label">Password or app token</span>
            <input className="input" type="password" autoComplete="current-password" value={settings.password} onChange={(event) => update("password", event.currentTarget.value)} />
          </label>
          <label className="field">
            <span className="label">Sync folder title</span>
            <input className="input" type="text" value={settings.folderTitle} onChange={(event) => update("folderTitle", event.currentTarget.value)} />
          </label>
          <label className="field">
            <span className="label">Device name</span>
            <input className="input" type="text" value={settings.deviceName} onChange={(event) => update("deviceName", event.currentTarget.value)} />
          </label>
        </div>
        <p className="message">Remote files are stored under HyperBrowserSync/bookmarks.json, webapps.json, launcher.json, manifest.json, and devices/.</p>
        <div className="actions">
          <button className="button primary" type="button" disabled={busy || !settings.webDavUrl.trim()} onClick={sync}>
            {busy ? "Syncing..." : "Sync"}
          </button>
        </div>
        {settings.deviceId && <p className="message">Device ID: {settings.deviceId}</p>}
        {message && <p className="message">{message}</p>}
        {error && <p className="error">{error}</p>}
      </section>
    </div>
  );
}

function isWebDavConfigError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error || "");
  return /webdav url is required/i.test(message);
}

function syncResultMessage(result: SyncResult): string {
  const deleted = result.deletedBookmarkCount > 0 ? `, ${result.deletedBookmarkCount} deleted` : "";
  const layout = result.launcherLayout?.direction && result.launcherLayout.direction !== "none"
    ? `, launcher ${result.launcherLayout.direction}`
    : "";
  return `Synced ${result.bookmarkCount} bookmarks${deleted}${layout}`;
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
