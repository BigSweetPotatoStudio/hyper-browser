import type { BookmarkRecord, WebAppRecord } from "./index";
import type { SyncBackgroundController, SyncBackgroundSignal } from "./background";
import type { LauncherCell, LauncherFolder, LauncherJson } from "./sync-json-types";

export type HyperBackgroundCommand = {
  type: string;
  payload?: unknown;
};

export type HyperBackgroundCommandResult = {
  handled: boolean;
  data?: unknown;
};

export type CurrentPageInfo = {
  title: string;
  url: string;
  iconDataUrl?: string | null;
};

export type BookmarkUpdateInput = Partial<BookmarkRecord> & {
  oldUrl?: string;
  title?: string;
  url?: string;
};

export type WebAppSaveInput = Partial<WebAppRecord> & {
  name: string;
  startUrl: string;
};

export type HyperBackgroundAdapter<TSyncResult extends SyncBackgroundSignal> = {
  sync: SyncBackgroundController<TSyncResult>;
  getSettings?: () => Promise<unknown>;
  getCurrentPage?: () => Promise<CurrentPageInfo>;
  listBookmarks?: () => Promise<unknown>;
  findBookmarkByUrl?: (input: { url: string }) => Promise<unknown>;
  saveBookmark?: (input: BookmarkUpdateInput) => Promise<unknown>;
  deleteBookmark?: (input: { url: string }) => Promise<unknown>;
  listWebApps?: () => Promise<unknown>;
  findWebAppsByUrl?: (input: { url: string }) => Promise<unknown>;
  saveWebApp?: (input: WebAppSaveInput) => Promise<unknown>;
  deleteWebApp?: (input: unknown) => Promise<unknown>;
  loadLauncherLayout?: () => Promise<unknown>;
  saveLauncherLayout?: (layout: unknown) => Promise<unknown>;
  notifyLauncherChanged?: (result?: TSyncResult) => void;
  shouldScheduleAfterMutation?: boolean | ((type: string) => boolean);
};

type LauncherLayoutMutation = {
  changed: boolean;
  layout: LauncherJson;
};

export function createHyperBackgroundCommandHandler<TSyncResult extends SyncBackgroundSignal>(
  adapter: HyperBackgroundAdapter<TSyncResult>,
) {
  async function handle(command: HyperBackgroundCommand): Promise<HyperBackgroundCommandResult> {
    switch (command.type) {
      case "settings.get":
        return handled(await requireCapability(adapter.getSettings, command.type)());
      case "sync.run":
        return handled(await adapter.sync.runFullSyncNow());
      case "sync.soon":
        adapter.sync.scheduleSync();
        return handled(null);
      case "remote.check":
        return handled(await adapter.sync.checkRemoteChanges({ notifyPages: false }));
      case "bookmarks.list":
        return handled(await requireCapability(adapter.listBookmarks, command.type)());
      case "bookmarks.getByUrl":
        return handled(await requireCapability(adapter.findBookmarkByUrl, command.type)(bookmarkUrlInput(command.payload)));
      case "bookmarks.save":
        return handled(await saveBookmark(command.type, command.payload));
      case "bookmarks.delete":
        return handled(await mutate(command.type, () => requireCapability(adapter.deleteBookmark, command.type)(bookmarkDeleteInput(command.payload))));
      case "webapps.list":
        return handled(await requireCapability(adapter.listWebApps, command.type)());
      case "webapps.getByUrl":
        return handled(await requireCapability(adapter.findWebAppsByUrl, command.type)(bookmarkUrlInput(command.payload)));
      case "webapps.save":
        return handled(await saveWebApp(command.type, command.payload));
      case "webapps.delete":
        return handled(await mutate(command.type, () => requireCapability(adapter.deleteWebApp, command.type)(command.payload), true));
      case "launcher.layout.addWebApp":
        return handled(await updateLauncherLayout(command.type, (layout) => addWebAppToLauncherLayout(layout, webAppItemId(command.payload))));
      case "launcher.layout.removeWebApp":
        return handled(await updateLauncherLayout(command.type, (layout) => removeWebAppFromLauncherLayout(layout, webAppItemId(command.payload))));
      case "launcher.layout.save":
        return handled(await mutate(command.type, () => requireCapability(adapter.saveLauncherLayout, command.type)(launcherLayout(command.payload)), true));
      default:
        return { handled: false };
    }
  }

  async function saveBookmark(type: string, payload: unknown): Promise<unknown> {
    const input = payload == null
      ? await currentPageBookmarkInput(type)
      : bookmarkUpdateInput(payload);
    return mutate(type, () => requireCapability(adapter.saveBookmark, type)(input));
  }

  async function currentPageBookmarkInput(type: string): Promise<BookmarkUpdateInput> {
    const page = await requireCapability(adapter.getCurrentPage, type)();
    return {
      title: page.title,
      url: page.url,
      ...(page.iconDataUrl ? { iconDataUrl: page.iconDataUrl } : {}),
    };
  }

  async function saveWebApp(type: string, payload: unknown): Promise<unknown> {
    const input = payload == null
      ? await currentPageWebAppInput(type)
      : await webAppSaveInputOrCurrentPage(type, payload);
    return mutate(type, () => requireCapability(adapter.saveWebApp, type)(input), true);
  }

  async function webAppSaveInputOrCurrentPage(type: string, payload: unknown): Promise<WebAppSaveInput> {
    if (!isPlainObject(payload)) throw new Error("Invalid WebApp payload.");
    if (stringValue(payload.startUrl)) return webAppSaveInput(payload);
    const page = await currentPageWebAppInput(type);
    const hasIconDataUrl = Object.prototype.hasOwnProperty.call(payload, "iconDataUrl");
    return {
      ...payload,
      name: typeof payload.name === "string" ? payload.name : page.name,
      startUrl: page.startUrl,
      iconDataUrl: hasIconDataUrl ? stringValue(payload.iconDataUrl) || null : page.iconDataUrl,
      iconSource: stringValue(payload.iconSource) || page.iconSource,
    } as WebAppSaveInput;
  }

  async function currentPageWebAppInput(type: string): Promise<WebAppSaveInput> {
    const page = await requireCapability(adapter.getCurrentPage, type)();
    return {
      name: page.title,
      startUrl: page.url,
      ...(page.iconDataUrl ? { iconDataUrl: page.iconDataUrl, iconSource: "site" as const } : {}),
    };
  }

  async function updateLauncherLayout(type: string, operation: (layout: unknown) => LauncherLayoutMutation): Promise<unknown> {
    const current = await requireCapability(adapter.loadLauncherLayout, type)();
    const result = operation(current);
    if (!result.changed) return result;
    await requireCapability(adapter.saveLauncherLayout, type)(result.layout);
    adapter.notifyLauncherChanged?.();
    if (shouldScheduleAfterMutation(type)) adapter.sync.scheduleSync();
    return result;
  }

  async function mutate(type: string, operation: () => Promise<unknown>, launcherChanged = false): Promise<unknown> {
    const result = await operation();
    if (launcherChanged) adapter.notifyLauncherChanged?.(syncResultOrUndefined<TSyncResult>(result));
    if (shouldScheduleAfterMutation(type)) adapter.sync.scheduleSync();
    return result;
  }

  function shouldScheduleAfterMutation(type: string): boolean {
    const rule = adapter.shouldScheduleAfterMutation;
    if (typeof rule === "function") return rule(type);
    return rule === true;
  }

  return { handle };
}

