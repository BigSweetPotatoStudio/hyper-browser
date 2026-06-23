import type { LauncherLayout, LauncherLayoutStorage } from "./index";

const LAUNCHER_FILE = "launcher.json";
const MAX_SYNC_ATTEMPTS = 3;
const MAX_DOCK_ITEMS = 4;

export type LauncherLayoutSyncSettings = {
  webDavUrl: string;
  username: string;
  password: string;
  deviceId: string;
  deviceName: string;
  clientName: string;
};

export type RemoteSyncManifest = {
  updatedAt: number;
  lastWriter: string;
};

type RemoteLauncherDocument = {
  type: "hyper-browser-launcher";
  schemaVersion: number;
  updatedAt: number;
  sourceDeviceId: string;
  sourceDeviceName: string;
  layout: LauncherLayout;
};

type RemoteJson<T> = {
  data: T;
  etag: string | null;
};

export type LauncherLayoutSyncResult = {
  changed: boolean;
  direction: "pull" | "push" | "none";
  updatedAt: number;
};

export type LauncherLayoutSyncOptions = {
  deprecatedEntryIds?: string[];
  availableEntryIds?: string[];
};

export async function syncLauncherLayout(
  storage: LauncherLayoutStorage,
  settings: LauncherLayoutSyncSettings,
  options: LauncherLayoutSyncOptions = {},
): Promise<LauncherLayoutSyncResult> {
  const client = new LauncherWebDavClient(settings);
  await client.ensureCollections();
  const deprecatedEntryIds = new Set(options.deprecatedEntryIds || []);

  let lastConflict: unknown;
  for (let attempt = 0; attempt < MAX_SYNC_ATTEMPTS; attempt += 1) {
    const availableAppEntryIds = normalizeAvailableAppEntryIds(options.availableEntryIds);
    const localResult = sanitizeLayout(completeLayout(await storage.load()), deprecatedEntryIds, availableAppEntryIds);
    const local = localResult.layout;
    const remote = await client.getJson<RemoteLauncherDocument>(LAUNCHER_FILE);
    const remoteResult = sanitizeLayout(completeLayout(remote?.data.layout), deprecatedEntryIds, availableAppEntryIds);
    const remoteLayout = remoteResult.layout;
    const localUpdatedAt = validTimestamp(local?.updatedAt);
    const remoteUpdatedAt = remoteLayout ? validTimestamp(remote?.data.updatedAt || remoteLayout.updatedAt) : 0;

    if (remote && remoteLayout && remoteResult.changed && remoteUpdatedAt > localUpdatedAt) {
      try {
        await storage.save({ ...remoteLayout, updatedAt: remoteUpdatedAt });
        await client.putJson(LAUNCHER_FILE, launcherDocument(remoteLayout, settings, remoteUpdatedAt), remote.etag);
        await client.putManifest();
        await client.putDeviceState();
        return { changed: true, direction: "pull", updatedAt: remoteUpdatedAt };
      } catch (error) {
        if (error instanceof WebDavConflictError) {
          lastConflict = error;
          continue;
        }
        throw error;
      }
    }

    if (remote && remoteLayout && remoteResult.changed && local && remoteUpdatedAt === localUpdatedAt) {
      try {
        await storage.save({ ...remoteLayout, updatedAt: remoteUpdatedAt });
        await client.putJson(LAUNCHER_FILE, launcherDocument(remoteLayout, settings, remoteUpdatedAt), remote.etag);
        await client.putManifest();
        await client.putDeviceState();
        return { changed: true, direction: "pull", updatedAt: remoteUpdatedAt };
      } catch (error) {
        if (error instanceof WebDavConflictError) {
          lastConflict = error;
          continue;
        }
        throw error;
      }
    }

    if (remote && remoteLayout && remoteResult.changed && local && remoteUpdatedAt < localUpdatedAt) {
      const updatedAt = localUpdatedAt || Date.now();
      try {
        await client.putJson(LAUNCHER_FILE, launcherDocument(local, settings, updatedAt), remote.etag);
        await client.putManifest();
        await client.putDeviceState();
        if (localResult.changed || !local.updatedAt) await storage.save({ ...local, updatedAt });
        return { changed: true, direction: "push", updatedAt };
      } catch (error) {
        if (error instanceof WebDavConflictError) {
          lastConflict = error;
          continue;
        }
        throw error;
      }
    }

    if (remoteLayout && remoteUpdatedAt > localUpdatedAt) {
      await storage.save({ ...remoteLayout, updatedAt: remoteUpdatedAt });
      return { changed: true, direction: "pull", updatedAt: remoteUpdatedAt };
    }

    if (!local) {
      return { changed: false, direction: "none", updatedAt: remoteUpdatedAt };
    }

    if (remoteLayout && remoteUpdatedAt === localUpdatedAt && !sameLauncherLayout(local, remoteLayout)) {
      await storage.save({ ...remoteLayout, updatedAt: remoteUpdatedAt });
      await client.putDeviceState();
      return { changed: true, direction: "pull", updatedAt: remoteUpdatedAt };
    }

    if (localUpdatedAt > remoteUpdatedAt || !remote) {
      const updatedAt = localUpdatedAt || Date.now();
      try {
        await client.putJson(LAUNCHER_FILE, launcherDocument(local, settings, updatedAt), remote?.etag);
        await client.putManifest();
        await client.putDeviceState();
        if (localResult.changed || !local.updatedAt) await storage.save({ ...local, updatedAt });
        return { changed: true, direction: "push", updatedAt };
      } catch (error) {
        if (error instanceof WebDavConflictError) {
          lastConflict = error;
          continue;
        }
        throw error;
      }
    }

    await client.putDeviceState();
    return { changed: false, direction: "none", updatedAt: remoteUpdatedAt };
  }

  throw lastConflict instanceof Error ? lastConflict : new Error("Unable to sync launcher layout.");
}

