import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import "../hyper-browser";
import "../styles.css";
import { t } from "../i18n";
import type { WebAppItem } from "../hyper-browser";

const LAYOUT_STORAGE_KEY = "hyper-home-launcher-layout-v1";
const LONG_PRESS_MS = 540;
const DRAG_THRESHOLD_PX = 10;

type SystemAction = "bookmarks" | "history" | "extensions";

type SystemEntry = {
  id: string;
  kind: "system";
  title: string;
  mark: string;
  color: string;
  action: SystemAction;
};

type AppEntry = {
  id: string;
  kind: "app";
  title: string;
  mark: string;
  color: string;
  app: WebAppItem;
};

type FolderLayout = {
  id: string;
  title: string;
  childIds: string[];
};

type FolderEntry = {
  id: string;
  kind: "folder";
  title: string;
  childIds: string[];
  children: LauncherEntry[];
};

type LauncherEntry = SystemEntry | AppEntry | FolderEntry;

type LauncherLayout = {
  order: string[];
  folders: FolderLayout[];
};

type ContextMenuState = {
  itemId: string;
  folderId?: string;
  x: number;
  y: number;
};

type GestureState = {
  itemId: string;
  folderId?: string;
  pointerId: number;
  startX: number;
  startY: number;
  lastX: number;
  lastY: number;
  moved: boolean;
  menuOpened: boolean;
  timer: number;
};

const emptyLayout: LauncherLayout = {
  order: [],
  folders: [],
};

