import React, { FormEvent, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "../hyper-browser";
import "../styles.css";
import { readBootstrapData } from "../bootstrap";
import { t } from "../i18n";
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
      <form className="search-form" autoComplete="off" onSubmit={submit}>
        <span className="search-mark" aria-hidden="true">⌕</span>
        <input
          className="search-input"
          name="q"
          type="search"
          inputMode="search"
          aria-label={t("search.placeholder")}
          placeholder={t("search.placeholder")}
          value={query}
          onChange={(event) => setQuery(event.currentTarget.value)}
        />
      </form>
      <nav className="shortcut-grid" aria-label={t("home.shortcutsLabel")}>
        <a className="shortcut" href="hyper://apps">{t("home.apps")}</a>
        <a className="shortcut" href="hyper://bookmarks">{t("home.bookmarks")}</a>
        <a className="shortcut" href="hyper://history">{t("home.history")}</a>
        <button className="shortcut" type="button" onClick={() => window.hyperBrowser.showExtensions()}>
          {t("home.extensions")}
        </button>
      </nav>
      <p className="section-title">{t("home.recent")}</p>
      {failed ? (
        <div className="empty">{t("home.recentFailed")}</div>
      ) : recent === null ? (
        <div className="empty">{t("home.recentLoading")}</div>
      ) : items.length === 0 ? (
        <div className="empty">{t("home.recentEmpty")}</div>
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
