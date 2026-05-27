import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "../hyper-browser";
import "../styles.css";
import { readBootstrapData } from "../bootstrap";
import type { HistoryItem } from "../hyper-browser";

function HistoryPage() {
  const [history, setHistory] = useState<HistoryItem[] | null>(() => readBootstrapData<HistoryItem>());
  const [failed, setFailed] = useState(false);

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
          <div className="list">
            {items.map((entry) => (
              <div className="item" key={entry.url}>
                <button
                  className="item-open"
                  type="button"
                  onClick={() => window.hyperBrowser.openHistory(entry.url)}
                >
                  <div className="item-title">{entry.title || entry.url}</div>
                  <div className="item-url">{entry.url}</div>
                  <div className="item-meta">{formatVisitTime(entry.visitedAt)}</div>
                </button>
                <button className="icon-button" type="button" aria-label="Remove history entry" onClick={() => remove(entry.url)}>
                  ×
                </button>
              </div>
            ))}
          </div>
        )}
      </main>
    </div>
  );
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