export async function readRemoteSyncManifest(settings: LauncherLayoutSyncSettings): Promise<RemoteSyncManifest | null> {
  const client = new LauncherWebDavClient(settings);
  const remote = await client.getJson<Partial<RemoteSyncManifest>>("manifest.json");
  if (!remote) return null;
  const updatedAt = validTimestamp(remote.data.updatedAt);
  if (!updatedAt) return null;
  return {
    updatedAt,
    lastWriter: typeof remote.data.lastWriter === "string" ? remote.data.lastWriter : "",
  };
}

function launcherDocument(layout: LauncherLayout, settings: LauncherLayoutSyncSettings, updatedAt: number): RemoteLauncherDocument {
  return {
    type: "hyper-browser-launcher",
    schemaVersion: 1,
    updatedAt,
    sourceDeviceId: settings.deviceId,
    sourceDeviceName: settings.deviceName,
    layout: { ...layout, updatedAt },
  };
}

function sameLauncherLayout(left: LauncherLayout, right: LauncherLayout): boolean {
  return layoutSignature(left) === layoutSignature(right);
}

function layoutSignature(layout: LauncherLayout): string {
  return JSON.stringify({
    cells: [...layout.cells]
      .map((cell) => ({
        id: cell.id,
        index: stableCellIndex(cell),
      }))
      .sort((left, right) => left.id.localeCompare(right.id)),
    dock: layout.dock.slice(0, MAX_DOCK_ITEMS),
    folders: [...layout.folders]
      .map((folder) => ({
        id: folder.id,
        title: folder.title,
        childIds: folder.childIds,
      }))
      .sort((left, right) => left.id.localeCompare(right.id)),
  });
}

function stableCellIndex(cell: LauncherLayout["cells"][number]): number | string {
  if (Number.isInteger(cell.index)) return Math.max(0, Number(cell.index));
  return `${cell.page}:${cell.row}:${cell.column}`;
}

function validTimestamp(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : 0;
}

function completeLayout(value: Awaited<ReturnType<LauncherLayoutStorage["load"]>>): LauncherLayout | null {
  if (!value || !Array.isArray(value.cells) || !Array.isArray(value.dock) || !Array.isArray(value.folders)) return null;
  return {
    version: 4,
    cells: value.cells,
    dock: value.dock,
    folders: value.folders,
    gridColumns: typeof value.gridColumns === "number" ? value.gridColumns : undefined,
    updatedAt: validTimestamp(value.updatedAt) || undefined,
  };
}

