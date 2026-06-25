# WebDAV 同步与 Background 写入模型

本文记录当前 WebDAV 同步和内置 WebExtension background 的职责边界。同步相关实现以代码为准，主要入口在：

- `shared/sync/src/sync-json-types.ts`
- `shared/sync/src/op-log.ts`
- `shared/sync/src/background.ts`
- `shared/sync/src/hyper-background.ts`
- `shared/sync/src/browser-sync.ts`
- `internal-pages/src/background.ts`
- `internal-pages/src/webdav-sync.ts`
- `app/src/main/java/com/dadigua/hyperbrowser/sync/WebDavLocalSyncAdapter.kt`

## 文件模型

WebDAV 目录固定为 `HyperBrowserSync/`。远端业务文件是：

- `bookmarks.json`
- `webapps.json`
- `launcher.json`
- `manifest.json`

其中 `manifest.json` 只是远端摘要和调试索引，不是业务合并源。业务合并只看前三个 JSON。

Android 本地也保留同形态业务文件：

- `files/bookmarks.json`
- `files/webapps.json`
- `files/launcher.json`

本地文件和远端文件应保持同一格式，不再额外引入 sidecar 或旧格式 fallback。旧数据迁移应作为单独迁移功能处理，不混进同步读写路径。

## JSON 类型

三份业务 JSON 的 TypeScript 类型统一定义在 `shared/sync/src/sync-json-types.ts`：

- `BookmarksJson`
- `WebAppsJson`
- `LauncherJson`
- `BookmarkRecord`
- `WebAppRecord`
- `BookmarkSyncRecord`
- `WebAppSyncRecord`
- `SyncTombstone`
- `SyncRevision`

关键规则：

- `BookmarkRecord` / `WebAppRecord` 是业务记录本体。
- `BookmarkSyncRecord` / `WebAppSyncRecord` 在业务记录基础上增加必填 `rev`。
- `rev.counter` 是本记录最后一次业务修改时间，用于 LWW 比较；不是操作次数。
- `rev.deviceId` 用于排查来源和 counter 相同时的稳定排序，不作为主要新旧判断。
- 书签以规范化 URL 为 key。
- WebApp 以 `id` 为 key，允许多个 WebApp 使用相同 URL。
- `launcher.json` 作为整体布局合并，`rev` 在布局根节点，不给每个 item 单独做版本。

## 合并规则

同步引擎在 `shared/sync/src/op-log.ts`：

- `readSyncStateFromFiles()` 从 `bookmarks.json`、`webapps.json`、`launcher.json` 读出本地合并态。
- `saveSyncStateToFiles()` 把合并态写回三份业务文件。
- `syncV2()` 负责读取远端、合并本地、上传远端。
- 书签和 WebApp 记录按 `rev.counter` 做 LWW 合并。
- 书签删除使用 `bookmarkTombstones`，WebApp 删除使用 `appTombstones`，避免旧设备把删除数据同步回来。
- 布局按 `launcher.json` 根 `rev` 整体合并。
- 书签展示顺序按 `createdAt` 降序，新添加的在前；标题修改不改变添加顺序。

## Background 统一写入

内置页和扩展侧的业务写入都应通过 background 命令进入统一逻辑：

```text
页面 / popup
  -> window.hyperBrowser 或 browser.runtime.sendMessage(...)
  -> internal-pages/src/background.ts 或 companion-extension/src/background.ts
  -> shared/sync/src/hyper-background.ts
  -> shared/sync/src/browser-sync.ts 或 internal-pages/src/webdav-sync.ts
  -> shared/sync/src/op-log.ts
  -> 本地 JSON / WebDAV
```

统一命令包括：

- `bookmarks.list`
- `bookmarks.getByUrl`
- `bookmarks.save`
- `bookmarks.delete`
- `webapps.list`
- `webapps.getByUrl`
- `webapps.save`
- `webapps.delete`
- `launcher.layout.addWebApp`
- `launcher.layout.removeWebApp`
- `launcher.layout.save`
- `sync.run`
- `sync.soon`
- `remote.check`

业务页面不要绕过 background 直接调用 native 写业务文件。Native 侧只负责系统能力、读取当前页面信息、保存最终 JSON 文件和返回数据。

## Android 本地桥

Android 内置页 background 通过 `internal-pages/src/webdav-sync.ts` 调用 native bridge：

- `sync.localFile.read`
- `sync.localFile.save`

Kotlin 侧由 `WebDavLocalSyncAdapter` 映射到本地文件：

- `bookmarks.json` -> `BrowserProfileStore.bookmarksSyncJson()` / `saveBookmarksSyncJson()`
- `webapps.json` -> `WebAppRepository.syncJson()` / `saveSyncJson()`
- `launcher.json` -> `BrowserProfileStore.launcherSyncJson()` / `saveLauncherSyncJson()`

这些 native API 是文件读写适配层，不承载业务合并策略。业务新旧判断、墓碑、布局版本和自动同步调度都在 background/shared sync 层完成。

## 自动同步

`shared/sync/src/background.ts` 提供通用 background 同步控制器：

- mutation 后可 debounce 调用 `scheduleSync()`。
- `runFullSyncNow()` 用于用户手动立即同步。
- `checkRemoteChanges()` 用于周期性远端检查。
- 同一时间只跑一个 sync，运行中有新请求会排队。

Android 内置 background 对 `bookmarks.*`、`webapps.*`、`launcher.layout.*` mutation 调度同步。桌面 companion extension 对 WebApp 和 launcher mutation 调度同步，并监听 Chrome bookmarks 变化生成本地书签快照。

## 图标与本地缓存

书签同步数据不保存图标。书签图标来自本地 favicon cache，`updateBookmarkIcon()` 只更新本地显示态，不写同步版本。

WebApp 同步记录允许保存自定义图标 `iconDataUrl` 和 `iconSource`，因为 WebApp 图标属于用户可编辑的业务数据。站点 favicon 缓存仍然是本地显示缓存。

## 不再使用的旧路径

后续修改不要重新引入以下模式：

- 页面直接写 native 业务数据，绕过 background。
- native 直接实现 WebApp install/update/delete 的同步业务 API。
- 为同步状态再引入本地/远端不同格式的 sidecar 文件。
- 在同步读路径里 fallback 读取旧格式；旧格式迁移应单独做迁移功能。
- 用 URL 作为 WebApp 唯一身份。
- 让 `updatedAt`、`createdAt`、`deletedAt` 代替 `rev.counter` 参与同步新旧判断。
