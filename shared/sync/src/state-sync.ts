import { type SyncSettings } from "./index";
import {
  activeBookmarksFromState,
  activeWebAppsFromState,
  appendLocalSnapshotOperations,
  appendOperation,
  appendWebAppDelete,
  appendWebAppUpsert,
  canonicalJson,
  findBookmarkByUrlInState,
  findWebAppsByUrlInState,
  identityKeyForUrl,
  readSyncStateFromFiles,
  saveSyncStateToFiles,
  syncV2,
  type SyncStateFileStorage,
  type SyncV2LocalSnapshot,
  type SyncV2Mode,
  type SyncV2Result,
  type SyncV2State,
} from "./op-log";
import type { BookmarkRecord, LauncherJson, WebAppRecord } from "./sync-json-types";

export type SyncStateRunOptions<TSettings> = {
  mode?: SyncV2Mode;
  settings?: TSettings;
};

export type SyncStateResultBase = {
  stateChanged: boolean;
  launcherChanged: boolean;
  bookmarkCount: number;
  deletedBookmarkCount: number;
  importedBookmarkCount: number;
  removedBookmarkCount: number;
  webAppCount: number;
  deletedWebAppCount: number;
  importedWebAppCount: number;
  removedWebAppCount: number;
  syncedAt: number;
  uploadedOperationCount: number;
  remoteOperationCount: number;
  pendingOperationCount: number;
};

export type SyncStateBookmarkSaveInput = Partial<BookmarkRecord> & {
  oldUrl?: string;
};

export type SyncStateService<TSettings, TResult extends SyncStateResultBase> = {
  syncNow: (options?: SyncStateRunOptions<TSettings>) => Promise<TResult>;
  loadBookmarks: () => Promise<BookmarkRecord[]>;
  findBookmarkByUrl: (url: string) => Promise<BookmarkRecord | null>;
  saveBookmark: (input: SyncStateBookmarkSaveInput) => Promise<BookmarkRecord[]>;
  deleteBookmark: (input: { url?: string }) => Promise<BookmarkRecord[]>;
  saveBookmarkSnapshot: (bookmarks: BookmarkRecord[]) => Promise<boolean>;
  loadWebApps: () => Promise<WebAppRecord[]>;
  findWebAppsByUrl: (url: string) => Promise<WebAppRecord[]>;
  saveWebApp: (input: Partial<WebAppRecord> & { name: string; startUrl: string }) => Promise<WebAppRecord[]>;
  deleteWebApp: (input: string | Partial<WebAppRecord> | null | undefined) => Promise<WebAppRecord[]>;
  loadLauncherLayout: () => Promise<LauncherJson | null>;
  saveLauncherLayout: (layout: LauncherJson | null | undefined) => Promise<void>;
};

type SyncStateContext<TSettings> = {
  settings: TSettings;
  syncSettings: SyncSettings;
};

type SyncStateResultContext<TSettings> = SyncStateContext<TSettings> & {
  state: SyncV2State;
  previous: SyncV2State;
  sync: Pick<SyncV2Result, "stateChanged" | "launcherChanged" | "uploadedOperationCount" | "remoteOperationCount" | "pendingOperationCount" | "syncedAt">;
  base: SyncStateResultBase;
};

export type SyncStateServiceOptions<TSettings, TResult extends SyncStateResultBase> = {
  loadSettings: () => Promise<TSettings>;
  ensureSettings?: (settings: TSettings) => Promise<TSettings>;
  toSyncSettings: (settings: TSettings) => SyncSettings;
  syncFiles: SyncStateFileStorage;
  loadLocalSnapshot?: (context: SyncStateContext<TSettings>, mode: SyncV2Mode) => Promise<SyncV2LocalSnapshot>;
  saveSyncedState?: (context: SyncStateContext<TSettings>, state: SyncV2State) => Promise<void>;
  withLocalLock?: <T>(operation: () => Promise<T>) => Promise<T>;
  allowLocalOnlySync?: boolean;
  buildResult: (context: SyncStateResultContext<TSettings>) => TResult;
};

