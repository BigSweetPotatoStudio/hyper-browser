import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
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
  type SortingStrategy,
  useSortable,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import "./styles.css";

const LAYOUT_VERSION = 4 as const;
const LONG_PRESS_MS = 540;
const DESKTOP_DROP_ID = "drop:desktop";
const DOCK_DROP_ID = "drop:dock";
const DROP_ZONE_IDS = new Set<string>([DESKTOP_DROP_ID, DOCK_DROP_ID]);
const DESKTOP_CELL_WIDTH = 116;
const DESKTOP_CELL_HEIGHT = 125;
const MOBILE_GRID_MIN_COLUMNS = 4;
const MOBILE_CELL_WIDTH = 76;
const MOBILE_CELL_HEIGHT = 112;
const MOBILE_GRID_COLUMN_GAP = 10;
const MAX_DOCK_ITEMS = 4;

export type LauncherApp = {
  id: string;
  name: string;
  startUrl: string;
  scopeUrl?: string;
  themeColor: number;
  displayMode?: string;
  iconDataUrl?: string | null;
  siteIconDataUrl?: string | null;
  iconSource?: "custom" | "site" | "title";
};

export type LauncherSystemEntry = {
  id: string;
  kind: "system";
  title: string;
  mark: string;
  color: string;
  action: string;
};

type AppEntry = {
  id: string;
  kind: "app";
  title: string;
  mark: string;
  color: string;
  app: LauncherApp;
};

export type LauncherFolderLayout = {
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

type LauncherEntry = LauncherSystemEntry | AppEntry | FolderEntry;

export type LauncherDesktopCell = {
  id: string;
  page: number;
  row: number;
  column: number;
  index?: number;
};

export type LauncherLayout = {
  version: typeof LAYOUT_VERSION;
  cells: LauncherDesktopCell[];
  dock: string[];
  folders: LauncherFolderLayout[];
  gridColumns?: number;
  updatedAt?: number;
};

type DesktopCell = LauncherDesktopCell;
type FolderLayout = LauncherFolderLayout;

type StoredLauncherLayout = Partial<LauncherLayout> & {
  order?: string[];
  pages?: string[][];
  version?: number;
};

type LauncherContainer = "desktop" | "dock" | "folder";

export type LauncherLayoutStorage = {
  load: () => Promise<StoredLauncherLayout | null | undefined>;
  save: (layout: LauncherLayout) => Promise<void> | void;
};

export type LauncherPlatform = {
  systemEntries: LauncherSystemEntry[];
  defaultDockEntryIds: string[];
  deprecatedEntryIds?: string[];
  loadApps: () => Promise<LauncherApp[]>;
  openApp: (app: LauncherApp) => void;
  openStandaloneApp?: (app: LauncherApp) => void;
  openSystem: (action: string) => void;
  deleteApp?: (app: LauncherApp) => Promise<LauncherApp[] | void> | LauncherApp[] | void;
  editApp?: (app: LauncherApp) => Promise<LauncherApp[] | void> | LauncherApp[] | void;
  saveApp?: (app: LauncherApp, changes: LauncherAppChanges) => Promise<LauncherApp[] | void> | LauncherApp[] | void;
  pinApp?: (app: LauncherApp) => Promise<void> | void;
  updateAppIcon?: (app: LauncherApp, iconDataUrl: string | null) => Promise<LauncherApp[] | void> | LauncherApp[] | void;
  chooseAppIcon?: (app: LauncherApp) => Promise<string | null | undefined> | string | null | undefined;
};

export type LauncherAppChanges = {
  name: string;
  startUrl: string;
  iconDataUrl?: string | null;
  iconSource?: "custom" | "site" | "title";
};

export type LauncherSyncState = "idle" | "syncing" | "success" | "error" | "needs-settings";

export type LauncherSyncActionLabels = {
  settings: string;
  sync: string;
  syncing: string;
  syncTitle?: string;
  setupTitle?: string;
};

export type LauncherLabels = {
  loading: string;
  emptyDesktop: string;
  loadAppsError: string;
  loadLayoutError: string;
  folder: string;
  folderEmpty: string;
  open: string;
  openStandaloneApp: string;
  editHomeScreen: string;
  done: string;
  newFolder: string;
  renameFolder: string;
  unpackFolder: string;
  moveToDesktop: string;
  moveToDock: string;
  dockFull: string;
  pinApp: string;
  editApp: string;
  editIcon: string;
  appName: string;
  appUrl: string;
  deleteApp: string;
  iconTitle: string;
  iconLetter: string;
  iconBackground: string;
  iconPreset: string;
  iconSourceTitle: string;
  iconSite: string;
  iconTitleFallback: string;
  iconDefaultLibrary: string;
  iconChooseImage: string;
  iconSelectedImage: string;
  iconPresetLabels?: Partial<Record<string, string>>;
  iconUpload: string;
  iconUseImage: string;
  iconReset: string;
  cancel: string;
  save: string;
  deleteFailed: string;
  editFailed: string;
  iconUpdateFailed: string;
};

const defaultLabels: LauncherLabels = {
  loading: "Loading desktop...",
  emptyDesktop: "This page is empty.",
  loadAppsError: "Unable to load WebApps.",
  loadLayoutError: "Unable to load desktop layout.",
  folder: "Folder",
  folderEmpty: "This folder is empty.",
  open: "Open",
  openStandaloneApp: "Open as WebApp",
  editHomeScreen: "Edit Desktop",
  done: "Done",
  newFolder: "New folder",
  renameFolder: "Rename folder",
  unpackFolder: "Move items out",
  moveToDesktop: "Move to desktop",
  moveToDock: "Move to Dock",
  dockFull: "Dock can hold up to 4 items.",
  pinApp: "Send to home screen",
  editApp: "Edit WebApp",
  editIcon: "Edit icon",
  appName: "Name",
  appUrl: "URL",
  deleteApp: "Delete",
  iconTitle: "Edit icon",
  iconLetter: "Text",
  iconBackground: "Background",
  iconPreset: "Preset",
  iconSourceTitle: "Icon source",
  iconSite: "Site icon",
  iconTitleFallback: "Title initial",
  iconDefaultLibrary: "Default icons",
  iconChooseImage: "Choose image",
  iconSelectedImage: "Selected image",
  iconUpload: "Upload image",
  iconUseImage: "Use image",
  iconReset: "Reset icon",
  cancel: "Cancel",
  save: "Save",
  deleteFailed: "Unable to delete WebApp.",
  editFailed: "Unable to edit WebApp.",
  iconUpdateFailed: "Unable to update icon.",
};

export function LauncherSyncActions(props: {
  labels: LauncherSyncActionLabels;
  message?: string;
  settingsConfigured?: boolean;
  state: LauncherSyncState;
  onOpenSettings: () => void;
  onSync: () => void;
}) {
  const syncing = props.state === "syncing";
  const title = props.settingsConfigured
    ? props.labels.syncTitle || props.labels.sync
    : props.labels.setupTitle || props.labels.sync;

  return (
    <>
      {props.message && (
        <button
          className={`desktop-sync-status ${props.state}`}
          type="button"
          onClick={() => props.state === "needs-settings" && props.onOpenSettings()}
        >
          {props.message}
        </button>
      )}
      <button type="button" onClick={props.onOpenSettings}>{props.labels.settings}</button>
      <button type="button" disabled={syncing} title={title} onClick={props.onSync}>
        {syncing ? props.labels.syncing : props.labels.sync}
      </button>
    </>
  );
}

export type LauncherPageProps = {
  platform: LauncherPlatform;
  storage: LauncherLayoutStorage;
  labels?: Partial<LauncherLabels>;
  className?: string;
  variant?: "desktop" | "mobile";
  topActions?: React.ReactNode;
  refreshToken?: unknown;
  onLayoutChanged?: (layout: LauncherLayout) => void;
};

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
  slotDropId?: string;
};

type ResolvedDropTarget = {
  targetId: string;
  targetContainer?: LauncherContainer;
  targetFolderId?: string;
  targetCell?: DesktopCellPosition;
  slotDropId?: string;
};

