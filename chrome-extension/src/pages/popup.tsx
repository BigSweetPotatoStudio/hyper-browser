import React, { useState } from "react";
import { createRoot } from "react-dom/client";
import "../styles.css";
import type { SyncResult } from "../types";
import { sendCommand } from "./bridge";

function Popup() {
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);

  function sync() {
    setBusy(true);
    setMessage("Syncing...");
    sendCommand<SyncResult>("sync.run")
      .then((result) => {
        setMessage(`Synced ${result.bookmarkCount} bookmarks. Tombstones: ${result.deletedBookmarkCount}.`);
      })
      .catch((error) => setMessage(error instanceof Error ? error.message : "Sync failed."))
      .finally(() => setBusy(false));
  }

  return (
    <main className="popup">
      <h1 className="title">Hyper Browser Companion</h1>
      <p className="subtitle">Desktop launcher and WebDAV tools</p>
      <div className="actions">
        <button className="button primary" type="button" onClick={() => sendCommand("open.home")}>Home</button>
        <button className="button primary" type="button" disabled={busy} onClick={sync}>
          {busy ? "Syncing..." : "Sync now"}
        </button>
        <button className="button" type="button" onClick={() => sendCommand("open.options")}>Settings</button>
        <button className="button" type="button" onClick={() => sendCommand("open.webapps")}>WebApps</button>
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
