import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { browser } from "wxt/browser";
import "../styles.css";
import type { BookmarkRecord, WebAppRecord } from "../types";
import { sendCommand } from "./bridge";

type PopupAction = "webapp" | "bookmark-add" | "bookmark-remove" | null;

type CurrentHttpPage = {
  title: string;
  url: string;
};

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
    getCurrentHttpPage()
      .then((page) => sendCommand<BookmarkRecord | null>("bookmarks.getByUrl", { url: page.url }))
      .then((record) => setBookmark(record))
      .catch(() => setBookmark(null))
      .finally(() => setBookmarkStatusLoaded(true));
  }

  function refreshWebAppStatus() {
    setWebAppStatusLoaded(false);
    getCurrentHttpPage()
      .then((page) => sendCommand<WebAppRecord[]>("webapps.getByUrl", { url: page.url }))
      .then((records) => setWebAppsForUrl(records))
      .catch(() => setWebAppsForUrl([]))
      .finally(() => setWebAppStatusLoaded(true));
  }

  function addWebApp() {
    setBusyAction("webapp");
    setMessage("Adding WebApp...");
    const id = crypto.randomUUID();
    getCurrentHttpPage()
      .then(() => sendCommand("webapps.save", { id }))
      .then(() => sendCommand("launcher.layout.addWebApp", { id }))
      .then(() => {
        setMessage("WebApp added locally. Syncing in background.");
        refreshWebAppStatus();
      })
      .catch((error: unknown) => setMessage(error instanceof Error ? error.message : "Unable to add WebApp."))
      .finally(() => setBusyAction(null));
  }

  function addBookmark() {
    const previousBookmark = bookmark;
    setBusyAction("bookmark-add");
    setMessage("Adding bookmark...");
    getCurrentHttpPage()
      .then((page) => {
        const now = Date.now();
        const optimisticBookmark = {
          title: page.title || page.url,
          url: page.url,
          createdAt: now,
          updatedAt: now,
        };
        setBookmark(optimisticBookmark);
        setMessage("Bookmark added locally. Syncing in background.");
        return sendCommand<BookmarkRecord[]>("bookmarks.save", {
          title: optimisticBookmark.title,
          url: optimisticBookmark.url,
        }).then((items) => {
          setBookmark(findBookmarkByUrl(items, optimisticBookmark.url) || optimisticBookmark);
          setMessage("Bookmark added locally. Syncing in background.");
        });
      })
      .catch((error: unknown) => {
        setBookmark(previousBookmark);
        setMessage(error instanceof Error ? error.message : "Unable to add bookmark.");
      })
      .finally(() => setBusyAction(null));
  }

  function removeBookmark() {
    if (!bookmark) return;
    const removedBookmark = bookmark;
    setBusyAction("bookmark-remove");
    setMessage("Removing bookmark...");
    setBookmark(null);
    setMessage("Bookmark removed locally. Syncing in background.");
    sendCommand<BookmarkRecord[]>("bookmarks.delete", { url: removedBookmark.url })
      .then((items) => {
        setBookmark(findBookmarkByUrl(items, removedBookmark.url));
        setMessage("Bookmark removed locally. Syncing in background.");
      })
      .catch((error: unknown) => {
        setBookmark(removedBookmark);
        setMessage(error instanceof Error ? error.message : "Unable to remove bookmark.");
      })
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
          {busyAction === "bookmark-add" ? "Adding..." : busyAction === "bookmark-remove" ? "Removing..." : bookmark ? "Remove bookmark" : "Add bookmark"}
        </button>
      </div>
      {message && <p className={message.toLowerCase().includes("failed") ? "error" : "message"}>{message}</p>}
    </main>
  );
}

async function getCurrentHttpPage(): Promise<CurrentHttpPage> {
  const tabs = await browser.tabs.query({ active: true, lastFocusedWindow: true });
  const url = typeof tabs[0]?.url === "string" ? tabs[0].url.trim() : "";
  if (!/^https?:\/\//i.test(url)) throw new Error("Current tab must be an http:// or https:// page.");
  return {
    title: tabs[0]?.title?.trim() || hostLabel(url),
    url,
  };
}

function findBookmarkByUrl(items: BookmarkRecord[], url: string): BookmarkRecord | null {
  const normalizedUrl = normalizeUrl(url);
  return items.find((item) => normalizeUrl(item.url) === normalizedUrl) || null;
}

function normalizeUrl(url: string): string {
  try {
    return new URL(url.trim()).toString();
  } catch {
    return url.trim();
  }
}

function hostLabel(url: string): string {
  try {
    return new URL(url).hostname || url;
  } catch {
    return url;
  }
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <Popup />
  </React.StrictMode>
);
