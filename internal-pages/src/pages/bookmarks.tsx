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
  const [currentFolderId, setCurrentFolderId] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [query, setQuery] = useState("");

  useEffect(() => {
    if (bookmarks !== null && bookmarks.length > 0) return;
    loadBookmarks();
  }, []);

  useEffect(() => {
    if (!currentFolderId || !bookmarks) return;
    if (!bookmarks.some((bookmark) => bookmark.id === currentFolderId && bookmark.kind === "folder")) {
      setCurrentFolderId(null);
    }
  }, [bookmarks, currentFolderId]);

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
    if (!currentFolderId || !bookmarks) {
      window.location.href = "hyper://home";
      return;
    }
    const current = bookmarks.find((bookmark) => bookmark.id === currentFolderId);
    setCurrentFolderId(current?.parentId || null);
  }

  function remove(bookmark: BookmarkItem) {
    const id = bookmark.id || "";
    const removeIds = id ? collectBookmarkDescendantIds(bookmarks || [], id) : new Set<string>();
    setBookmarks((items) => (items || []).filter((item) => (
      id ? !removeIds.has(item.id || "") : item.url !== bookmark.url
    )));
    setEditingId((current) => current === id ? null : current);
    setPendingRemove(null);
    sendBackgroundCommand<BookmarkItem[]>("bookmarks.delete", { ...(id ? { id } : { url: bookmark.url }) })
      .then((items) => setBookmarks(normalizeBookmarkItems(items)))
      .catch((error) => {
        console.warn("Unable to remove bookmark.", error);
        loadBookmarks();
      });
  }

  function save(bookmark: BookmarkItem, title: string, url: string) {
    const kind: "bookmark" | "folder" = bookmark.kind === "folder" ? "folder" : "bookmark";
    const cleanUrl = kind === "folder" ? "" : url.trim();
    const cleanTitle = title.trim();
    if (kind === "bookmark" && !cleanUrl) return;
    const payload = {
      id: bookmark.id,
      oldUrl: bookmark.url,
      title: cleanTitle || (kind === "folder" ? bookmark.title || t("bookmarks.folderLabel") : cleanUrl),
      url: cleanUrl,
      kind,
      parentId: bookmark.parentId || "",
      index: bookmark.index,
    };
    setBookmarks((items) => (items || []).map((item) => (
      item.id === bookmark.id
        ? {
          ...item,
          title: payload.title,
          url: payload.url,
          kind,
          parentId: bookmark.parentId || null,
          index: bookmark.index,
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
  const currentFolder = currentFolderId ? items.find((bookmark) => bookmark.id === currentFolderId) : null;
  const visibleItems = useMemo(() => {
    if (query.trim()) return sortBookmarks(filterBookmarks(items, query));
    return sortBookmarks(items.filter((item) => (item.parentId || null) === (currentFolderId || null)));
  }, [items, query, currentFolderId]);

  return (
    <div className="page">
      <header className="chrome-header">
        <button className="back" type="button" aria-label={t("common.back")} onClick={goBack}>‹</button>
        <h1 className="chrome-title">{currentFolder?.title || t("bookmarks.title")}</h1>
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
              <div className="empty">{query.trim() ? t("bookmarks.noMatches") : t("bookmarks.folderEmpty")}</div>
            ) : (
              <div className="list">
                {visibleItems.map((bookmark) => (
                  <BookmarkRow
                    bookmark={bookmark}
                    editing={editingId === bookmark.id}
                    key={bookmark.id || bookmark.url}
                    onEdit={() => setEditingId(bookmark.id || bookmark.url)}
                    onCancel={() => setEditingId(null)}
                    onOpen={() => {
                      if (bookmark.kind === "folder") {
                        setCurrentFolderId(bookmark.id || null);
                      } else {
                        window.hyperBrowser.openBookmark(bookmark.url);
                      }
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
            <p>{pendingRemove.title || pendingRemove.url || t("bookmarks.folderLabel")}</p>
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
  return items.map((item) => ({
    ...item,
    id: item.id || item.url,
    kind: item.kind === "folder" ? "folder" : "bookmark",
    parentId: item.parentId || null,
    index: typeof item.index === "number" && Number.isFinite(item.index) ? item.index : undefined,
    url: item.url || "",
  }));
}

function sortBookmarks(items: BookmarkItem[]): BookmarkItem[] {
  return [...items].sort((left, right) => (
    (left.kind === right.kind ? 0 : left.kind === "folder" ? -1 : 1) ||
    (left.index ?? 0) - (right.index ?? 0) ||
    (left.title || left.url).localeCompare(right.title || right.url) ||
    (left.id || left.url).localeCompare(right.id || right.url)
  ));
}

function filterBookmarks(items: BookmarkItem[], query: string): BookmarkItem[] {
  const normalizedQuery = query.trim().toLocaleLowerCase();
  if (!normalizedQuery) return items;
  return items.filter((item) => (
    (item.title || "").toLocaleLowerCase().includes(normalizedQuery) ||
    (item.url || "").toLocaleLowerCase().includes(normalizedQuery)
  ));
}

function collectBookmarkDescendantIds(items: BookmarkItem[], id: string): Set<string> {
  const result = new Set([id]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const item of items) {
      if (item.id && item.parentId && result.has(item.parentId) && !result.has(item.id)) {
        result.add(item.id);
        changed = true;
      }
    }
  }
  return result;
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
  const isFolder = bookmark.kind === "folder";
  const [title, setTitle] = useState(bookmark.title || "");
  const [url, setUrl] = useState(bookmark.url || "");
  const urlInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    setTitle(bookmark.title || "");
    setUrl(bookmark.url || "");
  }, [bookmark.id, bookmark.title, bookmark.url]);

  function submit(event: FormEvent) {
    event.preventDefault();
    const cleanUrl = url.trim();
    if (!isFolder && !cleanUrl) {
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
    <div className={`item bookmark ${isFolder ? "folder" : ""}`}>
      <button className="item-open" type="button" onClick={props.onOpen}>
        <span className="item-favicon">
          {isFolder ? "▣" : bookmark.iconDataUrl ? <img src={bookmark.iconDataUrl} alt="" /> : "★"}
        </span>
        <span className="item-text">
          <span className="item-title">{bookmark.title || bookmark.url || t("bookmarks.folderLabel")}</span>
          <span className="item-url">{isFolder ? t("bookmarks.folderLabel") : bookmark.url}</span>
        </span>
      </button>
      <button className="icon-button" type="button" aria-label={t("bookmarks.editLabel")} onClick={props.onEdit}>✎</button>
      <button className="icon-button" type="button" aria-label={t("bookmarks.removeLabel")} onClick={props.onRemove}>×</button>
      {props.editing && (
        <form className="editor" onSubmit={submit}>
          <input value={title} placeholder={t("bookmarks.titlePlaceholder")} onChange={(event) => setTitle(event.currentTarget.value)} />
          {!isFolder && (
            <input
              ref={urlInputRef}
              value={url}
              placeholder={t("common.url")}
              inputMode="url"
              required
              onChange={(event) => setUrl(event.currentTarget.value)}
            />
          )}
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
