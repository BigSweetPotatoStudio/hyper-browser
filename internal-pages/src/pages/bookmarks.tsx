import React, { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import "../hyper-browser";
import "../styles.css";
import { readBootstrapData } from "../bootstrap";
import { t } from "../i18n";
import type { BookmarkItem } from "../hyper-browser";
import { sendBackgroundCommand } from "../background-command";

function BookmarksPage() {
  const [bookmarks, setBookmarks] = useState<BookmarkItem[] | null>(() => {
    const bootstrap = readBootstrapData<BookmarkItem>();
    return bootstrap ? normalizeBookmarkItems(bootstrap) : null;
  });
  const [editingId, setEditingId] = useState<string | null>(null);
  const [pendingRemove, setPendingRemove] = useState<BookmarkItem | null>(null);
  const [failed, setFailed] = useState(false);
  const [query, setQuery] = useState("");

  useEffect(() => {
    if (bookmarks !== null && bookmarks.length > 0) return;
    loadBookmarks();
  }, []);

  function loadBookmarks() {
    setFailed(false);
    sendBackgroundCommand<BookmarkItem[]>("bookmarks.list")
      .then((items) => {
        setBookmarks(normalizeBookmarkItems(items));
        setFailed(false);
      })
      .catch(() => setFailed(true));
  }

  function goBack() {
    window.location.href = "hyper://home";
  }

  function remove(bookmark: BookmarkItem) {
    setBookmarks((items) => (items || []).filter((item) => (
      item.url !== bookmark.url
    )));
    setEditingId((current) => current === bookmark.url ? null : current);
    setPendingRemove(null);
    sendBackgroundCommand<BookmarkItem[]>("bookmarks.delete", { url: bookmark.url })
      .then((items) => setBookmarks(normalizeBookmarkItems(items)))
      .catch((error) => {
        console.warn("Unable to remove bookmark.", error);
        loadBookmarks();
      });
  }

  function save(bookmark: BookmarkItem, title: string, url: string) {
    const cleanUrl = url.trim();
    const cleanTitle = title.trim();
    if (!cleanUrl) return;
    const payload = {
      oldUrl: bookmark.url,
      title: cleanTitle || cleanUrl,
      url: cleanUrl,
    };
    setBookmarks((items) => (items || []).map((item) => (
      item.url === bookmark.url
        ? {
          ...item,
          title: payload.title,
          url: payload.url,
        }
        : item
    )));
    setEditingId(null);
    sendBackgroundCommand<BookmarkItem[]>("bookmarks.save", payload)
      .then((items) => setBookmarks(normalizeBookmarkItems(items)))
      .catch((error) => {
        console.warn("Unable to save bookmark.", error);
        loadBookmarks();
      });
  }

  const items = bookmarks || [];
  const visibleItems = useMemo(() => {
    if (query.trim()) return sortBookmarksByAddedOrder(filterBookmarks(items, query));
    return sortBookmarksByAddedOrder(items);
  }, [items, query]);

  return (
    <div className="page">
      <header className="chrome-header">
        <button className="back" type="button" aria-label={t("common.back")} onClick={goBack}>‹</button>
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
              <div className="empty">{query.trim() ? t("bookmarks.noMatches") : t("bookmarks.empty")}</div>
            ) : (
              <div className="list">
                {visibleItems.map((bookmark) => (
                  <BookmarkRow
                    bookmark={bookmark}
                    editing={editingId === bookmark.url}
                    key={bookmark.url}
                    onEdit={() => setEditingId(bookmark.url)}
                    onCancel={() => setEditingId(null)}
                    onOpen={() => {
                      window.hyperBrowser.openBookmark(bookmark.url);
                    }}
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
              <button className="danger" type="button" onClick={() => remove(pendingRemove)}>{t("common.delete")}</button>
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

function normalizeBookmarkItems(items: BookmarkItem[]): BookmarkItem[] {
  return items
    .filter((item) => item.url)
    .map((item) => ({
      ...item,
      url: item.url || "",
    }));
}

function sortBookmarksByAddedOrder(items: BookmarkItem[]): BookmarkItem[] {
  return items
    .map((item, index) => ({ item, index }))
    .sort((left, right) => {
      const leftCreatedAt = Number(left.item.createdAt) || 0;
      const rightCreatedAt = Number(right.item.createdAt) || 0;
      if (leftCreatedAt && rightCreatedAt && leftCreatedAt !== rightCreatedAt) {
        return rightCreatedAt - leftCreatedAt;
      }
      if (leftCreatedAt && !rightCreatedAt) return -1;
      if (!leftCreatedAt && rightCreatedAt) return 1;
      return left.index - right.index;
    })
    .map(({ item }) => item);
}

function filterBookmarks(items: BookmarkItem[], query: string): BookmarkItem[] {
  const normalizedQuery = query.trim().toLocaleLowerCase();
  if (!normalizedQuery) return items;
  return items.filter((item) => (
    (item.title || "").toLocaleLowerCase().includes(normalizedQuery) ||
    (item.url || "").toLocaleLowerCase().includes(normalizedQuery)
  ));
}

function BookmarkRow(props: {
  bookmark: BookmarkItem;
  editing: boolean;
  onOpen: () => void;
  onEdit: () => void;
  onRemove: () => void;
  onCancel: () => void;
  onSave: (bookmark: BookmarkItem, title: string, url: string) => void;
}) {
  const { bookmark } = props;
  const [title, setTitle] = useState(bookmark.title || "");
  const [url, setUrl] = useState(bookmark.url || "");
  const urlInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    setTitle(bookmark.title || "");
    setUrl(bookmark.url || "");
  }, [bookmark.title, bookmark.url]);

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
    props.onSave(bookmark, title, cleanUrl);
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
