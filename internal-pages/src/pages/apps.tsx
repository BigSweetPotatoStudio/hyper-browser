import React, { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import "../hyper-browser";
import "../styles.css";
import { readBootstrapData } from "../bootstrap";
import { t } from "../i18n";
import type { WebAppItem } from "../hyper-browser";

function AppsPage() {
  const [apps, setApps] = useState<WebAppItem[] | null>(() => readBootstrapData<WebAppItem>());
  const [failed, setFailed] = useState(false);
  const [menuApp, setMenuApp] = useState<WebAppItem | null>(null);
  const [editingApp, setEditingApp] = useState<WebAppItem | null>(null);
  const [deletingApp, setDeletingApp] = useState<WebAppItem | null>(null);
  const [query, setQuery] = useState("");

  useEffect(() => {
    if (apps !== null) return;
    window.hyperBrowser.requestAppsData()
      .then(setApps)
      .catch(() => setFailed(true));
  }, [apps]);

  const items = apps || [];
  const visibleItems = filterApps(items, query);

  return (
    <div className="apps-page">
      <header className="apps-header">
        <a className="back apps-back" href="hyper://home" aria-label={t("common.back")}>‹</a>
        <h1>{t("apps.title")}</h1>
      </header>
      <main className="apps-content">
        {failed ? (
          <div className="apps-empty">{t("apps.failed")}</div>
        ) : apps === null ? (
          <div className="apps-empty">{t("apps.loading")}</div>
        ) : items.length === 0 ? (
          <div className="apps-empty">{t("apps.empty")}</div>
        ) : (
          <>
            <SearchBox
              label={t("apps.searchLabel")}
              placeholder={t("apps.searchPlaceholder")}
              value={query}
              onChange={setQuery}
            />
            {visibleItems.length === 0 ? (
              <div className="apps-empty">{t("apps.noMatches")}</div>
            ) : (
              <div className="apps-grid" aria-label={t("apps.installedLabel")}>
                {visibleItems.map((app) => (
                  <AppTile app={app} key={app.id} onMenu={setMenuApp} />
                ))}
              </div>
            )}
          </>
        )}
      </main>
      {menuApp && (
        <AppActionSheet
          app={menuApp}
          onClose={() => setMenuApp(null)}
          onPin={() => {
            window.hyperBrowser.pinApp(menuApp.id);
            setMenuApp(null);
          }}
          onEdit={() => {
            setEditingApp(menuApp);
            setMenuApp(null);
          }}
          onDelete={() => {
            setDeletingApp(menuApp);
            setMenuApp(null);
          }}
        />
      )}
      {deletingApp && (
        <DeleteAppConfirmDialog
          app={deletingApp}
          onClose={() => setDeletingApp(null)}
          onConfirm={() => {
            window.hyperBrowser.deleteApp(deletingApp.id);
            setApps((current) => (current || []).filter((app) => app.id !== deletingApp.id));
            setDeletingApp(null);
          }}
        />
      )}
      {editingApp && (
        <EditAppDialog
          app={editingApp}
          onClose={() => setEditingApp(null)}
          onSave={(name, startUrl) => {
            window.hyperBrowser.editApp(editingApp.id, name, startUrl);
            setApps((current) => (current || []).map((app) => (
              app.id === editingApp.id
                ? { ...app, name, startUrl }
                : app
            )));
            setEditingApp(null);
          }}
        />
      )}
    </div>
  );
}

function DeleteAppConfirmDialog(props: {
  app: WebAppItem;
  onClose: () => void;
  onConfirm: () => void;
}) {
  const label = props.app.name || hostLabel(props.app.startUrl);
  return (
    <div className="confirm-scrim" onClick={props.onClose}>
      <section
        className="confirm-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="delete-app-title"
        onClick={(event) => event.stopPropagation()}
      >
        <h2 id="delete-app-title">{t("common.delete")} {label}?</h2>
        <p>{props.app.startUrl}</p>
        <div className="confirm-actions">
          <button type="button" onClick={props.onClose}>{t("common.cancel")}</button>
          <button className="danger" type="button" onClick={props.onConfirm}>{t("common.delete")}</button>
        </div>
      </section>
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

function filterApps(items: WebAppItem[], query: string): WebAppItem[] {
  const normalizedQuery = query.trim().toLocaleLowerCase();
  if (!normalizedQuery) return items;
  return items.filter((app) => {
    const fields = [
      app.name,
      app.startUrl,
      hostLabel(app.startUrl),
    ];
    return fields.some((field) => field.toLocaleLowerCase().includes(normalizedQuery));
  });
}

function AppTile({ app, onMenu }: { app: WebAppItem; onMenu: (app: WebAppItem) => void }) {
  const icon = useMemo(() => appInitial(app.name), [app.name]);
  const color = useMemo(() => colorFromTheme(app.themeColor, app.id), [app.themeColor, app.id]);
  const label = app.name || hostLabel(app.startUrl);
  const hasIcon = !!app.iconDataUrl;
  const longPressTimer = useRef<number | null>(null);
  const longPressed = useRef(false);

  function clearLongPress() {
    if (longPressTimer.current !== null) {
      window.clearTimeout(longPressTimer.current);
      longPressTimer.current = null;
    }
  }

  return (
    <button
      className="app-tile"
      type="button"
      onClick={() => {
        clearLongPress();
        if (longPressed.current) {
          longPressed.current = false;
          return;
        }
        window.hyperBrowser.openApp(app.id);
      }}
      onContextMenu={(event) => {
        event.preventDefault();
        clearLongPress();
        onMenu(app);
      }}
      onPointerDown={() => {
        clearLongPress();
        longPressed.current = false;
        longPressTimer.current = window.setTimeout(() => {
          longPressed.current = true;
          onMenu(app);
        }, 560);
      }}
      onPointerMove={clearLongPress}
      onPointerCancel={clearLongPress}
      onPointerUp={clearLongPress}
      onPointerLeave={clearLongPress}
      title={label}
    >
      <span
        className={hasIcon ? "app-icon app-icon-image" : "app-icon"}
        style={{ background: hasIcon ? "transparent" : color.background, color: color.foreground }}
      >
        {hasIcon ? <img src={app.iconDataUrl!} alt="" /> : icon}
      </span>
      <span className="app-label">{label}</span>
    </button>
  );
}

function AppActionSheet(props: {
  app: WebAppItem;
  onClose: () => void;
  onPin: () => void;
  onEdit: () => void;
  onDelete: () => void;
}) {
  return (
    <div className="app-menu-scrim" onClick={props.onClose}>
      <div className="app-menu" role="menu" onClick={(event) => event.stopPropagation()}>
        <div className="app-menu-title">{props.app.name || hostLabel(props.app.startUrl)}</div>
        <button type="button" role="menuitem" onClick={props.onPin}>{t("apps.pin")}</button>
        <button type="button" role="menuitem" onClick={props.onEdit}>{t("common.edit")}</button>
        <button className="danger" type="button" role="menuitem" onClick={props.onDelete}>{t("common.delete")}</button>
      </div>
    </div>
  );
}

function EditAppDialog(props: {
  app: WebAppItem;
  onClose: () => void;
  onSave: (name: string, startUrl: string) => void;
}) {
  const [name, setName] = useState(props.app.name || "");
  const [startUrl, setStartUrl] = useState(props.app.startUrl);
  const startUrlInputRef = useRef<HTMLInputElement>(null);

  function submit(event: FormEvent) {
    event.preventDefault();
    const cleanUrl = startUrl.trim();
    if (!cleanUrl) {
      setStartUrl("");
      window.requestAnimationFrame(() => {
        startUrlInputRef.current?.focus();
        startUrlInputRef.current?.reportValidity();
      });
      return;
    }
    props.onSave(name.trim() || hostLabel(cleanUrl), cleanUrl);
  }

  return (
    <div className="app-menu-scrim">
      <form className="app-edit-dialog" onSubmit={submit}>
        <h2>{t("apps.editTitle")}</h2>
        <label>
          <span>{t("common.name")}</span>
          <input value={name} onChange={(event) => setName(event.currentTarget.value)} />
        </label>
        <label>
          <span>{t("common.url")}</span>
          <input
            ref={startUrlInputRef}
            value={startUrl}
            inputMode="url"
            required
            onChange={(event) => setStartUrl(event.currentTarget.value)}
          />
        </label>
        <div className="app-edit-actions">
          <button type="button" onClick={props.onClose}>{t("common.cancel")}</button>
          <button type="submit">{t("common.save")}</button>
        </div>
      </form>
    </div>
  );
}

function appInitial(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return "A";
  return Array.from(trimmed)[0].toUpperCase();
}

function hostLabel(url: string): string {
  try {
    return new URL(url).host || url;
  } catch {
    return url;
  }
}

function colorFromTheme(themeColor: number, id: string): { background: string; foreground: string } {
  const palettes = ["#fbbc04", "#34a853", "#4285f4", "#ea4335", "#00a884", "#9c27b0", "#ff7043", "#5e97f6"];
  const rgb = (themeColor >>> 0) & 0xffffff;
  const fallback = palettes[Math.abs(hashCode(id)) % palettes.length];
  const background = rgb === 0 ? fallback : `#${rgb.toString(16).padStart(6, "0")}`;
  return { background, foreground: readableTextColor(background) };
}

function readableTextColor(hex: string): string {
  const value = hex.replace("#", "");
  const r = parseInt(value.slice(0, 2), 16);
  const g = parseInt(value.slice(2, 4), 16);
  const b = parseInt(value.slice(4, 6), 16);
  const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
  return luminance > 0.68 ? "#202124" : "#fff";
}

function hashCode(value: string): number {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = ((hash << 5) - hash + value.charCodeAt(index)) | 0;
  }
  return hash;
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <AppsPage />
  </React.StrictMode>
);
