import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "../styles.css";
import type { BookmarkRecord, SyncResult } from "../types";
import { sendCommand } from "./bridge";

type PopupAction = "webapp" | "bookmark" | null;

function Popup() {
  const [message, setMessage] = useState("");
  const [busyAction, setBusyAction] = useState<PopupAction>(null);
  const [bookmark, setBookmark] = useState<BookmarkRecord | null>(null);
  const [bookmarkStatusLoaded, setBookmarkStatusLoaded] = useState(false);

  useEffect(() => {
    refreshBookmarkStatus();
  }, []);

  function refreshBookmarkStatus() {
    setBookmarkStatusLoaded(false);
    sendCommand<BookmarkRecord | null>("bookmarks.getFromCurrentPage")
      .then((record) => setBookmark(record))
      .catch(() => setBookmark(null))
      .finally(() => setBookmarkStatusLoaded(true));
  }

  function addWebApp() {
    setBusyAction("webapp");
    setMessage("Adding WebApp...");
    sendCommand("webapps.addFromCurrentPage")
      .then(() => setMessage("WebApp added and synced."))
      .catch((error) => setMessage(error instanceof Error ? error.message : "Unable to add WebApp."))
      .finally(() => setBusyAction(null));
  }

  function addBookmark() {
    setBusyAction("bookmark");
    setMessage("Adding bookmark...");
    sendCommand<SyncResult>("bookmarks.addFromCurrentPage")
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
        <button className="button" type="button" disabled={!!busyAction} onClick={addWebApp}>
          {busyAction === "webapp" ? "Adding..." : "Add WebApp"}
        </button>
        <button className="button" type="button" disabled={!!busyAction || !bookmarkStatusLoaded} onClick={bookmark ? removeBookmark : addBookmark}>
          {busyAction === "bookmark" ? (bookmark ? "Removing..." : "Adding...") : bookmark ? "Remove bookmark" : "Add bookmark"}
        </button>
      </div>
      {message && <p className={message.toLowerCase().includes("failed") ? "error" : "message"}>{message}</p>}
    </main>
  );
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <Popup />
  </React.StrictMode>
);
