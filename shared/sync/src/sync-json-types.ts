// WebDAV 同步文件的目标类型草案。
// 这个文件刻意不 import 现有实现类型，用来和实际代码解耦后逐项对齐。

export type SyncSchemaVersion = 2;

export type SyncRevision = {
  // 唯一冲突判断依据：谁的 counter 更大，谁更新。
  // 它表示本条记录最后一次业务修改时间，不表示操作次数。
  counter: number;
  // 产生这次修改的设备。只用于排查来源和 counter 相同场景的稳定排序，不作为新旧判断主依据。
  deviceId: string;
};

export type BookmarksJson = {
  // 文件格式版本。当前版本只认 schemaVersion=2。
  schemaVersion: SyncSchemaVersion;
  // key 是规范化后的书签 URL。
  bookmarks: Record<string, BookmarkSyncRecord>;
  // key 和 bookmarks 一致。删除后保留墓碑，避免旧设备把书签同步回来。
  bookmarkTombstones: Record<string, SyncTombstone>;
};

export type BookmarkSyncRecord = {
  // 书签身份。通常和 bookmarks 的 key 一致。
  url: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  iconDataUrl?: string | null;
  // 书签本条记录的最后修改版本。
  rev: SyncRevision;
};

export type SyncTombstone = {
  // 删除发生时间，主要用于可读和后续清理策略。
  deletedAt: string;
  // 删除本身也是一次修改，必须用 rev 参与合并。
  rev: SyncRevision;
};

export type WebAppsJson = {
  // 文件格式版本。当前版本只认 schemaVersion=2。
  schemaVersion: SyncSchemaVersion;
  // key 是 WebApp id。允许多个 WebApp 拥有相同 URL，所以不能用 URL 做 key。
  apps: Record<string, WebAppSyncRecord>;
  // key 和 apps 一致。删除后保留墓碑，避免旧设备把 WebApp 同步回来。
  appTombstones: Record<string, SyncTombstone>;
};

export type WebAppSyncRecord = {
  // WebApp 实例 id。它是身份，不是 URL。
  id: string;
  name: string;
  startUrl: string;
  themeColor: number;
  displayMode: string;
  createdAt: number;
  lastOpenedAt: number;
  updatedAt: number;
  iconDataUrl?: string | null;
  iconSource?: "custom" | "site" | "title";
  // WebApp 本条记录的最后修改版本。
  rev: SyncRevision;
};

export type LauncherJson = {
  // 本地 launcher.json 和远端 launcher.json 应完全一致。
  // 这是首页布局文件本体，不再额外包一层 layout/items。
  pages?: LauncherPage[];
  dock?: LauncherCell[];
  folders?: LauncherFolder[];
  // 整个 launcher 布局最后修改版本。布局不是单个 item 的业务数据，整体按这个版本合并。
  rev: SyncRevision;
};

export type LauncherPage = {
  // 当前页上的 cells。
  cells?: LauncherCell[];
};

export type LauncherCell = {
  // launcher item id，例如 app:<webAppId>、folder:<folderId>、history。
  id: string;
  // 当前容器内顺序。数组位置不作为顺序依据；如果冲突，以 index 为准。
  index: number;
};

export type LauncherFolder = {
  id: string;
  title?: string;
  // 文件夹内 item 顺序。
  cells?: LauncherCell[];
};

export type SyncJsonFileName = "bookmarks.json" | "webapps.json" | "launcher.json";

export type SyncJsonByFileName = {
  "bookmarks.json": BookmarksJson;
  "webapps.json": WebAppsJson;
  "launcher.json": LauncherJson;
};

export type SyncJsonDocument = SyncJsonByFileName[SyncJsonFileName];
