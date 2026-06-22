export type SyncSettings = {
  webDavUrl: string;
  username: string;
  password: string;
  folderTitle: string;
  folderId: string;
  deviceName: string;
  deviceId: string;
};

export type SyncDocument<T> = {
  type: string;
  schemaVersion: number;
  updatedAt: number;
  items: T[];
};

export type BookmarkRecord = {
  url: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  deletedAt: number | null;
  sourceDeviceId: string;
  iconDataUrl?: string | null;
};

export type WebAppRecord = {
  id: string;
  name: string;
  startUrl: string;
  scopeUrl: string;
  themeColor: number;
  displayMode: string;
  createdAt: number;
  lastOpenedAt: number;
  updatedAt: number;
  deletedAt: number | null;
  sourceDeviceId: string;
  iconDataUrl?: string | null;
  iconSource?: "custom" | "site" | "title";
};

export type SyncMetadata = {
  bookmarks: Record<string, BookmarkRecord>;
};

export type SyncResult = {
  bookmarkCount: number;
  deletedBookmarkCount: number;
  importedBookmarkCount: number;
  removedBookmarkCount: number;
  syncedAt: number;
  folderTitle: string;
  attemptCount: number;
};

export type RemoteSyncManifest = {
  updatedAt: number;
  lastWriter: string;
};
