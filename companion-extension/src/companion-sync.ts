import { browser, type Browser } from "wxt/browser";
import type { SyncSettings } from "@hyper-sync";
import type { SyncBackgroundRunOptions } from "@hyper-sync/background";
import {
  activeBookmarksFromState,
  layoutFromState,
  saveSyncStateToFiles,
  type SyncStateFileStorage,
  type SyncV2LocalSnapshot,
  type SyncV2Mode,
  type SyncV2State,
} from "@hyper-sync/op-log";
import { createSyncStateService, type SyncStateResultBase } from "@hyper-sync/state-sync";
import { createSyncStateBackgroundAdapter, type BackgroundAdapter } from "@hyper-sync/background-adapter";
import {
  createCompanionBookmarkProjection,
  type BrowserBookmarkEvent,
  type BrowserBookmarkNode,
  type CompanionBookmarkProjectionOptions,
} from "./companion-bookmarks";
import { launcherLayoutStorage } from "./launcher-layout";
import { loadSettings, readSyncFile, saveSettings, saveSyncFile } from "./storage";

export type CompanionSyncResult = SyncStateResultBase & {
  folderTitle: string;
};

type CompanionBackgroundAdapterOptions = {
  sync: BackgroundAdapter<CompanionSyncResult>["sync"];
  getSettings?: BackgroundAdapter<CompanionSyncResult>["getSettings"];
  getCurrentPage?: BackgroundAdapter<CompanionSyncResult>["getCurrentPage"];
  notifyLauncherChanged?: BackgroundAdapter<CompanionSyncResult>["notifyLauncherChanged"];
};

export type CompanionSyncOptions = {
  loadSettings: () => Promise<SyncSettings>;
  saveSettings: (settings: SyncSettings) => Promise<void>;
  syncFiles: SyncStateFileStorage;
  loadLauncherLayout: () => Promise<SyncV2LocalSnapshot["layout"] | undefined>;
  bookmarks: CompanionBookmarkProjectionOptions["bookmarks"];
};

export function createCompanionSync(options: CompanionSyncOptions) {
  const bookmarks = createCompanionBookmarkProjection({
    loadSettings: options.loadSettings,
    saveSettings: options.saveSettings,
    bookmarks: options.bookmarks,
  });

  const stateSync = createSyncStateService({
    loadSettings: options.loadSettings,
    ensureSettings: bookmarks.ensureBookmarkFolder,
    toSyncSettings: (settings) => settings,
    syncFiles: options.syncFiles,
    loadLocalSnapshot: ({ syncSettings }, mode) => loadLocalSnapshot(syncSettings, mode),
    saveSyncedState: ({ syncSettings }, state) => saveLocalState(syncSettings, state),
    allowLocalOnlySync: true,
    buildResult: ({ base, syncSettings }) => ({
      ...base,
      folderTitle: syncSettings.folderTitle,
    }),
  });

  async function syncNow(syncOptions: SyncBackgroundRunOptions = {}): Promise<CompanionSyncResult> {
    return stateSync.syncNow(syncOptions);
  }

  async function recordLocalBookmarkFolderSnapshot(events: BrowserBookmarkEvent[] = []): Promise<boolean | null> {
    return bookmarks.recordLocalBookmarkFolderSnapshot(
      events,
      await stateSync.loadBookmarks(),
      (snapshot) => stateSync.saveBookmarkSnapshot(snapshot),
    );
  }

  async function addBookmarkToSyncFolder(input: { title: string; url: string }) {
    return bookmarks.addBookmarkToSyncFolder(input, (bookmark) => stateSync.saveBookmark(bookmark));
  }

  async function deleteRemoteBookmark(input: string | { url?: string } | null | undefined) {
    return bookmarks.deleteBookmarkFromSyncFolder(
      input,
      () => stateSync.loadBookmarks(),
      (bookmark) => stateSync.deleteBookmark(bookmark),
    );
  }

  function createBackgroundAdapter(adapterOptions: CompanionBackgroundAdapterOptions): BackgroundAdapter<CompanionSyncResult> {
    const baseAdapter = createSyncStateBackgroundAdapter({
      sync: adapterOptions.sync,
      state: stateSync,
      getSettings: adapterOptions.getSettings,
      getCurrentPage: adapterOptions.getCurrentPage,
      notifyLauncherChanged: adapterOptions.notifyLauncherChanged,
      shouldScheduleAfterMutation: (type) =>
        type.startsWith("bookmarks.") ||
        type.startsWith("webapps.") ||
        type.startsWith("launcher.layout."),
    });
    return {
      ...baseAdapter,
      saveBookmark: (input) => addBookmarkToSyncFolder({
        title: input.title || input.url || "",
        url: input.url || "",
      }),
      deleteBookmark: deleteRemoteBookmark,
    };
  }

  async function loadLocalSnapshot(settings: SyncSettings, mode: SyncV2Mode): Promise<SyncV2LocalSnapshot> {
    const layout = await options.loadLauncherLayout();
    if (mode !== "pushLocal") {
      return { layout };
    }
    return {
      bookmarks: await bookmarks.collectLocalBookmarkRecords(settings, await stateSync.loadBookmarks()),
      layout,
    };
  }

  async function saveLocalState(settings: SyncSettings, state: SyncV2State): Promise<void> {
    await saveStateFiles(state);
    await bookmarks.applyBookmarkRecords(settings, activeBookmarksFromState(state));
    void layoutFromState(state);
  }

  async function saveStateFiles(state: SyncV2State): Promise<void> {
    await saveSyncStateToFiles(options.syncFiles, state);
  }

  return {
    syncNow,
    recordLocalBookmarkFolderSnapshot,
    createBackgroundAdapter,
  };
}

export const companionSync = createCompanionSync({
  loadSettings,
  saveSettings,
  syncFiles: {
    readFile: readSyncFile,
    saveFile: saveSyncFile,
  },
  loadLauncherLayout: () => launcherLayoutStorage.load(),
  bookmarks: {
    getTree: () => browser.bookmarks.getTree() as Promise<BrowserBookmarkNode[]>,
    get: (id) => browser.bookmarks.get(id) as Promise<BrowserBookmarkNode[]>,
    getSubTree: (id) => browser.bookmarks.getSubTree(id) as Promise<BrowserBookmarkNode[]>,
    create: (bookmark) => browser.bookmarks.create(bookmark as Browser.bookmarks.CreateDetails) as Promise<BrowserBookmarkNode>,
    update: (id, changes) => browser.bookmarks.update(id, changes as Browser.bookmarks.UpdateChanges) as Promise<BrowserBookmarkNode>,
    remove: (id) => browser.bookmarks.remove(id),
  },
});
