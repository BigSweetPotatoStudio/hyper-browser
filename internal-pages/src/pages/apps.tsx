import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { WebAppEditorDialog, type LauncherAppChanges, type LauncherLabels } from "@hyper-launcher";
import "../hyper-browser";
import "../styles.css";
import { readBootstrapData } from "../bootstrap";
import { t } from "../i18n";
import type { WebAppItem } from "../hyper-browser";

function AppsPage() {
  const [apps, setApps] = useState<WebAppItem[] | null>(() => readBootstrapData<WebAppItem>());
  const [failed, setFailed] = useState(false);
  const [menuApp, setMenuApp] = useState<WebAppItem | null>(null);
  const [deletingApp, setDeletingApp] = useState<WebAppItem | null>(null);
  const [editingApp, setEditingApp] = useState<WebAppItem | null>(null);
  const [query, setQuery] = useState("");

  function loadApps(showLoading = true) {
    setFailed(false);
    if (showLoading) setApps(null);
    window.hyperBrowser.requestAppsData()
      .then((items) => {
        setApps(items);
        setFailed(false);
      })
      .catch(() => setFailed(true));
  }

  useEffect(() => {
    if (apps !== null) return;
    loadApps(true);
  }, [apps]);

  const items = apps || [];
  const visibleItems = filterApps(items, query);
  const editorLabels = useMemo<LauncherLabels>(() => ({
    loading: t("apps.loading"),
    emptyDesktop: t("apps.empty"),
    loadAppsError: t("apps.failed"),
    loadLayoutError: t("apps.failed"),
    folder: t("home.folder"),
    folderEmpty: t("home.folderEmpty"),
    open: t("home.open"),
    editHomeScreen: t("home.editHomeScreen"),
    done: t("home.done"),
    newFolder: t("home.newFolder"),
    renameFolder: t("home.renameFolder"),
    unpackFolder: t("home.unpackFolder"),
    moveToDesktop: t("home.moveToDesktop"),
    moveToDock: t("home.moveToDock"),
    moveToFolder: (name: string) => t("home.moveToFolder", { name }),
    pinApp: t("apps.pin"),
    editApp: t("apps.editTitle"),
    editIcon: t("apps.editIcon"),
    appName: t("common.name"),
    appUrl: t("common.url"),
    deleteApp: t("common.delete"),
    iconTitle: t("apps.editIcon"),
    iconLetter: t("apps.iconLetter"),
    iconBackground: t("apps.iconBackground"),
    iconPreset: t("apps.iconPreset"),
    iconSourceTitle: t("apps.iconSourceTitle"),
    iconSite: t("apps.iconSite"),
    iconTitleFallback: t("apps.iconTitleFallback"),
    iconDefaultLibrary: t("apps.iconDefaultLibrary"),
    iconChooseImage: t("apps.iconChooseImage"),
    iconSelectedImage: t("apps.iconSelectedImage"),
    iconPresetLabels: {
      news: t("apps.iconPresetNews"),
      video: t("apps.iconPresetVideo"),
      music: t("apps.iconPresetMusic"),
      shop: t("apps.iconPresetShop"),
      chat: t("apps.iconPresetChat"),
      docs: t("apps.iconPresetDocs"),
      work: t("apps.iconPresetWork"),
      star: t("apps.iconPresetStar"),
    },
    iconUpload: t("apps.iconUpload"),
    iconUseImage: t("apps.iconUseImage"),
    iconReset: t("apps.iconReset"),
    cancel: t("common.cancel"),
    save: t("common.save"),
    deleteFailed: t("apps.failed"),
    editFailed: t("apps.failed"),
    iconUpdateFailed: t("apps.iconUpdateFailed"),
  }), []);

  function saveEditingApp(app: WebAppItem, changes: LauncherAppChanges) {
    window.hyperBrowser.updateApp(app.id, changes.name, changes.startUrl, changes.iconDataUrl)
      .then((items) => {
        setApps(items);
        setFailed(false);
        setEditingApp(null);
      })
      .catch(() => loadApps(false));
  }

  return (
    <div className="apps-page">
      <header className="apps-header">
        <a className="back apps-back" href="hyper://home" aria-label={t("common.back")}>‹</a>
        <h1>{t("apps.title")}</h1>
      </header>
      <main className="apps-content">
        {failed ? (
          <div className="apps-empty">
            {t("apps.failed")}{" "}
            <button className="go-button" type="button" onClick={() => loadApps(true)}>{t("apps.retry")}</button>
          </div>
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
        <WebAppEditorDialog
          app={editingApp}
          labels={editorLabels}
          onClose={() => setEditingApp(null)}
          onChooseImage={() => window.hyperBrowser.chooseAppIcon(editingApp.id)}
          onSave={(changes) => saveEditingApp(editingApp, changes)}
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
    <div className="app-tile" title={label}>
      <button
        className="app-launch"
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
      >
        <span
          className={hasIcon ? "app-icon app-icon-image" : "app-icon"}
          style={{ background: hasIcon ? "transparent" : color.background, color: color.foreground }}
        >
          {hasIcon ? <img src={app.iconDataUrl!} alt="" /> : icon}
        </span>
        <span className="app-label">{label}</span>
      </button>
      <button
        className="app-tile-menu"
        type="button"
        aria-label={t("apps.actionsLabel", { name: label })}
        onClick={() => {
          clearLongPress();
          onMenu(app);
        }}
      >
        ⋮
      </button>
    </div>
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

function appInitial(name: string): string {
  const trimmed = name.trimStart();
  if (!trimmed) return "A";
  const segmenterType = (Intl as typeof Intl & {
    Segmenter?: new (
      locale?: string,
      options?: { granularity?: "grapheme" },
    ) => { segment: (value: string) => Iterable<{ segment: string }> };
  }).Segmenter;
  const first = segmenterType
    ? Array.from(new segmenterType(undefined, { granularity: "grapheme" }).segment(trimmed))[0]?.segment
    : Array.from(trimmed)[0];
  return first ? first.toLocaleUpperCase() : "A";
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
