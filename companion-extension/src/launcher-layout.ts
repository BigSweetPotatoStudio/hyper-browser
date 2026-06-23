import type { LauncherDesktopCell, LauncherFolderLayout, LauncherLayout, LauncherLayoutStorage } from "@hyper-launcher";
import { syncLauncherLayout, type LauncherLayoutSyncResult } from "@hyper-launcher/webdav-layout";
import { COMPANION_CLIENT_NAME, DEFAULT_DEVICE_NAME } from "./identity";
import { loadSettings, storageGet, storageSet } from "./storage";

export const LAYOUT_STORAGE_KEY = "launcherLayout";
export const DEFAULT_DOCK_ENTRY_IDS = ["system:bookmarks", "system:history", "system:extensions"];
export const DEPRECATED_ENTRY_IDS = ["system:chrome"];

const LAYOUT_VERSION = 4;
const DEFAULT_GRID_COLUMNS = 4;

export const launcherLayoutStorage: LauncherLayoutStorage = {
  async load() {
    const stored = await storageGet<Record<string, unknown>>(LAYOUT_STORAGE_KEY);
    return stored[LAYOUT_STORAGE_KEY] as never;
  },
  save(layout: LauncherLayout) {
    return storageSet({ [LAYOUT_STORAGE_KEY]: layout });
  },
};

export async function appendWebAppToLauncher(appId: string, knownAppIds: string[] = []): Promise<void> {
  const itemId = `app:${appId}`;
  const knownAppEntryIds = new Set(uniqueStrings(knownAppIds).map((id) => `app:${id}`));
  const layout = normalizeStoredLayout(await launcherLayoutStorage.load());
  const columns = Math.max(1, layout.gridColumns || inferGridColumns(layout.cells));
  const folders = layout.folders
    .map((folder) => ({
      ...folder,
      childIds: uniqueStrings(folder.childIds).filter((id) => (
        id !== itemId
        && !DEPRECATED_ENTRY_IDS.includes(id)
        && (!id.startsWith("app:") || knownAppEntryIds.size === 0 || knownAppEntryIds.has(id))
      )),
    }))
    .filter((folder) => folder.childIds.length > 0);
  const dock = uniqueStrings(layout.dock).filter((id) => (
    id !== itemId
    && !DEPRECATED_ENTRY_IDS.includes(id)
    && (!id.startsWith("app:") || knownAppEntryIds.size === 0 || knownAppEntryIds.has(id))
  ));
  let cells = layout.cells
    .filter((cell) => (
      cell.id !== itemId
      && !DEPRECATED_ENTRY_IDS.includes(cell.id)
      && (!cell.id.startsWith("app:") || knownAppEntryIds.size === 0 || knownAppEntryIds.has(cell.id))
    ))
    .map((cell) => normalizeCell(cell, columns))
    .sort((left, right) => compareCells(left, right, columns));
  cells = appendMissingAppCells(cells, knownAppEntryIds, new Set([
    ...dock,
    ...folders.flatMap((folder) => folder.childIds),
    itemId,
  ]), columns);
  const lastAppCell = [...cells].reverse().find((cell) => cell.id.startsWith("app:"));
  const baseCell = lastAppCell || cells.at(-1);
  const targetIndex = baseCell ? cellGlobalIndex(baseCell, columns) + 1 : 0;
  const shiftedCells = cells.map((cell) => (
    cellGlobalIndex(cell, columns) >= targetIndex
      ? cellFromGlobalIndex(cellGlobalIndex(cell, columns) + 1, columns, cell.id)
      : cell
  ));
  const nextLayout: LauncherLayout = {
    version: LAYOUT_VERSION,
    cells: [
      ...shiftedCells,
      cellFromGlobalIndex(targetIndex, columns, itemId),
    ].sort((left, right) => compareCells(left, right, columns)),
    dock,
    folders,
    gridColumns: columns,
    updatedAt: Date.now(),
  };
  await launcherLayoutStorage.save(nextLayout);
}

function appendMissingAppCells(
  cells: LauncherDesktopCell[],
  knownAppEntryIds: Set<string>,
  unavailableIds: Set<string>,
  columns: number,
): LauncherDesktopCell[] {
  if (knownAppEntryIds.size === 0) return cells;
  const nextCells = [...cells].sort((left, right) => compareCells(left, right, columns));
  const occupiedIds = new Set([
    ...nextCells.map((cell) => cell.id),
    ...unavailableIds,
  ]);
  for (const id of [...knownAppEntryIds].sort(compareLauncherIds)) {
    if (occupiedIds.has(id)) continue;
    const baseCell = nextCells.at(-1);
    const targetIndex = baseCell ? cellGlobalIndex(baseCell, columns) + 1 : 0;
    nextCells.push(cellFromGlobalIndex(targetIndex, columns, id));
    occupiedIds.add(id);
  }
  return nextCells.sort((left, right) => compareCells(left, right, columns));
}

