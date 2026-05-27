import React, { useEffect, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import "../hyper-browser";
import "../styles.css";
import { readBootstrapData } from "../bootstrap";
import type { WebAppItem } from "../hyper-browser";

function AppsPage() {
  const [apps, setApps] = useState<WebAppItem[] | null>(() => readBootstrapData<WebAppItem>());
  const [failed, setFailed] = useState(false);

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
              <AppTile app={app} key={app.id} />
            ))}
          </div>
        )}
      </main>
    </div>
  );
}

function AppTile({ app }: { app: WebAppItem }) {
  const icon = useMemo(() => appInitial(app.name), [app.name]);
  const color = useMemo(() => colorFromTheme(app.themeColor, app.id), [app.themeColor, app.id]);
  const label = app.name || hostLabel(app.startUrl);

  return (
    <button
      className="app-tile"
      type="button"
      onClick={() => window.hyperBrowser.openApp(app.id)}
      title={label}
    >
      <span className="app-icon" style={{ background: color.background, color: color.foreground }}>
        {icon}
      </span>
      <span className="app-label">{label}</span>
    </button>
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
