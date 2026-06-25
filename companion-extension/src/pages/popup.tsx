import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { browser } from "wxt/browser";
import "../styles.css";
import type { BookmarkRecord, SyncResult, WebAppRecord } from "../types";
import { sendCommand } from "./bridge";

type PopupAction = "webapp" | "bookmark" | null;

function Popup() {
  const [message, setMessage] = useState("");
  const [busyAction, setBusyAction] = useState<PopupAction>(null);
  const [bookmark, setBookmark] = useState<BookmarkRecord | null>(null);
  const [bookmarkStatusLoaded, setBookmarkStatusLoaded] = useState(false);
  const [webAppsForUrl, setWebAppsForUrl] = useState<WebAppRecord[]>([]);
  const [webAppStatusLoaded, setWebAppStatusLoaded] = useState(false);

  useEffect(() => {
    refreshBookmarkStatus();
    refreshWebAppStatus();
  }, []);

  function refreshBookmarkStatus() {
    setBookmarkStatusLoaded(false);
    getCurrentHttpUrl()
      .then((url) => sendCommand<BookmarkRecord | null>("bookmarks.getByUrl", { url }))
      .then((record) => setBookmark(record))
      .catch(() => setBookmark(null))
      .finally(() => setBookmarkStatusLoaded(true));
  }

  function refreshWebAppStatus() {
    setWebAppStatusLoaded(false);
    getCurrentHttpUrl()
      .then((url) => sendCommand<WebAppRecord[]>("webapps.getByUrl", { url }))
      .then((records) => setWebAppsForUrl(records))
      .catch(() => setWebAppsForUrl([]))
      .finally(() => setWebAppStatusLoaded(true));
  }

  function addWebApp() {
    setBusyAction("webapp");
    setMessage("Adding WebApp...");
    const id = crypto.randomUUID();
    getCurrentHttpUrl()
      .then(() => sendCommand("webapps.save", { id }))
      .then(() => sendCommand("launcher.layout.addWebApp", { id }))
      .then(() => {
        setMessage("WebApp added and synced.");
        refreshWebAppStatus();
      })
      .catch((error) => setMessage(error instanceof Error ? error.message : "Unable to add WebApp."))
      .finally(() => setBusyAction(null));
  }

  function addBookmark() {
    setBusyAction("bookmark");
    setMessage("Adding bookmark...");
    sendCommand<SyncResult>("bookmarks.save")
      .then((result) => {
        setMessage(`Bookmark added and synced ${result.bookmarkCount} bookmarks.`);
        refreshBookmarkStatus();
      })
      .catch((error) => setMessage(error instanceof Error ? error.message : "Unable to add bookmark."))
      .finally(() => setBusyAction(null));
  }

  function removeBookmark() {
    if (!bookmark) return;
    setBusyAction("bookmark");
    setMessage("Removing bookmark...");
    sendCommand<SyncResult>("bookmarks.delete", { url: bookmark.url })
      .then((result) => {
        setBookmark(null);
        setMessage(`Bookmark removed and synced ${result.bookmarkCount} bookmarks.`);
      })
      .catch((error) => setMessage(error instanceof Error ? error.message : "Unable to remove bookmark."))
      .finally(() => setBusyAction(null));
  }

  return (
    <main className="popup">
      <h1 className="title">Hyper Browser Companion</h1>
      <p className="subtitle">Desktop launcher and WebDAV tools</p>
      <div className="popup-actions">
        <button className="button primary" type="button" onClick={() => sendCommand("open.home")}>Home</button>
        <button className="button" type="button" disabled={!!busyAction || !webAppStatusLoaded} onClick={addWebApp}>
          {busyAction === "webapp" ? "Adding..." : webAppsForUrl.length > 0 ? "Add another WebApp" : "Add WebApp"}
        </button>
        <button className="button" type="button" disabled={!!busyAction || !bookmarkStatusLoaded} onClick={bookmark ? removeBookmark : addBookmark}>
          {busyAction === "bookmark" ? (bookmark ? "Removing..." : "Adding...") : bookmark ? "Remove bookmark" : "Add bookmark"}
        </button>
      </div>
      {message && <p className={message.toLowerCase().includes("failed") ? "error" : "message"}>{message}</p>}
    </main>
  );
}

async function getCurrentHttpUrl(): Promise<string> {
  const tabs = await browser.tabs.query({ active: true, lastFocusedWindow: true });
  const url = typeof tabs[0]?.url === "string" ? tabs[0].url.trim() : "";
  if (!/^https?:\/\//i.test(url)) throw new Error("Current tab must be an http:// or https:// page.");
  return url;
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <Popup />
  </React.StrictMode>
);
