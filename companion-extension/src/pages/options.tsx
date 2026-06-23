import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { browser } from "wxt/browser";
import { getDefaultSettings, loadSettings, saveSettings } from "../storage";
import "../styles.css";
import type { SyncResult, SyncSettings } from "../types";
import { sendCommand } from "./bridge";

function OptionsPage() {
  const [settings, setSettings] = useState<SyncSettings>(getDefaultSettings());
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    loadSettings()
      .then(setSettings)
      .catch((loadError) => setError(loadError instanceof Error ? loadError.message : "Unable to load settings."));
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
    saveSettings(next)
      .then(() => sendCommand<SyncResult>("sync.run"))
      .then((result) => {
        setMessage(syncResultMessage(result));
        return loadSettings();
      })
      .then(setSettings)
      .catch((syncError) => {
        setMessage("");
        setError(syncError instanceof Error ? syncError.message : "Sync failed.");
      })
      .finally(() => setBusy(false));
  }

  return (
    <main className="shell">
      <header className="header">
        <div>
          <h1 className="title">Hyper Browser Companion</h1>
          <p className="subtitle">Sync one Chrome bookmark folder with Hyper Browser through WebDAV.</p>
        </div>
        <div className="actions compact">
          <button className="button" type="button" onClick={() => openExtensionPage("/home.html")}>
            Home
          </button>
          <button className="button" type="button" onClick={() => openExtensionPage("/webapps.html")}>
            WebApps
          </button>
        </div>
      </header>

      <section className="panel">
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
            <span className="label">Chrome folder title</span>
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
    </main>
  );
}

function syncResultMessage(result: SyncResult): string {
  const deleted = result.deletedBookmarkCount > 0 ? ` Tombstones: ${result.deletedBookmarkCount}.` : "";
  const layout = result.launcherLayout?.direction && result.launcherLayout.direction !== "none"
    ? ` Launcher layout ${result.launcherLayout.direction}.`
    : "";
  return `Synced ${result.bookmarkCount} bookmarks in "${result.folderTitle}".${deleted}${layout}`;
}

function normalizeSettings(settings: SyncSettings): SyncSettings {
  return {
    ...settings,
    webDavUrl: settings.webDavUrl.trim(),
    username: settings.username.trim(),
    folderTitle: settings.folderTitle.trim() || "Hyper Browser",
    deviceName: settings.deviceName.trim() || "Chrome",
  };
}

function openExtensionPage(path: "/home.html" | "/webapps.html"): void {
  browser.tabs.create({ url: browser.runtime.getURL(path) }).catch((error) => {
    console.warn("Unable to open extension page.", error);
  });
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <OptionsPage />
  </React.StrictMode>
);
