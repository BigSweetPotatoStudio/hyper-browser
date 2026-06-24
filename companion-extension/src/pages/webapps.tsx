import React, { FormEvent, useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import "../styles.css";
import type { WebAppRecord } from "../types";
import { sendCommand } from "./bridge";

type Draft = {
  id?: string;
  name: string;
  startUrl: string;
  scopeUrl: string;
  displayMode: string;
  themeColor: string;
};

const emptyDraft: Draft = {
  name: "",
  startUrl: "",
  scopeUrl: "",
  displayMode: "standalone",
  themeColor: "#126d6a",
};

function WebAppsPage() {
  const [items, setItems] = useState<WebAppRecord[]>([]);
  const [draft, setDraft] = useState<Draft>(emptyDraft);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    refresh();
  }, []);

  const sortedItems = useMemo(() => [...items].sort((a, b) => b.updatedAt - a.updatedAt), [items]);

  function refresh() {
    setBusy(true);
    setError("");
    sendCommand<WebAppRecord[]>("webapps.list")
      .then(setItems)
      .catch((loadError) => setError(loadError instanceof Error ? loadError.message : "Unable to load WebApps."))
      .finally(() => setBusy(false));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    sendCommand<WebAppRecord[]>("webapps.save", {
      id: draft.id,
      name: draft.name,
      startUrl: draft.startUrl,
      scopeUrl: draft.scopeUrl,
      displayMode: draft.displayMode,
      themeColor: colorToInt(draft.themeColor),
    })
      .then((next) => {
        setItems(next);
        setDraft(emptyDraft);
        setMessage("WebApp saved.");
      })
      .catch((saveError) => {
        setMessage("");
        setError(saveError instanceof Error ? saveError.message : "Unable to save WebApp.");
      })
      .finally(() => setBusy(false));
  }

  function edit(item: WebAppRecord) {
    setDraft({
      id: item.id,
      name: item.name,
      startUrl: item.startUrl,
      scopeUrl: item.scopeUrl,
      displayMode: item.displayMode || "standalone",
      themeColor: intToColor(item.themeColor),
    });
    setMessage("");
    setError("");
  }

  function remove(item: WebAppRecord) {
    setBusy(true);
    setError("");
    sendCommand<WebAppRecord[]>("webapps.delete", { id: item.id })
      .then((next) => {
        setItems(next);
        setMessage("WebApp deleted. A tombstone was kept for offline devices.");
      })
      .catch((deleteError) => {
        setMessage("");
        setError(deleteError instanceof Error ? deleteError.message : "Unable to delete WebApp.");
      })
      .finally(() => setBusy(false));
  }

  return (
    <main className="shell">
      <header className="header">
        <div>
          <h1 className="title">Hyper Browser WebApps</h1>
          <p className="subtitle">Manage WebApps synced through WebDAV v2 operations.</p>
        </div>
        <button className="button" type="button" onClick={refresh} disabled={busy}>Refresh</button>
      </header>

      <form className="panel" onSubmit={submit}>
        <div className="grid">
          <label className="field">
            <span className="label">Name</span>
            <input className="input" value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.currentTarget.value })} />
          </label>
          <label className="field">
            <span className="label">Display mode</span>
            <select className="select" value={draft.displayMode} onChange={(event) => setDraft({ ...draft, displayMode: event.currentTarget.value })}>
              <option value="standalone">Standalone</option>
              <option value="browser">Browser</option>
              <option value="fullscreen">Fullscreen</option>
            </select>
          </label>
          <label className="field full">
            <span className="label">Start URL</span>
            <input className="input" type="url" required value={draft.startUrl} onChange={(event) => setDraft({ ...draft, startUrl: event.currentTarget.value })} />
          </label>
          <label className="field">
            <span className="label">Scope URL</span>
            <input className="input" type="url" value={draft.scopeUrl} onChange={(event) => setDraft({ ...draft, scopeUrl: event.currentTarget.value })} />
          </label>
          <label className="field">
            <span className="label">Theme color</span>
            <input className="input" type="color" value={draft.themeColor} onChange={(event) => setDraft({ ...draft, themeColor: event.currentTarget.value })} />
          </label>
        </div>
        <div className="actions">
          <button className="button primary" type="submit" disabled={busy}>{draft.id ? "Save WebApp" : "Add WebApp"}</button>
          {draft.id && <button className="button" type="button" disabled={busy} onClick={() => setDraft(emptyDraft)}>Cancel edit</button>}
        </div>
        {message && <p className="message">{message}</p>}
        {error && <p className="error">{error}</p>}
      </form>

      <section className="list">
        {sortedItems.length === 0 ? (
          <div className="empty">No WebApps yet.</div>
        ) : sortedItems.map((item) => (
          <article className="item" key={item.id}>
            <div>
              <span className="item-title">{item.name}</span>
              <span className="item-url">{item.startUrl}</span>
            </div>
            <div className="actions">
              <button className="button" type="button" onClick={() => edit(item)}>Edit</button>
              <button className="button danger" type="button" onClick={() => remove(item)}>Delete</button>
            </div>
          </article>
        ))}
      </section>
    </main>
  );
}

function colorToInt(value: string): number {
  const rgb = Number.parseInt(value.replace("#", ""), 16);
  return (0xff000000 | rgb);
}

function intToColor(value: number): string {
  return `#${(value & 0xffffff).toString(16).padStart(6, "0")}`;
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <WebAppsPage />
  </React.StrictMode>
);