export function createSyncStateService<TSettings, TResult extends SyncStateResultBase>(
  options: SyncStateServiceOptions<TSettings, TResult>,
): SyncStateService<TSettings, TResult> {
  let localLock: Promise<void> = Promise.resolve();

  async function syncNow(runOptions: SyncStateRunOptions<TSettings> = {}): Promise<TResult> {
    const context = await prepareSettings(runOptions.settings);
    const before = await loadStateFiles();
    const mode = runOptions.mode || "merge";
    let result: Pick<SyncV2Result, "state" | "stateChanged" | "launcherChanged" | "uploadedOperationCount" | "remoteOperationCount" | "pendingOperationCount" | "syncedAt">;

    if (context.syncSettings.webDavUrl.trim()) {
      result = await syncV2({
        settings: context.syncSettings,
        loadState: () => loadStateFiles(),
        saveState: (state) => saveStateFiles(context, state),
        loadLocalSnapshot: options.loadLocalSnapshot
          ? () => options.loadLocalSnapshot!(context, mode)
          : undefined,
        withLocalLock,
        mode,
      });
    } else if (options.allowLocalOnlySync) {
      let localResult = {
        state: before,
        stateChanged: false,
        launcherChanged: false,
        uploadedOperationCount: 0,
        remoteOperationCount: 0,
        pendingOperationCount: 0,
        syncedAt: Date.now(),
      };
      await withLocalLock(async () => {
        const currentState = await loadStateFiles();
        const snapshot = await options.loadLocalSnapshot?.(context, "pushLocal") || {};
        const next = appendLocalSnapshotOperations(context.syncSettings.deviceId, currentState, snapshot);
        await saveStateFiles(context, next.state);
        localResult = {
          ...localResult,
          state: next.state,
          stateChanged: canonicalJson(before) !== canonicalJson(next.state),
          syncedAt: Date.now(),
        };
      });
      result = localResult;
    } else {
      throw new Error("WebDAV URL is required.");
    }

    return resultFromState(result.state, before, context, result);
  }

  async function loadBookmarks(): Promise<BookmarkRecord[]> {
    return activeBookmarksFromState(await loadStateFiles());
  }

  async function findBookmarkByUrl(url: string): Promise<BookmarkRecord | null> {
    const normalizedUrl = identityKeyForUrl(url.trim());
    if (!normalizedUrl) return null;
    return findBookmarkByUrlInState(await loadStateFiles(), normalizedUrl);
  }

  async function saveBookmark(input: SyncStateBookmarkSaveInput): Promise<BookmarkRecord[]> {
    const context = await prepareSettings();
    let nextState: SyncV2State | null = null;
    await withLocalLock(async () => {
      let state = await loadStateFiles();
      const url = identityKeyForUrl(input.url || "");
      if (!url) throw new Error("Bookmark URL is required.");
      const oldUrl = identityKeyForUrl(input.oldUrl || "");
    const existing = findBookmarkByUrlInState(state, url) ||
        (oldUrl ? findBookmarkByUrlInState(state, oldUrl) : null);
      if (oldUrl && oldUrl !== url && findBookmarkByUrlInState(state, oldUrl)) {
        const next = appendOperation(context.syncSettings.deviceId, state, {
          type: "bookmark.delete",
          url: oldUrl,
          title: existing?.title,
        });
        state = next.state;
      }
      const now = Date.now();
      const next = appendOperation(context.syncSettings.deviceId, state, {
        type: "bookmark.upsert",
        bookmark: {
          url,
          title: (input.title || existing?.title || url).trim() || url,
          createdAt: existing?.createdAt || input.createdAt || now,
          updatedAt: now,
        },
      });
      await saveStateFiles(context, next.state);
      nextState = next.state;
    });
    return activeBookmarksFromState(nextState || await loadStateFiles());
  }

  async function deleteBookmark(input: { url?: string }): Promise<BookmarkRecord[]> {
    const context = await prepareSettings();
    let nextState: SyncV2State | null = null;
    await withLocalLock(async () => {
      const state = await loadStateFiles();
      const url = identityKeyForUrl(input.url || "");
      const bookmark = url ? findBookmarkByUrlInState(state, url) : null;
      if (bookmark) {
        const next = appendOperation(context.syncSettings.deviceId, state, {
          type: "bookmark.delete",
          url: bookmark.url,
          title: bookmark.title,
        });
        await saveStateFiles(context, next.state);
        nextState = next.state;
      }
    });
    return activeBookmarksFromState(nextState || await loadStateFiles());
  }

  async function saveBookmarkSnapshot(bookmarks: BookmarkRecord[]): Promise<boolean> {
    const context = await prepareSettings();
    let changed = false;
    await withLocalLock(async () => {
      const currentState = await loadStateFiles();
      const next = appendLocalSnapshotOperations(context.syncSettings.deviceId, currentState, { bookmarks });
      changed = canonicalJson(currentState) !== canonicalJson(next.state);
      if (changed) {
        await saveStateFiles(context, next.state);
      }
    });
    return changed;
  }

  async function loadWebApps(): Promise<WebAppRecord[]> {
    return activeWebAppsFromState(await loadStateFiles());
  }

  async function findWebAppsByUrl(url: string): Promise<WebAppRecord[]> {
    const normalizedUrl = identityKeyForUrl(url.trim());
    if (!normalizedUrl) return [];
    return findWebAppsByUrlInState(await loadStateFiles(), normalizedUrl);
  }

  async function saveWebApp(input: Partial<WebAppRecord> & { name: string; startUrl: string }): Promise<WebAppRecord[]> {
    const context = await prepareSettings();
    let nextState: SyncV2State | null = null;
    await withLocalLock(async () => {
      const currentState = await loadStateFiles();
      const next = appendWebAppUpsert(context.syncSettings.deviceId, currentState, input);
      await saveStateFiles(context, next.state);
      nextState = next.state;
    });
    return activeWebAppsFromState(nextState || await loadStateFiles());
  }

  async function deleteWebApp(input: string | Partial<WebAppRecord> | null | undefined): Promise<WebAppRecord[]> {
    const context = await prepareSettings();
    const id = typeof input === "string" ? input.trim() : input?.id?.trim() || "";
    let nextState: SyncV2State | null = null;
    await withLocalLock(async () => {
      const currentState = await loadStateFiles();
      const next = appendWebAppDelete(context.syncSettings.deviceId, currentState, id);
      if (canonicalJson(currentState) !== canonicalJson(next.state)) {
        await saveStateFiles(context, next.state);
        nextState = next.state;
      }
    });
    return activeWebAppsFromState(nextState || await loadStateFiles());
  }

  async function loadLauncherLayout(): Promise<LauncherJson | null> {
    return (await loadStateFiles()).layout || null;
  }

  async function saveLauncherLayout(layout: LauncherJson | null | undefined): Promise<void> {
    if (!layout) return;
    const context = await prepareSettings();
    await withLocalLock(async () => {
      const currentState = await loadStateFiles();
      const next = appendLocalSnapshotOperations(context.syncSettings.deviceId, currentState, { layout }, { forceLayout: true });
      if (canonicalJson(currentState) !== canonicalJson(next.state)) {
        await saveStateFiles(context, next.state);
      }
    });
  }

  async function prepareSettings(input?: TSettings): Promise<SyncStateContext<TSettings>> {
    const loaded = input || await options.loadSettings();
    const settings = options.ensureSettings ? await options.ensureSettings(loaded) : loaded;
    return {
      settings,
      syncSettings: options.toSyncSettings(settings),
    };
  }

  async function loadStateFiles(): Promise<SyncV2State> {
    return readSyncStateFromFiles(options.syncFiles);
  }

  async function saveStateFiles(context: SyncStateContext<TSettings>, state: SyncV2State): Promise<void> {
    if (options.saveSyncedState) {
      await options.saveSyncedState(context, state);
      return;
    }
    await saveSyncStateToFiles(options.syncFiles, state);
  }

  async function withLocalLock<T>(operation: () => Promise<T>): Promise<T> {
    if (options.withLocalLock) return options.withLocalLock(operation);
    const previous = localLock;
    let release!: () => void;
    localLock = new Promise<void>((resolve) => {
      release = resolve;
    });
    await previous;
    try {
      return await operation();
    } finally {
      release();
    }
  }

  function resultFromState(
    state: SyncV2State,
    previous: SyncV2State,
    context: SyncStateContext<TSettings>,
    sync: Pick<SyncV2Result, "stateChanged" | "launcherChanged" | "uploadedOperationCount" | "remoteOperationCount" | "pendingOperationCount" | "syncedAt">,
  ): TResult {
    const base = createSyncStateResultBase(state, previous, sync);
    return options.buildResult({
      ...context,
      state,
      previous,
      sync,
      base,
    });
  }

  return {
    syncNow,
    loadBookmarks,
    findBookmarkByUrl,
    saveBookmark,
    deleteBookmark,
    saveBookmarkSnapshot,
    loadWebApps,
    findWebAppsByUrl,
    saveWebApp,
    deleteWebApp,
    loadLauncherLayout,
    saveLauncherLayout,
  };
}