function handled(data: unknown): HyperBackgroundCommandResult {
  return { handled: true, data };
}

function requireCapability<T>(capability: T | undefined, commandType: string): T {
  if (!capability) throw new Error(`Unsupported background command: ${commandType}`);
  return capability;
}

function bookmarkUpdateInput(payload: unknown): BookmarkUpdateInput {
  if (!isPlainObject(payload)) throw new Error("Invalid bookmark payload.");
  return payload as BookmarkUpdateInput;
}

function bookmarkUrlInput(payload: unknown): { url: string } {
  if (typeof payload === "string") return { url: payload };
  if (isPlainObject(payload) && typeof payload.url === "string") return { url: payload.url };
  throw new Error("Invalid bookmark URL payload.");
}

function bookmarkDeleteInput(payload: unknown): { url: string } {
  if (typeof payload === "string") return { url: payload };
  if (isPlainObject(payload)) {
    const url = stringValue(payload.url);
    if (url) return { url };
  }
  throw new Error("Invalid bookmark delete payload.");
}

function webAppSaveInput(payload: unknown): WebAppSaveInput {
  if (!isPlainObject(payload)) throw new Error("Invalid WebApp payload.");
  const name = typeof payload.name === "string" ? payload.name : "";
  const startUrl = typeof payload.startUrl === "string" ? payload.startUrl : "";
  if (!startUrl.trim()) throw new Error("WebApp startUrl is required.");
  return { ...payload, name, startUrl } as WebAppSaveInput;
}

function launcherLayout(payload: unknown): LauncherJson {
  if (isPlainObject(payload) && Object.prototype.hasOwnProperty.call(payload, "layout")) {
    return normalizeLauncherLayout(payload.layout);
  }
  return normalizeLauncherLayout(payload);
}

function webAppItemId(payload: unknown): string {
  const rawId = webAppId(payload);
  if (!rawId) throw new Error("WebApp id is required.");
  return rawId.startsWith("app:") ? rawId : `app:${rawId}`;
}

function webAppId(payload: unknown): string {
  if (typeof payload === "string") return payload.trim();
  if (!isPlainObject(payload)) return "";
  const direct = stringValue(payload.id) || stringValue(payload.webAppId) || stringValue(payload.itemId);
  if (direct) return direct;
  const webApp = payload.webApp;
  return isPlainObject(webApp)
    ? stringValue(webApp.id) || stringValue(webApp.webAppId) || stringValue(webApp.itemId)
    : "";
}

