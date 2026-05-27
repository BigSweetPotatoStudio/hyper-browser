import React, { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import "../hyper-browser";
import "../styles.css";
import { readBootstrapData } from "../bootstrap";
import type { WebAppItem } from "../hyper-browser";

function AppsPage() {
  const [apps, setApps] = useState<WebAppItem[] | null>(() => readBootstrapData<WebAppItem>());
  const [failed, setFailed] = useState(false);
  const [menuApp, setMenuApp] = useState<WebAppItem | null>(null);
  const [editingApp, setEditingApp] = useState<WebAppItem | null>(null);

  useEffect(() => {
    if (apps !== null) return;
    window.hyperBrowser.requestAppsData()
      .then(setApps)
      .catch(() => setFailed(true));
  }, [apps]);

  const items = apps || [];

  return (
    <div className="apps-page">
      <header className="apps-header">
        <a className="back apps-back" href="hyper://home" aria-label="Back">‹</a>
        <h1>Apps</h1>
      </header>
      <main className="apps-content">
        {failed ? (
          <div className="apps-empty">App 列表暂时不可用。</div>
        ) : apps === null ? (
          <div className="apps-empty">正在加载 App...</div>
        ) : items.length === 0 ? (
          <div className="apps-empty">还没有安装 WebApp。打开网页后从菜单选择 Install as WebApp。</div>
        ) : (
          <div className="apps-grid" aria-label="Installed web apps">
            {items.map((app) => (
              <AppTile app={app} key={app.id} onMenu={setMenuApp} />
            ))}
          </div>
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
            window.hyperBrowser.deleteApp(menuApp.id);
            setApps((current) => (current || []).filter((app) => app.id !== menuApp.id));
            setMenuApp(null);
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
        <button type="button" role="menuitem" onClick={props.onPin}>发送到桌面</button>
        <button type="button" role="menuitem" onClick={props.onEdit}>修改</button>
        <button className="danger" type="button" role="menuitem" onClick={props.onDelete}>删除</button>
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

  function submit(event: FormEvent) {
    event.preventDefault();
    const cleanUrl = startUrl.trim();
    if (!cleanUrl) return;
    props.onSave(name.trim() || hostLabel(cleanUrl), cleanUrl);
  }

  return (
    <div className="app-menu-scrim">
      <form className="app-edit-dialog" onSubmit={submit}>
        <h2>修改 WebApp</h2>
        <label>
          <span>名称</span>
          <input value={name} onChange={(event) => setName(event.currentTarget.value)} />
        </label>
        <label>
          <span>URL</span>
          <input value={startUrl} inputMode="url" onChange={(event) => setStartUrl(event.currentTarget.value)} />
        </label>
        <div className="app-edit-actions">
          <button type="button" onClick={props.onClose}>取消</button>
          <button type="submit">保存</button>
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
