import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "../hyper-browser";
import "../styles.css";
import { readBootstrapData } from "../bootstrap";
import type { HistoryItem } from "../hyper-browser";

function HistoryPage() {
  const [history, setHistory] = useState<HistoryItem[] | null>(() => readBootstrapData<HistoryItem>());
  const [failed, setFailed] = useState(false);
  const [query, setQuery] = useState("");

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
    window.hyperBrowser.clearHistory();
  }

  const items = history || [];
  const visibleItems = filterHistory(items, query);

  return (
    <div className="page">
      <header className="chrome-header">
        <a className="back" href="hyper://home" aria-label="Back">‹</a>
        <h1 className="chrome-title">历史记录</h1>
        {items.length > 0 && <button className="clear" type="button" onClick={clear}>清空</button>}
      </header>
      <main className="content">
        {failed ? (
          <div className="empty">历史记录暂时不可用。</div>
        ) : history === null ? (
          <div className="empty">正在加载历史记录...</div>
        ) : items.length === 0 ? (
          <div className="empty">还没有历史记录。访问过的网页会显示在这里。</div>
        ) : (
          <>
            <SearchBox
              label="搜索历史记录"
              placeholder="搜索标题、网址或时间"
              value={query}
              onChange={setQuery}
            />
            {visibleItems.length === 0 ? (
              <div className="empty">没有匹配的历史记录。</div>
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
                    <button className="icon-button" type="button" aria-label="Remove history entry" onClick={() => remove(entry.url)}>
                      ×
                    </button>
                  </div>
                ))}
              </div>
            )}
          </>
        )}
      </main>
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
        <button className="page-search-clear" type="button" aria-label="清除搜索" onClick={() => props.onChange("")}>
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
