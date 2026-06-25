import type { BookmarkRecord, WebAppRecord } from "./index";
import type { SyncBackgroundController, SyncBackgroundSignal } from "./background";

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
  saveWebApp?: (input: WebAppSaveInput) => Promise<unknown>;
  deleteWebApp?: (input: unknown) => Promise<unknown>;
  loadLauncherLayout?: () => Promise<unknown>;
  saveLauncherLayout?: (layout: unknown) => Promise<unknown>;
  notifyLauncherChanged?: (result?: TSyncResult) => void;
  shouldScheduleAfterMutation?: boolean | ((type: string) => boolean);
};

type LauncherCell = Record<string, unknown> & {
  id?: string;
  page?: number;
  row?: number;
  column?: number;
  index?: number;
};

type LauncherFolder = Record<string, unknown> & {
  childIds?: unknown[];
};

type LauncherLayout = Record<string, unknown> & {
  version?: number;
  cells?: LauncherCell[];
  dock?: unknown[];
  folders?: LauncherFolder[];
};

type LauncherLayoutMutation = {
  changed: boolean;
  layout: LauncherLayout;
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
      case "bookmarks.getFromCurrentPage":
        return handled(await findBookmarkFromCurrentPage(command.type));
      case "bookmarks.save":
        return handled(await saveBookmark(command.type, command.payload));
      case "bookmarks.delete":
        return handled(await mutate(command.type, () => requireCapability(adapter.deleteBookmark, command.type)(bookmarkDeleteInput(command.payload))));
      case "webapps.list":
        return handled(await requireCapability(adapter.listWebApps, command.type)());
      case "webapps.addFromCurrentPage":
        return handled(await addWebAppFromCurrentPage(command.type));
      case "webapps.save":
        return handled(await mutate(command.type, () => requireCapability(adapter.saveWebApp, command.type)(webAppSaveInput(command.payload)), true));
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

  async function findBookmarkFromCurrentPage(type: string): Promise<unknown> {
    const page = await requireCapability(adapter.getCurrentPage, type)();
    return requireCapability(adapter.findBookmarkByUrl, type)({ url: page.url });
  }

  async function addWebAppFromCurrentPage(type: string): Promise<unknown> {
    const page = await requireCapability(adapter.getCurrentPage, type)();
    return mutate(type, () => requireCapability(adapter.saveWebApp, type)({
      name: page.title,
      startUrl: page.url,
      ...(page.iconDataUrl ? { iconDataUrl: page.iconDataUrl, iconSource: "site" as const } : {}),
    }), true);
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

function launcherLayout(payload: unknown): unknown {
  if (isPlainObject(payload) && Object.prototype.hasOwnProperty.call(payload, "layout")) {
    return payload.layout;
  }
  return payload;
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

  const cells = current.cells || [];
  const nextIndex = nextLauncherCellIndex(cells);
  current.cells = [
    ...cells,
    {
      page: 0,
      row: 0,
      column: 0,
      id: itemId,
      index: nextIndex,
    },
  ];
  current.version = positiveInteger(current.version) || 4;
  return { changed: true, layout: current };
}

function removeWebAppFromLauncherLayout(layout: unknown, itemId: string): LauncherLayoutMutation {
  const current = normalizeLauncherLayout(layout);
  let changed = false;

  if (current.cells) {
    const cells = current.cells.filter((cell) => {
      const keep = stringValue(cell.id) !== itemId;
      if (!keep) changed = true;
      return keep;
    });
    current.cells = cells;
  }

  if (current.dock) {
    const dock = current.dock.filter((id) => {
      const keep = stringValue(id) !== itemId;
      if (!keep) changed = true;
      return keep;
    });
    current.dock = dock;
  }

  if (current.folders) {
    current.folders = current.folders.map((folder) => {
      if (!Array.isArray(folder.childIds)) return folder;
      const childIds = folder.childIds.filter((id) => {
        const keep = stringValue(id) !== itemId;
        if (!keep) changed = true;
        return keep;
      });
      return childIds.length === folder.childIds.length ? folder : { ...folder, childIds };
    });
  }

  return { changed, layout: current };
}

function normalizeLauncherLayout(layout: unknown): LauncherLayout {
  const source = isPlainObject(layout) ? layout : {};
  const cells = Array.isArray(source.cells)
    ? source.cells.map((cell) => isPlainObject(cell) ? { ...cell } : { id: stringValue(cell) })
    : undefined;
  const folders = Array.isArray(source.folders)
    ? source.folders.map((folder) => isPlainObject(folder) ? {
      ...folder,
      childIds: Array.isArray(folder.childIds) ? [...folder.childIds] : folder.childIds,
    } : {})
    : undefined;
  return {
    ...source,
    ...(cells ? { cells } : {}),
    ...(Array.isArray(source.dock) ? { dock: [...source.dock] } : {}),
    ...(folders ? { folders } : {}),
  } as LauncherLayout;
}

function launcherLayoutContainsItem(layout: LauncherLayout, itemId: string): boolean {
  return (layout.cells || []).some((cell) => stringValue(cell.id) === itemId) ||
    (layout.dock || []).some((id) => stringValue(id) === itemId) ||
    (layout.folders || []).some((folder) => Array.isArray(folder.childIds) && folder.childIds.some((id) => stringValue(id) === itemId));
}

function nextLauncherCellIndex(cells: LauncherCell[]): number {
  return cells.reduce((maxIndex, cell) => {
    const explicit = positiveInteger(cell.index);
    const cellIndex = explicit !== null ? explicit : maxIndex + 1;
    return cellIndex > maxIndex ? cellIndex : maxIndex;
  }, -1) + 1;
}

function positiveInteger(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) && value > 0
    ? Math.floor(value)
    : null;
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