export async function syncLauncherLayoutNow(knownAppIds: string[] = []): Promise<LauncherLayoutSyncResult> {
  const settings = await loadSettings();
  const availableEntryIds = uniqueStrings(knownAppIds).map((id) => (id.startsWith("app:") ? id : `app:${id}`));
  return syncLauncherLayout(launcherLayoutStorage, {
    webDavUrl: settings.webDavUrl,
    username: settings.username,
    password: settings.password,
    deviceId: settings.deviceId,
    deviceName: settings.deviceName || DEFAULT_DEVICE_NAME,
    clientName: COMPANION_CLIENT_NAME,
  }, {
    deprecatedEntryIds: DEPRECATED_ENTRY_IDS,
    availableEntryIds,
    defaultDockEntryIds: DEFAULT_DOCK_ENTRY_IDS,
  });
}

function normalizeStoredLayout(value: Awaited<ReturnType<LauncherLayoutStorage["load"]>>): LauncherLayout {
  const rawCells = Array.isArray(value?.cells)
    ? value.cells.filter(isLauncherCell)
    : [];
  const gridColumns = typeof value?.gridColumns === "number" && Number.isFinite(value.gridColumns) && value.gridColumns > 0
    ? Math.floor(value.gridColumns)
    : inferGridColumns(rawCells);
  const cells = rawCells
    .map((cell) => normalizeCell(cell, gridColumns))
    .sort((left, right) => compareCells(left, right, gridColumns));
  const dock = Array.isArray(value?.dock)
    ? uniqueStrings(value.dock).filter((id) => !DEPRECATED_ENTRY_IDS.includes(id))
    : [...DEFAULT_DOCK_ENTRY_IDS];
  const folders = Array.isArray(value?.folders)
    ? value.folders
      .filter(isLauncherFolder)
      .map((folder) => ({
        ...folder,
        childIds: uniqueStrings(folder.childIds).filter((id) => !DEPRECATED_ENTRY_IDS.includes(id)),
      }))
      .filter((folder) => folder.childIds.length > 0)
    : [];
  const updatedAt = typeof value?.updatedAt === "number" && Number.isFinite(value.updatedAt) && value.updatedAt > 0
    ? value.updatedAt
    : undefined;
  return {
    version: LAYOUT_VERSION,
    cells,
    dock,
    folders,
    gridColumns,
    updatedAt,
  };
}

function normalizeCell(cell: LauncherDesktopCell, columns: number): LauncherDesktopCell {
  const index = cellGlobalIndex(cell, columns);
  return cellFromGlobalIndex(index, columns, cell.id);
}

function cellFromGlobalIndex(index: number, columns: number, id: string): LauncherDesktopCell {
  const safeIndex = Math.max(0, Math.floor(index));
  return {
    id,
    index: safeIndex,
    page: 0,
    row: Math.floor(safeIndex / columns),
    column: safeIndex % columns,
  };
}

function cellGlobalIndex(cell: LauncherDesktopCell, columns: number): number {
  if (Number.isInteger(cell.index) && Number(cell.index) >= 0) return Number(cell.index);
  const row = Math.max(0, Number.isInteger(cell.row) ? Number(cell.row) : 0);
  const column = Math.max(0, Math.min(columns - 1, Number.isInteger(cell.column) ? Number(cell.column) : 0));
  return row * columns + column;
}

function compareCells(left: LauncherDesktopCell, right: LauncherDesktopCell, columns: number): number {
  return cellGlobalIndex(left, columns) - cellGlobalIndex(right, columns) || left.id.localeCompare(right.id);
}

function compareLauncherIds(left: string, right: string): number {
  return left.localeCompare(right);
}

function inferGridColumns(cells: readonly LauncherDesktopCell[] | undefined): number {
  const maxColumn = (cells || []).reduce((max, cell) => {
    if (!Number.isInteger(cell.column)) return max;
    return Math.max(max, Number(cell.column));
  }, -1);
  return Math.max(DEFAULT_GRID_COLUMNS, maxColumn + 1);
}

function isLauncherCell(value: unknown): value is LauncherDesktopCell {
  if (!value || typeof value !== "object") return false;
  const cell = value as Partial<LauncherDesktopCell>;
  return typeof cell.id === "string" && cell.id.length > 0;
}

function isLauncherFolder(value: unknown): value is LauncherFolderLayout {
  if (!value || typeof value !== "object") return false;
  const folder = value as Partial<LauncherFolderLayout>;
  return typeof folder.id === "string" && Array.isArray(folder.childIds);
}

function uniqueStrings(values: unknown[]): string[] {
  const seen = new Set<string>();
  return values.filter((value): value is string => {
    if (typeof value !== "string" || !value || seen.has(value)) return false;
    seen.add(value);
    return true;
  });
}
