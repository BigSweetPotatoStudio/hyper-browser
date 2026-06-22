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
const DOCK_DROP_ID = "drop:dock";
const DEFAULT_DOCK_ENTRY_IDS = ["system:bookmarks", "system:history", "system:extensions"] as const;
const DROP_ZONE_IDS = new Set<string>([DESKTOP_DROP_ID, DOCK_DROP_ID]);

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
  dock: string[];
  folders: FolderLayout[];
};

type LauncherContainer = "desktop" | "dock" | "folder";

type MenuState = {
  itemId: string;
  sourceContainer: LauncherContainer;
  folderId?: string;
  x: number;
  y: number;
};

type DropPlacement = "before" | "inside" | "after";

function DesktopPage() {
  const [apps, setApps] = useState<WebAppRecord[]>([]);
  const [layout, setLayout] = useState<LauncherLayout>({ order: [], dock: [...DEFAULT_DOCK_ENTRY_IDS], folders: [] });
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
        if (!cancelled && !isWebDavConfigError(loadError)) {
          setError(loadError instanceof Error ? loadError.message : "Unable to load WebApps.");
        }
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
    childIds: folder.childIds.filter((id) => availableEntries.has(id)),
    children: folder.childIds
      .map((id) => availableEntries.get(id))
      .filter((item): item is SystemEntry | AppEntry => !!item)
  })), [availableEntries, layout.folders]);

  const folderEntries = useMemo(() => new Map(folders.map((folder) => [folder.id, folder] as const)), [folders]);
  const containedIds = useMemo(() => new Set(folders.flatMap((folder) => folder.childIds)), [folders]);
  const dockIds = useMemo(() => layout.dock.filter((id) => !containedIds.has(id) && (availableEntries.has(id) || folderEntries.has(id))), [availableEntries, containedIds, folderEntries, layout.dock]);
  const dockEntries = useMemo(() => dockIds
    .map((id) => folderEntries.get(id) || availableEntries.get(id))
    .filter((item): item is LauncherEntry => !!item), [availableEntries, dockIds, folderEntries]);

  const desktopEntries = useMemo(() => {
    const allIds = [...availableEntries.keys(), ...folderEntries.keys()];
    const visibleIds = allIds.filter((id) => !containedIds.has(id) && !dockIds.includes(id));
    const ordered = [
      ...layout.order.filter((id) => visibleIds.includes(id)),
      ...visibleIds.filter((id) => !layout.order.includes(id)),
    ];
    return ordered
      .map((id) => folderEntries.get(id) || availableEntries.get(id))
      .filter((item): item is LauncherEntry => !!item);
  }, [availableEntries, containedIds, dockIds, folderEntries, layout.order]);

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

  function startLongPress(event: React.PointerEvent<HTMLElement>, itemId: string, sourceContainer: LauncherContainer, folderId?: string) {
    if (editMode) return;
    if (event.button !== 0) return;
    clearLongPress();
    const x = event.clientX;
    const y = event.clientY;
    longPressTimer.current = window.setTimeout(() => {
      longPressTimer.current = null;
      if (editMode) return;
      suppressClickForId.current = itemId;
      setMenu((current) => current || { itemId, sourceContainer, folderId, x, y });
    }, LONG_PRESS_MS);
  }

  function clearLongPress() {
    if (longPressTimer.current === null) return;
    window.clearTimeout(longPressTimer.current);
    longPressTimer.current = null;
  }

  function openContextMenu(event: React.MouseEvent<HTMLElement>, itemId: string, sourceContainer: LauncherContainer, folderId?: string) {
    event.preventDefault();
    clearLongPress();
    if (editMode) return;
    setMenu({ itemId, sourceContainer, folderId, x: event.clientX, y: event.clientY });
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
    const sourceContainer = containerData(event.active.data.current?.container);
    const targetFolderId = stringData(event.over?.data.current?.folderId);
    const targetContainer = containerData(event.over?.data.current?.container);
    const placement = event.over ? dropPlacementFor(event.active.rect.current.translated, event.over.rect) : "inside";
    setActiveId(null);
    if (!targetId || draggedId === targetId) return;
    handleDrop(draggedId, sourceContainer, sourceFolderId, targetId, targetContainer, targetFolderId, placement);
  }

  function handleDrop(
    itemId: string,
    sourceContainer: LauncherContainer | undefined,
    sourceFolderId: string | undefined,
    targetId: string,
    targetContainer: LauncherContainer | undefined,
    targetFolderId: string | undefined,
    placement: DropPlacement,
  ) {
    if (targetId === DESKTOP_DROP_ID) {
      moveToDesktopEnd(itemId);
      return;
    }
    if (targetId === DOCK_DROP_ID) {
      moveToDockEnd(itemId);
      return;
    }
    const targetEntry = resolveEntry(targetId);
    if (!targetEntry) return;
    if (targetEntry.kind === "folder") {
      if (!itemId.startsWith("folder:")) moveToFolder(itemId, targetEntry.id);
      return;
    }
    if (targetContainer === "dock") {
      if (!itemId.startsWith("folder:") && placement === "inside") {
        createFolderFromDrop(itemId, targetId, "dock");
      } else {
        moveToDockNear(itemId, targetId, placement);
      }
      return;
    }
    if (sourceFolderId && targetFolderId === sourceFolderId) {
      moveInsideFolderRelative(sourceFolderId, itemId, targetId, placement);
      return;
    }
    if (!targetFolderId && !itemId.startsWith("folder:") && placement === "inside") {
      createFolderFromDrop(itemId, targetId, "desktop");
      return;
    }
    if (sourceContainer === "folder" && !targetFolderId) {
      moveToDesktopNear(itemId, targetId, placement);
      return;
    }
    if (!targetFolderId) {
      moveToDesktopNear(itemId, targetId, placement);
      return;
    }
    if (targetFolderId) {
      moveToFolder(itemId, targetFolderId);
    }
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
      dock: current.dock.filter((id) => id !== itemId),
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    setOpenFolderId(null);
  }

  function moveToDesktopEnd(itemId: string) {
    setLayout((current) => ({
      order: [...desktopIds.filter((id) => id !== itemId), itemId],
      dock: current.dock.filter((id) => id !== itemId),
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    setOpenFolderId(null);
  }

  function moveToDockNear(itemId: string, targetId: string, placement: DropPlacement) {
    setLayout((current) => ({
      order: current.order.filter((id) => id !== itemId),
      dock: insertRelative(dockIds, itemId, targetId, placement),
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    setOpenFolderId(null);
  }

  function moveToDockEnd(itemId: string) {
    setLayout((current) => ({
      order: current.order.filter((id) => id !== itemId),
      dock: [...dockIds.filter((id) => id !== itemId), itemId],
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    setOpenFolderId(null);
  }

  function moveToFolder(itemId: string, folderId: string) {
    if (itemId.startsWith("folder:")) return;
    setLayout((current) => ({
      order: current.order.filter((id) => id !== itemId),
      dock: current.dock.filter((id) => id !== itemId),
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
      dock: current.dock.filter((id) => id !== itemId),
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    closeMenu();
  }

  function moveToDock(itemId: string) {
    setLayout((current) => ({
      order: current.order.filter((id) => id !== itemId),
      dock: [...current.dock.filter((id) => id !== itemId), itemId],
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    setOpenFolderId(null);
    closeMenu();
  }

  function createFolderWith(itemId: string, sourceContainer: LauncherContainer) {
    if (itemId.startsWith("folder:")) return;
    const folderId = `folder:${crypto.randomUUID()}`;
    setLayout((current) => ({
      order: sourceContainer === "dock"
        ? current.order.filter((id) => id !== itemId)
        : (current.order.includes(itemId)
          ? current.order.map((id) => (id === itemId ? folderId : id))
          : [...desktopIds.filter((id) => id !== itemId), folderId]),
      dock: sourceContainer === "dock"
        ? current.dock.map((id) => (id === itemId ? folderId : id))
        : current.dock.filter((id) => id !== itemId),
      folders: [
        ...removeChildFromFolders(current.folders, itemId),
        { id: folderId, title: "Folder", childIds: [itemId] },
      ],
    }));
    setOpenFolderId(folderId);
    closeMenu();
  }

  function createFolderFromDrop(itemId: string, targetId: string, targetContainer: "desktop" | "dock") {
    if (itemId === targetId || itemId.startsWith("folder:") || targetId.startsWith("folder:")) return;
    const folderId = `folder:${crypto.randomUUID()}`;
    const targetIds = targetContainer === "dock" ? dockIds : desktopIds;
    const targetIndex = Math.max(0, targetIds.indexOf(targetId));
    const nextIds = targetIds.filter((id) => id !== itemId && id !== targetId);
    nextIds.splice(Math.min(targetIndex, nextIds.length), 0, folderId);
    setLayout((current) => ({
      order: targetContainer === "desktop" ? nextIds : current.order.filter((id) => id !== itemId && id !== targetId),
      dock: targetContainer === "dock" ? nextIds : current.dock.filter((id) => id !== itemId && id !== targetId),
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
      dock: current.dock.flatMap((id) => (id === folderId ? folder.childIds : [id])),
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
      dock: current.dock.filter((id) => id !== itemId),
      folders: removeChildFromFolders(current.folders, itemId),
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
      {error && (
        <button className="desktop-notice" type="button" onClick={() => chrome.tabs.create({ url: chrome.runtime.getURL("options.html") })}>
          {error}
        </button>
      )}

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
            {desktopEntries.map((entry) => (
              <DesktopTile
                container="desktop"
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
                      container="folder"
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

        <SortableContext items={dockIds} strategy={rectSortingStrategy}>
          <DesktopDock
            entries={dockEntries}
            editMode={editMode}
            onClick={clickEntry}
            onContextMenu={openContextMenu}
            onPointerDown={startLongPress}
            onPointerEnd={clearLongPress}
          />
        </SortableContext>

        <DragOverlay>
          {activeEntry && (
            <div className="desktop-drag-overlay">
              <DesktopTileVisual entry={activeEntry} />
            </div>
          )}
        </DragOverlay>
      </DndContext>

      {menu && (
        <DesktopMenu
          folders={folders}
          item={resolveEntry(menu.itemId)}
          sourceContainer={menu.sourceContainer}
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
          onMoveToDock={moveToDock}
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

function DesktopDock(props: {
  entries: LauncherEntry[];
  editMode: boolean;
  onClick: (entry: LauncherEntry) => void;
  onContextMenu: (event: React.MouseEvent<HTMLElement>, itemId: string, sourceContainer: LauncherContainer, folderId?: string) => void;
  onPointerDown: (event: React.PointerEvent<HTMLElement>, itemId: string, sourceContainer: LauncherContainer, folderId?: string) => void;
  onPointerEnd: () => void;
}) {
  const { setNodeRef, isOver } = useDroppable({
    id: DOCK_DROP_ID,
    data: { container: "dock" satisfies LauncherContainer, kind: "dock" },
    disabled: !props.editMode,
  });
  return (
    <nav className={`desktop-dock${isOver ? " desktop-dock-over" : ""}`} aria-label="Dock" ref={setNodeRef}>
      {props.entries.map((entry) => (
        <DockTile
          editMode={props.editMode}
          entry={entry}
          key={entry.id}
          onClick={props.onClick}
          onContextMenu={props.onContextMenu}
          onPointerDown={props.onPointerDown}
          onPointerEnd={props.onPointerEnd}
        />
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
  container: LauncherContainer;
  entry: LauncherEntry;
  editMode: boolean;
  folderId?: string;
  onClick: (entry: LauncherEntry) => void;
  onContextMenu: (event: React.MouseEvent<HTMLElement>, itemId: string, sourceContainer: LauncherContainer, folderId?: string) => void;
  onPointerDown: (event: React.PointerEvent<HTMLElement>, itemId: string, sourceContainer: LauncherContainer, folderId?: string) => void;
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
    data: { container: props.container, folderId: props.folderId || "", kind: props.entry.kind },
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
      onContextMenu={(event) => props.onContextMenu(event, props.entry.id, props.container, props.folderId)}
      onPointerDown={props.editMode ? undefined : (event) => props.onPointerDown(event, props.entry.id, props.container, props.folderId)}
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

function DockTile(props: {
  entry: LauncherEntry;
  editMode: boolean;
  onClick: (entry: LauncherEntry) => void;
  onContextMenu: (event: React.MouseEvent<HTMLElement>, itemId: string, sourceContainer: LauncherContainer, folderId?: string) => void;
  onPointerDown: (event: React.PointerEvent<HTMLElement>, itemId: string, sourceContainer: LauncherContainer, folderId?: string) => void;
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
    data: { container: "dock" satisfies LauncherContainer, folderId: "", kind: props.entry.kind },
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
      aria-label={props.entry.title}
      className={`desktop-dock-tile${props.editMode ? " editing" : ""}`}
      data-launcher-id={props.entry.id}
      ref={setNodeRef}
      style={style}
      title={props.entry.title}
      type="button"
      onClick={() => props.onClick(props.entry)}
      onContextMenu={(event) => props.onContextMenu(event, props.entry.id, "dock")}
      onPointerDown={props.editMode ? undefined : (event) => props.onPointerDown(event, props.entry.id, "dock")}
      onPointerUp={props.editMode ? undefined : props.onPointerEnd}
      onPointerLeave={props.editMode ? undefined : props.onPointerEnd}
      onPointerCancel={props.editMode ? undefined : props.onPointerEnd}
      {...dragAttributes}
      {...dragListeners}
    >
      <DesktopLauncherIcon entry={props.entry} />
    </button>
  );
}

function DesktopTileVisual({ entry }: { entry: LauncherEntry }) {
  return (
    <>
      {entry.kind === "folder" ? (
        <DesktopLauncherIcon entry={entry} />
      ) : (
        <DesktopLauncherIcon entry={entry} />
      )}
      <span className="desktop-label">{entry.title}</span>
    </>
  );
}

function DesktopLauncherIcon({ entry }: { entry: LauncherEntry }) {
  if (entry.kind === "folder") {
    const previewChildren = entry.children.slice(0, 4);
    return (
      <span className="desktop-icon desktop-folder-icon" aria-hidden="true">
        {Array.from({ length: 4 }, (_, index) => {
          const child = previewChildren[index];
          return child ? (
            <span className="desktop-folder-dot" key={child.id} style={{ background: child.kind === "folder" ? "#dfe5eb" : child.color }}>
              {child.kind === "folder" ? "" : child.mark.slice(0, 1)}
            </span>
          ) : (
            <span className="desktop-folder-dot placeholder" key={`empty-${index}`} />
          );
        })}
      </span>
    );
  }
  return (
    <span className={entry.kind === "app" && entry.app.iconDataUrl ? "desktop-icon image" : "desktop-icon"} style={{ background: entry.color }} aria-hidden="true">
      {entry.kind === "app" && entry.app.iconDataUrl ? <img src={entry.app.iconDataUrl} alt="" /> : entry.mark}
    </span>
  );
}

function DesktopMenu(props: {
  item?: LauncherEntry;
  sourceContainer: LauncherContainer;
  sourceFolderId?: string;
  folders: FolderEntry[];
  x: number;
  y: number;
  onClose: () => void;
  onOpen: (item: LauncherEntry) => void;
  onStartEditMode: () => void;
  onCreateFolder: (itemId: string, sourceContainer: LauncherContainer) => void;
  onMoveToDock: (itemId: string) => void;
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
            {props.sourceContainer === "dock" ? (
              <button type="button" role="menuitem" onClick={() => props.onMoveToDesktop(props.item!.id)}>Move to desktop</button>
            ) : (
              <button type="button" role="menuitem" onClick={() => props.onMoveToDock(props.item!.id)}>Move to Dock</button>
            )}
          </>
        ) : (
          <>
            <button type="button" role="menuitem" onClick={() => props.onCreateFolder(props.item!.id, props.sourceContainer)}>New folder</button>
            {props.sourceContainer === "dock" || props.sourceFolderId ? (
              <button type="button" role="menuitem" onClick={() => props.onMoveToDesktop(props.item!.id)}>Move to desktop</button>
            ) : null}
            {props.sourceContainer !== "dock" && (
              <button type="button" role="menuitem" onClick={() => props.onMoveToDock(props.item!.id)}>Move to Dock</button>
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
  const dock = Array.isArray(value?.dock) ? uniqueStrings(value.dock) : [...DEFAULT_DOCK_ENTRY_IDS];
  const dockSet = new Set(dock);
  return {
    order: Array.isArray(value?.order)
      ? uniqueStrings(value.order).filter((id) => !dockSet.has(id))
      : [],
    dock,
    folders: Array.isArray(value?.folders)
      ? value.folders
        .filter((folder): folder is FolderLayout => typeof folder?.id === "string" && Array.isArray(folder.childIds))
        .map((folder) => ({
          id: folder.id,
          title: typeof folder.title === "string" ? folder.title : "Folder",
          childIds: uniqueStrings(folder.childIds).filter((id) => !dockSet.has(id)),
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
  const pointerTargets = pointerCollisions.filter((collision) => !DROP_ZONE_IDS.has(String(collision.id)));
  if (pointerTargets.length > 0) return pointerTargets;

  const dropZoneCollision = pointerCollisions.find((collision) => DROP_ZONE_IDS.has(String(collision.id)));
  if (dropZoneCollision) return [dropZoneCollision];

  const centerCollisions = closestCenter(args);
  const centerTargets = centerCollisions.filter((collision) => !DROP_ZONE_IDS.has(String(collision.id)));
  return centerTargets.length > 0 ? centerTargets : centerCollisions;
};

function insertRelative(ids: string[], itemId: string, targetId: string, placement: DropPlacement): string[] {
  const next = ids.filter((id) => id !== itemId);
  const index = next.indexOf(targetId);
  if (index < 0) return [...next, itemId];
  next.splice(placement === "after" ? index + 1 : index, 0, itemId);
  return next;
}

function removeChildFromFolders(folders: FolderLayout[], itemId: string): FolderLayout[] {
  return folders.map((folder) => ({ ...folder, childIds: folder.childIds.filter((id) => id !== itemId) }));
}

function uniqueStrings(values: unknown[]): string[] {
  const seen = new Set<string>();
  return values.filter((value): value is string => {
    if (typeof value !== "string" || seen.has(value)) return false;
    seen.add(value);
    return true;
  });
}

function stringData(value: unknown): string | undefined {
  return typeof value === "string" && value ? value : undefined;
}

function containerData(value: unknown): LauncherContainer | undefined {
  return value === "desktop" || value === "dock" || value === "folder" ? value : undefined;
}

function isWebDavConfigError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error || "");
  return /webdav url is required/i.test(message);
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
