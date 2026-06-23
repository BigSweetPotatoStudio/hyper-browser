import type { LauncherLayoutSyncResult } from "@hyper-launcher/webdav-layout";

export type { BookmarkRecord, RemoteSyncManifest, SyncDocument, SyncMetadata, SyncSettings, WebAppRecord } from "@hyper-sync";

export type SyncResult = {
  bookmarkCount: number;
  deletedBookmarkCount: number;
  importedBookmarkCount: number;
  removedBookmarkCount: number;
  launcherLayout?: LauncherLayoutSyncResult;
  syncedAt: number;
  folderTitle: string;
  attemptCount: number;
};
