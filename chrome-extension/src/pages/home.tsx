import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import "../styles.css";
import type { WebAppRecord } from "../types";
import { sendCommand } from "./bridge";

const LAYOUT_STORAGE_KEY = "launcherLayout";
const LONG_PRESS_MS = 540;

type SystemAction = "chrome" | "bookmarks" | "history" | "extensions";

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
  app: WebAppRecord;
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

type MenuState = {
  itemId: string;
  folderId?: string;
  x: number;
  y: number;
};

type DropPlacement = "before" | "inside" | "after";

function DesktopPage() {
  const [apps, setApps] = useState<WebAppRecord[]>([]);
  const [layout, setLayout] = useState<LauncherLayout>({ order: [], folders: [] });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [menu, setMenu] = useState<MenuState | null>(null);
  const [openFolderId, setOpenFolderId] = useState<string | null>(null);
  const [editMode, setEditMode] = useState(false);
  const dragRef = useRef<{ itemId: string; folderId?: string } | null>(null);
  const pressTimer = useRef<number | null>(null);
  const suppressClickUntil = useRef(0);
  const rootIds = useRef<string[]>([]);

  useEffect(() => {
    Promise.all([loadLayout(), sendCommand<WebAppRecord[]>("webapps.list")])
      .then(([nextLayout, records]) => {
        setLayout(nextLayout);
        setApps(records);
        setError("");
      })
      .catch((loadError) => {
        setError(loadError instanceof Error ? loadError.message : "Unable to load desktop.");
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    chrome.storage.local.set({ [LAYOUT_STORAGE_KEY]: layout }).catch(console.error);
  }, [layout]);

  const systemEntries = useMemo<SystemEntry[]>(() => ([
    { id: "system:chrome", kind: "system", title: "Chrome", mark: "C", color: "#4285f4", action: "chrome" },
    { id: "system:bookmarks", kind: "system", title: "Bookmarks", mark: "B", color: "#34a853", action: "bookmarks" },
    { id: "system:history", kind: "system", title: "History", mark: "H", color: "#fbbc04", action: "history" },
    { id: "system:extensions", kind: "system", title: "Extensions", mark: "Ex", color: "#ea4335", action: "extensions" },
  ]), []);

  const appEntries = useMemo<AppEntry[]>(() => apps.map((app) => ({
    id: `app:${app.id}`,
    kind: "app",
    title: app.name || hostLabel(app.startUrl),
    mark: appInitial(app.name || hostLabel(app.startUrl)),
    color: colorFromTheme(app.themeColor, app.id),
    app,
  })), [apps]);

  const availableEntries = useMemo(() => new Map<string, SystemEntry | AppEntry>([
    ...systemEntries.map((item) => [item.id, item] as const),
    ...appEntries.map((item) => [item.id, item] as const),
  ]), [appEntries, systemEntries]);

  const folders = useMemo<FolderEntry[]>(() => layout.folders.map((folder) => ({
    id: folder.id,
    kind: "folder",
    title: folder.title || "Folder",
    childIds: folder.childIds.filter((id) => availableEntries.has(id)),
    children: folder.childIds
      .map((id) => availableEntries.get(id))
      .filter((item): item is SystemEntry | AppEntry => !!item),
  })), [availableEntries, layout.folders]);

  const folderEntries = useMemo(() => new Map(folders.map((folder) => [folder.id, folder] as const)), [folders]);
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
      chrome.tabs.create({ url: entry.app.startUrl });
      return;
    }
    const urls: Record<SystemAction, string> = {
      chrome: "chrome://newtab/",
      bookmarks: "chrome://bookmarks/",
      history: "chrome://history/",
      extensions: "chrome://extensions/",
    };
    chrome.tabs.create({ url: urls[entry.action] });
  }

  function startLongPress(event: React.PointerEvent<HTMLElement>, itemId: string, folderId?: string) {
    if (editMode) return;
    if (event.button !== 0) return;
    stopLongPress();
    pressTimer.current = window.setTimeout(() => {
      suppressClickUntil.current = Date.now() + 650;
      setMenu({ itemId, folderId, x: event.clientX, y: event.clientY });
    }, LONG_PRESS_MS);
  }

  function stopLongPress() {
    if (pressTimer.current !== null) {
      window.clearTimeout(pressTimer.current);
      pressTimer.current = null;
    }
  }

  function openContextMenu(event: React.MouseEvent<HTMLElement>, itemId: string, folderId?: string) {
    event.preventDefault();
    stopLongPress();
    setMenu({ itemId, folderId, x: event.clientX, y: event.clientY });
  }

  function clickEntry(entry: LauncherEntry) {
    stopLongPress();
    if (editMode) return;
    if (Date.now() < suppressClickUntil.current) return;
    openEntry(entry);
  }

  function moveRootRelative(itemId: string, targetId: string, placement: DropPlacement) {
    setLayout((current) => ({ ...current, order: insertRelative(rootIds.current, itemId, targetId, placement) }));
  }

  function moveInsideFolderRelative(folderId: string, itemId: string, targetId: string, placement: DropPlacement) {
    setLayout((current) => ({
      ...current,
      folders: current.folders.map((folder) => (
        folder.id === folderId ? { ...folder, childIds: insertRelative(folder.childIds, itemId, targetId, placement) } : folder
      )),
    }));
  }

  function moveToDesktopNear(itemId: string, targetId: string, placement: DropPlacement) {
    setLayout((current) => ({
      order: insertRelative(rootIds.current, itemId, targetId, placement),
      folders: current.folders.map((folder) => ({ ...folder, childIds: folder.childIds.filter((id) => id !== itemId) })),
    }));
  }

  function moveToFolder(itemId: string, folderId: string) {
    if (itemId.startsWith("folder:")) return;
    setLayout((current) => ({
      order: current.order.filter((id) => id !== itemId),
      folders: current.folders.map((folder) => {
        const childIds = folder.childIds.filter((id) => id !== itemId);
        return folder.id === folderId ? { ...folder, childIds: [...childIds, itemId] } : { ...folder, childIds };
      }),
    }));
    setMenu(null);
  }

  function moveToDesktop(itemId: string) {
    setLayout((current) => ({
      order: [...current.order.filter((id) => id !== itemId), itemId],
      folders: current.folders.map((folder) => ({ ...folder, childIds: folder.childIds.filter((id) => id !== itemId) })),
    }));
    setMenu(null);
  }

  function createFolderWith(itemId: string) {
    if (itemId.startsWith("folder:")) return;
    const folderId = `folder:${crypto.randomUUID()}`;
    setLayout((current) => ({
      order: current.order.includes(itemId)
        ? current.order.map((id) => (id === itemId ? folderId : id))
        : [...rootIds.current.filter((id) => id !== itemId), folderId],
      folders: [
        ...current.folders.map((folder) => ({ ...folder, childIds: folder.childIds.filter((id) => id !== itemId) })),
        { id: folderId, title: "Folder", childIds: [itemId] },
      ],
    }));
    setOpenFolderId(folderId);
    setMenu(null);
  }

  function createFolderFromDrop(itemId: string, targetId: string) {
    if (itemId === targetId || itemId.startsWith("folder:") || targetId.startsWith("folder:")) return;
    const folderId = `folder:${crypto.randomUUID()}`;
    const rootOrder = rootIds.current;
    const targetIndex = Math.max(0, rootOrder.indexOf(targetId));
    const nextOrder = rootOrder.filter((id) => id !== itemId && id !== targetId);
    nextOrder.splice(Math.min(targetIndex, nextOrder.length), 0, folderId);
    setLayout((current) => ({
      order: nextOrder,
      folders: [
        ...current.folders.map((folder) => ({
          ...folder,
          childIds: folder.childIds.filter((id) => id !== itemId && id !== targetId),
        })),
        { id: folderId, title: "Folder", childIds: [targetId, itemId] },
      ],
    }));
    setOpenFolderId(folderId);
    setMenu(null);
  }

  function renameFolder(folderId: string) {
    const folder = layout.folders.find((item) => item.id === folderId);
    const title = window.prompt("Folder name", folder?.title || "Folder");
    if (!title) return;
    setLayout((current) => ({
      ...current,
      folders: current.folders.map((item) => (item.id === folderId ? { ...item, title: title.trim() || "Folder" } : item)),
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
    sendCommand<WebAppRecord[]>("webapps.delete", { startUrl: entry.app.startUrl })
      .then(setApps)
      .catch((deleteError) => setError(deleteError instanceof Error ? deleteError.message : "Unable to delete WebApp."));
    setLayout((current) => ({
      order: current.order.filter((id) => id !== itemId),
      folders: current.folders.map((folder) => ({ ...folder, childIds: folder.childIds.filter((id) => id !== itemId) })),
    }));
    setMenu(null);
  }

  function onDrop(event: React.DragEvent<HTMLElement>, targetId: string, targetFolderId?: string) {
    event.preventDefault();
    const dragged = dragRef.current;
    const placement = dropPlacementFor(event.currentTarget, event.clientX);
    dragRef.current = null;
    if (!dragged || dragged.itemId === targetId) return;
    if (targetId.startsWith("folder:") && !dragged.itemId.startsWith("folder:")) {
      moveToFolder(dragged.itemId, targetId);
    } else if (!targetFolderId && !dragged.itemId.startsWith("folder:") && placement === "inside") {
      createFolderFromDrop(dragged.itemId, targetId);
    } else if (dragged.folderId && dragged.folderId === targetFolderId) {
      moveInsideFolderRelative(dragged.folderId, dragged.itemId, targetId, placement);
    } else if (dragged.folderId && !targetFolderId) {
      moveToDesktopNear(dragged.itemId, targetId, placement);
    } else if (!targetFolderId) {
      moveRootRelative(dragged.itemId, targetId, placement);
    }
  }

  const openFolder = openFolderId ? folderEntries.get(openFolderId) : undefined;

  return (
    <main className="desktop-page">
      <div className="desktop-commands">
        {editMode && <button type="button" onClick={() => setEditMode(false)}>Done</button>}
        <button type="button" onClick={() => chrome.tabs.create({ url: chrome.runtime.getURL("options.html") })}>Settings</button>
        <button type="button" onClick={() => sendCommand("sync.run").catch((syncError) => setError(syncError instanceof Error ? syncError.message : "Sync failed."))}>Sync</button>
      </div>

      <section className="desktop-grid" aria-label="Hyper Browser desktop">
        {loading && <div className="desktop-status">Loading desktop...</div>}
        {error && <button className="desktop-status" type="button" onClick={() => chrome.tabs.create({ url: chrome.runtime.getURL("options.html") })}>{error}</button>}
        {desktopEntries.map((entry) => (
          <DesktopTile
            entry={entry}
            editMode={editMode}
            folderId={undefined}
            key={entry.id}
            onClick={clickEntry}
            onContextMenu={openContextMenu}
            onPointerDown={startLongPress}
            onPointerUp={stopLongPress}
            onDragStart={(itemId, folderId) => {
              dragRef.current = { itemId, folderId };
            }}
            onDrop={onDrop}
          />
        ))}
      </section>

      {openFolder && (
        <div className="desktop-folder-scrim" onClick={() => setOpenFolderId(null)}>
          <section className="desktop-folder" role="dialog" aria-modal="true" aria-label={openFolder.title} onClick={(event) => event.stopPropagation()}>
            <header className="desktop-folder-header">
              <h2>{openFolder.title}</h2>
              <button type="button" onClick={() => setOpenFolderId(null)}>Close</button>
            </header>
            <div className="desktop-folder-grid">
              {openFolder.children.length === 0 ? (
                <div className="desktop-status">This folder is empty.</div>
              ) : openFolder.children.map((entry) => (
                <DesktopTile
                  entry={entry}
                  editMode={editMode}
                  folderId={openFolder.id}
                  key={entry.id}
                  onClick={clickEntry}
                  onContextMenu={openContextMenu}
                  onPointerDown={startLongPress}
                  onPointerUp={stopLongPress}
                  onDragStart={(itemId, folderId) => {
                    dragRef.current = { itemId, folderId };
                  }}
                  onDrop={onDrop}
                />
              ))}
            </div>
          </section>
        </div>
      )}

      {menu && (
        <DesktopMenu
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
          onStartEditMode={() => {
            setEditMode(true);
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

function DesktopTile(props: {
  entry: LauncherEntry;
  editMode: boolean;
  folderId?: string;
  onClick: (entry: LauncherEntry) => void;
  onContextMenu: (event: React.MouseEvent<HTMLElement>, itemId: string, folderId?: string) => void;
  onPointerDown: (event: React.PointerEvent<HTMLElement>, itemId: string, folderId?: string) => void;
  onPointerUp: () => void;
  onDragStart: (itemId: string, folderId?: string) => void;
  onDrop: (event: React.DragEvent<HTMLElement>, itemId: string, folderId?: string) => void;
}) {
  return (
    <button
      className={`desktop-tile${props.editMode ? " editing" : ""}`}
      type="button"
      draggable={props.editMode}
      title={props.entry.title}
      onClick={() => props.onClick(props.entry)}
      onContextMenu={(event) => props.onContextMenu(event, props.entry.id, props.folderId)}
      onPointerDown={(event) => props.onPointerDown(event, props.entry.id, props.folderId)}
      onPointerUp={props.onPointerUp}
      onPointerLeave={props.onPointerUp}
      onDragStart={() => props.onDragStart(props.entry.id, props.folderId)}
      onDragOver={(event) => event.preventDefault()}
      onDrop={(event) => props.onDrop(event, props.entry.id, props.folderId)}
    >
      {props.entry.kind === "folder" ? (
        <span className="desktop-icon desktop-folder-icon" aria-hidden="true">
          {props.entry.children.slice(0, 4).map((child) => (
            <span className="desktop-folder-dot" key={child.id} style={{ background: child.kind === "folder" ? "#dfe5eb" : child.color }}>
              {child.kind === "folder" ? "" : child.mark.slice(0, 1)}
            </span>
          ))}
        </span>
      ) : (
        <span className={props.entry.kind === "app" && props.entry.app.iconDataUrl ? "desktop-icon image" : "desktop-icon"} style={{ background: props.entry.color }} aria-hidden="true">
          {props.entry.kind === "app" && props.entry.app.iconDataUrl ? <img src={props.entry.app.iconDataUrl} alt="" /> : props.entry.mark}
        </span>
      )}
      <span className="desktop-label">{props.entry.title}</span>
    </button>
  );
}

function DesktopMenu(props: {
  item?: LauncherEntry;
  sourceFolderId?: string;
  folders: FolderEntry[];
  x: number;
  y: number;
  onClose: () => void;
  onOpen: (item: LauncherEntry) => void;
  onStartEditMode: () => void;
  onCreateFolder: (itemId: string) => void;
  onMoveToFolder: (itemId: string, folderId: string) => void;
  onMoveToDesktop: (itemId: string) => void;
  onRenameFolder: (folderId: string) => void;
  onUnpackFolder: (folderId: string) => void;
  onDeleteApp: (itemId: string) => void;
}) {
  if (!props.item) return null;
  const top = Math.min(props.y, window.innerHeight - 270);
  const left = Math.min(props.x, window.innerWidth - 240);
  return (
    <div className="desktop-menu-scrim" onClick={props.onClose}>
      <div className="desktop-menu" role="menu" style={{ top, left }} onClick={(event) => event.stopPropagation()}>
        <div className="desktop-menu-title">{props.item.title}</div>
        <button type="button" role="menuitem" onClick={() => props.onOpen(props.item!)}>Open</button>
        <button type="button" role="menuitem" onClick={props.onStartEditMode}>Edit Desktop</button>
        {props.item.kind === "folder" ? (
          <>
            <button type="button" role="menuitem" onClick={() => props.onRenameFolder(props.item!.id)}>Rename folder</button>
            <button type="button" role="menuitem" onClick={() => props.onUnpackFolder(props.item!.id)}>Move items out</button>
          </>
        ) : (
          <>
            <button type="button" role="menuitem" onClick={() => props.onCreateFolder(props.item!.id)}>New folder</button>
            {props.sourceFolderId && (
              <button type="button" role="menuitem" onClick={() => props.onMoveToDesktop(props.item!.id)}>Move to desktop</button>
            )}
            {props.folders.filter((folder) => folder.id !== props.item?.id).map((folder) => (
              <button type="button" role="menuitem" key={folder.id} onClick={() => props.onMoveToFolder(props.item!.id, folder.id)}>
                Move to {folder.title}
              </button>
            ))}
            {props.item.kind === "app" && (
              <button className="danger" type="button" role="menuitem" onClick={() => props.onDeleteApp(props.item!.id)}>Delete</button>
            )}
          </>
        )}
      </div>
    </div>
  );
}

async function loadLayout(): Promise<LauncherLayout> {
  const stored = await chrome.storage.local.get(LAYOUT_STORAGE_KEY);
  const value = stored[LAYOUT_STORAGE_KEY] as Partial<LauncherLayout> | undefined;
  return {
    order: Array.isArray(value?.order) ? value.order.filter((id): id is string => typeof id === "string") : [],
    folders: Array.isArray(value?.folders)
      ? value.folders
        .filter((folder): folder is FolderLayout => typeof folder?.id === "string" && Array.isArray(folder.childIds))
        .map((folder) => ({
          id: folder.id,
          title: typeof folder.title === "string" ? folder.title : "Folder",
          childIds: folder.childIds.filter((id): id is string => typeof id === "string"),
        }))
      : [],
  };
}

function dropPlacementFor(element: HTMLElement, x: number): DropPlacement {
  const rect = element.getBoundingClientRect();
  const ratio = rect.width > 0 ? (x - rect.left) / rect.width : 0.5;
  if (ratio < 0.24) return "before";
  if (ratio > 0.76) return "after";
  return "inside";
}

function insertRelative(ids: string[], itemId: string, targetId: string, placement: DropPlacement): string[] {
  const next = ids.filter((id) => id !== itemId);
  const index = next.indexOf(targetId);
  if (index < 0) return [...next, itemId];
  next.splice(placement === "after" ? index + 1 : index, 0, itemId);
  return next;
}

function appInitial(name: string): string {
  return Array.from(name.trimStart())[0]?.toLocaleUpperCase() || "A";
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
    <DesktopPage />
  </React.StrictMode>
);
