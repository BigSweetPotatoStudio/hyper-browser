import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  closestCenter,
  DndContext,
  DragEndEvent,
  DragMoveEvent,
  DragOverlay,
  DragOverEvent,
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
import { getDefaultSettings, loadSettings, saveSettings } from "../storage";
import "../styles.css";
import type { SyncResult, SyncSettings, WebAppRecord } from "../types";
import { sendCommand } from "./bridge";

const LAYOUT_STORAGE_KEY = "launcherLayout";
const LAYOUT_VERSION = 3;
const LONG_PRESS_MS = 540;
const DESKTOP_DROP_ID = "drop:desktop";
const DOCK_DROP_ID = "drop:dock";
const DEFAULT_DOCK_ENTRY_IDS = ["system:bookmarks", "system:history", "system:extensions"] as const;
const DROP_ZONE_IDS = new Set<string>([DESKTOP_DROP_ID, DOCK_DROP_ID]);
const DESKTOP_CELL_WIDTH = 116;
const DESKTOP_CELL_HEIGHT = 125;

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

type DesktopCell = {
  id: string;
  page: number;
  row: number;
  column: number;
};

type LauncherLayout = {
  version: typeof LAYOUT_VERSION;
  cells: DesktopCell[];
  dock: string[];
  folders: FolderLayout[];
};

