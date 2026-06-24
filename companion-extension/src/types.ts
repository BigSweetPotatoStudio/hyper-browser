export type { BookmarkRecord, SyncSettings, WebAppRecord } from "@hyper-sync";

export type SyncResult = {
  bookmarkCount: number;
  deletedBookmarkCount: number;
  importedBookmarkCount: number;
  removedBookmarkCount: number;
  webAppCount: number;
  deletedWebAppCount: number;
  importedWebAppCount: number;
  removedWebAppCount: number;
  syncedAt: number;
  folderTitle: string;
  uploadedOperationCount: number;
  remoteOperationCount: number;
  pendingOperationCount: number;
};
