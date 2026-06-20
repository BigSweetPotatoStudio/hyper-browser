import React, { FormEvent, useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import "../hyper-browser";
import "../styles.css";
import { readBootstrapData } from "../bootstrap";
import { t } from "../i18n";
import type { BookmarkItem } from "../hyper-browser";

function BookmarksPage() {
  const [bookmarks, setBookmarks] = useState<BookmarkItem[] | null>(() => readBootstrapData<BookmarkItem>());
  const [editingUrl, setEditingUrl] = useState<string | null>(null);
  const [pendingRemove, setPendingRemove] = useState<BookmarkItem | null>(null);
  const [failed, setFailed] = useState(false);
  const [query, setQuery] = useState("");

  useEffect(() => {
    if (bookmarks !== null) return;
    loadBookmarks();
  }, [bookmarks]);

  function loadBookmarks() {
    setFailed(false);
    setBookmarks(null);
    window.hyperBrowser.requestBookmarksData()
      .then((items) => {
        setBookmarks(items);
        setFailed(false);
      })
      .catch(() => setFailed(true));
  }

  function remove(url: string) {
    setBookmarks((items) => (items || []).filter((item) => item.url !== url));
    setEditingUrl((current) => current === url ? null : current);
    setPendingRemove(null);
    window.hyperBrowser.removeBookmark(url);
  }

  function save(oldUrl: string, title: string, url: string) {
    const cleanUrl = url.trim();
    const cleanTitle = title.trim();
    if (!cleanUrl) return;
    setBookmarks((items) => (items || []).map((item) => (
      item.url === oldUrl ? { ...item, title: cleanTitle || cleanUrl, url: cleanUrl } : item
    )));
    setEditingUrl(null);
    window.hyperBrowser.editBookmark(oldUrl, cleanTitle, cleanUrl);
  }

  const items = bookmarks || [];
  const visibleItems = filterBookmarks(items, query);

  return (
    <div className="page">
      <header className="chrome-header">
        <a className="back" href="hyper://home" aria-label={t("common.back")}>‹</a>
        <h1 className="chrome-title">{t("bookmarks.title")}</h1>
      </header>
      <main className="content">
        {failed ? (
          <div className="empty">
            {t("bookmarks.failed")}{" "}
            <button className="go-button" type="button" onClick={loadBookmarks}>{t("bookmarks.retry")}</button>
          </div>
        ) : bookmarks === null ? (
          <div className="empty">{t("bookmarks.loading")}</div>
        ) : items.length === 0 ? (
          <div className="empty">{t("bookmarks.empty")}</div>
        ) : (
          <>
            <SearchBox
              label={t("bookmarks.searchLabel")}
              placeholder={t("bookmarks.searchPlaceholder")}
              value={query}
              onChange={setQuery}
            />
            {visibleItems.length === 0 ? (
              <div className="empty">{t("bookmarks.noMatches")}</div>
            ) : (
              <div className="list">
                {visibleItems.map((bookmark) => (
                  <BookmarkRow
                    bookmark={bookmark}
                    editing={editingUrl === bookmark.url}
                    key={bookmark.url}
                    onEdit={() => setEditingUrl(bookmark.url)}
                    onCancel={() => setEditingUrl(null)}
                    onOpen={() => window.hyperBrowser.openBookmark(bookmark.url)}
                    onRemove={() => setPendingRemove(bookmark)}
                    onSave={save}
                  />
                ))}
              </div>
            )}
          </>
        )}
      </main>
      {pendingRemove && (
        <div className="confirm-scrim" onClick={() => setPendingRemove(null)}>
          <section
            className="confirm-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="remove-bookmark-title"
            onClick={(event) => event.stopPropagation()}
          >
            <h2 id="remove-bookmark-title">{t("bookmarks.removeLabel")}</h2>
            <p>{pendingRemove.title || pendingRemove.url}</p>
            <div className="confirm-actions">
              <button type="button" onClick={() => setPendingRemove(null)}>{t("common.cancel")}</button>
              <button className="danger" type="button" onClick={() => remove(pendingRemove.url)}>{t("common.delete")}</button>
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

function filterBookmarks(items: BookmarkItem[], query: string): BookmarkItem[] {
  const normalizedQuery = query.trim().toLocaleLowerCase();
  if (!normalizedQuery) return items;
  return items.filter((item) => (
    (item.title || "").toLocaleLowerCase().includes(normalizedQuery) ||
    item.url.toLocaleLowerCase().includes(normalizedQuery)
  ));
}

function BookmarkRow(props: {
  bookmark: BookmarkItem;
  editing: boolean;
  onOpen: () => void;
  onEdit: () => void;
  onRemove: () => void;
  onCancel: () => void;
  onSave: (oldUrl: string, title: string, url: string) => void;
}) {
  const { bookmark } = props;
  const [title, setTitle] = useState(bookmark.title || "");
  const [url, setUrl] = useState(bookmark.url);
  const urlInputRef = useRef<HTMLInputElement>(null);

  function submit(event: FormEvent) {
    event.preventDefault();
    const cleanUrl = url.trim();
    if (!cleanUrl) {
      setUrl("");
      window.requestAnimationFrame(() => {
        urlInputRef.current?.focus();
        urlInputRef.current?.reportValidity();
      });
      return;
    }
    props.onSave(bookmark.url, title, cleanUrl);
  }

  return (
    <div className="item bookmark">
      <button className="item-open" type="button" onClick={props.onOpen}>
        <span className="item-favicon">
          {bookmark.iconDataUrl ? <img src={bookmark.iconDataUrl} alt="" /> : "★"}
        </span>
        <span className="item-text">
          <span className="item-title">{bookmark.title || bookmark.url}</span>
          <span className="item-url">{bookmark.url}</span>
        </span>
      </button>
      <button className="icon-button" type="button" aria-label={t("bookmarks.editLabel")} onClick={props.onEdit}>✎</button>
      <button className="icon-button" type="button" aria-label={t("bookmarks.removeLabel")} onClick={props.onRemove}>×</button>
      {props.editing && (
        <form className="editor" onSubmit={submit}>
          <input value={title} placeholder={t("bookmarks.titlePlaceholder")} onChange={(event) => setTitle(event.currentTarget.value)} />
          <input
            ref={urlInputRef}
            value={url}
            placeholder={t("common.url")}
            inputMode="url"
            required
            onChange={(event) => setUrl(event.currentTarget.value)}
          />
          <div className="editor-actions">
            <button className="cancel" type="button" onClick={props.onCancel}>{t("common.cancel")}</button>
            <button className="save" type="submit">{t("common.save")}</button>
          </div>
        </form>
      )}
    </div>
  );
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <BookmarksPage />
  </React.StrictMode>
);