type StoredLauncherLayout = Partial<LauncherLayout> & {
  order?: string[];
  pages?: string[][];
  version?: number;
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
type DropPreviewKind = "cell" | "folder" | "insert";

type DesktopGridMetrics = {
  columns: number;
  rows: number;
  capacity: number;
};

type DesktopCellPosition = {
  page: number;
  row: number;
  column: number;
};

type DesktopSlot = DesktopCellPosition & {
  dropId: string;
  entry?: LauncherEntry;
  index: number;
};

type DropPreview = {
  kind: DropPreviewKind;
  targetId: string;
  placement: DropPlacement;
};

type SyncState = "idle" | "syncing" | "success" | "error" | "needs-settings";

function DesktopPage() {
  const [apps, setApps] = useState<WebAppRecord[]>([]);
  const [layout, setLayout] = useState<LauncherLayout>({ version: LAYOUT_VERSION, cells: [], dock: [...DEFAULT_DOCK_ENTRY_IDS], folders: [] });
  const [loading, setLoading] = useState(true);
  const [toast, setToast] = useState("");
  const [menu, setMenu] = useState<MenuState | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [openFolderId, setOpenFolderId] = useState<string | null>(null);
  const [editMode, setEditMode] = useState(false);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [dropPreview, setDropPreview] = useState<DropPreview | null>(null);
  const [pageIndex, setPageIndex] = useState(0);
  const [settingsConfigured, setSettingsConfigured] = useState(false);
  const [syncState, setSyncState] = useState<SyncState>("idle");
  const [syncMessage, setSyncMessage] = useState("");
  const longPressTimer = useRef<number | null>(null);
  const pointerX = useRef<number | null>(null);
  const suppressClickForId = useRef<string | null>(null);
  const gridMetrics = useDesktopGridMetrics();
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));

  useEffect(() => {
    let cancelled = false;
    async function loadDesktop() {
      try {
        const nextLayout = await loadLayout();
        if (!cancelled) setLayout(nextLayout);
      } catch (loadError) {
        if (!cancelled) showToast(loadError instanceof Error ? loadError.message : "Unable to load desktop layout.");
      }

      try {
        const records = await sendCommand<WebAppRecord[]>("webapps.list");
        if (!cancelled) {
          setApps(records);
        }
      } catch (loadError) {
        if (!cancelled && !isWebDavConfigError(loadError)) {
          showToast(loadError instanceof Error ? loadError.message : "Unable to load WebApps.");
        }
      } finally {
        if (!cancelled) setLoading(false);
      }

      loadSettings()
        .then((settings) => {
          if (!cancelled) setSettingsConfigured(!!settings.webDavUrl.trim());
        })
        .catch(() => undefined);
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

  useEffect(() => {
    if (!toast) return undefined;
    const timer = window.setTimeout(() => setToast(""), 3000);
    return () => window.clearTimeout(timer);
  }, [toast]);

  useEffect(() => {
    const updatePointerX = (event: Event) => {
      pointerX.current = pointerClientX(event) ?? pointerX.current;
    };
    window.addEventListener("pointermove", updatePointerX, { capture: true });
    window.addEventListener("mousemove", updatePointerX, { capture: true });
    window.addEventListener("touchmove", updatePointerX, { capture: true, passive: true });
    return () => {
      window.removeEventListener("pointermove", updatePointerX, { capture: true });
      window.removeEventListener("mousemove", updatePointerX, { capture: true });
      window.removeEventListener("touchmove", updatePointerX, { capture: true });
    };
  }, []);

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
  const availableEntryIds = useMemo(() => new Set(availableEntries.keys()), [availableEntries]);

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

  const visibleDesktopIds = useMemo(() => {
    const allIds = [...availableEntries.keys(), ...folderEntries.keys()];
    return allIds.filter((id) => !containedIds.has(id) && !dockIds.includes(id));
  }, [availableEntries, containedIds, dockIds, folderEntries]);

  const desktopCells = useMemo(
    () => normalizeDesktopCells(layout.cells, visibleDesktopIds, gridMetrics),
    [gridMetrics, layout.cells, visibleDesktopIds],
  );
  const pageCount = pageCountForCells(desktopCells);
  const currentPageIndex = Math.min(pageIndex, pageCount - 1);
  const desktopIds = useMemo(
    () => desktopCells
      .filter((cell) => cell.page === currentPageIndex)
      .sort((left, right) => cellIndex(left, gridMetrics.columns) - cellIndex(right, gridMetrics.columns))
      .map((cell) => cell.id),
    [currentPageIndex, desktopCells, gridMetrics.columns],
  );
  const desktopEntries = useMemo(() => desktopIds
      .map((id) => folderEntries.get(id) || availableEntries.get(id))
      .filter((item): item is LauncherEntry => !!item), [availableEntries, desktopIds, folderEntries]);
  const desktopSlots = useMemo(() => buildDesktopSlots(desktopCells, currentPageIndex, gridMetrics, resolveEntry), [currentPageIndex, desktopCells, gridMetrics, availableEntries, folderEntries]);

  const openFolder = openFolderId ? folderEntries.get(openFolderId) : undefined;
  const openFolderIds = openFolder?.childIds || [];
  const activeEntry = activeId ? resolveEntry(activeId) : undefined;

  function commitLayout(updater: (current: LauncherLayout) => LauncherLayout) {
    setLayout((current) => pruneEmptyFolders(updater(current), availableEntryIds));
  }

  useEffect(() => {
    if (pageIndex < pageCount) return;
    setPageIndex(Math.max(0, pageCount - 1));
  }, [pageCount, pageIndex]);

  useEffect(() => {
    if (loading) return;
    setLayout((current) => {
      const pruned = pruneEmptyFolders(current, availableEntryIds);
      const nextCells = normalizeDesktopCells(pruned.cells, visibleDesktopIds, gridMetrics);
      const next = sameCells(pruned.cells, nextCells, gridMetrics) ? pruned : { ...pruned, cells: nextCells };
      return next === current ? current : next;
    });
  }, [availableEntryIds, gridMetrics, loading, visibleDesktopIds]);

  useEffect(() => {
    if (openFolderId && !folderEntries.has(openFolderId)) {
      setOpenFolderId(null);
    }
  }, [folderEntries, openFolderId]);

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

  function showToast(message: string) {
    setToast(message);
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
    pointerX.current = pointerClientX(event.activatorEvent) ?? pointerX.current;
    suppressClickForId.current = draggedId;
    setActiveId(draggedId);
    setDropPreview(null);
  }

  function handleDragOver(event: DragOverEvent) {
    updateDropPreview(event);
  }

  function handleDragMove(event: DragMoveEvent) {
    updateDropPreview(event);
  }

  function updateDropPreview(event: DragMoveEvent) {
    if (!editMode || !event.over) {
      setDropPreview(null);
      return;
    }
    const targetId = String(event.over.id);
    if (targetId === String(event.active.id) || DROP_ZONE_IDS.has(targetId)) {
      setDropPreview(null);
      return;
    }
    const currentPointerX = dragPointerClientX(event, pointerX.current);
    pointerX.current = currentPointerX ?? pointerX.current;
    const placement = dropPlacementFor(currentPointerX, event.over.rect, event.active.rect.current.translated);
    setDropPreview(dropPreviewFor(
      String(event.active.id),
      containerData(event.active.data.current?.container),
      stringData(event.active.data.current?.folderId),
      targetId,
      containerData(event.over.data.current?.container),
      stringData(event.over.data.current?.folderId),
      cellData(event.over.data.current),
      placement,
    ));
  }

  function dropPreviewFor(
    itemId: string,
    sourceContainer: LauncherContainer | undefined,
    sourceFolderId: string | undefined,
    targetId: string,
    targetContainer: LauncherContainer | undefined,
    targetFolderId: string | undefined,
    targetCell: DesktopCellPosition | undefined,
    placement: DropPlacement,
  ): DropPreview | null {
    if (targetCell) {
      return { kind: "cell", targetId, placement: "inside" };
    }
    const targetEntry = resolveEntry(targetId);
    if (!targetEntry) return null;
    const itemIsFolder = itemId.startsWith("folder:");

    if (targetEntry.kind === "folder") {
      return itemIsFolder ? null : { kind: "folder", targetId, placement: "inside" };
    }
    if (targetContainer === "dock") {
      return { kind: "insert", targetId, placement: normalizeInsertPlacement(placement, "after") };
    }
    if (sourceFolderId && targetFolderId === sourceFolderId) {
      return { kind: "insert", targetId, placement: normalizeInsertPlacement(placement, "before") };
    }
    if (!targetFolderId && !itemIsFolder && placement === "inside") {
      return { kind: "folder", targetId, placement };
    }
    if (sourceContainer === "folder" && !targetFolderId) {
      return { kind: "insert", targetId, placement: normalizeInsertPlacement(placement, "before") };
    }
    if (!targetFolderId) {
      return { kind: "insert", targetId, placement: normalizeInsertPlacement(placement, "before") };
    }
    return itemIsFolder ? null : { kind: "folder", targetId, placement: "inside" };
  }

  function handleDragEnd(event: DragEndEvent) {
    const draggedId = String(event.active.id);
    const targetId = event.over ? String(event.over.id) : "";
    const sourceFolderId = stringData(event.active.data.current?.folderId);
    const sourceContainer = containerData(event.active.data.current?.container);
    const targetFolderId = stringData(event.over?.data.current?.folderId);
    const targetContainer = containerData(event.over?.data.current?.container);
    const targetCell = cellData(event.over?.data.current);
    const currentPointerX = dragPointerClientX(event, pointerX.current);
    pointerX.current = currentPointerX ?? pointerX.current;
    const placement = event.over ? dropPlacementFor(currentPointerX, event.over.rect, event.active.rect.current.translated) : "inside";
    setActiveId(null);
    setDropPreview(null);
    if (!targetId || draggedId === targetId) return;
    handleDrop(draggedId, sourceContainer, sourceFolderId, targetId, targetContainer, targetFolderId, targetCell, placement);
  }

  function handleDrop(
    itemId: string,
    sourceContainer: LauncherContainer | undefined,
    sourceFolderId: string | undefined,
    targetId: string,
    targetContainer: LauncherContainer | undefined,
    targetFolderId: string | undefined,
    targetCell: DesktopCellPosition | undefined,
    placement: DropPlacement,
  ) {
    if (targetCell) {
      moveToDesktopCell(itemId, targetCell);
      return;
    }
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
      moveToDockNear(itemId, targetId, normalizeInsertPlacement(placement, "after"));
      return;
    }
    if (sourceFolderId && targetFolderId === sourceFolderId) {
      moveInsideFolderRelative(sourceFolderId, itemId, targetId, normalizeInsertPlacement(placement, "before"));
      return;
    }
    if (!targetFolderId && !itemId.startsWith("folder:") && placement === "inside") {
      createFolderFromDrop(itemId, targetId);
      return;
    }
    if (sourceContainer === "folder" && !targetFolderId) {
      moveToDesktopNear(itemId, targetId, normalizeInsertPlacement(placement, "before"));
      return;
    }
    if (!targetFolderId) {
      moveToDesktopNear(itemId, targetId, normalizeInsertPlacement(placement, "before"));
      return;
    }
    if (targetFolderId) {
      moveToFolder(itemId, targetFolderId);
    }
  }

  function moveInsideFolderRelative(folderId: string, itemId: string, targetId: string, placement: DropPlacement) {
    commitLayout((current) => ({
      ...current,
      folders: current.folders.map((folder) => (
        folder.id === folderId ? { ...folder, childIds: insertRelative(folder.childIds, itemId, targetId, placement) } : folder
      )),
    }));
  }

  function moveToDesktopCell(itemId: string, position: DesktopCellPosition) {
    commitLayout((current) => ({
      ...current,
      cells: placeItemAtCell(current.cells, itemId, position, gridMetrics),
      dock: current.dock.filter((id) => id !== itemId),
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    setOpenFolderId(null);
  }

  function moveToDesktopNear(itemId: string, targetId: string, placement: DropPlacement) {
    commitLayout((current) => ({
      ...current,
      cells: insertNearDesktopCell(current.cells, itemId, targetId, placement, currentPageIndex, gridMetrics),
      dock: current.dock.filter((id) => id !== itemId),
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    setOpenFolderId(null);
  }

  function moveToDesktopEnd(itemId: string) {
    commitLayout((current) => ({
      ...current,
      cells: appendToDesktopCells(current.cells, currentPageIndex, itemId, gridMetrics),
      dock: current.dock.filter((id) => id !== itemId),
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    setOpenFolderId(null);
  }

  function moveToDockNear(itemId: string, targetId: string, placement: DropPlacement) {
    commitLayout((current) => ({
      ...current,
      cells: removeFromCells(current.cells, itemId),
      dock: insertRelative(dockIds, itemId, targetId, placement),
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    setOpenFolderId(null);
  }

  function moveToDockEnd(itemId: string) {
    commitLayout((current) => ({
      ...current,
      cells: removeFromCells(current.cells, itemId),
      dock: [...dockIds.filter((id) => id !== itemId), itemId],
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    setOpenFolderId(null);
  }

  function moveToFolder(itemId: string, folderId: string) {
    if (itemId.startsWith("folder:")) return;
    commitLayout((current) => ({
      ...current,
      cells: removeFromCells(current.cells, itemId),
      dock: current.dock.filter((id) => id !== itemId),
      folders: current.folders.map((folder) => {
        const childIds = folder.childIds.filter((id) => id !== itemId);
        return folder.id === folderId ? { ...folder, childIds: [...childIds, itemId] } : { ...folder, childIds };
      }),
    }));
    closeMenu();
  }

  function moveToDesktop(itemId: string) {
    commitLayout((current) => ({
      ...current,
      cells: appendToDesktopCells(current.cells, currentPageIndex, itemId, gridMetrics),
      dock: current.dock.filter((id) => id !== itemId),
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    closeMenu();
  }

  function moveToDock(itemId: string) {
    commitLayout((current) => ({
      ...current,
      cells: removeFromCells(current.cells, itemId),
      dock: [...current.dock.filter((id) => id !== itemId), itemId],
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    setOpenFolderId(null);
    closeMenu();
  }

  function createFolderWith(itemId: string, sourceContainer: LauncherContainer) {
    if (itemId.startsWith("folder:")) return;
    const folderId = `folder:${crypto.randomUUID()}`;
    commitLayout((current) => ({
      ...current,
      cells: sourceContainer === "dock"
        ? removeFromCells(current.cells, itemId)
        : replaceInCellsOrAppend(current.cells, currentPageIndex, itemId, folderId, gridMetrics),
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

  function createFolderFromDrop(itemId: string, targetId: string) {
    if (itemId === targetId || itemId.startsWith("folder:") || targetId.startsWith("folder:")) return;
    const folderId = `folder:${crypto.randomUUID()}`;
    commitLayout((current) => ({
      ...current,
      cells: replaceCellPairWithFolder(current.cells, currentPageIndex, itemId, targetId, folderId, gridMetrics),
      dock: current.dock.filter((id) => id !== itemId && id !== targetId),
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
    commitLayout((current) => ({
      ...current,
      folders: current.folders.map((item) => (item.id === folderId ? { ...item, title: title.trim() || "Folder" } : item)),
    }));
    closeMenu();
  }

  function unpackFolder(folderId: string) {
    const folder = layout.folders.find((item) => item.id === folderId);
    if (!folder) return;
    commitLayout((current) => ({
      ...current,
      cells: replaceCellWithMany(current.cells, folderId, folder.childIds, gridMetrics),
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
      .catch((deleteError) => showToast(deleteError instanceof Error ? deleteError.message : "Unable to delete WebApp."));
    commitLayout((current) => ({
      ...current,
      cells: removeFromCells(current.cells, itemId),
      dock: current.dock.filter((id) => id !== itemId),
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    closeMenu();
  }

  async function runSync() {
    setSyncState("syncing");
    setSyncMessage("Syncing...");
    try {
      const settings = await loadSettings();
      const configured = !!settings.webDavUrl.trim();
      setSettingsConfigured(configured);
      if (!configured) {
        setSyncState("needs-settings");
        setSyncMessage("Set WebDAV URL first");
        showToast("Set WebDAV URL before syncing.");
        setSettingsOpen(true);
        return;
      }
      const result = await sendCommand<SyncResult>("sync.run");
      setSyncState("success");
      setSyncMessage(syncResultMessage(result));
      showToast("Sync complete.");
    } catch (syncError) {
      const text = syncError instanceof Error ? syncError.message : "Sync failed.";
      setSyncState(isWebDavConfigError(syncError) ? "needs-settings" : "error");
      setSyncMessage(text);
      showToast(text);
      if (isWebDavConfigError(syncError)) setSettingsOpen(true);
    }
  }

  return (
    <main className="desktop-page" onPointerMoveCapture={(event) => { pointerX.current = event.clientX; }}>
      <div className="desktop-commands">
        {syncMessage && (
          <button className={`desktop-sync-status ${syncState}`} type="button" onClick={() => syncState === "needs-settings" && setSettingsOpen(true)}>
            {syncMessage}
          </button>
        )}
        {editMode && <button type="button" onClick={() => setEditMode(false)}>Done</button>}
        <button type="button" onClick={() => setSettingsOpen(true)}>Settings</button>
        <button type="button" disabled={syncState === "syncing"} title={settingsConfigured ? "Sync" : "Set up WebDAV before syncing"} onClick={runSync}>
          {syncState === "syncing" ? "Syncing" : "Sync"}
        </button>
      </div>
      {toast && (
        <button className="desktop-toast" type="button" onClick={() => setSettingsOpen(true)}>
          {toast}
        </button>
      )}

      <DndContext
        sensors={sensors}
        collisionDetection={desktopCollisionDetection}
        onDragCancel={() => {
          setActiveId(null);
          setDropPreview(null);
        }}
        onDragEnd={handleDragEnd}
        onDragMove={handleDragMove}
        onDragOver={handleDragOver}
        onDragStart={handleDragStart}
      >
        <SortableContext items={desktopIds} strategy={rectSortingStrategy}>
          <DesktopDropGrid columns={gridMetrics.columns} editMode={editMode} enabled={editMode && !openFolder}>
            {loading && <div className="desktop-status">Loading desktop...</div>}
            {!loading && desktopEntries.length === 0 && !editMode && <div className="desktop-status desktop-empty-status">This page is empty.</div>}
            {!loading && desktopSlots.map((slot) => (
              <DesktopCellSlot
                dropPreview={dropPreview}
                editMode={editMode}
                key={slot.dropId}
                slot={slot}
              >
                {slot.entry && (
                  <DesktopTile
                    container="desktop"
                    dropPreview={dropPreview}
                    entry={slot.entry}
                    editMode={editMode}
                    folderId={undefined}
                    onClick={clickEntry}
                    onContextMenu={openContextMenu}
                    onPointerDown={startLongPress}
                    onPointerEnd={clearLongPress}
                  />
                )}
              </DesktopCellSlot>
            ))}
          </DesktopDropGrid>
        </SortableContext>

        {openFolder && (
          <DesktopFolderScrim editMode={editMode} onClose={() => setOpenFolderId(null)}>
            <section className="desktop-folder" role="dialog" aria-modal="true" aria-label={openFolder.title} onClick={(event) => event.stopPropagation()}>
              <header className="desktop-folder-header">
                <h2>{openFolder.title}</h2>
                <div className="desktop-folder-actions">
                  {editMode && <button type="button" onClick={() => renameFolder(openFolder.id)}>Rename</button>}
                  <button type="button" onClick={() => setOpenFolderId(null)}>Close</button>
                </div>
              </header>
              <SortableContext items={openFolderIds} strategy={rectSortingStrategy}>
                <div className="desktop-folder-grid">
                  {openFolder.children.length === 0 ? (
                    <div className="desktop-status">This folder is empty.</div>
                  ) : openFolder.children.map((entry) => (
                    <DesktopTile
                      container="folder"
                      dropPreview={dropPreview}
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
            dropPreview={dropPreview}
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

      {pageCount > 1 && (
        <div className="desktop-page-dots" aria-label="Desktop pages">
          {Array.from({ length: pageCount }, (_, index) => (
            <button
              aria-label={`Page ${index + 1}`}
              aria-current={index === currentPageIndex ? "page" : undefined}
              className={index === currentPageIndex ? "active" : ""}
              key={`page-${index}`}
              type="button"
              onClick={() => setPageIndex(index)}
            />
          ))}
        </div>
      )}

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
      {settingsOpen && (
        <SettingsDialog
          onSettingsSaved={(settings) => setSettingsConfigured(!!settings.webDavUrl.trim())}
          onSyncError={(message) => {
            setSyncState(isWebDavConfigError(message) ? "needs-settings" : "error");
            setSyncMessage(message);
          }}
          onSyncResult={(result) => {
            setSyncState("success");
            setSyncMessage(syncResultMessage(result));
          }}
          onClose={() => setSettingsOpen(false)}
          onToast={showToast}
        />
      )}
    </main>
  );
}

function SettingsDialog(props: {
  onClose: () => void;
  onSettingsSaved: (settings: SyncSettings) => void;
  onSyncError: (message: string) => void;
  onSyncResult: (result: SyncResult) => void;
  onToast: (message: string) => void;
}) {
  const [settings, setSettings] = useState<SyncSettings>(getDefaultSettings());
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    loadSettings()
      .then((loaded) => {
        if (!cancelled) setSettings(loaded);
      })
      .catch((loadError) => {
        if (!cancelled) setError(loadError instanceof Error ? loadError.message : "Unable to load settings.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  function update<K extends keyof SyncSettings>(key: K, value: SyncSettings[K]) {
    setSettings((current) => ({ ...current, [key]: value }));
    setMessage("");
    setError("");
  }

  function save() {
    const next = normalizeSettings(settings);
    setBusy(true);
    saveSettings(next)
      .then(() => {
        setSettings(next);
        props.onSettingsSaved(next);
        setMessage("Settings saved.");
        setError("");
      })
      .catch((saveError) => {
        const text = saveError instanceof Error ? saveError.message : "Unable to save settings.";
        setError(text);
        props.onToast(text);
      })
      .finally(() => setBusy(false));
  }

  function sync() {
    const next = normalizeSettings(settings);
    setBusy(true);
    setMessage("Syncing...");
    setError("");
    saveSettings(next)
      .then(() => sendCommand<SyncResult>("sync.run"))
      .then((result) => {
        setMessage(`Synced ${result.bookmarkCount} bookmarks. Tombstones: ${result.deletedBookmarkCount}.`);
        props.onSyncResult(result);
        return loadSettings();
      })
      .then((loaded) => {
        setSettings(loaded);
        props.onSettingsSaved(loaded);
      })
      .catch((syncError) => {
        const text = syncError instanceof Error ? syncError.message : "Sync failed.";
        setMessage("");
        setError(text);
        props.onSyncError(text);
        props.onToast(text);
      })
      .finally(() => setBusy(false));
  }

  return (
    <div className="settings-scrim" onClick={props.onClose}>
      <section className="settings-dialog" role="dialog" aria-modal="true" aria-label="Settings" onClick={(event) => event.stopPropagation()}>
        <header className="settings-dialog-header">
          <h2>Settings</h2>
          <button type="button" onClick={props.onClose}>Close</button>
        </header>
        <div className="grid">
          <label className="field full">
            <span className="label">WebDAV address</span>
            <input className="input" type="url" placeholder="https://example.com/dav" value={settings.webDavUrl} onChange={(event) => update("webDavUrl", event.currentTarget.value)} />
          </label>
          <label className="field">
            <span className="label">Username</span>
            <input className="input" type="text" autoComplete="username" value={settings.username} onChange={(event) => update("username", event.currentTarget.value)} />
          </label>
          <label className="field">
            <span className="label">Password or app token</span>
            <input className="input" type="password" autoComplete="current-password" value={settings.password} onChange={(event) => update("password", event.currentTarget.value)} />
          </label>
          <label className="field">
            <span className="label">Chrome folder title</span>
            <input className="input" type="text" value={settings.folderTitle} onChange={(event) => update("folderTitle", event.currentTarget.value)} />
          </label>
          <label className="field">
            <span className="label">Device name</span>
            <input className="input" type="text" value={settings.deviceName} onChange={(event) => update("deviceName", event.currentTarget.value)} />
          </label>
        </div>
        <div className="actions">
          <button className="button" type="button" disabled={busy} onClick={save}>Save</button>
          <button className="button primary" type="button" disabled={busy || !settings.webDavUrl.trim()} onClick={sync}>
            {busy ? "Working..." : "Save and sync"}
          </button>
        </div>
        {settings.deviceId && <p className="message">Device ID: {settings.deviceId}</p>}
        {message && <p className="message">{message}</p>}
        {error && <p className="error">{error}</p>}
      </section>
    </div>
  );
}

function DesktopDock(props: {
  dropPreview: DropPreview | null;
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
          dropPreview={props.dropPreview}
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

function DesktopDropGrid(props: { children: React.ReactNode; columns: number; editMode: boolean; enabled: boolean }) {
  const { setNodeRef, isOver } = useDroppable({
    id: DESKTOP_DROP_ID,
    data: { kind: "desktop" },
    disabled: !props.enabled,
  });
  const style = { "--desktop-columns": String(props.columns) } as React.CSSProperties;
  return (
    <section className={`desktop-grid${props.editMode ? " editing" : ""}${isOver ? " desktop-grid-over" : ""}`} aria-label="Hyper Browser desktop" ref={setNodeRef} style={style}>
      {props.children}
    </section>
  );
}

function DesktopCellSlot(props: {
  children: React.ReactNode;
  dropPreview: DropPreview | null;
  editMode: boolean;
  slot: DesktopSlot;
}) {
  const { setNodeRef, isOver } = useDroppable({
    id: props.slot.dropId,
    data: {
      container: "desktop" satisfies LauncherContainer,
      kind: "cell",
      page: props.slot.page,
      row: props.slot.row,
      column: props.slot.column,
    },
    disabled: !props.editMode,
  });
  const isPreview = props.dropPreview?.kind === "cell" && props.dropPreview.targetId === props.slot.dropId;
  return (
    <div
      className={`desktop-cell-slot${props.editMode ? " editing" : ""}${isOver || isPreview ? " cell-over" : ""}`}
      data-cell={`${props.slot.page}:${props.slot.row}:${props.slot.column}`}
      ref={setNodeRef}
    >
      {props.children}
    </div>
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
  dropPreview: DropPreview | null;
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
  const dropClass = props.dropPreview?.targetId === props.entry.id ? ` drop-${props.dropPreview.kind} drop-${props.dropPreview.placement}` : "";

  return (
    <button
      className={`desktop-tile${props.editMode ? " editing" : ""}${dropClass}`}
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
  dropPreview: DropPreview | null;
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
  const dropClass = props.dropPreview?.targetId === props.entry.id ? ` drop-${props.dropPreview.kind} drop-${props.dropPreview.placement}` : "";

  return (
    <button
      aria-label={props.entry.title}
      className={`desktop-dock-tile${props.editMode ? " editing" : ""}${dropClass}`}
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
  const value = stored[LAYOUT_STORAGE_KEY] as StoredLauncherLayout | undefined;
  const dock = Array.isArray(value?.dock) ? uniqueStrings(value.dock) : [...DEFAULT_DOCK_ENTRY_IDS];
  const dockSet = new Set(dock);
  const metrics = calculateDesktopGridMetrics();
  const legacyOrder = Array.isArray(value?.order)
    ? uniqueStrings(value.order).filter((id) => !dockSet.has(id))
    : [];
  const pages = Array.isArray(value?.pages)
    ? value.pages
      .filter((page): page is string[] => Array.isArray(page))
      .map((page) => uniqueStrings(page).filter((id) => !dockSet.has(id)))
    : [];
  const cells = Array.isArray(value?.cells)
    ? value.cells
      .filter((cell): cell is DesktopCell => (
        typeof cell?.id === "string"
        && Number.isInteger(cell.page)
        && Number.isInteger(cell.row)
        && Number.isInteger(cell.column)
      ))
      .filter((cell) => !dockSet.has(cell.id))
    : [];
  return {
    version: LAYOUT_VERSION,
    cells: cells.length > 0 ? normalizeDesktopCells(cells, [], metrics) : (pages.length > 0 ? pagesToCells(pages, metrics) : idsToCells(legacyOrder, metrics, 0)),
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

function useDesktopGridMetrics(): DesktopGridMetrics {
  const [metrics, setMetrics] = useState<DesktopGridMetrics>(() => calculateDesktopGridMetrics());
  useEffect(() => {
    function update() {
      setMetrics(calculateDesktopGridMetrics());
    }
    update();
    window.addEventListener("resize", update);
    return () => window.removeEventListener("resize", update);
  }, []);
  return metrics;
}

function calculateDesktopGridMetrics(): DesktopGridMetrics {
  const narrow = window.innerWidth <= 680;
  const topSafe = narrow ? 82 : 96;
  const sideSafe = narrow ? 18 : 24;
  const bottomSafe = narrow ? 132 : 150;
  const width = Math.max(DESKTOP_CELL_WIDTH, window.innerWidth - sideSafe * 2);
  const height = Math.max(DESKTOP_CELL_HEIGHT, window.innerHeight - topSafe - bottomSafe);
  const columns = Math.max(1, Math.floor(width / DESKTOP_CELL_WIDTH));
  const rows = Math.max(1, Math.floor(height / DESKTOP_CELL_HEIGHT));
  return { columns, rows, capacity: columns * rows };
}

function buildDesktopSlots(cells: DesktopCell[], page: number, metrics: DesktopGridMetrics, resolveEntry: (id: string) => LauncherEntry | undefined): DesktopSlot[] {
  const entriesByIndex = new Map<number, LauncherEntry>();
  for (const cell of cells) {
    if (cell.page !== page) continue;
    const entry = resolveEntry(cell.id);
    if (entry) entriesByIndex.set(cellIndex(cell, metrics.columns), entry);
  }
  return Array.from({ length: metrics.capacity }, (_, index) => {
    const position = indexToCell(index, metrics, page);
    return {
      ...position,
      dropId: cellDropId(position),
      entry: entriesByIndex.get(index),
      index,
    };
  });
}

function normalizeDesktopCells(cells: DesktopCell[], visibleIds: string[], metrics: DesktopGridMetrics): DesktopCell[] {
  const visibleSet = new Set(visibleIds);
  const hasVisibleSet = visibleSet.size > 0;
  const seen = new Set<string>();
  const usedIndexes = new Set<number>();
  const nextCells: DesktopCell[] = [];
  for (const cell of cells) {
    if (seen.has(cell.id)) continue;
    if (hasVisibleSet && !visibleSet.has(cell.id)) continue;
    const normalized = normalizeCell(cell, metrics);
    let globalIndex = globalCellIndex(normalized, metrics);
    while (usedIndexes.has(globalIndex)) globalIndex += 1;
    const nextCell = globalIndexToCell(globalIndex, metrics);
    nextCells.push({ ...nextCell, id: cell.id });
    seen.add(cell.id);
    usedIndexes.add(globalIndex);
  }
  for (const id of visibleIds) {
    if (seen.has(id)) continue;
    let globalIndex = 0;
    while (usedIndexes.has(globalIndex)) globalIndex += 1;
    const nextCell = globalIndexToCell(globalIndex, metrics);
    nextCells.push({ ...nextCell, id });
    seen.add(id);
    usedIndexes.add(globalIndex);
  }
  return sortCells(nextCells, metrics);
}

function idsToCells(ids: string[], metrics: DesktopGridMetrics, startPage: number): DesktopCell[] {
  return uniqueStrings(ids).map((id, index) => ({ ...indexToCell(index, metrics, startPage), id }));
}

function pagesToCells(pages: string[][], metrics: DesktopGridMetrics): DesktopCell[] {
  return pages.flatMap((page, pageIndex) => idsToCells(page, metrics, pageIndex));
}

function pageCountForCells(cells: DesktopCell[]): number {
  if (cells.length === 0) return 1;
  return Math.max(1, Math.max(...cells.map((cell) => cell.page)) + 1);
}

function sameCells(left: DesktopCell[], right: DesktopCell[], metrics: DesktopGridMetrics): boolean {
  if (left.length !== right.length) return false;
  const sortedLeft = sortCells(left, metrics);
  const sortedRight = sortCells(right, metrics);
  return sortedLeft.every((cell, index) => (
    cell.id === sortedRight[index].id
    && cell.page === sortedRight[index].page
    && cell.row === sortedRight[index].row
    && cell.column === sortedRight[index].column
  ));
}

function removeFromCells(cells: DesktopCell[], itemId: string): DesktopCell[] {
  return cells.filter((cell) => cell.id !== itemId);
}

function removeManyFromCells(cells: DesktopCell[], itemIds: string[]): DesktopCell[] {
  const itemSet = new Set(itemIds);
  return cells.filter((cell) => !itemSet.has(cell.id));
}

function placeItemAtCell(cells: DesktopCell[], itemId: string, position: DesktopCellPosition, metrics: DesktopGridMetrics): DesktopCell[] {
  return placeItemAtGlobalIndex(cells, itemId, globalCellIndex(position, metrics), metrics);
}

function appendToDesktopCells(cells: DesktopCell[], pageIndex: number, itemId: string, metrics: DesktopGridMetrics): DesktopCell[] {
  const occupied = new Set(removeFromCells(cells, itemId).map((cell) => globalCellIndex(cell, metrics)));
  let globalIndex = pageIndex * metrics.capacity;
  const pageEnd = globalIndex + metrics.capacity;
  while (globalIndex < pageEnd && occupied.has(globalIndex)) globalIndex += 1;
  while (occupied.has(globalIndex)) globalIndex += 1;
  return placeItemAtGlobalIndex(cells, itemId, globalIndex, metrics);
}

function insertNearDesktopCell(cells: DesktopCell[], itemId: string, targetId: string, placement: DropPlacement, pageIndex: number, metrics: DesktopGridMetrics): DesktopCell[] {
  const targetCell = cells.find((cell) => cell.id === targetId);
  if (!targetCell) return appendToDesktopCells(cells, pageIndex, itemId, metrics);
  const targetIndex = globalCellIndex(targetCell, metrics);
  return placeItemAtGlobalIndex(cells, itemId, targetIndex + (placement === "after" ? 1 : 0), metrics);
}

function replaceInCellsOrAppend(cells: DesktopCell[], pageIndex: number, itemId: string, replacementId: string, metrics: DesktopGridMetrics): DesktopCell[] {
  let replaced = false;
  const nextCells = cells.map((cell) => {
    if (cell.id !== itemId) return cell;
    replaced = true;
    return { ...cell, id: replacementId };
  });
  return replaced ? sortCells(nextCells, metrics) : appendToDesktopCells(nextCells, pageIndex, replacementId, metrics);
}

function replaceCellPairWithFolder(cells: DesktopCell[], pageIndex: number, itemId: string, targetId: string, folderId: string, metrics: DesktopGridMetrics): DesktopCell[] {
  const targetCell = cells.find((cell) => cell.id === targetId);
  const nextCells = removeManyFromCells(cells, [itemId, targetId]);
  if (targetCell) return placeItemAtGlobalIndex(nextCells, folderId, globalCellIndex(targetCell, metrics), metrics);
  return appendToDesktopCells(nextCells, pageIndex, folderId, metrics);
}

function replaceCellWithMany(cells: DesktopCell[], itemId: string, replacementIds: string[], metrics: DesktopGridMetrics): DesktopCell[] {
  const targetCell = cells.find((cell) => cell.id === itemId);
  let nextCells = removeFromCells(cells, itemId);
  let globalIndex = targetCell ? globalCellIndex(targetCell, metrics) : 0;
  for (const replacementId of replacementIds) {
    nextCells = placeItemAtGlobalIndex(nextCells, replacementId, globalIndex, metrics);
    globalIndex += 1;
  }
  return sortCells(nextCells, metrics);
}

function placeItemAtGlobalIndex(cells: DesktopCell[], itemId: string, targetIndex: number, metrics: DesktopGridMetrics): DesktopCell[] {
  const occupied = new Map<number, string>();
  for (const cell of cells) {
    if (cell.id === itemId) continue;
    occupied.set(globalCellIndex(cell, metrics), cell.id);
  }
  let cursor = Math.max(0, targetIndex);
  let carry: string | undefined = itemId;
  while (carry) {
    const nextCarry = occupied.get(cursor);
    occupied.set(cursor, carry);
    carry = nextCarry;
    cursor += 1;
  }
  return Array.from(occupied.entries())
    .map(([index, id]) => ({ ...globalIndexToCell(index, metrics), id }))
    .sort((left, right) => globalCellIndex(left, metrics) - globalCellIndex(right, metrics));
}

function sortCells(cells: DesktopCell[], metrics: DesktopGridMetrics): DesktopCell[] {
  return [...cells].sort((left, right) => globalCellIndex(left, metrics) - globalCellIndex(right, metrics));
}

function normalizeCell(cell: DesktopCell, metrics: DesktopGridMetrics): DesktopCell {
  const page = Math.max(0, cell.page);
  const row = Math.max(0, Math.min(metrics.rows - 1, cell.row));
  const column = Math.max(0, Math.min(metrics.columns - 1, cell.column));
  return { ...cell, page, row, column };
}

function cellIndex(cell: DesktopCellPosition, columns: number): number {
  return cell.row * columns + cell.column;
}

function globalCellIndex(cell: DesktopCellPosition, metrics: DesktopGridMetrics): number {
  return cell.page * metrics.capacity + cellIndex(cell, metrics.columns);
}

function indexToCell(index: number, metrics: DesktopGridMetrics, page: number): DesktopCellPosition {
  return {
    page,
    row: Math.floor(index / metrics.columns),
    column: index % metrics.columns,
  };
}

function globalIndexToCell(index: number, metrics: DesktopGridMetrics): DesktopCellPosition {
  const page = Math.floor(index / metrics.capacity);
  return indexToCell(index % metrics.capacity, metrics, page);
}

function cellDropId(cell: DesktopCellPosition): string {
  return `drop:cell:${cell.page}:${cell.row}:${cell.column}`;
}

function dropPlacementFor(pointerClientX: number | null, overRect: ClientRect, activeRect: ClientRect | null): DropPlacement {
  const fallbackX = activeRect ? activeRect.left + activeRect.width / 2 : overRect.left + overRect.width / 2;
  const clientX = typeof pointerClientX === "number" ? pointerClientX : fallbackX;
  const ratio = overRect.width > 0 ? (clientX - overRect.left) / overRect.width : 0.5;
  if (ratio < 0.24) return "before";
  if (ratio > 0.76) return "after";
  return "inside";
}

function normalizeInsertPlacement(placement: DropPlacement, insidePlacement: "before" | "after"): DropPlacement {
  return placement === "inside" ? insidePlacement : placement;
}

function dragPointerClientX(event: { activatorEvent: Event; delta: { x: number } }, fallback: number | null): number | null {
  const startX = pointerClientX(event.activatorEvent);
  return startX === null ? fallback : startX + event.delta.x;
}

function pointerClientX(event: Event): number | null {
  if ("clientX" in event && typeof event.clientX === "number") {
    return event.clientX;
  }
  const touchEvent = event as Partial<TouchEvent>;
  if (touchEvent.touches && touchEvent.touches.length > 0) {
    return touchEvent.touches[0].clientX;
  }
  if (touchEvent.changedTouches && touchEvent.changedTouches.length > 0) {
    return touchEvent.changedTouches[0].clientX;
  }
  return null;
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

function pruneEmptyFolders(layout: LauncherLayout, availableEntryIds?: Set<string>): LauncherLayout {
  const removedFolderIds = new Set<string>();
  let changed = false;
  const folders: FolderLayout[] = [];

  for (const folder of layout.folders) {
    const childIds = uniqueStrings(folder.childIds)
      .filter((id) => !availableEntryIds || availableEntryIds.has(id));
    const folderChanged = !sameStringList(childIds, folder.childIds);
    if (childIds.length === 0) {
      removedFolderIds.add(folder.id);
      changed = true;
      continue;
    }
    if (folderChanged) changed = true;
    folders.push(folderChanged ? { ...folder, childIds } : folder);
  }

  if (removedFolderIds.size === 0 && !changed) return layout;
  return {
    ...layout,
    cells: removedFolderIds.size > 0 ? layout.cells.filter((cell) => !removedFolderIds.has(cell.id)) : layout.cells,
    dock: removedFolderIds.size > 0 ? layout.dock.filter((id) => !removedFolderIds.has(id)) : layout.dock,
    folders,
  };
}

function sameStringList(left: string[], right: string[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
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

function cellData(value: unknown): DesktopCellPosition | undefined {
  if (!value || typeof value !== "object") return undefined;
  const data = value as Record<string, unknown>;
  if (data.kind !== "cell") return undefined;
  if (!Number.isInteger(data.page) || !Number.isInteger(data.row) || !Number.isInteger(data.column)) return undefined;
  return {
    page: Math.max(0, Number(data.page)),
    row: Math.max(0, Number(data.row)),
    column: Math.max(0, Number(data.column)),
  };
}

function isWebDavConfigError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error || "");
  return /webdav url is required/i.test(message);
}

function syncResultMessage(result: SyncResult): string {
  const deleted = result.deletedBookmarkCount > 0 ? `, ${result.deletedBookmarkCount} deleted` : "";
  return `Synced ${result.bookmarkCount} bookmarks${deleted}`;
}

function normalizeSettings(settings: SyncSettings): SyncSettings {
  return {
    ...settings,
    webDavUrl: settings.webDavUrl.trim(),
    username: settings.username.trim(),
    folderTitle: settings.folderTitle.trim() || "Hyper Browser",
    deviceName: settings.deviceName.trim() || "Chrome",
  };
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