function sanitizeLayout(
  layout: LauncherLayout | null,
  deprecatedEntryIds: Set<string>,
  availableAppEntryIds: Set<string>,
): { layout: LauncherLayout | null; changed: boolean } {
  if (!layout) return { layout, changed: false };
  let changed = false;
  const removedFolderIds = new Set<string>();
  const hasAvailableApps = availableAppEntryIds.size > 0;
  const shouldKeepId = (id: string) => {
    if (deprecatedEntryIds.has(id)) return false;
    return !hasAvailableApps || !id.startsWith("app:") || availableAppEntryIds.has(id);
  };
  const folders = layout.folders.flatMap((folder) => {
    const childIds = uniqueStrings(folder.childIds).filter(shouldKeepId);
    if (childIds.length === 0) {
      changed = true;
      removedFolderIds.add(folder.id);
      return [];
    }
    if (childIds.length !== folder.childIds.length) {
      changed = true;
      return [{ ...folder, childIds }];
    }
    return [folder];
  });
  const filteredCells = layout.cells.filter((cell) => {
    const keep = shouldKeepId(cell.id);
    const keepFolder = !removedFolderIds.has(cell.id);
    if (!keep) changed = true;
    if (!keepFolder) changed = true;
    return keep && keepFolder;
  });
  const normalizedCells = normalizeCells(filteredCells, layout.gridColumns);
  if (!sameCells(filteredCells, normalizedCells)) changed = true;
  const dock = layout.dock.filter((id) => {
    const keep = shouldKeepId(id);
    const keepFolder = !removedFolderIds.has(id);
    if (!keep) changed = true;
    if (!keepFolder) changed = true;
    return keep && keepFolder;
  }).slice(0, MAX_DOCK_ITEMS);
  if (dock.length !== layout.dock.length) changed = true;
  const cells = appendMissingAppCells(normalizedCells, dock, folders, availableAppEntryIds, layout.gridColumns);
  if (!sameCells(normalizedCells, cells)) changed = true;
  return {
    layout: changed ? { ...layout, cells, dock, folders } : layout,
    changed,
  };
}

function appendMissingAppCells(
  cells: LauncherLayout["cells"],
  dock: string[],
  folders: LauncherLayout["folders"],
  availableAppEntryIds: Set<string>,
  gridColumns: number | undefined,
): LauncherLayout["cells"] {
  if (availableAppEntryIds.size === 0) return cells;
  const columns = inferGridColumns(cells, gridColumns);
  const nextCells = [...cells];
  const occupiedIds = new Set([
    ...nextCells.map((cell) => cell.id),
    ...dock,
    ...folders.flatMap((folder) => folder.childIds),
  ]);
  let appendIndex = nextCells.reduce((max, cell) => Math.max(max, cellGlobalIndex(cell, columns)), -1) + 1;
  for (const id of [...availableAppEntryIds].sort(compareLauncherIds)) {
    if (occupiedIds.has(id)) continue;
    nextCells.push(cellFromGlobalIndex(appendIndex, columns, id));
    occupiedIds.add(id);
    appendIndex += 1;
  }
  return sortCells(nextCells, columns);
}

function normalizeCells(cells: LauncherLayout["cells"], gridColumns: number | undefined): LauncherLayout["cells"] {
  const columns = inferGridColumns(cells, gridColumns);
  const seenIds = new Set<string>();
  const usedIndexes = new Set<number>();
  const nextCells: LauncherLayout["cells"] = [];
  for (const cell of cells) {
    if (seenIds.has(cell.id)) continue;
    let index = cellGlobalIndex(cell, columns);
    while (usedIndexes.has(index)) index += 1;
    nextCells.push(cellFromGlobalIndex(index, columns, cell.id));
    seenIds.add(cell.id);
    usedIndexes.add(index);
  }
  return sortCells(nextCells, columns);
}

function sameCells(left: LauncherLayout["cells"], right: LauncherLayout["cells"]): boolean {
  return left.length === right.length && left.every((cell, index) => {
    const next = right[index];
    return cell.id === next.id
      && cell.index === next.index
      && cell.page === next.page
      && cell.row === next.row
      && cell.column === next.column;
  });
}

function sortCells(cells: LauncherLayout["cells"], columns: number): LauncherLayout["cells"] {
  return [...cells].sort((left, right) => cellGlobalIndex(left, columns) - cellGlobalIndex(right, columns) || left.id.localeCompare(right.id));
}

function cellGlobalIndex(cell: LauncherLayout["cells"][number], columns: number): number {
  if (Number.isInteger(cell.index) && Number(cell.index) >= 0) return Number(cell.index);
  const row = Math.max(0, Number.isInteger(cell.row) ? Number(cell.row) : 0);
  const column = Math.max(0, Math.min(columns - 1, Number.isInteger(cell.column) ? Number(cell.column) : 0));
  return row * columns + column;
}

function cellFromGlobalIndex(index: number, columns: number, id: string): LauncherLayout["cells"][number] {
  const safeIndex = Math.max(0, Math.floor(index));
  return {
    id,
    index: safeIndex,
    page: 0,
    row: Math.floor(safeIndex / columns),
    column: safeIndex % columns,
  };
}

