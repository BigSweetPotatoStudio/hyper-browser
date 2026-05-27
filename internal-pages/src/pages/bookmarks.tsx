import React, { FormEvent, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "../hyper-browser";
import "../styles.css";
import { readBootstrapData } from "../bootstrap";
import type { BookmarkItem } from "../hyper-browser";

function BookmarksPage() {
  const [bookmarks, setBookmarks] = useState<BookmarkItem[] | null>(() => readBootstrapData<BookmarkItem>());
  const [editingUrl, setEditingUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (bookmarks !== null) return;
    window.hyperBrowser.requestBookmarksData()
      .then(setBookmarks)
      .catch(() => setFailed(true));
  }, [bookmarks]);

  function remove(url: string) {
    setBookmarks((items) => (items || []).filter((item) => item.url !== url));
    setEditingUrl((current) => current === url ? null : current);
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

  return (
    <div className="page">
      <header className="chrome-header">
        <a className="back" href="hyper://home" aria-label="Back">‹</a>
        <h1 className="chrome-title">书签</h1>
      </header>
      <main className="content">
        {failed ? (
          <div className="empty">书签暂时不可用。</div>
        ) : bookmarks === null ? (
          <div className="empty">正在加载书签...</div>
        ) : items.length === 0 ? (
          <div className="empty">还没有书签。打开网页后可以从菜单添加。</div>
        ) : (
          <div className="list">
            {items.map((bookmark) => (
              <BookmarkRow
                bookmark={bookmark}
                editing={editingUrl === bookmark.url}
                key={bookmark.url}
                onEdit={() => setEditingUrl(bookmark.url)}
                onCancel={() => setEditingUrl(null)}
                onOpen={() => window.hyperBrowser.openBookmark(bookmark.url)}
                onRemove={() => remove(bookmark.url)}
                onSave={save}
              />
            ))}
          </div>
        )}
      </main>
    </div>
  );
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

  function submit(event: FormEvent) {
    event.preventDefault();
    props.onSave(bookmark.url, title, url);
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
      <button className="icon-button" type="button" aria-label="Edit bookmark" onClick={props.onEdit}>✎</button>
      <button className="icon-button" type="button" aria-label="Remove bookmark" onClick={props.onRemove}>×</button>
      {props.editing && (
        <form className="editor" onSubmit={submit}>
          <input value={title} placeholder="标题" onChange={(event) => setTitle(event.currentTarget.value)} />
          <input value={url} placeholder="URL" inputMode="url" onChange={(event) => setUrl(event.currentTarget.value)} />
          <div className="editor-actions">
            <button className="cancel" type="button" onClick={props.onCancel}>取消</button>
            <button className="save" type="submit">保存</button>
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
