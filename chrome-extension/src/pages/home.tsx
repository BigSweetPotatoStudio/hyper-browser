import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  closestCenter,
  DndContext,
  DragEndEvent,
  DragOverlay,
  DragStartEvent,
  pointerWithin,
  PointerSensor,
  useSensor,
  useSensors,
  useDroppable,
  type ClientRect,
  type CollisionDetection,
} from "@dnd-kit/core";
import {
  rectSortingStrategy,
  SortableContext,
  useSortable,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import "../styles.css";
import type { WebAppRecord } from "../types";
import { sendCommand } from "./bridge";

const LAYOUT_STORAGE_KEY = "launcherLayout";
const LONG_PRESS_MS = 540;
const DESKTOP_DROP_ID = "drop:desktop";
const DOCK_ENTRY_IDS = ["system:bookmarks", "system:history", "system:extensions"] as const;
const DOCK_ENTRY_ID_SET = new Set<string>(DOCK_ENTRY_IDS);

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
  const [activeId, setActiveId] = useState<string | null>(null);
  const longPressTimer = useRef<number | null>(null);
  const suppressClickForId = useRef<string | null>(null);
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));

  useEffect(() => {
    let cancelled = false;
    async function loadDesktop() {
      try {
        const nextLayout = await loadLayout();
        if (!cancelled) setLayout(nextLayout);
      } catch (loadError) {
        if (!cancelled) setError(loadError instanceof Error ? loadError.message : "Unable to load desktop layout.");
      }

      try {
        const records = await sendCommand<WebAppRecord[]>("webapps.list");
        if (!cancelled) {
          setApps(records);
          setError("");
        }
      } catch (loadError) {
        if (!cancelled) setError(loadError instanceof Error ? loadError.message : "Unable to load WebApps.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    loadDesktop();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (loading) return;
    chrome.storage.local.set({ [LAYOUT_STORAGE_KEY]: layout }).catch(console.error);
  }, [layout, loading]);

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
    childIds: folder.childIds.filter((id) => availableEntries.has(id) && !DOCK_ENTRY_ID_SET.has(id)),
    children: folder.childIds
      .map((id) => availableEntries.get(id))
      .filter((item): item is SystemEntry | AppEntry => !!item && !DOCK_ENTRY_ID_SET.has(item.id))
  })), [availableEntries, layout.folders]);

  const dockEntries = useMemo(() => DOCK_ENTRY_IDS
    .map((id) => availableEntries.get(id))
    .filter((item): item is SystemEntry | AppEntry => !!item), [availableEntries]);

  const folderEntries = useMemo(() => new Map(folders.map((folder) => [folder.id, folder] as const)), [folders]);
  const containedIds = useMemo(() => new Set(folders.flatMap((folder) => folder.childIds)), [folders]);

  const desktopEntries = useMemo(() => {
    const allIds = [...availableEntries.keys(), ...folderEntries.keys()];
    const visibleIds = allIds.filter((id) => !containedIds.has(id) && !DOCK_ENTRY_ID_SET.has(id));
    const ordered = [
      ...layout.order.filter((id) => visibleIds.includes(id)),
      ...visibleIds.filter((id) => !layout.order.includes(id)),
    ];
    return ordered
      .map((id) => folderEntries.get(id) || availableEntries.get(id))
      .filter((item): item is LauncherEntry => !!item);
  }, [availableEntries, containedIds, folderEntries, layout.order]);

  const desktopIds = useMemo(() => desktopEntries.map((entry) => entry.id), [desktopEntries]);
  const openFolder = openFolderId ? folderEntries.get(openFolderId) : undefined;
  const openFolderIds = openFolder?.childIds || [];
  const activeEntry = activeId ? resolveEntry(activeId) : undefined;

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
    clearLongPress();
    const x = event.clientX;
    const y = event.clientY;
    longPressTimer.current = window.setTimeout(() => {
      longPressTimer.current = null;
      if (editMode) return;
      suppressClickForId.current = itemId;
      setMenu((current) => current || { itemId, folderId, x, y });
    }, LONG_PRESS_MS);
  }

  function clearLongPress() {
    if (longPressTimer.current === null) return;
    window.clearTimeout(longPressTimer.current);
    longPressTimer.current = null;
  }

  function openContextMenu(event: React.MouseEvent<HTMLElement>, itemId: string, folderId?: string) {
    event.preventDefault();
    clearLongPress();
    if (editMode) return;
    setMenu({ itemId, folderId, x: event.clientX, y: event.clientY });
  }

  function closeMenu() {
    suppressClickForId.current = null;
    setMenu(null);
  }

  function clickEntry(entry: LauncherEntry) {
    clearLongPress();
    if (suppressClickForId.current === entry.id) {
      suppressClickForId.current = null;
      return;
    }
    if (editMode) {
      if (entry.kind === "folder") setOpenFolderId(entry.id);
      return;
    }
    openEntry(entry);
  }

  function handleDragStart(event: DragStartEvent) {
    if (!editMode) return;
    closeMenu();
    const draggedId = String(event.active.id);
    suppressClickForId.current = draggedId;
    setActiveId(draggedId);
  }

  function handleDragEnd(event: DragEndEvent) {
    const draggedId = String(event.active.id);
    const targetId = event.over ? String(event.over.id) : "";
    const sourceFolderId = stringData(event.active.data.current?.folderId);
    const targetFolderId = stringData(event.over?.data.current?.folderId);
    const placement = event.over ? dropPlacementFor(event.active.rect.current.translated, event.over.rect) : "inside";
    setActiveId(null);
    if (!targetId || draggedId === targetId) return;
    handleDrop(draggedId, sourceFolderId, targetId, targetFolderId, placement);
  }

  function handleDrop(itemId: string, sourceFolderId: string | undefined, targetId: string, targetFolderId: string | undefined, placement: DropPlacement) {
    if (targetId === DESKTOP_DROP_ID) {
      if (sourceFolderId) {
        moveToDesktopEnd(itemId);
      } else {
        moveRootToEnd(itemId);
      }
      return;
    }
    const targetEntry = resolveEntry(targetId);
    if (!targetEntry) return;
    if (targetEntry.kind === "folder") {
      if (!itemId.startsWith("folder:")) moveToFolder(itemId, targetEntry.id);
      return;
    }
    if (!targetFolderId && !itemId.startsWith("folder:") && placement === "inside") {
      createFolderFromDrop(itemId, targetId);
      return;
    }
    if (sourceFolderId && targetFolderId === sourceFolderId) {
      moveInsideFolderRelative(sourceFolderId, itemId, targetId, placement);
      return;
    }
    if (sourceFolderId && !targetFolderId) {
      moveToDesktopNear(itemId, targetId, placement);
      return;
    }
    if (!sourceFolderId && !targetFolderId) {
      moveRootRelative(itemId, targetId, placement);
      return;
    }
    if (!sourceFolderId && targetFolderId) {
      moveToFolder(itemId, targetFolderId);
    }
  }

  function moveRootRelative(itemId: string, targetId: string, placement: DropPlacement) {
    setLayout((current) => ({ ...current, order: insertRelative(desktopIds, itemId, targetId, placement) }));
  }

  function moveRootToEnd(itemId: string) {
    setLayout((current) => ({ ...current, order: [...desktopIds.filter((id) => id !== itemId), itemId] }));
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
      order: insertRelative(desktopIds, itemId, targetId, placement),
      folders: current.folders.map((folder) => ({ ...folder, childIds: folder.childIds.filter((id) => id !== itemId) })),
    }));
    setOpenFolderId(null);
  }

  function moveToDesktopEnd(itemId: string) {
    setLayout((current) => ({
      order: [...desktopIds.filter((id) => id !== itemId), itemId],
      folders: current.folders.map((folder) => ({ ...folder, childIds: folder.childIds.filter((id) => id !== itemId) })),
    }));
    setOpenFolderId(null);
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
    closeMenu();
  }

  function moveToDesktop(itemId: string) {
    setLayout((current) => ({
      order: [...current.order.filter((id) => id !== itemId), itemId],
      folders: current.folders.map((folder) => ({ ...folder, childIds: folder.childIds.filter((id) => id !== itemId) })),
    }));
    closeMenu();
  }

  function createFolderWith(itemId: string) {
    if (itemId.startsWith("folder:")) return;
    const folderId = `folder:${crypto.randomUUID()}`;
    setLayout((current) => ({
      order: current.order.includes(itemId)
        ? current.order.map((id) => (id === itemId ? folderId : id))
        : [...desktopIds.filter((id) => id !== itemId), folderId],
      folders: [
        ...current.folders.map((folder) => ({ ...folder, childIds: folder.childIds.filter((id) => id !== itemId) })),
        { id: folderId, title: "Folder", childIds: [itemId] },
      ],
    }));
    setOpenFolderId(folderId);
    closeMenu();
  }

  function createFolderFromDrop(itemId: string, targetId: string) {
    if (itemId === targetId || itemId.startsWith("folder:") || targetId.startsWith("folder:")) return;
    const folderId = `folder:${crypto.randomUUID()}`;
    const targetIndex = Math.max(0, desktopIds.indexOf(targetId));
    const nextOrder = desktopIds.filter((id) => id !== itemId && id !== targetId);
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
    closeMenu();
  }

  function renameFolder(folderId: string) {
    const folder = layout.folders.find((item) => item.id === folderId);
    const title = window.prompt("Folder name", folder?.title || "Folder");
    if (!title) return;
    setLayout((current) => ({
      ...current,
      folders: current.folders.map((item) => (item.id === folderId ? { ...item, title: title.trim() || "Folder" } : item)),
    }));
    closeMenu();
  }

  function unpackFolder(folderId: string) {
    const folder = layout.folders.find((item) => item.id === folderId);
    if (!folder) return;
    setLayout((current) => ({
      order: current.order.flatMap((id) => (id === folderId ? folder.childIds : [id])),
      folders: current.folders.filter((item) => item.id !== folderId),
    }));
    setOpenFolderId(null);
    closeMenu();
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
    closeMenu();
  }

  return (
    <main className="desktop-page">
      <div className="desktop-commands">
        {editMode && <button type="button" onClick={() => setEditMode(false)}>Done</button>}
        <button type="button" onClick={() => chrome.tabs.create({ url: chrome.runtime.getURL("options.html") })}>Settings</button>
        <button type="button" onClick={() => sendCommand("sync.run").catch((syncError) => setError(syncError instanceof Error ? syncError.message : "Sync failed."))}>Sync</button>
      </div>

      <DndContext
        sensors={sensors}
        collisionDetection={desktopCollisionDetection}
        onDragCancel={() => setActiveId(null)}
        onDragEnd={handleDragEnd}
        onDragStart={handleDragStart}
      >
        <SortableContext items={desktopIds} strategy={rectSortingStrategy}>
          <DesktopDropGrid enabled={editMode && !openFolder}>
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
                onPointerEnd={clearLongPress}
              />
            ))}
          </DesktopDropGrid>
        </SortableContext>

        {openFolder && (
          <DesktopFolderScrim editMode={editMode} onClose={() => setOpenFolderId(null)}>
            <section className="desktop-folder" role="dialog" aria-modal="true" aria-label={openFolder.title} onClick={(event) => event.stopPropagation()}>
              <header className="desktop-folder-header">
                <h2>{openFolder.title}</h2>
                <button type="button" onClick={() => setOpenFolderId(null)}>Close</button>
              </header>
              <SortableContext items={openFolderIds} strategy={rectSortingStrategy}>
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
                      onPointerEnd={clearLongPress}
                    />
                  ))}
                </div>
              </SortableContext>
            </section>
          </DesktopFolderScrim>
        )}

        <DragOverlay>
          {activeEntry && (
            <div className="desktop-drag-overlay">
              <DesktopTileVisual entry={activeEntry} />
            </div>
          )}
        </DragOverlay>
      </DndContext>

      <DesktopDock entries={dockEntries} editMode={editMode} onClick={clickEntry} />

      {menu && (
        <DesktopMenu
          folders={folders}
          item={resolveEntry(menu.itemId)}
          sourceFolderId={menu.folderId}
          x={menu.x}
          y={menu.y}
          onClose={closeMenu}
          onOpen={(item) => {
            openEntry(item);
            closeMenu();
          }}
          onStartEditMode={() => {
            setEditMode(true);
            closeMenu();
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

function DesktopDock(props: { entries: Array<SystemEntry | AppEntry>; editMode: boolean; onClick: (entry: SystemEntry | AppEntry) => void }) {
  return (
    <nav className="desktop-dock" aria-label="Dock">
      {props.entries.map((entry) => (
        <button
          className={`desktop-dock-tile${props.editMode ? " editing" : ""}`}
          key={entry.id}
          title={entry.title}
          type="button"
          aria-label={entry.title}
          onClick={() => props.onClick(entry)}
        >
          <DesktopIconVisual entry={entry} />
        </button>
      ))}
    </nav>
  );
}

function DesktopDropGrid(props: { children: React.ReactNode; enabled: boolean }) {
  const { setNodeRef, isOver } = useDroppable({
    id: DESKTOP_DROP_ID,
    data: { kind: "desktop" },
    disabled: !props.enabled,
  });
  return (
    <section className={`desktop-grid${isOver ? " desktop-grid-over" : ""}`} aria-label="Hyper Browser desktop" ref={setNodeRef}>
      {props.children}
    </section>
  );
}

function DesktopFolderScrim(props: { children: React.ReactNode; editMode: boolean; onClose: () => void }) {
  const { setNodeRef } = useDroppable({
    id: DESKTOP_DROP_ID,
    data: { kind: "desktop" },
    disabled: !props.editMode,
  });
  return (
    <div className="desktop-folder-scrim" onClick={props.onClose} ref={setNodeRef}>
      {props.children}
    </div>
  );
}

function DesktopTile(props: {
  entry: LauncherEntry;
  editMode: boolean;
  folderId?: string;
  onClick: (entry: LauncherEntry) => void;
  onContextMenu: (event: React.MouseEvent<HTMLElement>, itemId: string, folderId?: string) => void;
  onPointerDown: (event: React.PointerEvent<HTMLElement>, itemId: string, folderId?: string) => void;
  onPointerEnd: () => void;
}) {
  const {
    attributes,
    isDragging,
    listeners,
    setNodeRef,
    transform,
    transition,
  } = useSortable({
    id: props.entry.id,
    data: { folderId: props.folderId || "", kind: props.entry.kind },
    disabled: !props.editMode,
  });
  const style = {
    opacity: isDragging ? 0.34 : undefined,
    transform: CSS.Transform.toString(transform),
    transition,
  };
  const dragListeners = props.editMode ? listeners : undefined;
  const dragAttributes = props.editMode ? attributes : undefined;

  return (
    <button
      className={`desktop-tile${props.editMode ? " editing" : ""}`}
      data-launcher-id={props.entry.id}
      ref={setNodeRef}
      style={style}
      title={props.entry.title}
      type="button"
      onClick={() => props.onClick(props.entry)}
      onContextMenu={(event) => props.onContextMenu(event, props.entry.id, props.folderId)}
      onPointerDown={props.editMode ? undefined : (event) => props.onPointerDown(event, props.entry.id, props.folderId)}
      onPointerUp={props.editMode ? undefined : props.onPointerEnd}
      onPointerLeave={props.editMode ? undefined : props.onPointerEnd}
      onPointerCancel={props.editMode ? undefined : props.onPointerEnd}
      {...dragAttributes}
      {...dragListeners}
    >
      <DesktopTileVisual entry={props.entry} />
    </button>
  );
}

function DesktopTileVisual({ entry }: { entry: LauncherEntry }) {
  return (
    <>
      {entry.kind === "folder" ? (
        <span className="desktop-icon desktop-folder-icon" aria-hidden="true">
          {entry.children.slice(0, 4).map((child) => (
            <span className="desktop-folder-dot" key={child.id} style={{ background: child.kind === "folder" ? "#dfe5eb" : child.color }}>
              {child.kind === "folder" ? "" : child.mark.slice(0, 1)}
            </span>
          ))}
        </span>
      ) : (
        <DesktopIconVisual entry={entry} />
      )}
      <span className="desktop-label">{entry.title}</span>
    </>
  );
}

function DesktopIconVisual({ entry }: { entry: SystemEntry | AppEntry }) {
  return (
    <span className={entry.kind === "app" && entry.app.iconDataUrl ? "desktop-icon image" : "desktop-icon"} style={{ background: entry.color }} aria-hidden="true">
      {entry.kind === "app" && entry.app.iconDataUrl ? <img src={entry.app.iconDataUrl} alt="" /> : entry.mark}
    </span>
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
    order: Array.isArray(value?.order)
      ? value.order.filter((id): id is string => typeof id === "string" && !DOCK_ENTRY_ID_SET.has(id))
      : [],
    folders: Array.isArray(value?.folders)
      ? value.folders
        .filter((folder): folder is FolderLayout => typeof folder?.id === "string" && Array.isArray(folder.childIds))
        .map((folder) => ({
          id: folder.id,
          title: typeof folder.title === "string" ? folder.title : "Folder",
          childIds: folder.childIds.filter((id): id is string => typeof id === "string" && !DOCK_ENTRY_ID_SET.has(id)),
        }))
      : [],
  };
}

function dropPlacementFor(activeRect: ClientRect | null, overRect: ClientRect): DropPlacement {
  if (!activeRect) return "inside";
  const activeCenterX = activeRect.left + activeRect.width / 2;
  const ratio = overRect.width > 0 ? (activeCenterX - overRect.left) / overRect.width : 0.5;
  if (ratio < 0.24) return "before";
  if (ratio > 0.76) return "after";
  return "inside";
}

const desktopCollisionDetection: CollisionDetection = (args) => {
  const pointerCollisions = pointerWithin(args);
  const pointerTargets = pointerCollisions.filter((collision) => collision.id !== DESKTOP_DROP_ID);
  if (pointerTargets.length > 0) return pointerTargets;

  const desktopCollision = pointerCollisions.find((collision) => collision.id === DESKTOP_DROP_ID);
  if (desktopCollision) return [desktopCollision];

  const centerCollisions = closestCenter(args);
  const centerTargets = centerCollisions.filter((collision) => collision.id !== DESKTOP_DROP_ID);
  return centerTargets.length > 0 ? centerTargets : centerCollisions;
};

function insertRelative(ids: string[], itemId: string, targetId: string, placement: DropPlacement): string[] {
  const next = ids.filter((id) => id !== itemId);
  const index = next.indexOf(targetId);
  if (index < 0) return [...next, itemId];
  next.splice(placement === "after" ? index + 1 : index, 0, itemId);
  return next;
}

function stringData(value: unknown): string | undefined {
  return typeof value === "string" && value ? value : undefined;
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
