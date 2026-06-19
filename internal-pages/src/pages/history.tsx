import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "../hyper-browser";
import "../styles.css";
import { readBootstrapData } from "../bootstrap";
import { t } from "../i18n";
import type { HistoryItem } from "../hyper-browser";

function HistoryPage() {
  const [history, setHistory] = useState<HistoryItem[] | null>(() => readBootstrapData<HistoryItem>());
  const [failed, setFailed] = useState(false);
  const [query, setQuery] = useState("");
  const [confirmingClear, setConfirmingClear] = useState(false);

  useEffect(() => {
    if (history !== null) return;
    window.hyperBrowser.requestHistoryData()
      .then(setHistory)
      .catch(() => setFailed(true));
  }, [history]);

  function remove(url: string) {
    setHistory((items) => (items || []).filter((item) => item.url !== url));
    window.hyperBrowser.removeHistory(url);
  }

  function clear() {
    setHistory([]);
    setConfirmingClear(false);
    window.hyperBrowser.clearHistory();
  }

  const items = history || [];
  const visibleItems = filterHistory(items, query);

  return (
    <div className="page">
      <header className="chrome-header">
        <a className="back" href="hyper://home" aria-label={t("common.back")}>‹</a>
        <h1 className="chrome-title">{t("history.title")}</h1>
        {items.length > 0 && <button className="clear" type="button" onClick={() => setConfirmingClear(true)}>{t("history.clear")}</button>}
      </header>
      <main className="content">
        {failed ? (
          <div className="empty">{t("history.failed")}</div>
        ) : history === null ? (
          <div className="empty">{t("history.loading")}</div>
        ) : items.length === 0 ? (
          <div className="empty">{t("history.empty")}</div>
        ) : (
          <>
            <SearchBox
              label={t("history.searchLabel")}
              placeholder={t("history.searchPlaceholder")}
              value={query}
              onChange={setQuery}
            />
            {visibleItems.length === 0 ? (
              <div className="empty">{t("history.noMatches")}</div>
            ) : (
              <div className="list">
                {visibleItems.map((entry) => (
                  <div className="item" key={entry.url}>
                    <button
                      className="item-open"
                      type="button"
                      onClick={() => window.hyperBrowser.openHistory(entry.url)}
                    >
                      <span className="item-favicon">
                        {entry.iconDataUrl ? <img src={entry.iconDataUrl} alt="" /> : "◷"}
                      </span>
                      <span className="item-text">
                        <span className="item-title">{entry.title || entry.url}</span>
                        <span className="item-url">{entry.url}</span>
                        <span className="item-meta">{formatVisitTime(entry.visitedAt)}</span>
                      </span>
                    </button>
                    <button className="icon-button" type="button" aria-label={t("history.removeLabel")} onClick={() => remove(entry.url)}>
                      ×
                    </button>
                  </div>
                ))}
              </div>
            )}
          </>
        )}
      </main>
      {confirmingClear && (
        <div className="confirm-scrim" onClick={() => setConfirmingClear(false)}>
          <section
            className="confirm-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="clear-history-title"
            onClick={(event) => event.stopPropagation()}
          >
            <h2 id="clear-history-title">{t("history.clearConfirmTitle")}</h2>
            <p>{t("history.clearConfirmMessage")}</p>
            <div className="confirm-actions">
              <button type="button" onClick={() => setConfirmingClear(false)}>{t("common.cancel")}</button>
              <button className="danger" type="button" onClick={clear}>{t("history.clearConfirmAction")}</button>
            </div>
          </section>
        </div>
      )}
    </div>
  );
}

function SearchBox(props: {
  label: string;
  placeholder: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <div className="page-search">
      <span className="page-search-icon">⌕</span>
      <input
        type="search"
        aria-label={props.label}
        value={props.value}
        placeholder={props.placeholder}
        onChange={(event) => props.onChange(event.currentTarget.value)}
      />
      {props.value && (
        <button className="page-search-clear" type="button" aria-label={t("common.clear")} onClick={() => props.onChange("")}>
          ×
        </button>
      )}
    </div>
  );
}

function filterHistory(items: HistoryItem[], query: string): HistoryItem[] {
  const normalizedQuery = query.trim().toLocaleLowerCase();
  if (!normalizedQuery) return items;
  return items.filter((item) => (
    (item.title || "").toLocaleLowerCase().includes(normalizedQuery) ||
    item.url.toLocaleLowerCase().includes(normalizedQuery) ||
    formatVisitTime(item.visitedAt).toLocaleLowerCase().includes(normalizedQuery)
  ));
}

function formatVisitTime(timestamp?: string): string {
  if (!timestamp) return "";
  try {
    return new Date(timestamp).toLocaleString();
  } catch {
    return "";
  }
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <HistoryPage />
  </React.StrictMode>
);
