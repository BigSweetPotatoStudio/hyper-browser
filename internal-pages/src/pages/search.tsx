import React, { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import "../hyper-browser";
import "../styles.css";
import { readHashParam } from "../bootstrap";
import { t } from "../i18n";
import type { SearchSuggestionItem } from "../hyper-browser";

function SearchPage() {
  const [query, setQuery] = useState(() => readHashParam("q") || "");
  const [suggestions, setSuggestions] = useState<SearchSuggestionItem[]>([]);
  const [failed, setFailed] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    window.hyperBrowser.requestSearchData()
      .then(setSuggestions)
      .catch(() => setFailed(true));
  }, []);

  useEffect(() => {
    window.setTimeout(() => {
      inputRef.current?.focus();
      inputRef.current?.select();
    }, 80);
  }, []);

  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return [];
    return suggestions
      .filter((item) => {
        const title = (item.title || "").toLowerCase();
        const url = item.url.toLowerCase();
        return title.includes(needle) || url.includes(needle);
      })
      .slice(0, 12);
  }, [query, suggestions]);

  function submit(event: FormEvent) {
    event.preventDefault();
    const value = query.trim();
    if (value) window.hyperBrowser.open(value);
  }

  return (
    <main className="omnibox-page">
      <form className="omnibox-bar" autoComplete="off" onSubmit={submit}>
        <button className="back" type="button" aria-label={t("common.back")} onClick={() => window.history.back()}>‹</button>
        <div className="omnibox-input-wrap">
          <input
            ref={inputRef}
            className="omnibox-input"
            name="q"
            type="search"
            inputMode="search"
            placeholder={t("search.placeholder")}
            value={query}
            onChange={(event) => setQuery(event.currentTarget.value)}
          />
          {query && (
            <button className="clear-query" type="button" aria-label={t("common.clear")} onClick={() => setQuery("")}>×</button>
          )}
        </div>
        <button className="go-button" type="submit">{t("search.go")}</button>
      </form>

      <section className="suggestion-section">
        <h2>{t("search.heading")}</h2>
        {failed ? (
          <div className="empty">{t("search.failed")}</div>
        ) : query.trim() === "" ? (
          <div className="empty">{t("search.emptyQuery")}</div>
        ) : matches.length === 0 ? (
          <div className="empty">{t("search.noMatches")}</div>
        ) : (
          <div className="suggestion-list">
            {matches.map((item) => (
              <button className="suggestion-row" type="button" key={`${item.source}:${item.url}`} onClick={() => window.hyperBrowser.open(item.url)}>
                <span className="suggestion-icon">{item.source === "bookmark" ? "★" : "◷"}</span>
                <span className="suggestion-text">
                  <span className="suggestion-title">{item.title || item.url}</span>
                  <span className="suggestion-url">{item.url}</span>
                </span>
              </button>
            ))}
          </div>
        )}
      </section>
    </main>
  );
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <SearchPage />
  </React.StrictMode>
);