export function LauncherPage({ platform, storage, labels: labelOverrides, className = "", variant = "desktop", topActions, refreshToken, onLayoutChanged }: LauncherPageProps) {
  const labels = useMemo<LauncherLabels>(() => ({ ...defaultLabels, ...labelOverrides }), [labelOverrides]);
  const gridMetrics = useDesktopGridMetrics(variant);
  const [apps, setApps] = useState<LauncherApp[]>([]);
  const [layout, setLayout] = useState<LauncherLayout>({ version: LAYOUT_VERSION, cells: [], dock: [...platform.defaultDockEntryIds], folders: [], gridColumns: gridMetrics.columns });
  const [loading, setLoading] = useState(true);
  const [toast, setToast] = useState("");
  const [menu, setMenu] = useState<MenuState | null>(null);
  const [appEditor, setAppEditor] = useState<AppEntry | null>(null);
  const [openFolderId, setOpenFolderId] = useState<string | null>(null);
  const [editMode, setEditMode] = useState(false);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [dropPreview, setDropPreview] = useState<DropPreview | null>(null);
  const longPressTimer = useRef<number | null>(null);
  const pointerX = useRef<number | null>(null);
  const pointerY = useRef<number | null>(null);
  const suppressClickForId = useRef<string | null>(null);
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));
  const deprecatedEntryIds = useMemo(() => new Set(platform.deprecatedEntryIds || []), [platform.deprecatedEntryIds]);

  useEffect(() => {
    let cancelled = false;
    async function loadDesktop() {
      try {
        const nextLayout = normalizeStoredLayout(await storage.load(), platform.defaultDockEntryIds, gridMetrics, labels.folder, deprecatedEntryIds);
        if (!cancelled) setLayout(nextLayout);
      } catch (loadError) {
        if (!cancelled) showToast(loadError instanceof Error ? loadError.message : labels.loadLayoutError);
      }

      try {
        const records = await platform.loadApps();
        if (!cancelled) {
          setApps(records);
        }
      } catch (loadError) {
        if (!cancelled) {
          showToast(loadError instanceof Error ? loadError.message : labels.loadAppsError);
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    loadDesktop();
    return () => {
      cancelled = true;
    };
  }, [deprecatedEntryIds, gridMetrics, labels.folder, labels.loadAppsError, labels.loadLayoutError, platform, refreshToken, storage]);

  useEffect(() => {
    if (loading) return;
    Promise.resolve(storage.save({ ...layout, version: LAYOUT_VERSION, gridColumns: gridMetrics.columns })).catch(console.error);
  }, [gridMetrics.columns, layout, loading, storage]);

  useEffect(() => {
    if (!toast) return undefined;
    const timer = window.setTimeout(() => setToast(""), 3000);
    return () => window.clearTimeout(timer);
  }, [toast]);

  useEffect(() => {
    const updatePointerX = (event: Event) => {
      pointerX.current = pointerClientX(event) ?? pointerX.current;
      pointerY.current = pointerClientY(event) ?? pointerY.current;
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

  const systemEntries = useMemo<LauncherSystemEntry[]>(() => platform.systemEntries, [platform.systemEntries]);

  const appEntries = useMemo<AppEntry[]>(() => apps.map((app) => ({
    id: `app:${app.id}`,
    kind: "app",
    title: app.name || hostLabel(app.startUrl),
    mark: appInitial(app.name || hostLabel(app.startUrl)),
    color: colorFromTheme(app.themeColor, app.id),
    app,
  })), [apps]);

  const availableEntries = useMemo(() => new Map<string, LauncherSystemEntry | AppEntry>([
    ...systemEntries.map((item) => [item.id, item] as const),
    ...appEntries.map((item) => [item.id, item] as const),
  ]), [appEntries, systemEntries]);

  const folders = useMemo<FolderEntry[]>(() => layout.folders.map((folder) => ({
    id: folder.id,
    kind: "folder",
    title: folder.title || labels.folder,
    childIds: folder.childIds.filter((id) => availableEntries.has(id)),
    children: folder.childIds
      .map((id) => availableEntries.get(id))
      .filter((item): item is LauncherSystemEntry | AppEntry => !!item)
  })), [availableEntries, labels.folder, layout.folders]);

  const folderEntries = useMemo(() => new Map(folders.map((folder) => [folder.id, folder] as const)), [folders]);
  const containedIds = useMemo(() => new Set(folders.flatMap((folder) => folder.childIds)), [folders]);
  const dockIds = useMemo(() => layout.dock.filter((id) => !containedIds.has(id) && (availableEntries.has(id) || folderEntries.has(id))), [availableEntries, containedIds, folderEntries, layout.dock]);
  const dockEntries = useMemo(() => dockIds
    .map((id) => folderEntries.get(id) || availableEntries.get(id))
    .filter((item): item is LauncherEntry => !!item), [availableEntries, dockIds, folderEntries]);

  const visibleDesktopIds = useMemo(() => {
    const allIds = [...availableEntries.keys(), ...folderEntries.keys()];
    return allIds
      .filter((id) => !containedIds.has(id) && !dockIds.includes(id))
      .sort(compareLauncherIds);
  }, [availableEntries, containedIds, dockIds, folderEntries]);

  const desktopCells = useMemo(
    () => normalizeDesktopCells(layout.cells, visibleDesktopIds, gridMetrics),
    [gridMetrics, layout.cells, visibleDesktopIds],
  );
  const desktopIds = useMemo(
    () => desktopCells
      .sort((left, right) => globalCellIndex(left, gridMetrics) - globalCellIndex(right, gridMetrics))
      .map((cell) => cell.id),
    [desktopCells, gridMetrics],
  );
  const desktopSortingStrategy = useMemo(
    () => createDesktopSortingStrategy(desktopIds, desktopCells, gridMetrics, activeId, dropPreview),
    [activeId, desktopCells, desktopIds, dropPreview, gridMetrics],
  );
  const desktopEntries = useMemo(() => desktopIds
      .map((id) => folderEntries.get(id) || availableEntries.get(id))
      .filter((item): item is LauncherEntry => !!item), [availableEntries, desktopIds, folderEntries]);
  const desktopSlots = useMemo(() => buildDesktopSlots(desktopCells, gridMetrics, resolveEntry), [desktopCells, gridMetrics, availableEntries, folderEntries]);

  const openFolder = openFolderId ? folderEntries.get(openFolderId) : undefined;
  const openFolderIds = openFolder?.childIds || [];
  const activeEntry = activeId ? resolveEntry(activeId) : undefined;

  function commitLayout(updater: (current: LauncherLayout) => LauncherLayout) {
    setLayout((current) => {
      const next = {
        ...limitDockItems(pruneEmptyFolders(removeDeprecatedEntryIds(updater(current), deprecatedEntryIds))),
        version: LAYOUT_VERSION,
        gridColumns: gridMetrics.columns,
        updatedAt: Date.now(),
      };
      onLayoutChanged?.(next);
      return next;
    });
  }

  useEffect(() => {
    if (loading) return;
    setLayout((current) => {
      const cleaned = limitDockItems(pruneEmptyFolders(removeDeprecatedEntryIds(current, deprecatedEntryIds)));
      const cleanedChanged = cleaned !== current;
      const prunedResult = removeUnavailableEntryIds(
        cleaned,
        availableEntries,
      );
      const pruned = prunedResult.layout;
      const visibleDesktopIdSet = new Set(visibleDesktopIds);
      const preservedCellIds = pruned.cells
        .map((cell) => cell.id)
        .filter((id) => visibleDesktopIdSet.has(id));
      const nextCellIds = uniqueStrings([...preservedCellIds, ...visibleDesktopIds]);
      const nextCells = normalizeDesktopCells(pruned.cells, nextCellIds, gridMetrics);
      const semanticCellsChanged = !sameCellIndexes(pruned.cells, nextCells, gridMetrics);
      const next = sameCells(pruned.cells, nextCells, gridMetrics) && pruned.gridColumns === gridMetrics.columns
        ? pruned
        : {
            ...pruned,
            cells: nextCells,
            version: LAYOUT_VERSION,
            gridColumns: gridMetrics.columns,
            updatedAt: cleanedChanged || prunedResult.changed || semanticCellsChanged ? Date.now() : pruned.updatedAt,
          };
      return next === current ? current : next;
    });
  }, [availableEntries, deprecatedEntryIds, gridMetrics, loading, visibleDesktopIds]);

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
      platform.openApp(entry.app);
      return;
    }
    platform.openSystem(entry.action);
  }

  function openStandaloneApp(itemId: string) {
    const entry = availableEntries.get(itemId);
    if (!entry || entry.kind !== "app" || !platform.openStandaloneApp) return;
    platform.openStandaloneApp(entry.app);
    closeMenu();
  }

  function showToast(message: string) {
    setToast(message);
  }

  function canPlaceInDock(itemId: string): boolean {
    return dockIds.includes(itemId) || dockIds.length < MAX_DOCK_ITEMS;
  }

  function rejectDockIfFull(itemId: string): boolean {
    if (canPlaceInDock(itemId)) return false;
    showToast(labels.dockFull);
    return true;
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
    pointerY.current = pointerClientY(event.activatorEvent) ?? pointerY.current;
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
    if (!editMode) {
      setDropPreview(null);
      return;
    }
    const currentPointerX = dragPointerClientX(event, pointerX.current);
    const currentPointerY = dragPointerClientY(event, pointerY.current);
    pointerX.current = currentPointerX ?? pointerX.current;
    pointerY.current = currentPointerY ?? pointerY.current;
    if (closeOpenFolderWhenDragLeaves(
      containerData(event.active.data.current?.container),
      stringData(event.active.data.current?.folderId),
      currentPointerX,
      currentPointerY,
    )) {
      setDropPreview(null);
      return;
    }
    if (!event.over) {
      setDropPreview(null);
      return;
    }
    const rawTargetId = String(event.over.id);
    const resolvedTarget = resolveDropTarget(
      rawTargetId,
      containerData(event.over.data.current?.container),
      stringData(event.over.data.current?.folderId),
      cellData(event.over.data.current),
    );
    if (resolvedTarget.targetId === String(event.active.id) || DROP_ZONE_IDS.has(resolvedTarget.targetId)) {
      setDropPreview(null);
      return;
    }
    const placementRect = resolvedTarget.slotDropId ? rectForDropSlot(resolvedTarget.slotDropId) || event.over.rect : event.over.rect;
    const placement = dropPlacementFor(currentPointerX, placementRect, event.active.rect.current.translated);
    setDropPreview(dropPreviewFor(
      String(event.active.id),
      containerData(event.active.data.current?.container),
      stringData(event.active.data.current?.folderId),
      resolvedTarget.targetId,
      resolvedTarget.targetContainer,
      resolvedTarget.targetFolderId,
      resolvedTarget.targetCell,
      placement,
      resolvedTarget.slotDropId,
    ));
  }

  function closeOpenFolderWhenDragLeaves(
    sourceContainer: LauncherContainer | undefined,
    sourceFolderId: string | undefined,
    clientX: number | null,
    clientY: number | null,
  ): boolean {
    if (sourceContainer !== "folder" || !sourceFolderId || openFolderId !== sourceFolderId) return false;
    if (clientX === null || clientY === null) return false;
    const folderRect = document.querySelector<HTMLElement>(".desktop-folder")?.getBoundingClientRect();
    if (!folderRect) return false;
    const insideFolder = clientX >= folderRect.left
      && clientX <= folderRect.right
      && clientY >= folderRect.top
      && clientY <= folderRect.bottom;
    if (insideFolder) return false;
    setOpenFolderId(null);
    return true;
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
    slotDropId?: string,
  ): DropPreview | null {
    if (targetCell) {
      return { kind: "cell", targetId, placement: "inside", slotDropId };
    }
    const targetEntry = resolveEntry(targetId);
    if (!targetEntry) return null;
    const itemIsFolder = itemId.startsWith("folder:");

    if (targetEntry.kind === "folder") {
      if (placement === "inside") return itemIsFolder ? null : { kind: "folder", targetId, placement: "inside", slotDropId };
      if (targetContainer === "dock") {
        if (!canPlaceInDock(itemId)) return null;
        return { kind: "insert", targetId, placement: normalizeInsertPlacement(placement, "after") };
      }
      if (!targetFolderId) {
        return { kind: "insert", targetId, placement: normalizeInsertPlacement(placement, "before"), slotDropId };
      }
      return null;
    }
    if (sourceFolderId && targetFolderId === sourceFolderId) {
      return { kind: "insert", targetId, placement: normalizeInsertPlacement(placement, "before") };
    }
    if (targetContainer === "dock" && placement !== "inside" && !canPlaceInDock(itemId)) {
      return null;
    }
    if (!targetFolderId && !itemIsFolder && placement === "inside") {
      return { kind: "folder", targetId, placement, slotDropId };
    }
    if (targetContainer === "dock") {
      return { kind: "insert", targetId, placement: normalizeInsertPlacement(placement, "after") };
    }
    if (sourceContainer === "folder" && !targetFolderId) {
      return { kind: "insert", targetId, placement: normalizeInsertPlacement(placement, "before"), slotDropId };
    }
    if (!targetFolderId) {
      return { kind: "insert", targetId, placement: normalizeInsertPlacement(placement, "before"), slotDropId };
    }
    return itemIsFolder ? null : { kind: "folder", targetId, placement: "inside" };
  }

  function desktopSlotDropId(
    targetId: string,
    targetContainer: LauncherContainer | undefined,
    targetCell: DesktopCellPosition | undefined,
  ): string | undefined {
    if (targetCell) return cellDropId(targetCell);
    if (targetContainer !== "desktop") return undefined;
    return desktopSlots.find((slot) => slot.entry?.id === targetId)?.dropId;
  }

  function resolveDropTarget(
    targetId: string,
    targetContainer: LauncherContainer | undefined,
    targetFolderId: string | undefined,
    targetCell: DesktopCellPosition | undefined,
  ): ResolvedDropTarget {
    const slotDropId = desktopSlotDropId(targetId, targetContainer, targetCell);
    if (targetCell && targetContainer === "desktop") {
      const slot = desktopSlots.find((item) => sameCellPosition(item, targetCell));
      if (slot?.entry) {
        return {
          targetId: slot.entry.id,
          targetContainer: "desktop",
          targetFolderId: undefined,
          targetCell: undefined,
          slotDropId: slot.dropId,
        };
      }
    }
    return { targetId, targetContainer, targetFolderId, targetCell, slotDropId };
  }

  function rectForDropSlot(slotDropId: string): DOMRect | null {
    return rectForDropSlotId(slotDropId);
  }

  function handleDragEnd(event: DragEndEvent) {
    const draggedId = String(event.active.id);
    const sourceFolderId = stringData(event.active.data.current?.folderId);
    const sourceContainer = containerData(event.active.data.current?.container);
    const resolvedTarget = event.over
      ? resolveDropTarget(
        String(event.over.id),
        containerData(event.over.data.current?.container),
        stringData(event.over.data.current?.folderId),
        cellData(event.over.data.current),
      )
      : null;
    const placementRect = resolvedTarget?.slotDropId ? rectForDropSlot(resolvedTarget.slotDropId) || event.over?.rect : event.over?.rect;
    const currentPointerX = dragPointerClientX(event, pointerX.current);
    pointerX.current = currentPointerX ?? pointerX.current;
    const placement = placementRect ? dropPlacementFor(currentPointerX, placementRect, event.active.rect.current.translated) : "inside";
    setActiveId(null);
    setDropPreview(null);
    if (!resolvedTarget || draggedId === resolvedTarget.targetId) return;
    handleDrop(
      draggedId,
      sourceContainer,
      sourceFolderId,
      resolvedTarget.targetId,
      resolvedTarget.targetContainer,
      resolvedTarget.targetFolderId,
      resolvedTarget.targetCell,
      placement,
    );
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
    const itemIsFolder = itemId.startsWith("folder:");
    if (targetEntry.kind === "folder") {
      if (placement === "inside") {
        if (!itemIsFolder) moveToFolder(itemId, targetEntry.id);
        return;
      }
      if (targetContainer === "dock") {
        moveToDockNear(itemId, targetId, normalizeInsertPlacement(placement, "after"));
        return;
      }
      if (sourceContainer === "folder" && !targetFolderId) {
        moveToDesktopNear(itemId, targetId, normalizeInsertPlacement(placement, "before"));
        return;
      }
      if (!targetFolderId) {
        moveToDesktopNear(itemId, targetId, normalizeInsertPlacement(placement, "before"));
      }
      return;
    }
    if (sourceFolderId && targetFolderId === sourceFolderId) {
      moveInsideFolderRelative(sourceFolderId, itemId, targetId, normalizeInsertPlacement(placement, "before"));
      return;
    }
    if (!targetFolderId && !itemIsFolder && placement === "inside") {
      createFolderFromDrop(itemId, targetId, targetContainer);
      return;
    }
    if (targetContainer === "dock") {
      moveToDockNear(itemId, targetId, normalizeInsertPlacement(placement, "after"));
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
      cells: insertNearDesktopCell(current.cells, itemId, targetId, placement, gridMetrics),
      dock: current.dock.filter((id) => id !== itemId),
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    setOpenFolderId(null);
  }

  function moveToDesktopEnd(itemId: string) {
    commitLayout((current) => ({
      ...current,
      cells: appendToDesktopCells(current.cells, itemId, gridMetrics),
      dock: current.dock.filter((id) => id !== itemId),
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    setOpenFolderId(null);
  }

  function moveToDockNear(itemId: string, targetId: string, placement: DropPlacement) {
    if (rejectDockIfFull(itemId)) return;
    commitLayout((current) => ({
      ...current,
      cells: removeFromCells(current.cells, itemId),
      dock: insertNearDockItem(dockIds, itemId, targetId, placement),
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    setOpenFolderId(null);
  }

  function moveToDockEnd(itemId: string) {
    if (rejectDockIfFull(itemId)) return;
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
      cells: appendToDesktopCells(current.cells, itemId, gridMetrics),
      dock: current.dock.filter((id) => id !== itemId),
      folders: removeChildFromFolders(current.folders, itemId),
    }));
    closeMenu();
  }

  function moveToDock(itemId: string) {
    if (rejectDockIfFull(itemId)) {
      closeMenu();
      return;
    }
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
        : replaceInCellsOrAppend(current.cells, itemId, folderId, gridMetrics),
      dock: sourceContainer === "dock"
        ? current.dock.map((id) => (id === itemId ? folderId : id))
        : current.dock.filter((id) => id !== itemId),
      folders: [
        ...removeChildFromFolders(current.folders, itemId),
        { id: folderId, title: labels.folder, childIds: [itemId] },
      ],
    }));
    setOpenFolderId(folderId);
    closeMenu();
  }

  function createFolderFromDrop(itemId: string, targetId: string, targetContainer: LauncherContainer | undefined) {
    if (itemId === targetId || itemId.startsWith("folder:") || targetId.startsWith("folder:")) return;
    const folderId = `folder:${crypto.randomUUID()}`;
    commitLayout((current) => ({
      ...current,
      cells: targetContainer === "dock"
        ? removeManyFromCells(current.cells, [itemId, targetId])
        : replaceCellPairWithFolder(current.cells, itemId, targetId, folderId, gridMetrics),
      dock: targetContainer === "dock"
        ? replaceDockPairWithFolder(current.dock, itemId, targetId, folderId)
        : current.dock.filter((id) => id !== itemId && id !== targetId),
      folders: [
        ...current.folders.map((folder) => ({
          ...folder,
          childIds: folder.childIds.filter((id) => id !== itemId && id !== targetId),
        })),
        { id: folderId, title: labels.folder, childIds: [targetId, itemId] },
      ],
    }));
    setOpenFolderId(folderId);
    closeMenu();
  }

  function renameFolder(folderId: string) {
    const folder = layout.folders.find((item) => item.id === folderId);
    const title = window.prompt(labels.renameFolder, folder?.title || labels.folder);
    if (!title) return;
    commitLayout((current) => ({
      ...current,
      folders: current.folders.map((item) => (item.id === folderId ? { ...item, title: title.trim() || labels.folder } : item)),
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
    if (!entry || entry.kind !== "app" || !platform.deleteApp) return;
    Promise.resolve(platform.deleteApp(entry.app))
      .then((nextApps) => {
        if (Array.isArray(nextApps)) setApps(nextApps);
        commitLayout((current) => ({
          ...current,
          cells: removeFromCells(current.cells, itemId),
          dock: current.dock.filter((id) => id !== itemId),
          folders: removeChildFromFolders(current.folders, itemId),
        }));
        closeMenu();
      })
      .catch((deleteError) => showToast(deleteError instanceof Error ? deleteError.message : labels.deleteFailed));
  }

  function pinApp(itemId: string) {
    const entry = availableEntries.get(itemId);
    if (!entry || entry.kind !== "app" || !platform.pinApp) return;
    Promise.resolve(platform.pinApp(entry.app)).catch((pinError) => showToast(pinError instanceof Error ? pinError.message : labels.editFailed));
    closeMenu();
  }

  function editApp(itemId: string) {
    const entry = availableEntries.get(itemId);
    if (!entry || entry.kind !== "app") return;
    if (platform.saveApp) {
      setAppEditor(entry);
      closeMenu();
      return;
    }
    if (!platform.editApp) return;
    Promise.resolve(platform.editApp(entry.app))
      .then((nextApps) => {
        if (Array.isArray(nextApps)) setApps(nextApps);
      })
      .catch((editError) => showToast(editError instanceof Error ? editError.message : labels.editFailed));
    closeMenu();
  }

  function editIcon(itemId: string) {
    const entry = availableEntries.get(itemId);
    if (!entry || entry.kind !== "app" || !platform.updateAppIcon) return;
    setAppEditor(entry);
    closeMenu();
  }

  function saveApp(app: LauncherApp, changes: LauncherAppChanges) {
    const updater = platform.saveApp
      ? platform.saveApp(app, changes)
      : (Object.prototype.hasOwnProperty.call(changes, "iconDataUrl") && platform.updateAppIcon
        ? platform.updateAppIcon(app, changes.iconDataUrl ?? null)
        : undefined);
    if (!updater) return;
    Promise.resolve(updater)
      .then((nextApps) => {
        if (Array.isArray(nextApps)) {
          setApps(nextApps);
        } else {
          setApps((current) => current.map((item) => (
            item.id === app.id
              ? {
                ...item,
                name: changes.name,
                startUrl: changes.startUrl,
                iconDataUrl: Object.prototype.hasOwnProperty.call(changes, "iconDataUrl") ? changes.iconDataUrl ?? undefined : item.iconDataUrl,
                iconSource: changes.iconSource || item.iconSource,
              }
              : item
          )));
        }
        setAppEditor(null);
      })
      .catch((editError) => showToast(editError instanceof Error ? editError.message : labels.editFailed));
  }

  return (
    <main className={`desktop-page ${variant}${className ? ` ${className}` : ""}`} onPointerMoveCapture={(event) => { pointerX.current = event.clientX; }}>
      <div className="desktop-commands">
        {topActions}
        {editMode && <button type="button" onClick={() => setEditMode(false)}>{labels.done}</button>}
      </div>
      {toast && (
        <button className="desktop-toast" type="button" onClick={() => setToast("")}>
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
        <SortableContext items={desktopIds} strategy={desktopSortingStrategy}>
          <DesktopDropGrid
            columns={gridMetrics.columns}
            editMode={editMode}
            enabled={editMode && !openFolder}
          >
            {loading && <div className="desktop-status">{labels.loading}</div>}
            {!loading && desktopEntries.length === 0 && !editMode && <div className="desktop-status desktop-empty-status">{labels.emptyDesktop}</div>}
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
                  {editMode && <button type="button" onClick={() => renameFolder(openFolder.id)}>{labels.renameFolder}</button>}
                  <button type="button" onClick={() => setOpenFolderId(null)}>{labels.done}</button>
                </div>
              </header>
              <SortableContext items={openFolderIds} strategy={rectSortingStrategy}>
                <div className="desktop-folder-grid">
                  {openFolder.children.length === 0 ? (
                    <div className="desktop-status">{labels.folderEmpty}</div>
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

      {menu && (
        <DesktopMenu
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
          onOpenStandaloneApp={openStandaloneApp}
          onStartEditMode={() => {
            setEditMode(true);
            closeMenu();
          }}
          onCreateFolder={createFolderWith}
          onMoveToDock={moveToDock}
          onMoveToDesktop={moveToDesktop}
          onRenameFolder={renameFolder}
          onUnpackFolder={unpackFolder}
          onPinApp={pinApp}
          onEditApp={editApp}
          onEditIcon={editIcon}
          onDeleteApp={deleteApp}
          canMoveToDock={menu.sourceContainer === "dock" || canPlaceInDock(menu.itemId)}
          canPinApp={!!platform.pinApp}
          canOpenStandaloneApp={!!platform.openStandaloneApp}
          canEditApp={!!platform.saveApp || !!platform.editApp}
          canEditIcon={!platform.saveApp && !!platform.updateAppIcon}
          canDeleteApp={!!platform.deleteApp}
          labels={labels}
        />
      )}
      {appEditor && (
        <WebAppEditorDialog
          app={appEditor.app}
          labels={labels}
          onClose={() => setAppEditor(null)}
          onChooseImage={platform.chooseAppIcon ? () => platform.chooseAppIcon?.(appEditor.app) : undefined}
          onSave={(changes) => saveApp(appEditor.app, changes)}
        />
      )}
    </main>
  );
}

export function WebAppEditorDialog(props: {
  app: LauncherApp;
  labels: LauncherLabels;
  onClose: () => void;
  onChooseImage?: () => Promise<string | null | undefined> | string | null | undefined;
  onSave: (changes: LauncherAppChanges) => void;
}) {
  const title = props.app.name || hostLabel(props.app.startUrl);
  const [name, setName] = useState(title);
  const [startUrl, setStartUrl] = useState(props.app.startUrl);
  const [imageDataUrl, setImageDataUrl] = useState<string | null>(props.app.iconDataUrl || null);
  const [imageName, setImageName] = useState("");
  const [selectedIcon, setSelectedIcon] = useState<WebAppEditorIconSelection>(() => (
    props.app.iconSource === "custom" && props.app.iconDataUrl
      ? { kind: "current" }
      : { kind: "site" }
  ));
  const [error, setError] = useState("");
  const [choosingImage, setChoosingImage] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const previewDataUrl = useMemo(() => {
    const label = name.trim() || title;
    if (selectedIcon.kind === "current" && props.app.iconDataUrl) return props.app.iconDataUrl;
    if (selectedIcon.kind === "image" && imageDataUrl) return imageDataUrl;
    if (selectedIcon.kind === "preset") return createPresetIconDataUrl(selectedIcon.id);
    return props.app.siteIconDataUrl || props.app.iconDataUrl || createTitleIconDataUrl(label, startUrl, props.app.themeColor, props.app.id);
  }, [imageDataUrl, name, props.app.iconDataUrl, props.app.iconSource, props.app.id, props.app.siteIconDataUrl, props.app.themeColor, selectedIcon, startUrl, title]);

  function readImage(file: File | undefined) {
    if (!file) return;
    normalizeUploadedIcon(file)
      .then((dataUrl) => {
        setImageDataUrl(dataUrl);
        setImageName(file.name);
        setSelectedIcon({ kind: "image" });
        setError("");
      })
      .catch(() => setError(props.labels.iconUpdateFailed));
  }

  function chooseImage() {
    setError("");
    if (!props.onChooseImage) {
      fileInputRef.current?.click();
      return;
    }
    setChoosingImage(true);
    Promise.resolve(props.onChooseImage())
      .then((dataUrl) => {
        if (!dataUrl) return;
        setImageDataUrl(dataUrl);
        setImageName(props.labels.iconSelectedImage);
        setSelectedIcon({ kind: "image" });
      })
      .catch(() => setError(props.labels.iconUpdateFailed))
      .finally(() => setChoosingImage(false));
  }

  function save() {
    const cleanName = name.trim() || hostLabel(startUrl);
    const cleanUrl = startUrl.trim();
    if (!cleanUrl) {
      setError(props.labels.editFailed);
      return;
    }
    const changes: LauncherAppChanges = { name: cleanName, startUrl: cleanUrl };
    if (selectedIcon.kind === "site") {
      changes.iconDataUrl = null;
      changes.iconSource = props.app.siteIconDataUrl || props.app.iconSource === "site" ? "site" : "title";
    }
    if (selectedIcon.kind === "preset") {
      changes.iconDataUrl = createPresetIconDataUrl(selectedIcon.id);
      changes.iconSource = "custom";
    }
    if (selectedIcon.kind === "image" && imageDataUrl) {
      changes.iconDataUrl = imageDataUrl;
      changes.iconSource = "custom";
    }
    props.onSave(changes);
  }

  const currentIsCustom = props.app.iconSource === "custom" && !!props.app.iconDataUrl;
  const siteLabel = props.app.siteIconDataUrl || props.app.iconSource === "site" ? props.labels.iconSite : props.labels.iconTitleFallback;
  const titleIconUrl = createTitleIconDataUrl(name.trim() || title, startUrl, props.app.themeColor, props.app.id);

  return (
    <div className="desktop-menu-scrim" onClick={props.onClose}>
      <section className="webapp-editor-dialog" role="dialog" aria-modal="true" aria-label={props.labels.editApp} onClick={(event) => event.stopPropagation()}>
        <header className="icon-editor-header">
          <h2>{props.labels.editApp}</h2>
          <button type="button" onClick={props.onClose}>{props.labels.cancel}</button>
        </header>
        <div className="webapp-editor-preview">
          <img src={previewDataUrl} alt="" />
          <div>
            <strong>{name.trim() || title}</strong>
            <span>{startUrl}</span>
          </div>
        </div>
        <div className="webapp-editor-fields">
          <label className="icon-editor-field">
            <span>{props.labels.appName}</span>
            <input value={name} onChange={(event) => { setName(event.currentTarget.value); setError(""); }} />
          </label>
          <label className="icon-editor-field">
            <span>{props.labels.appUrl}</span>
            <input value={startUrl} onChange={(event) => { setStartUrl(event.currentTarget.value); setError(""); }} />
          </label>
        </div>
        <div className="webapp-icon-source-title">{props.labels.iconSourceTitle}</div>
        <div className="webapp-icon-options" aria-label={props.labels.iconSourceTitle}>
          {currentIsCustom && (
            <IconSourceOption
              label={props.labels.iconSelectedImage}
              selected={selectedIcon.kind === "current"}
              onClick={() => setSelectedIcon({ kind: "current" })}
            >
              <img src={props.app.iconDataUrl!} alt="" />
            </IconSourceOption>
          )}
          <IconSourceOption
            label={siteLabel}
            selected={selectedIcon.kind === "site"}
            onClick={() => setSelectedIcon({ kind: "site" })}
          >
            <img src={props.app.siteIconDataUrl || (props.app.iconSource === "site" ? props.app.iconDataUrl || titleIconUrl : titleIconUrl)} alt="" />
          </IconSourceOption>
          {WEBAPP_ICON_PRESETS.map((preset) => (
            <IconSourceOption
              key={preset.id}
              label={props.labels.iconPresetLabels?.[preset.id] || preset.label}
              selected={selectedIcon.kind === "preset" && selectedIcon.id === preset.id}
              onClick={() => setSelectedIcon({ kind: "preset", id: preset.id })}
            >
              <PresetIconPreview preset={preset} />
            </IconSourceOption>
          ))}
          {imageDataUrl && selectedIcon.kind === "image" && (
            <IconSourceOption
              label={props.labels.iconSelectedImage}
              selected
              onClick={() => setSelectedIcon({ kind: "image" })}
            >
              <img src={imageDataUrl} alt="" />
            </IconSourceOption>
          )}
        </div>
        <div className="icon-editor-upload">
          <span>{props.labels.iconChooseImage}</span>
          <button className="icon-editor-file-button" type="button" disabled={choosingImage} onClick={chooseImage}>
            {props.labels.iconChooseImage}
          </button>
          <span className="icon-editor-file-name">{imageName || (selectedIcon.kind === "image" ? props.labels.iconSelectedImage : "")}</span>
          <input
            accept="image/*"
            ref={fileInputRef}
            type="file"
            onChange={(event) => readImage(event.currentTarget.files?.[0])}
          />
        </div>
        {error && <div className="webapp-editor-error">{error}</div>}
        <div className="icon-editor-actions">
          <button type="button" onClick={props.onClose}>{props.labels.cancel}</button>
          <button className="primary" type="button" onClick={save}>{props.labels.save}</button>
        </div>
      </section>
    </div>
  );
}

type WebAppEditorIconSelection =
  | { kind: "current" }
  | { kind: "site" }
  | { kind: "preset"; id: string }
  | { kind: "image" };

function IconSourceOption(props: {
  children: React.ReactNode;
  label: string;
  selected: boolean;
  onClick: () => void;
}) {
  return (
    <button className={`webapp-icon-option${props.selected ? " selected" : ""}`} type="button" onClick={props.onClick}>
      <span className="webapp-icon-option-preview">{props.children}</span>
      <span>{props.label}</span>
    </button>
  );
}

function PresetIconPreview({ preset }: { preset: WebAppIconPreset }) {
  return (
    <svg viewBox="0 0 192 192" aria-hidden="true">
      <rect width="192" height="192" rx="42" fill={preset.background} />
      <path d={preset.path} fill={preset.foreground} transform="translate(36 36) scale(5)" />
    </svg>
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

function DesktopDropGrid(props: {
  children: React.ReactNode;
  columns: number;
  editMode: boolean;
  enabled: boolean;
}) {
  const gridRef = useRef<HTMLElement | null>(null);
  const [scrollbar, setScrollbar] = useState<{ thumbHeight: number; thumbTop: number } | null>(null);
  const { setNodeRef, isOver } = useDroppable({
    id: DESKTOP_DROP_ID,
    data: { kind: "desktop" },
    disabled: !props.enabled,
  });
  const setGridRef = useCallback((node: HTMLElement | null) => {
    gridRef.current = node;
    setNodeRef(node);
  }, [setNodeRef]);

  useEffect(() => {
    const node = gridRef.current;
    if (!node) return undefined;

    let frame = 0;
    const updateScrollbar = () => {
      window.cancelAnimationFrame(frame);
      frame = window.requestAnimationFrame(() => {
        const target = gridRef.current;
        if (!target) {
          setScrollbar(null);
          return;
        }

        const canScroll = target.scrollHeight > target.clientHeight + 2;
        if (!canScroll) {
          setScrollbar(null);
          return;
        }

        const trackInset = 8;
        const trackHeight = Math.max(1, target.clientHeight - trackInset * 2);
        const thumbHeight = Math.max(34, Math.round((target.clientHeight / target.scrollHeight) * trackHeight));
        const maxScroll = target.scrollHeight - target.clientHeight;
        const maxThumbTop = Math.max(0, trackHeight - thumbHeight);
        const thumbTop = trackInset + (maxScroll > 0 ? Math.round((target.scrollTop / maxScroll) * maxThumbTop) : 0);

        setScrollbar((current) => {
          if (current?.thumbHeight === thumbHeight && current.thumbTop === thumbTop) return current;
          return { thumbHeight, thumbTop };
        });
      });
    };

    const resizeObserver = new ResizeObserver(updateScrollbar);
    resizeObserver.observe(node);
    node.addEventListener("scroll", updateScrollbar, { passive: true });
    window.addEventListener("resize", updateScrollbar);
    updateScrollbar();

    return () => {
      window.cancelAnimationFrame(frame);
      resizeObserver.disconnect();
      node.removeEventListener("scroll", updateScrollbar);
      window.removeEventListener("resize", updateScrollbar);
    };
  }, [props.children, props.columns]);

  const style = { "--desktop-columns": String(props.columns) } as React.CSSProperties;
  return (
    <div className="desktop-grid-shell">
      <section
        className={`desktop-grid${props.editMode ? " editing" : ""}${isOver ? " desktop-grid-over" : ""}`}
        aria-label="Hyper Browser desktop"
        ref={setGridRef}
        style={style}
      >
        {props.children}
      </section>
      {scrollbar && (
        <div className="desktop-scrollbar" aria-hidden="true">
          <span
            className="desktop-scrollbar-thumb"
            style={{
              height: `${scrollbar.thumbHeight}px`,
              transform: `translateY(${scrollbar.thumbTop}px)`,
            }}
          />
        </div>
      )}
    </div>
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
    disabled: !props.editMode || !!props.slot.entry,
  });
  const isPreview = props.dropPreview?.slotDropId === props.slot.dropId || (props.dropPreview?.kind === "cell" && props.dropPreview.targetId === props.slot.dropId);
  const dropClass = isPreview ? ` drop-${props.dropPreview?.kind} drop-${props.dropPreview?.placement}` : "";
  return (
    <div
      className={`desktop-cell-slot${props.editMode ? " editing" : ""}${isOver || (isPreview && props.dropPreview?.kind === "cell") ? " cell-over" : ""}${dropClass}`}
      data-cell={`${props.slot.page}:${props.slot.row}:${props.slot.column}`}
      data-drop-id={props.slot.dropId}
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
  const dropClass = props.dropPreview?.targetId === props.entry.id && !props.dropPreview.slotDropId ? ` drop-${props.dropPreview.kind} drop-${props.dropPreview.placement}` : "";

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
          if (!child) {
            return <span className="desktop-folder-dot placeholder" key={`empty-${index}`} />;
          }
          const iconDataUrl = child.kind === "app" ? child.app.iconDataUrl : null;
          return (
            <span className={iconDataUrl ? "desktop-folder-dot image" : "desktop-folder-dot"} key={child.id} style={{ background: iconDataUrl ? "#fff" : child.kind === "folder" ? "#dfe5eb" : child.color }}>
              {iconDataUrl ? <img src={iconDataUrl} alt="" /> : child.kind === "folder" ? "" : child.mark.slice(0, 1)}
            </span>
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
  x: number;
  y: number;
  onClose: () => void;
  onOpen: (item: LauncherEntry) => void;
  onOpenStandaloneApp: (itemId: string) => void;
  onStartEditMode: () => void;
  onCreateFolder: (itemId: string, sourceContainer: LauncherContainer) => void;
  onMoveToDock: (itemId: string) => void;
  onMoveToDesktop: (itemId: string) => void;
  onRenameFolder: (folderId: string) => void;
  onUnpackFolder: (folderId: string) => void;
  onPinApp: (itemId: string) => void;
  onEditApp: (itemId: string) => void;
  onEditIcon: (itemId: string) => void;
  onDeleteApp: (itemId: string) => void;
  canMoveToDock: boolean;
  canOpenStandaloneApp: boolean;
  canPinApp: boolean;
  canEditApp: boolean;
  canEditIcon: boolean;
  canDeleteApp: boolean;
  labels: LauncherLabels;
}) {
  const menuRef = useRef<HTMLDivElement | null>(null);
  const [position, setPosition] = useState(() => menuPositionFor(props.x, props.y));
  useLayoutEffect(() => {
    const menu = menuRef.current;
    if (!menu) return;
    const rect = menu.getBoundingClientRect();
    setPosition(menuPositionFor(props.x, props.y, rect.width, rect.height));
  }, [props.x, props.y, props.item?.id, props.sourceContainer, props.sourceFolderId]);

  if (!props.item) return null;
  return (
    <div className="desktop-menu-scrim" onClick={props.onClose}>
      <div className="desktop-menu" ref={menuRef} role="menu" style={position} onClick={(event) => event.stopPropagation()}>
        <div className="desktop-menu-title">{props.item.title}</div>
        <button type="button" role="menuitem" onClick={() => props.onOpen(props.item!)}>{props.labels.open}</button>
        <button type="button" role="menuitem" onClick={props.onStartEditMode}>{props.labels.editHomeScreen}</button>
        {props.item.kind === "folder" ? (
          <>
            <button type="button" role="menuitem" onClick={() => props.onRenameFolder(props.item!.id)}>{props.labels.renameFolder}</button>
            <button type="button" role="menuitem" onClick={() => props.onUnpackFolder(props.item!.id)}>{props.labels.unpackFolder}</button>
            {props.sourceContainer === "dock" ? (
              <button type="button" role="menuitem" onClick={() => props.onMoveToDesktop(props.item!.id)}>{props.labels.moveToDesktop}</button>
            ) : (
              <button type="button" role="menuitem" disabled={!props.canMoveToDock} onClick={() => props.onMoveToDock(props.item!.id)}>{props.labels.moveToDock}</button>
            )}
          </>
        ) : (
          <>
            <button type="button" role="menuitem" onClick={() => props.onCreateFolder(props.item!.id, props.sourceContainer)}>{props.labels.newFolder}</button>
            {props.sourceContainer === "dock" || props.sourceFolderId ? (
              <button type="button" role="menuitem" onClick={() => props.onMoveToDesktop(props.item!.id)}>{props.labels.moveToDesktop}</button>
            ) : null}
            {props.sourceContainer !== "dock" && (
              <button type="button" role="menuitem" disabled={!props.canMoveToDock} onClick={() => props.onMoveToDock(props.item!.id)}>{props.labels.moveToDock}</button>
            )}
            {props.item.kind === "app" && props.canPinApp && (
              <button type="button" role="menuitem" onClick={() => props.onPinApp(props.item!.id)}>{props.labels.pinApp}</button>
            )}
            {props.item.kind === "app" && props.canOpenStandaloneApp && (
              <button type="button" role="menuitem" onClick={() => props.onOpenStandaloneApp(props.item!.id)}>{props.labels.openStandaloneApp}</button>
            )}
            {props.item.kind === "app" && props.canEditApp && (
              <button type="button" role="menuitem" onClick={() => props.onEditApp(props.item!.id)}>{props.labels.editApp}</button>
            )}
            {props.item.kind === "app" && props.canEditIcon && (
              <button type="button" role="menuitem" onClick={() => props.onEditIcon(props.item!.id)}>{props.labels.editIcon}</button>
            )}
            {props.item.kind === "app" && props.canDeleteApp && (
              <button className="danger" type="button" role="menuitem" onClick={() => props.onDeleteApp(props.item!.id)}>{props.labels.deleteApp}</button>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function menuPositionFor(x: number, y: number, width = 240, height = 270): React.CSSProperties {
  const edgePadding = 12;
  const bottomSafe = currentDesktopBottomSafe();
  const viewportWidth = window.visualViewport?.width || window.innerWidth;
  const viewportHeight = window.visualViewport?.height || window.innerHeight;
  const maxLeft = Math.max(edgePadding, viewportWidth - width - edgePadding);
  const maxTop = Math.max(edgePadding, viewportHeight - height - bottomSafe);
  return {
    left: Math.max(edgePadding, Math.min(x, maxLeft)),
    top: Math.max(edgePadding, Math.min(y, maxTop)),
  };
}

function currentDesktopBottomSafe(): number {
  const page = document.querySelector<HTMLElement>(".desktop-page");
  const raw = page ? getComputedStyle(page).getPropertyValue("--desktop-bottom-safe").trim() : "";
  const parsed = Number.parseFloat(raw);
  return Number.isFinite(parsed) ? Math.max(12, parsed) : 12;
}

function normalizeStoredLayout(
  value: StoredLauncherLayout | null | undefined,
  defaultDockEntryIds: string[],
  metrics: DesktopGridMetrics,
  defaultFolderTitle: string,
  deprecatedEntryIds: Set<string>,
): LauncherLayout {
  const dock = (Array.isArray(value?.dock) ? uniqueStrings(value.dock) : [...defaultDockEntryIds])
    .filter((id) => !deprecatedEntryIds.has(id))
    .slice(0, MAX_DOCK_ITEMS);
  const dockSet = new Set(dock);
  const legacyOrder = Array.isArray(value?.order)
    ? uniqueStrings(value.order).filter((id) => !dockSet.has(id) && !deprecatedEntryIds.has(id))
    : [];
  const pages = Array.isArray(value?.pages)
    ? value.pages
      .filter((page): page is string[] => Array.isArray(page))
      .map((page) => uniqueStrings(page).filter((id) => !dockSet.has(id) && !deprecatedEntryIds.has(id)))
    : [];
  const cells = Array.isArray(value?.cells)
    ? value.cells
      .filter((cell): cell is DesktopCell => (
        typeof cell?.id === "string"
        && Number.isInteger(cell.page)
        && Number.isInteger(cell.row)
        && Number.isInteger(cell.column)
      ))
      .filter((cell) => !dockSet.has(cell.id) && !deprecatedEntryIds.has(cell.id))
    : [];
  const gridColumns = readStoredGridColumns(value?.gridColumns, cells, metrics);
  return {
    version: LAYOUT_VERSION,
    cells: cells.length > 0 ? normalizeDesktopCells(cells, [], metrics, gridColumns) : (pages.length > 0 ? pagesToCells(pages, metrics) : idsToCells(legacyOrder, metrics, 0)),
    dock,
    folders: Array.isArray(value?.folders)
      ? value.folders
        .filter((folder): folder is FolderLayout => typeof folder?.id === "string" && Array.isArray(folder.childIds))
        .map((folder) => ({
          id: folder.id,
          title: typeof folder.title === "string" ? folder.title : defaultFolderTitle,
          childIds: uniqueStrings(folder.childIds).filter((id) => !dockSet.has(id) && !deprecatedEntryIds.has(id)),
        }))
      : [],
    gridColumns: metrics.columns,
    updatedAt: Number.isFinite(value?.updatedAt) ? Number(value?.updatedAt) : undefined,
  };
}

function useDesktopGridMetrics(variant: LauncherPageProps["variant"]): DesktopGridMetrics {
  const [metrics, setMetrics] = useState<DesktopGridMetrics>(() => calculateDesktopGridMetrics(variant));
  useEffect(() => {
    function update() {
      const next = calculateDesktopGridMetrics(variant);
      setMetrics((current) => sameDesktopGridMetrics(current, next) ? current : next);
    }
    update();
    window.addEventListener("resize", update);
    return () => window.removeEventListener("resize", update);
  }, [variant]);
  return metrics;
}

function sameDesktopGridMetrics(left: DesktopGridMetrics, right: DesktopGridMetrics): boolean {
  return left.columns === right.columns && left.rows === right.rows && left.capacity === right.capacity;
}

function calculateDesktopGridMetrics(variant: LauncherPageProps["variant"]): DesktopGridMetrics {
  const mobile = variant === "mobile";
  const narrow = mobile || window.innerWidth <= 680;
  const topSafe = mobile ? 72 : narrow ? 82 : 96;
  const sideSafe = mobile ? 14 : narrow ? 18 : 24;
  const bottomSafe = mobile ? 126 : narrow ? 132 : 150;
  const width = Math.max(DESKTOP_CELL_WIDTH, window.innerWidth - sideSafe * 2);
  const height = Math.max(DESKTOP_CELL_HEIGHT, window.innerHeight - topSafe - bottomSafe);
  const columns = mobile
    ? Math.max(MOBILE_GRID_MIN_COLUMNS, Math.floor((width + MOBILE_GRID_COLUMN_GAP) / (MOBILE_CELL_WIDTH + MOBILE_GRID_COLUMN_GAP)))
    : Math.max(1, Math.floor(width / DESKTOP_CELL_WIDTH));
  const rows = Math.max(1, Math.floor(height / (mobile ? MOBILE_CELL_HEIGHT : DESKTOP_CELL_HEIGHT)));
  return { columns, rows, capacity: columns * rows };
}

function buildDesktopSlots(cells: DesktopCell[], metrics: DesktopGridMetrics, resolveEntry: (id: string) => LauncherEntry | undefined): DesktopSlot[] {
  const entriesByIndex = new Map<number, LauncherEntry>();
  let lastIndex = -1;
  for (const cell of cells) {
    const entry = resolveEntry(cell.id);
    if (!entry) continue;
    const index = globalCellIndex(cell, metrics);
    entriesByIndex.set(index, entry);
    lastIndex = Math.max(lastIndex, index);
  }
  const slotCount = Math.max(metrics.capacity, lastIndex + 1 + metrics.columns);
  return Array.from({ length: slotCount }, (_, index) => {
    const position = globalIndexToCell(index, metrics);
    return {
      ...position,
      dropId: cellDropId(position),
      entry: entriesByIndex.get(index),
      index,
    };
  });
}

function normalizeDesktopCells(cells: DesktopCell[], visibleIds: string[], metrics: DesktopGridMetrics, sourceColumns?: number): DesktopCell[] {
  const visibleSet = new Set(visibleIds);
  const hasVisibleSet = visibleSet.size > 0;
  const seen = new Set<string>();
  const usedIndexes = new Set<number>();
  const nextCells: DesktopCell[] = [];
  for (const cell of cells) {
    if (seen.has(cell.id)) continue;
    if (hasVisibleSet && !visibleSet.has(cell.id)) continue;
    let globalIndex = canonicalCellIndex(cell, metrics, sourceColumns);
    while (usedIndexes.has(globalIndex)) globalIndex += 1;
    nextCells.push(cellFromGlobalIndex(globalIndex, metrics, cell.id));
    seen.add(cell.id);
    usedIndexes.add(globalIndex);
  }
  let appendIndex = usedIndexes.size > 0 ? Math.max(...usedIndexes) + 1 : 0;
  for (const id of visibleIds) {
    if (seen.has(id)) continue;
    let globalIndex = appendIndex;
    while (usedIndexes.has(globalIndex)) globalIndex += 1;
    nextCells.push(cellFromGlobalIndex(globalIndex, metrics, id));
    seen.add(id);
    usedIndexes.add(globalIndex);
    appendIndex = globalIndex + 1;
  }
  return sortCells(nextCells, metrics);
}

function idsToCells(ids: string[], metrics: DesktopGridMetrics, startPage: number): DesktopCell[] {
  const startIndex = startPage * metrics.capacity;
  return uniqueStrings(ids).map((id, index) => cellFromGlobalIndex(startIndex + index, metrics, id));
}

function pagesToCells(pages: string[][], metrics: DesktopGridMetrics): DesktopCell[] {
  return pages.flatMap((page, pageIndex) => idsToCells(page, metrics, pageIndex));
}

function sameCells(left: DesktopCell[], right: DesktopCell[], metrics: DesktopGridMetrics): boolean {
  if (left.length !== right.length) return false;
  const sortedLeft = sortCells(left, metrics);
  const sortedRight = sortCells(right, metrics);
  return sortedLeft.every((cell, index) => (
    cell.id === sortedRight[index].id
    && globalCellIndex(cell, metrics) === globalCellIndex(sortedRight[index], metrics)
    && cell.page === sortedRight[index].page
    && cell.row === sortedRight[index].row
    && cell.column === sortedRight[index].column
  ));
}

function sameCellIndexes(left: DesktopCell[], right: DesktopCell[], metrics: DesktopGridMetrics): boolean {
  if (left.length !== right.length) return false;
  const leftIndexes = cellIndexSignature(left, metrics);
  const rightIndexes = cellIndexSignature(right, metrics);
  return leftIndexes.every((cell, index) => cell.id === rightIndexes[index].id && cell.index === rightIndexes[index].index);
}

function cellIndexSignature(cells: DesktopCell[], metrics: DesktopGridMetrics): Array<{ id: string; index: number }> {
  return cells
    .map((cell) => ({ id: cell.id, index: globalCellIndex(cell, metrics) }))
    .sort((left, right) => left.id.localeCompare(right.id));
}

function sameCellPosition(left: DesktopCellPosition, right: DesktopCellPosition): boolean {
  return left.page === right.page && left.row === right.row && left.column === right.column;
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

function appendToDesktopCells(cells: DesktopCell[], itemId: string, metrics: DesktopGridMetrics): DesktopCell[] {
  const occupied = new Set(removeFromCells(cells, itemId).map((cell) => globalCellIndex(cell, metrics)));
  let globalIndex = occupied.size > 0 ? Math.max(...occupied) + 1 : 0;
  while (occupied.has(globalIndex)) globalIndex += 1;
  return placeItemAtGlobalIndex(cells, itemId, globalIndex, metrics);
}

function insertNearDesktopCell(cells: DesktopCell[], itemId: string, targetId: string, placement: DropPlacement, metrics: DesktopGridMetrics): DesktopCell[] {
  const targetCell = cells.find((cell) => cell.id === targetId);
  if (!targetCell) return appendToDesktopCells(cells, itemId, metrics);
  const targetIndex = globalCellIndex(targetCell, metrics);
  return placement === "after"
    ? placeItemAtGlobalIndexBackward(cells, itemId, targetIndex, metrics)
    : placeItemAtGlobalIndex(cells, itemId, targetIndex, metrics);
}

function replaceInCellsOrAppend(cells: DesktopCell[], itemId: string, replacementId: string, metrics: DesktopGridMetrics): DesktopCell[] {
  let replaced = false;
  const nextCells = cells.map((cell) => {
    if (cell.id !== itemId) return cell;
    replaced = true;
    return { ...cell, id: replacementId };
  });
  return replaced ? sortCells(nextCells, metrics) : appendToDesktopCells(nextCells, replacementId, metrics);
}

function replaceCellPairWithFolder(cells: DesktopCell[], itemId: string, targetId: string, folderId: string, metrics: DesktopGridMetrics): DesktopCell[] {
  const targetCell = cells.find((cell) => cell.id === targetId);
  const nextCells = removeManyFromCells(cells, [itemId, targetId]);
  if (targetCell) return placeItemAtGlobalIndex(nextCells, folderId, globalCellIndex(targetCell, metrics), metrics);
  return appendToDesktopCells(nextCells, folderId, metrics);
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
    .map(([index, id]) => cellFromGlobalIndex(index, metrics, id))
    .sort((left, right) => globalCellIndex(left, metrics) - globalCellIndex(right, metrics));
}

function placeItemAtGlobalIndexBackward(cells: DesktopCell[], itemId: string, targetIndex: number, metrics: DesktopGridMetrics): DesktopCell[] {
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
    cursor -= 1;
    if (cursor >= 0 || !carry) continue;
    cursor = targetIndex + 1;
    while (carry) {
      const nextForwardCarry = occupied.get(cursor);
      occupied.set(cursor, carry);
      carry = nextForwardCarry;
      cursor += 1;
    }
  }
  return Array.from(occupied.entries())
    .map(([index, id]) => cellFromGlobalIndex(index, metrics, id))
    .sort((left, right) => globalCellIndex(left, metrics) - globalCellIndex(right, metrics));
}

function sortCells(cells: DesktopCell[], metrics: DesktopGridMetrics): DesktopCell[] {
  return [...cells].sort((left, right) => globalCellIndex(left, metrics) - globalCellIndex(right, metrics));
}

function cellIndex(cell: DesktopCellPosition, columns: number): number {
  return cell.row * columns + cell.column;
}

function globalCellIndex(cell: DesktopCellPosition | DesktopCell, metrics: DesktopGridMetrics): number {
  if ("index" in cell && Number.isInteger(cell.index)) return Math.max(0, Number(cell.index));
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

function cellFromGlobalIndex(index: number, metrics: DesktopGridMetrics, id: string): DesktopCell {
  const normalizedIndex = Math.max(0, index);
  return { ...globalIndexToCell(normalizedIndex, metrics), id, index: normalizedIndex };
}

function canonicalCellIndex(cell: DesktopCell, metrics: DesktopGridMetrics, sourceColumns?: number): number {
  if (Number.isInteger(cell.index)) return Math.max(0, Number(cell.index));
  const columns = Math.max(1, sourceColumns || metrics.columns);
  const page = Math.max(0, cell.page);
  const row = Math.max(0, cell.row);
  const column = Math.max(0, cell.column);
  return page * metrics.rows * columns + row * columns + column;
}

function readStoredGridColumns(value: unknown, cells: DesktopCell[], metrics: DesktopGridMetrics): number {
  if (Number.isInteger(value) && Number(value) > 0) return Number(value);
  const maxStoredColumn = cells.reduce((max, cell) => Math.max(max, Number.isInteger(cell.column) ? cell.column : 0), 0);
  return Math.max(1, metrics.columns, maxStoredColumn + 1);
}

function cellDropId(cell: DesktopCellPosition): string {
  return `drop:cell:${cell.page}:${cell.row}:${cell.column}`;
}

function rectForDropSlotId(slotDropId: string): DOMRect | null {
  const safeDropId = slotDropId.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
  return document.querySelector<HTMLElement>(`[data-drop-id="${safeDropId}"]`)?.getBoundingClientRect() || null;
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

function createDesktopSortingStrategy(
  items: string[],
  cells: DesktopCell[],
  metrics: DesktopGridMetrics,
  activeId: string | null,
  dropPreview: DropPreview | null,
): SortingStrategy {
  if (!activeId || !dropPreview?.slotDropId) return rectSortingStrategy;
  if (dropPreview.kind === "folder") return stableSortingStrategy;
  if (dropPreview.kind !== "insert") return rectSortingStrategy;
  if (!items.includes(dropPreview.targetId)) return rectSortingStrategy;

  const targetCell = cells.find((cell) => cell.id === dropPreview.targetId);
  if (!targetCell) return rectSortingStrategy;
  const targetIndex = globalCellIndex(targetCell, metrics);
  const previewCells = dropPreview.placement === "after"
    ? placeItemAtGlobalIndexBackward(cells, activeId, targetIndex, metrics)
    : placeItemAtGlobalIndex(cells, activeId, targetIndex, metrics);
  const previewCellById = new Map(previewCells.map((cell) => [cell.id, cell]));

  return ({ rects, index }) => {
    const id = items[index];
    const currentRect = rects[index];
    const nextCell = previewCellById.get(id);
    const nextRect = nextCell ? rectForDropSlotId(cellDropId(nextCell)) : null;
    if (!currentRect || !nextRect) return null;
    return {
      x: nextRect.left - currentRect.left,
      y: nextRect.top - currentRect.top,
      scaleX: nextRect.width / currentRect.width,
      scaleY: nextRect.height / currentRect.height,
    };
  };
}

const stableSortingStrategy: SortingStrategy = () => null;

function insertRelative(ids: string[], itemId: string, targetId: string, placement: DropPlacement): string[] {
  const next = ids.filter((id) => id !== itemId);
  const index = next.indexOf(targetId);
  if (index < 0) return [...next, itemId];
  next.splice(placement === "after" ? index + 1 : index, 0, itemId);
  return next;
}

function insertNearDockItem(ids: string[], itemId: string, targetId: string, placement: DropPlacement): string[] {
  const targetIndex = ids.indexOf(targetId);
  if (targetIndex < 0) return [...ids.filter((id) => id !== itemId), itemId];
  return placement === "after"
    ? placeItemAtIndexBackward(ids, itemId, targetIndex)
    : placeItemAtIndex(ids, itemId, targetIndex);
}

function placeItemAtIndex(ids: string[], itemId: string, targetIndex: number): string[] {
  const occupied = new Map<number, string>();
  ids.forEach((id, index) => {
    if (id !== itemId) occupied.set(index, id);
  });
  let cursor = Math.max(0, targetIndex);
  let carry: string | undefined = itemId;
  while (carry) {
    const nextCarry = occupied.get(cursor);
    occupied.set(cursor, carry);
    carry = nextCarry;
    cursor += 1;
  }
  return Array.from(occupied.entries())
    .sort(([left], [right]) => left - right)
    .map(([, id]) => id);
}

function placeItemAtIndexBackward(ids: string[], itemId: string, targetIndex: number): string[] {
  const occupied = new Map<number, string>();
  ids.forEach((id, index) => {
    if (id !== itemId) occupied.set(index, id);
  });
  let cursor = Math.max(0, targetIndex);
  let carry: string | undefined = itemId;
  while (carry) {
    const nextCarry = occupied.get(cursor);
    occupied.set(cursor, carry);
    carry = nextCarry;
    cursor -= 1;
    if (cursor >= 0 || !carry) continue;
    cursor = targetIndex + 1;
    while (carry) {
      const nextForwardCarry = occupied.get(cursor);
      occupied.set(cursor, carry);
      carry = nextForwardCarry;
      cursor += 1;
    }
  }
  return Array.from(occupied.entries())
    .sort(([left], [right]) => left - right)
    .map(([, id]) => id);
}

function replaceDockPairWithFolder(ids: string[], itemId: string, targetId: string, folderId: string): string[] {
  const next = ids
    .filter((id) => id !== itemId)
    .map((id) => (id === targetId ? folderId : id));
  return next.includes(folderId) ? next : [...next, folderId];
}

function dragPointerClientX(event: { activatorEvent: Event; delta: { x: number } }, fallback: number | null): number | null {
  const startX = pointerClientX(event.activatorEvent);
  return startX === null ? fallback : startX + event.delta.x;
}

function dragPointerClientY(event: { activatorEvent: Event; delta: { y: number } }, fallback: number | null): number | null {
  const startY = pointerClientY(event.activatorEvent);
  return startY === null ? fallback : startY + event.delta.y;
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

function pointerClientY(event: Event): number | null {
  if ("clientY" in event && typeof event.clientY === "number") {
    return event.clientY;
  }
  const touchEvent = event as Partial<TouchEvent>;
  if (touchEvent.touches && touchEvent.touches.length > 0) {
    return touchEvent.touches[0].clientY;
  }
  if (touchEvent.changedTouches && touchEvent.changedTouches.length > 0) {
    return touchEvent.changedTouches[0].clientY;
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

function removeChildFromFolders(folders: FolderLayout[], itemId: string): FolderLayout[] {
  return folders.map((folder) => ({ ...folder, childIds: folder.childIds.filter((id) => id !== itemId) }));
}

function removeDeprecatedEntryIds(layout: LauncherLayout, deprecatedEntryIds: Set<string>): LauncherLayout {
  if (deprecatedEntryIds.size === 0) return layout;
  let changed = false;
  const cells = layout.cells.filter((cell) => {
    const keep = !deprecatedEntryIds.has(cell.id);
    if (!keep) changed = true;
    return keep;
  });
  const dock = layout.dock.filter((id) => {
    const keep = !deprecatedEntryIds.has(id);
    if (!keep) changed = true;
    return keep;
  });
  const folders = layout.folders.map((folder) => {
    const childIds = folder.childIds.filter((id) => !deprecatedEntryIds.has(id));
    if (sameStringList(childIds, folder.childIds)) return folder;
    changed = true;
    return { ...folder, childIds };
  });
  return changed ? { ...layout, cells, dock, folders } : layout;
}

function limitDockItems(layout: LauncherLayout): LauncherLayout {
  const dock = uniqueStrings(layout.dock).slice(0, MAX_DOCK_ITEMS);
  return sameStringList(dock, layout.dock) ? layout : { ...layout, dock };
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

function removeUnavailableEntryIds(layout: LauncherLayout, availableEntries: Map<string, LauncherSystemEntry | AppEntry>): { layout: LauncherLayout; changed: boolean } {
  const availableEntryIds = new Set(availableEntries.keys());
  const withoutInvalidFolderChildren = pruneEmptyFolders(layout, availableEntryIds);
  const folderIds = new Set(withoutInvalidFolderChildren.folders.map((folder) => folder.id));
  const isAvailableLayoutId = (id: string) => availableEntryIds.has(id) || folderIds.has(id);
  const cells = withoutInvalidFolderChildren.cells.filter((cell) => isAvailableLayoutId(cell.id));
  const dock = withoutInvalidFolderChildren.dock.filter(isAvailableLayoutId);
  if (
    cells.length === withoutInvalidFolderChildren.cells.length
    && dock.length === withoutInvalidFolderChildren.dock.length
    && withoutInvalidFolderChildren === layout
  ) {
    return { layout, changed: false };
  }
  return {
    changed: true,
    layout: {
      ...withoutInvalidFolderChildren,
      cells,
      dock,
    },
  };
}

function sameStringList(left: string[], right: string[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function compareLauncherIds(left: string, right: string): number {
  return left.localeCompare(right);
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

type WebAppIconPreset = {
  id: string;
  label: string;
  background: string;
  foreground: string;
  path: string;
};

const WEBAPP_ICON_PRESETS: WebAppIconPreset[] = [
  {
    id: "news",
    label: "News",
    background: "#1967d2",
    foreground: "#ffffff",
    path: "M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM8,17H6v-2h2v2zM8,13H6v-2h2v2zM8,9H6V7h2v2zM18,17h-8v-2h8v2zM18,13h-8v-2h8v2zM18,9h-8V7h8v2z",
  },
  {
    id: "video",
    label: "Video",
    background: "#d50032",
    foreground: "#ffffff",
    path: "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,16.5v-9l6,4.5 -6,4.5z",
  },
  {
    id: "music",
    label: "Music",
    background: "#7b1fa2",
    foreground: "#ffffff",
    path: "M12,3v10.55A4,4 0,1 0,14 17V7h4V3h-6z",
  },
  {
    id: "shop",
    label: "Shop",
    background: "#0f7943",
    foreground: "#ffffff",
    path: "M20,6h-4c0,-2.21 -1.79,-4 -4,-4S8,3.79 8,6H4v16h16V6zM12,4c1.1,0 2,0.9 2,2h-4c0,-1.1 0.9,-2 2,-2zM18,20H6V8h2v2h2V8h4v2h2V8h2v12z",
  },
  {
    id: "chat",
    label: "Chat",
    background: "#00897b",
    foreground: "#ffffff",
    path: "M20,2H4C2.9,2 2,2.9 2,4v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM20,16H5.17L4,17.17V4h16v12zM7,9h10v2H7V9zM7,12h7v2H7v-2zM7,6h10v2H7V6z",
  },
  {
    id: "docs",
    label: "Docs",
    background: "#5f6368",
    foreground: "#ffffff",
    path: "M14,2H6C4.9,2 4,2.9 4,4v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V8l-6,-6zM13,9V3.5L18.5,9H13zM8,13h8v2H8v-2zM8,17h8v2H8v-2z",
  },
  {
    id: "work",
    label: "Work",
    background: "#f57c00",
    foreground: "#ffffff",
    path: "M20,6h-4V4c0,-1.11 -0.89,-2 -2,-2h-4C8.89,2 8,2.89 8,4v2H4c-1.11,0 -2,0.89 -2,2v11c0,1.11 0.89,2 2,2h16c1.11,0 2,-0.89 2,-2V8c0,-1.11 -0.89,-2 -2,-2zM14,6h-4V4h4v2zM20,19H4v-5h6v2h4v-2h6v5zM20,11H4V8h16v3z",
  },
  {
    id: "star",
    label: "Star",
    background: "#fbbc04",
    foreground: "#202124",
    path: "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z",
  },
];

function appInitial(name: string): string {
  return firstDisplaySymbol(name) || "A";
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

export function createGeneratedIconDataUrl(letter: string, background: string, size = 192): string {
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;
  const context = canvas.getContext("2d");
  if (!context) return "";
  const fill = normalizeCssColor(background, "#126d6a");
  context.fillStyle = fill;
  context.fillRect(0, 0, size, size);
  const label = (letter.trim() || "A").slice(0, 3).toUpperCase();
  context.fillStyle = readableTextColor(fill);
  context.font = `700 ${Math.round(size * (label.length > 2 ? 0.38 : 0.5))}px system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`;
  context.textAlign = "center";
  context.textBaseline = "middle";
  context.fillText(label, size / 2, size / 2 + size * 0.015);
  return canvas.toDataURL("image/png");
}

function createTitleIconDataUrl(title: string, startUrl: string, themeColor: number, id: string, size = 192): string {
  const label = firstDisplaySymbol(title || hostLabel(startUrl)) || "A";
  const background = colorFromTheme(themeColor, startUrl || id);
  return createGeneratedIconDataUrl(label, background, size);
}

function createPresetIconDataUrl(presetId: string, size = 192): string {
  const preset = WEBAPP_ICON_PRESETS.find((item) => item.id === presetId) || WEBAPP_ICON_PRESETS[0];
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;
  const context = canvas.getContext("2d");
  if (!context) return "";

  const radius = size * 0.22;
  context.fillStyle = preset.background;
  roundedRect(context, 0, 0, size, size, radius);
  context.fill();

  context.save();
  context.translate(size * 0.1875, size * 0.1875);
  context.scale(size / 24 * 0.625, size / 24 * 0.625);
  context.fillStyle = preset.foreground;
  context.fill(new Path2D(preset.path));
  context.restore();
  return canvas.toDataURL("image/png");
}

function normalizeUploadedIcon(file: File, size = 512): Promise<string> {
  return readFileAsDataUrl(file)
    .then((dataUrl) => loadImage(dataUrl))
    .then((image) => {
      const canvas = document.createElement("canvas");
      canvas.width = size;
      canvas.height = size;
      const context = canvas.getContext("2d");
      if (!context || image.naturalWidth <= 0 || image.naturalHeight <= 0) {
        throw new Error("Unable to decode image.");
      }
      context.clearRect(0, 0, size, size);
      const cropSize = Math.min(image.naturalWidth, image.naturalHeight);
      const sourceX = Math.max(0, (image.naturalWidth - cropSize) / 2);
      const sourceY = Math.max(0, (image.naturalHeight - cropSize) / 2);
      context.drawImage(image, sourceX, sourceY, cropSize, cropSize, 0, 0, size, size);
      return canvas.toDataURL("image/png");
    });
}

function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = typeof reader.result === "string" ? reader.result : "";
      const normalized = normalizeImageDataUrl(result, file.type);
      if (!normalized) {
        reject(new Error("Unable to read image."));
        return;
      }
      resolve(normalized);
    };
    reader.onerror = () => reject(reader.error || new Error("Unable to read image."));
    reader.readAsDataURL(file);
  });
}

function normalizeImageDataUrl(dataUrl: string, fileType: string): string | null {
  if (dataUrl.startsWith("data:image/")) return dataUrl;
  const commaIndex = dataUrl.indexOf(",");
  if (!dataUrl.startsWith("data:") || commaIndex <= 0) return null;
  const meta = dataUrl.slice(5, commaIndex).toLowerCase();
  const encoded = dataUrl.slice(commaIndex + 1);
  if (!meta.includes(";base64") || !encoded) return null;
  const sniffedMime = sniffImageMimeFromBase64(encoded);
  const mime = sniffedMime || (fileType.startsWith("image/") ? fileType : "");
  return mime ? `data:${mime};base64,${encoded}` : null;
}

function sniffImageMimeFromBase64(encoded: string): string | null {
  const head = encoded.slice(0, 32);
  if (head.startsWith("iVBOR")) return "image/png";
  if (head.startsWith("/9j/")) return "image/jpeg";
  if (head.startsWith("R0lGOD")) return "image/gif";
  if (head.startsWith("UklGR")) return "image/webp";
  return null;
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error("Unable to load image."));
    image.src = src;
  });
}

function roundedRect(context: CanvasRenderingContext2D, x: number, y: number, width: number, height: number, radius: number) {
  context.beginPath();
  context.moveTo(x + radius, y);
  context.lineTo(x + width - radius, y);
  context.quadraticCurveTo(x + width, y, x + width, y + radius);
  context.lineTo(x + width, y + height - radius);
  context.quadraticCurveTo(x + width, y + height, x + width - radius, y + height);
  context.lineTo(x + radius, y + height);
  context.quadraticCurveTo(x, y + height, x, y + height - radius);
  context.lineTo(x, y + radius);
  context.quadraticCurveTo(x, y, x + radius, y);
  context.closePath();
}

function firstDisplaySymbol(value: string): string {
  const trimmed = value.trimStart();
  if (!trimmed) return "";
  const Segmenter = Intl.Segmenter;
  if (Segmenter) {
    const segmenter = new Segmenter(undefined, { granularity: "grapheme" });
    for (const segment of segmenter.segment(trimmed)) {
      const symbol = segment.segment.trim();
      if (symbol) return symbol.toLocaleUpperCase();
    }
  }
  return Array.from(trimmed)[0]?.toLocaleUpperCase() || "";
}

function normalizeCssColor(value: string, fallback: string): string {
  return /^#[0-9a-f]{6}$/i.test(value) ? value : fallback;
}

function readableTextColor(background: string): string {
  const match = /^#?([0-9a-f]{6})$/i.exec(background);
  if (!match) return "#ffffff";
  const rgb = Number.parseInt(match[1], 16);
  const red = (rgb >> 16) & 255;
  const green = (rgb >> 8) & 255;
  const blue = rgb & 255;
  const luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255;
  return luminance > 0.62 ? "#202124" : "#ffffff";
}

function hashCode(value: string): number {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = ((hash << 5) - hash + value.charCodeAt(index)) | 0;
  }
  return hash;
}