export function createSyncStateResultBase(
  state: SyncV2State,
  previous: SyncV2State,
  sync: Pick<SyncV2Result, "stateChanged" | "launcherChanged" | "uploadedOperationCount" | "remoteOperationCount" | "pendingOperationCount" | "syncedAt">,
): SyncStateResultBase {
  const bookmarks = activeBookmarksFromState(state);
  const webApps = activeWebAppsFromState(state);
  const previousBookmarks = new Set(activeBookmarksFromState(previous).map((bookmark) => bookmark.url));
  const previousWebApps = new Set(activeWebAppsFromState(previous).map((app) => app.id));
  return {
    stateChanged: sync.stateChanged,
    launcherChanged: sync.launcherChanged,
    bookmarkCount: bookmarks.length,
    deletedBookmarkCount: Object.keys(state.bookmarkTombstones).length,
    importedBookmarkCount: bookmarks.filter((bookmark) => !previousBookmarks.has(bookmark.url)).length,
    removedBookmarkCount: [...previousBookmarks].filter((id) => !state.bookmarks[id]).length,
    webAppCount: webApps.length,
    deletedWebAppCount: Object.keys(state.appTombstones).length,
    importedWebAppCount: webApps.filter((app) => !previousWebApps.has(app.id)).length,
    removedWebAppCount: [...previousWebApps].filter((id) => !state.apps[id]).length,
    syncedAt: sync.syncedAt,
    uploadedOperationCount: sync.uploadedOperationCount,
    remoteOperationCount: sync.remoteOperationCount,
    pendingOperationCount: sync.pendingOperationCount,
  };
}