function inferGridColumns(cells: LauncherLayout["cells"], gridColumns: number | undefined): number {
  if (Number.isInteger(gridColumns) && Number(gridColumns) > 0) return Number(gridColumns);
  const maxColumn = cells.reduce((max, cell) => (Number.isInteger(cell.column) ? Math.max(max, Number(cell.column)) : max), -1);
  return Math.max(4, maxColumn + 1);
}

function normalizeAvailableAppEntryIds(ids: string[] | undefined): Set<string> {
  return new Set(uniqueStrings(ids || []).map((id) => (id.startsWith("app:") ? id : `app:${id}`)));
}

function uniqueStrings(values: unknown[]): string[] {
  const seen = new Set<string>();
  return values.filter((value): value is string => {
    if (typeof value !== "string" || !value || seen.has(value)) return false;
    seen.add(value);
    return true;
  });
}

function compareLauncherIds(left: string, right: string): number {
  return left.localeCompare(right);
}

class LauncherWebDavClient {
  private readonly rootUrl: string;

  constructor(private readonly settings: LauncherLayoutSyncSettings) {
    this.rootUrl = normalizeRootUrl(settings.webDavUrl);
  }

  async ensureCollections(): Promise<void> {
    await this.mkcol("");
    await this.mkcol("devices/");
  }

  async getJson<T>(path: string): Promise<RemoteJson<T> | null> {
    const response = await fetch(this.urlFor(path), {
      method: "GET",
      headers: this.headers(),
    });
    if (response.status === 404) return null;
    if (!response.ok) throw new Error(`WebDAV GET failed: HTTP ${response.status}`);
    return {
      data: await response.json() as T,
      etag: response.headers.get("ETag"),
    };
  }

  async putJson(path: string, body: unknown, etag?: string | null): Promise<void> {
    const headers = this.headers({ "Content-Type": "application/json; charset=utf-8" });
    if (etag) headers.set("If-Match", etag);
    const response = await fetch(this.urlFor(path), {
      method: "PUT",
      headers,
      body: JSON.stringify(body, null, 2),
    });
    if (response.status === 409 || response.status === 412) throw new WebDavConflictError();
    if (!response.ok) throw new Error(`WebDAV PUT failed: HTTP ${response.status}`);
  }

  async putManifest(): Promise<void> {
    await this.putJson("manifest.json", {
      type: "hyper-browser-sync",
      schemaVersion: 1,
      updatedAt: Date.now(),
      syncRoot: "HyperBrowserSync",
      lastWriter: this.settings.deviceId,
      files: ["bookmarks.json", "webapps.json", "launcher.json", "devices/"],
    });
  }

  async putDeviceState(): Promise<void> {
    await this.putJson(`devices/${safeSegment(this.settings.clientName)}-${safeSegment(this.settings.deviceId)}.json`, {
      schemaVersion: 1,
      deviceId: this.settings.deviceId,
      deviceName: this.settings.deviceName,
      client: this.settings.clientName,
      lastSyncAt: Date.now(),
    });
  }

  private async mkcol(path: string): Promise<void> {
    const response = await fetch(this.urlFor(path), {
      method: "MKCOL",
      headers: this.headers(),
    });
    if (response.ok || response.status === 405) return;
    if (response.status === 409 && path === "devices/") {
      await this.mkcol("");
      return;
    }
    throw new Error(`WebDAV MKCOL failed: HTTP ${response.status}`);
  }

  private headers(extra?: Record<string, string>): Headers {
    const headers = new Headers(extra);
    if (this.settings.username || this.settings.password) {
      headers.set("Authorization", `Basic ${base64Utf8(`${this.settings.username}:${this.settings.password}`)}`);
    }
    return headers;
  }

  private urlFor(path: string): string {
    if (!path) return this.rootUrl;
    const encoded = path.split("/")
      .filter(Boolean)
      .map((segment) => encodeURIComponent(segment))
      .join("/");
    return `${this.rootUrl}${encoded}${path.endsWith("/") ? "/" : ""}`;
  }
}

class WebDavConflictError extends Error {
  constructor() {
    super("WebDAV write conflict.");
  }
}

function normalizeRootUrl(value: string): string {
  const clean = value.trim().replace(/\/+$/, "");
  if (!clean) throw new Error("WebDAV URL is required.");
  if (!/^https?:\/\//i.test(clean)) throw new Error("WebDAV URL must start with http:// or https://.");
  return clean.toLowerCase().endsWith("/hyperbrowsersync") ? `${clean}/` : `${clean}/HyperBrowserSync/`;
}

function base64Utf8(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = "";
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary);
}

function safeSegment(value: string): string {
  return value.replace(/[^a-zA-Z0-9._-]/g, "_") || crypto.randomUUID();
}
