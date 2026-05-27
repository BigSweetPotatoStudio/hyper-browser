import React, { FormEvent, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "../hyper-browser";
import "../styles.css";
import { readBootstrapData } from "../bootstrap";
import type { HistoryItem } from "../hyper-browser";

function HomePage() {
  const [recent, setRecent] = useState<HistoryItem[] | null>(() => readBootstrapData<HistoryItem>());
  const [failed, setFailed] = useState(false);
  const [query, setQuery] = useState("");

  useEffect(() => {
    if (recent !== null) return;
    window.hyperBrowser.requestHomeData()
      .then(setRecent)
      .catch(() => setFailed(true));
  }, [recent]);

  function submit(event: FormEvent) {
    event.preventDefault();
    const value = query.trim();
    if (value) window.hyperBrowser.open(value);
  }

  const items = (recent || []).slice(0, 10);

  return (
    <main className="home-main">
      <h1 className="home-title">Hyper Browser</h1>
      <nav className="shortcut-grid" aria-label="Browser shortcuts">
        <a className="shortcut" href="hyper://apps">Apps</a>
        <a className="shortcut" href="hyper://bookmarks">Bookmarks</a>
        <a className="shortcut" href="hyper://history">History</a>
        <button className="shortcut" type="button" onClick={() => window.hyperBrowser.showExtensions()}>
          Extensions
        </button>
      </nav>
      <p className="section-title">Recent</p>
      {failed ? (
        <div className="empty">最近访问记录暂时不可用。</div>
      ) : recent === null ? (
        <div className="empty">正在加载最近访问记录...</div>
      ) : items.length === 0 ? (
        <div className="empty">最近访问记录在历史页中管理。</div>
      ) : (
        <div className="recent-list">
          {items.map((entry) => (
            <a
              className="recent-item"
              href="#"
              key={entry.url}
              onClick={(event) => {
                event.preventDefault();
                window.hyperBrowser.openHistory(entry.url);
              }}
            >
              <span className="recent-favicon">
                {entry.iconDataUrl ? <img src={entry.iconDataUrl} alt="" /> : "•"}
              </span>
              <span className="recent-text">
                <span className="recent-title">{entry.title || entry.url}</span>
                <span className="recent-url">{entry.url}</span>
              </span>
            </a>
          ))}
        </div>
      )}
    </main>
  );
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <HomePage />
  </React.StrictMode>
);