function HomePage() {
  const [apps, setApps] = useState<WebAppItem[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [layout, setLayout] = useState<LauncherLayout>(() => readLayout());
  const [menu, setMenu] = useState<ContextMenuState | null>(null);
  const [openFolderId, setOpenFolderId] = useState<string | null>(null);
  const [draggingId, setDraggingId] = useState<string | null>(null);
  const gesture = useRef<GestureState | null>(null);
  const rootIds = useRef<string[]>([]);

  useEffect(() => {
    loadApps();
  }, []);

  useEffect(() => {
    writeLayout(layout);
  }, [layout]);

  function loadApps() {
    setFailed(false);
    window.hyperBrowser.requestAppsData()
      .then((items) => {
        setApps(items);
        setFailed(false);
      })
      .catch(() => setFailed(true));
  }

  const systemEntries = useMemo<SystemEntry[]>(() => ([
    {
      id: "system:bookmarks",
      kind: "system",
      title: t("home.bookmarks"),
      mark: "B",
      color: "#4285f4",
      action: "bookmarks",
    },
    {
      id: "system:history",
      kind: "system",
      title: t("home.history"),
      mark: "H",
      color: "#34a853",
      action: "history",
    },
    {
      id: "system:extensions",
      kind: "system",
      title: t("home.extensions"),
      mark: "Ex",
      color: "#fbbc04",
      action: "extensions",
    },
  ]), []);

  const appEntries = useMemo<AppEntry[]>(() => (apps || []).map((app) => ({
    id: `app:${app.id}`,
    kind: "app",
    title: app.name || hostLabel(app.startUrl),
    mark: appInitial(app.name || hostLabel(app.startUrl)),
    color: colorFromTheme(app.themeColor, app.id),
    app,
  })), [apps]);

  const availableEntries = useMemo(() => {
    return new Map<string, SystemEntry | AppEntry>([
      ...systemEntries.map((item) => [item.id, item] as const),
      ...appEntries.map((item) => [item.id, item] as const),
    ]);
  }, [appEntries, systemEntries]);

  const folders = useMemo<FolderEntry[]>(() => {
    return layout.folders.map((folder) => ({
      id: folder.id,
      kind: "folder",
      title: folder.title.trim() || t("home.folder"),
      childIds: folder.childIds.filter((id) => availableEntries.has(id)),
      children: folder.childIds
        .map((id) => availableEntries.get(id))
        .filter((item): item is SystemEntry | AppEntry => !!item),
    }));
  }, [availableEntries, layout.folders]);

  const folderEntries = useMemo(() => {
    return new Map(folders.map((folder) => [folder.id, folder] as const));
  }, [folders]);

  const containedIds = useMemo(() => new Set(folders.flatMap((folder) => folder.childIds)), [folders]);

  const desktopEntries = useMemo(() => {
    const allIds = [...availableEntries.keys(), ...folderEntries.keys()];
    const visibleIds = allIds.filter((id) => !containedIds.has(id));
    const ordered = [
      ...layout.order.filter((id) => visibleIds.includes(id)),
      ...visibleIds.filter((id) => !layout.order.includes(id)),
    ];
    rootIds.current = ordered;
    return ordered
      .map((id) => folderEntries.get(id) || availableEntries.get(id))
      .filter((item): item is LauncherEntry => !!item);
  }, [availableEntries, containedIds, folderEntries, layout.order]);

  function resolveEntry(id: string): LauncherEntry | undefined {
    return folderEntries.get(id) || availableEntries.get(id);
  }

  function openEntry(entry: LauncherEntry) {
    if (entry.kind === "folder") {
      setOpenFolderId(entry.id);
      return;
    }
    if (entry.kind === "app") {
      window.hyperBrowser.openApp(entry.app.id);
      return;
    }
    if (entry.action === "bookmarks") window.hyperBrowser.showBookmarks();
    if (entry.action === "history") window.hyperBrowser.showHistory();
    if (entry.action === "extensions") window.hyperBrowser.showExtensions();
  }

  function beginPress(event: React.PointerEvent<HTMLElement>, itemId: string, folderId?: string) {
    if (event.button !== 0) return;
    const target = event.currentTarget;
    target.setPointerCapture(event.pointerId);
    clearGesture();
    gesture.current = {
      itemId,
      folderId,
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      lastX: event.clientX,
      lastY: event.clientY,
      moved: false,
      menuOpened: false,
      timer: window.setTimeout(() => {
        if (!gesture.current || gesture.current.itemId !== itemId) return;
        gesture.current.menuOpened = true;
        setDraggingId(null);
        setMenu({ itemId, folderId, x: gesture.current.lastX, y: gesture.current.lastY });
      }, LONG_PRESS_MS),
    };
  }

  function movePress(event: React.PointerEvent<HTMLElement>) {
    const current = gesture.current;
    if (!current || current.pointerId !== event.pointerId) return;
    current.lastX = event.clientX;
    current.lastY = event.clientY;
    const distance = Math.hypot(event.clientX - current.startX, event.clientY - current.startY);
    if (distance < DRAG_THRESHOLD_PX || current.menuOpened) return;
    window.clearTimeout(current.timer);
    current.moved = true;
    setMenu(null);
    setDraggingId(current.itemId);
    const target = launcherTargetFromPoint(event.clientX, event.clientY);
    if (!target || target.itemId === current.itemId) return;
    if (current.folderId && target.folderId === current.folderId) {
      moveInsideFolder(current.folderId, current.itemId, target.itemId);
    } else if (!current.folderId && !target.folderId && !target.itemId.startsWith("folder:")) {
      moveRootBefore(current.itemId, target.itemId);
    }
  }

  function endPress(event: React.PointerEvent<HTMLElement>) {
    const current = gesture.current;
    if (!current || current.pointerId !== event.pointerId) return;
    window.clearTimeout(current.timer);
    const target = launcherTargetFromPoint(event.clientX, event.clientY);
    setDraggingId(null);
    gesture.current = null;

    if (current.moved) {
      if (!current.itemId.startsWith("folder:") && target?.itemId.startsWith("folder:")) {
        moveToFolder(current.itemId, target.itemId);
      }
      return;
    }
    if (current.menuOpened) return;
    const entry = resolveEntry(current.itemId);
    if (entry) openEntry(entry);
  }

  function openContextMenu(event: React.MouseEvent<HTMLElement>, itemId: string, folderId?: string) {
    event.preventDefault();
    clearGesture();
    setDraggingId(null);
    setMenu({ itemId, folderId, x: event.clientX, y: event.clientY });
  }

  function moveRootBefore(itemId: string, beforeId: string) {
    if (itemId === beforeId) return;
    const nextOrder = moveBefore(rootIds.current, itemId, beforeId);
    setLayout((current) => ({ ...current, order: nextOrder }));
  }

  function moveInsideFolder(folderId: string, itemId: string, beforeId: string) {
    if (itemId === beforeId) return;
    setLayout((current) => ({
      ...current,
      folders: current.folders.map((folder) => {
        if (folder.id !== folderId) return folder;
        return { ...folder, childIds: moveBefore(folder.childIds, itemId, beforeId) };
      }),
    }));
  }

  function moveToFolder(itemId: string, folderId: string) {
    if (itemId.startsWith("folder:") || itemId === folderId) return;
    setLayout((current) => ({
      order: current.order.filter((id) => id !== itemId),
      folders: current.folders.map((folder) => {
        const withoutItem = folder.childIds.filter((id) => id !== itemId);
        if (folder.id !== folderId) return { ...folder, childIds: withoutItem };
        return { ...folder, childIds: [...withoutItem, itemId] };
      }),
    }));
    setMenu(null);
  }

  function moveToDesktop(itemId: string) {
    setLayout((current) => ({
      order: [...current.order.filter((id) => id !== itemId), itemId],
      folders: current.folders.map((folder) => ({
        ...folder,
        childIds: folder.childIds.filter((id) => id !== itemId),
      })),
    }));
    setMenu(null);
  }

  function createFolderWith(itemId: string) {
    if (itemId.startsWith("folder:")) return;
    const folderId = `folder:${createId()}`;
    const title = t("home.folder");
    setLayout((current) => {
      const nextOrder = current.order.includes(itemId)
        ? current.order.map((id) => (id === itemId ? folderId : id))
        : [...rootIds.current.filter((id) => id !== itemId), folderId];
      return {
        order: nextOrder,
        folders: [
          ...current.folders.map((folder) => ({
            ...folder,
            childIds: folder.childIds.filter((id) => id !== itemId),
          })),
          { id: folderId, title, childIds: [itemId] },
        ],
      };
    });
    setOpenFolderId(folderId);
    setMenu(null);
  }

  function renameFolder(folderId: string) {
    const current = layout.folders.find((folder) => folder.id === folderId);
    const nextTitle = window.prompt(t("home.renameFolder"), current?.title || t("home.folder"));
    if (!nextTitle) return;
    setLayout((value) => ({
      ...value,
      folders: value.folders.map((folder) => (
        folder.id === folderId ? { ...folder, title: nextTitle.trim() || t("home.folder") } : folder
      )),
    }));
    setMenu(null);
  }

  function unpackFolder(folderId: string) {
    const folder = layout.folders.find((item) => item.id === folderId);
    if (!folder) return;
    setLayout((current) => ({
      order: current.order.flatMap((id) => (id === folderId ? folder.childIds : [id])),
      folders: current.folders.filter((item) => item.id !== folderId),
    }));
    setOpenFolderId(null);
    setMenu(null);
  }

  function deleteApp(itemId: string) {
    const entry = availableEntries.get(itemId);
    if (!entry || entry.kind !== "app") return;
    window.hyperBrowser.deleteApp(entry.app.id);
    setApps((current) => (current || []).filter((app) => app.id !== entry.app.id));
    setLayout((current) => ({
      order: current.order.filter((id) => id !== itemId),
      folders: current.folders.map((folder) => ({
        ...folder,
        childIds: folder.childIds.filter((id) => id !== itemId),
      })),
    }));
    setMenu(null);
  }

  function clearGesture() {
    if (!gesture.current) return;
    window.clearTimeout(gesture.current.timer);
    gesture.current = null;
  }

  const openFolder = openFolderId ? folderEntries.get(openFolderId) : undefined;

  return (
    <main className="launcher-page">
      <section className="launcher-grid" aria-label={t("home.desktopLabel")}>
        {failed && (
          <button className="launcher-status" type="button" onClick={loadApps}>
            {t("apps.failed")} {t("common.retry")}
          </button>
        )}
        {apps === null && !failed && <div className="launcher-status">{t("apps.loading")}</div>}
        {desktopEntries.map((entry) => (
          <LauncherTile
            entry={entry}
            dragging={draggingId === entry.id}
            folderId={undefined}
            key={entry.id}
            onPointerDown={beginPress}
            onPointerMove={movePress}
            onPointerUp={endPress}
            onContextMenu={openContextMenu}
          />
        ))}
      </section>

      {openFolder && (
        <div className="launcher-folder-scrim" onClick={() => setOpenFolderId(null)}>
          <section className="launcher-folder" role="dialog" aria-modal="true" aria-label={openFolder.title} onClick={(event) => event.stopPropagation()}>
            <header className="launcher-folder-header">
              <h2>{openFolder.title}</h2>
              <button type="button" onClick={() => setOpenFolderId(null)}>{t("common.back")}</button>
            </header>
            <div className="launcher-folder-grid">
              {openFolder.children.length === 0 ? (
                <div className="launcher-status">{t("home.folderEmpty")}</div>
              ) : openFolder.children.map((entry) => (
                <LauncherTile
                  entry={entry}
                  dragging={draggingId === entry.id}
                  folderId={openFolder.id}
                  key={entry.id}
                  onPointerDown={beginPress}
                  onPointerMove={movePress}
                  onPointerUp={endPress}
                  onContextMenu={openContextMenu}
                />
              ))}
            </div>
          </section>
        </div>
      )}

      {menu && (
        <LauncherMenu
          folders={folders}
          item={resolveEntry(menu.itemId)}
          sourceFolderId={menu.folderId}
          x={menu.x}
          y={menu.y}
          onClose={() => setMenu(null)}
          onOpen={(item) => {
            openEntry(item);
            setMenu(null);
          }}
          onCreateFolder={createFolderWith}
          onMoveToFolder={moveToFolder}
          onMoveToDesktop={moveToDesktop}
          onRenameFolder={renameFolder}
          onUnpackFolder={unpackFolder}
          onDeleteApp={deleteApp}
        />
      )}
    </main>
  );
}

function LauncherTile(props: {
  entry: LauncherEntry;
  folderId?: string;
  dragging: boolean;
  onPointerDown: (event: React.PointerEvent<HTMLElement>, itemId: string, folderId?: string) => void;
  onPointerMove: (event: React.PointerEvent<HTMLElement>) => void;
  onPointerUp: (event: React.PointerEvent<HTMLElement>) => void;
  onContextMenu: (event: React.MouseEvent<HTMLElement>, itemId: string, folderId?: string) => void;
}) {
  const iconStyle = props.entry.kind === "folder" ? undefined : { background: props.entry.color };
  return (
    <button
      className={`launcher-tile${props.dragging ? " dragging" : ""}`}
      type="button"
      data-launcher-id={props.entry.id}
      data-folder-id={props.folderId || ""}
      title={props.entry.title}
      onContextMenu={(event) => props.onContextMenu(event, props.entry.id, props.folderId)}
      onPointerDown={(event) => props.onPointerDown(event, props.entry.id, props.folderId)}
      onPointerMove={props.onPointerMove}
      onPointerUp={props.onPointerUp}
      onPointerCancel={props.onPointerUp}
    >
      {props.entry.kind === "folder" ? (
        <span className="launcher-icon launcher-folder-icon" aria-hidden="true">
          {props.entry.children.slice(0, 4).map((child) => (
            <span className="launcher-folder-dot" key={child.id} style={{ background: child.kind === "folder" ? "#dfe5eb" : child.color }}>
              {child.kind === "folder" ? "" : child.mark.slice(0, 1)}
            </span>
          ))}
        </span>
      ) : (
        <span className={props.entry.kind === "app" && props.entry.app.iconDataUrl ? "launcher-icon image" : "launcher-icon"} style={iconStyle} aria-hidden="true">
          {props.entry.kind === "app" && props.entry.app.iconDataUrl ? <img src={props.entry.app.iconDataUrl} alt="" /> : props.entry.mark}
        </span>
      )}
      <span className="launcher-label">{props.entry.title}</span>
    </button>
  );
}

function LauncherMenu(props: {
  item?: LauncherEntry;
  sourceFolderId?: string;
  folders: FolderEntry[];
  x: number;
  y: number;
  onClose: () => void;
  onOpen: (item: LauncherEntry) => void;
  onCreateFolder: (itemId: string) => void;
  onMoveToFolder: (itemId: string, folderId: string) => void;
  onMoveToDesktop: (itemId: string) => void;
  onRenameFolder: (folderId: string) => void;
  onUnpackFolder: (folderId: string) => void;
  onDeleteApp: (itemId: string) => void;
}) {
  if (!props.item) return null;
  const top = Math.min(props.y, window.innerHeight - 260);
  const left = Math.min(props.x, window.innerWidth - 230);
  const movableFolders = props.folders.filter((folder) => folder.id !== props.item?.id);
  return (
    <div className="launcher-menu-scrim" onClick={props.onClose}>
      <div className="launcher-menu" role="menu" style={{ top, left }} onClick={(event) => event.stopPropagation()}>
        <div className="launcher-menu-title">{props.item.title}</div>
        <button type="button" role="menuitem" onClick={() => props.onOpen(props.item!)}>{t("home.open")}</button>
        {props.item.kind === "folder" ? (
          <>
            <button type="button" role="menuitem" onClick={() => props.onRenameFolder(props.item!.id)}>{t("home.renameFolder")}</button>
            <button type="button" role="menuitem" onClick={() => props.onUnpackFolder(props.item!.id)}>{t("home.unpackFolder")}</button>
          </>
        ) : (
          <>
            <button type="button" role="menuitem" onClick={() => props.onCreateFolder(props.item!.id)}>{t("home.newFolder")}</button>
            {props.sourceFolderId && (
              <button type="button" role="menuitem" onClick={() => props.onMoveToDesktop(props.item!.id)}>{t("home.moveToDesktop")}</button>
            )}
            {movableFolders.map((folder) => (
              <button type="button" role="menuitem" key={folder.id} onClick={() => props.onMoveToFolder(props.item!.id, folder.id)}>
                {t("home.moveToFolder", { name: folder.title })}
              </button>
            ))}
            {props.item.kind === "app" && (
              <button className="danger" type="button" role="menuitem" onClick={() => props.onDeleteApp(props.item!.id)}>{t("common.delete")}</button>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function launcherTargetFromPoint(x: number, y: number): { itemId: string; folderId?: string } | null {
  const element = document.elementFromPoint(x, y)?.closest<HTMLElement>("[data-launcher-id]");
  const itemId = element?.dataset.launcherId;
  if (!element || !itemId) return null;
  return { itemId, folderId: element.dataset.folderId || undefined };
}

function moveBefore(ids: string[], itemId: string, beforeId: string): string[] {
  const next = ids.filter((id) => id !== itemId);
  const index = next.indexOf(beforeId);
  if (index < 0) return [...next, itemId];
  next.splice(index, 0, itemId);
  return next;
}

function readLayout(): LauncherLayout {
  try {
    const raw = window.localStorage.getItem(LAYOUT_STORAGE_KEY);
    if (!raw) return emptyLayout;
    const parsed = JSON.parse(raw) as Partial<LauncherLayout>;
    return {
      order: Array.isArray(parsed.order) ? parsed.order.filter((id): id is string => typeof id === "string") : [],
      folders: Array.isArray(parsed.folders)
        ? parsed.folders
          .filter((folder): folder is FolderLayout => typeof folder?.id === "string" && Array.isArray(folder.childIds))
          .map((folder) => ({
            id: folder.id,
            title: typeof folder.title === "string" ? folder.title : t("home.folder"),
            childIds: folder.childIds.filter((id): id is string => typeof id === "string"),
          }))
        : [],
    };
  } catch {
    return emptyLayout;
  }
}

function writeLayout(layout: LauncherLayout) {
  window.localStorage.setItem(LAYOUT_STORAGE_KEY, JSON.stringify(layout));
}

function createId(): string {
  return globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`;
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

function colorFromTheme(themeColor: number, id: string): string {
  const palettes = ["#fbbc04", "#34a853", "#4285f4", "#ea4335", "#00a884", "#9c27b0", "#ff7043", "#5e97f6"];
  const rgb = (themeColor >>> 0) & 0xffffff;
  if (rgb !== 0) return `#${rgb.toString(16).padStart(6, "0")}`;
  return palettes[Math.abs(hashCode(id)) % palettes.length];
}

function hostLabel(url: string): string {
  try {
    return new URL(url).host || url;
  } catch {
    return url;
  }
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
    <HomePage />
  </React.StrictMode>
);
