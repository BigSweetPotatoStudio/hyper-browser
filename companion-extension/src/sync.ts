import { browser, type Browser } from "wxt/browser";
import { createBrowserSyncService, type BrowserBookmarkNode } from "@hyper-sync/browser-sync";
import { launcherLayoutStorage } from "./launcher-layout";
import { loadSettings, loadSyncV2Store, saveSettings, saveSyncV2Store } from "./storage";

export const browserSyncService = createBrowserSyncService({
  loadSettings,
  saveSettings,
  loadSyncV2Store,
  saveSyncV2Store,
  loadLauncherLayout: () => launcherLayoutStorage.load(),
  bookmarks: {
    getTree: () => browser.bookmarks.getTree() as Promise<BrowserBookmarkNode[]>,
    get: (id) => browser.bookmarks.get(id) as Promise<BrowserBookmarkNode[]>,
    getSubTree: (id) => browser.bookmarks.getSubTree(id) as Promise<BrowserBookmarkNode[]>,
    create: (bookmark) => browser.bookmarks.create(bookmark as Browser.bookmarks.CreateDetails) as Promise<BrowserBookmarkNode>,
    update: (id, changes) => browser.bookmarks.update(id, changes as Browser.bookmarks.UpdateChanges) as Promise<BrowserBookmarkNode>,
    remove: (id) => browser.bookmarks.remove(id),
    removeTree: (id) => browser.bookmarks.removeTree(id),
  },
});