function addWebAppToLauncherLayout(layout: unknown, itemId: string): LauncherLayoutMutation {
  const current = normalizeLauncherLayout(layout);
  if (launcherLayoutContainsItem(current, itemId)) return { changed: false, layout: current };

  const cells = current.pages?.[0]?.cells || [];
  const nextIndex = nextLauncherCellIndex(cells);
  return {
    changed: true,
    layout: normalizeLauncherLayout({
      ...current,
      pages: [{ cells: [...cells, { id: itemId, index: nextIndex }] }],
    }),
  };
}

function removeWebAppFromLauncherLayout(layout: unknown, itemId: string): LauncherLayoutMutation {
  const current = normalizeLauncherLayout(layout);
  let changed = false;

  const pageCells = (current.pages?.[0]?.cells || []).filter((cell) => {
    const keep = cell.id !== itemId;
    if (!keep) changed = true;
    return keep;
  });

  const dock = (current.dock || []).filter((cell) => {
    const keep = cell.id !== itemId;
    if (!keep) changed = true;
    return keep;
  });

  const folders = (current.folders || []).map((folder) => {
    const cells = (folder.cells || []).filter((cell) => {
      const keep = cell.id !== itemId;
      if (!keep) changed = true;
      return keep;
    });
    return cells.length === (folder.cells || []).length ? folder : { ...folder, cells };
  });

  return {
    changed,
    layout: normalizeLauncherLayout({
      ...current,
      pages: pageCells.length > 0 ? [{ cells: pageCells }] : [],
      dock,
      folders,
    }),
  };
}

function normalizeLauncherLayout(layout: unknown): LauncherJson {
  if (!isPlainObject(layout)) return { rev: { counter: 0, deviceId: "" } };
  const firstPage = Array.isArray(layout.pages) ? layout.pages[0] : undefined;
  const pageCells = normalizeLauncherCells(isPlainObject(firstPage) ? firstPage.cells : undefined);
  const dock = normalizeLauncherCells(layout.dock).slice(0, 4);
  const folders = Array.isArray(layout.folders)
    ? layout.folders.map(normalizeLauncherFolder).filter((folder): folder is LauncherFolder => !!folder)
    : [];
  return {
    ...(pageCells.length > 0 ? { pages: [{ cells: pageCells }] } : {}),
    ...(dock.length > 0 ? { dock } : {}),
    ...(folders.length > 0 ? { folders } : {}),
    rev: normalizeLauncherRevision(layout.rev),
  };
}

function normalizeLauncherRevision(value: unknown): LauncherJson["rev"] {
  if (!isPlainObject(value)) return { counter: 0, deviceId: "" };
  return {
    counter: Number.isSafeInteger(value.counter) ? Number(value.counter) : 0,
    deviceId: typeof value.deviceId === "string" ? value.deviceId : "",
  };
}

function normalizeLauncherFolder(value: unknown): LauncherFolder | null {
  if (!isPlainObject(value)) return null;
  const id = stringValue(value.id);
  if (!id) return null;
  const cells = normalizeLauncherCells(value.cells);
  return {
    id,
    ...(typeof value.title === "string" ? { title: value.title } : {}),
    ...(cells.length > 0 ? { cells } : {}),
  };
}

function normalizeLauncherCells(value: unknown): LauncherCell[] {
  if (!Array.isArray(value)) return [];
  const seen = new Set<string>();
  return value
    .map((cell, fallbackIndex) => {
      if (typeof cell === "string") return launcherCell(cell, fallbackIndex);
      if (!isPlainObject(cell)) return null;
      return launcherCell(cell.id, cell.index, fallbackIndex);
    })
    .filter((cell): cell is LauncherCell => {
      if (!cell || seen.has(cell.id)) return false;
      seen.add(cell.id);
      return true;
    })
    .sort((left, right) => left.index - right.index || left.id.localeCompare(right.id));
}

function launcherCell(idValue: unknown, indexValue: unknown, fallbackIndex = 0): LauncherCell | null {
  const id = stringValue(idValue);
  if (!id) return null;
  const index = Number(indexValue);
  return {
    id,
    index: Number.isFinite(index) && index >= 0 ? Math.floor(index) : fallbackIndex,
  };
}

function launcherLayoutContainsItem(layout: LauncherJson, itemId: string): boolean {
  return (layout.pages?.[0]?.cells || []).some((cell) => cell.id === itemId) ||
    (layout.dock || []).some((cell) => cell.id === itemId) ||
    (layout.folders || []).some((folder) => folder.id === itemId || (folder.cells || []).some((cell) => cell.id === itemId));
}

function nextLauncherCellIndex(cells: LauncherCell[]): number {
  return cells.reduce((maxIndex, cell) => {
    const cellIndex = Number.isFinite(cell.index) ? Math.max(0, Math.floor(cell.index)) : maxIndex + 1;
    return cellIndex > maxIndex ? cellIndex : maxIndex;
  }, -1) + 1;
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function syncResultOrUndefined<TSyncResult extends SyncBackgroundSignal>(value: unknown): TSyncResult | undefined {
  if (!isPlainObject(value)) return undefined;
  if ("stateChanged" in value || "launcherChanged" in value) return value as TSyncResult;
  return undefined;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}
