import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
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

  function save() {
    setBusy(true);
    saveSettings({
      ...settings,
      webDavUrl: settings.webDavUrl.trim(),
      username: settings.username.trim(),
      folderTitle: settings.folderTitle.trim() || "Hyper Browser",
      deviceName: settings.deviceName.trim() || "Chrome",
    })
      .then(() => setMessage("Settings saved."))
      .catch((saveError) => setError(saveError instanceof Error ? saveError.message : "Unable to save settings."))
      .finally(() => setBusy(false));
  }

  function sync() {
    setBusy(true);
    setMessage("Syncing...");
    saveSettings(settings)
      .then(() => sendCommand<SyncResult>("sync.run"))
      .then((result) => {
        setMessage(`Synced ${result.bookmarkCount} bookmarks in "${result.folderTitle}". Tombstones: ${result.deletedBookmarkCount}.`);
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
          <button className="button" type="button" onClick={() => chrome.tabs.create({ url: chrome.runtime.getURL("home.html") })}>
            Home
          </button>
          <button className="button" type="button" onClick={() => chrome.tabs.create({ url: chrome.runtime.getURL("webapps.html") })}>
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
          <button className="button" type="button" disabled={busy} onClick={save}>Save</button>
          <button className="button primary" type="button" disabled={busy || !settings.webDavUrl.trim()} onClick={sync}>
            {busy ? "Working..." : "Save and sync"}
          </button>
        </div>
        {settings.deviceId && <p className="message">Device ID: {settings.deviceId}</p>}
        {message && <p className="message">{message}</p>}
        {error && <p className="error">{error}</p>}
      </section>
    </main>
  );
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <OptionsPage />
  </React.StrictMode>
);
