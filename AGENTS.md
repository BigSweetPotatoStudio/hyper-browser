# Hyper Browser 开发约定

## 文档职责

- `README.md` 面向用户介绍项目、能力、截图和安装方式，不写实现方案。
- `AGENTS.md` 只保留开发时必须遵守的架构边界、交互语义、验证和发布规则。
- 构建、同步、发布的详细说明分别放在 `CONTRIBUTING.md`、`docs/SYNC.md`、`docs/RELEASE.md`，不要把长篇操作手册复制回本文件。
- 当前源码是真相。实现改变了本文件描述的架构或语义时，必须在同一次修改中更新本文件。

## 项目定位

Hyper Browser 是 Android 原生浏览器和 WebApp 容器：

- Kotlin、Jetpack Compose / Material3
- GeckoView
- Gradle Wrapper、Java 17
- 包名 `com.dadigua.hyperbrowser`

目标是让任意 URL 可作为独立 WebApp 安装，同时提供接近 Chrome Android 的基础浏览体验；不要把它退化成单页 WebView Demo。

## 环境与命令

先判断当前 shell，再使用对应语法，不要混用 PowerShell 和 bash/zsh：

```bash
# WSL / Linux
./gradlew :app:assembleDebug --console=plain
./gradlew :app:installDebug --console=plain
```

```powershell
# Windows PowerShell
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:installDebug --console=plain
```

- 始终使用 Gradle Wrapper，不依赖全局 Gradle。
- `internal-pages/` 使用 pnpm，不使用 npm。
- 不要为执行 Gradle 或 adb 再套一层不必要的 `cmd.exe`。
- WSL 多设备安装和扩展打包优先使用 `pnpm install-all-adb-and-package-extension`；脚本会选择 Windows `adb.exe` 并转换路径。
- 指定设备后，设备枚举、安装、启动、截图和状态检查必须始终使用同一个 adb 可执行文件及同一个 serial；每条设备命令都显式带 `-s <serial>`。

## 当前代码边界

主要入口：

- `HyperBrowserApp.kt`：应用级仓库入口；`extensions` 等重对象保持懒加载，不要为了预热拖慢冷启动。
- `BrowserActivity.kt`：浏览器壳、标签管理、面板路由和主交互。
- `WebAppActivity.kt`：WebApp 独立任务模式。
- `GeckoRuntimeProvider.kt`：全局单例 `GeckoRuntime`。
- `GeckoSessionController.kt`：单个标签或 WebApp 的 Gecko 会话封装与安全边界。
- `BrowserProfileStore.kt`、`DownloadStore.kt`：浏览器状态和下载持久化。
- `WebAppRepository.kt`、`ExtensionRepository.kt`：WebApp 与扩展能力。
- `internal-pages/`：内置 WebExtension、React 首页、阅读模式和内容脚本源码。
- `shared/sync/`：WebDAV 同步、合并、墓碑和同步 UI 的共享实现。

当前页面分工必须保持清楚：

- `hyper://home` 由内置 WebExtension 的 React 首页渲染。
- 搜索、设置、书签、历史、下载、扩展和标签页是 Compose 面板。
- `hyper://settings`、`hyper://bookmarks`、`hyper://history` 是语义路由，进入对应 Compose 面板。
- 地址栏和历史记录只显示 `hyper://...` 语义地址，不暴露 `moz-extension://...` 或资源路径。

不要根据旧文档把全部内置页重新实现为 HTML，也不要把 React 首页逻辑塞回 `BrowserActivity`。

## UI 与导航语义

- UI 参考 Chrome Android 的移动端层级，但必须兼容当前顶部、底部、动态底部和悬浮点工具栏模式；不要假定地址栏永远在顶部。
- 常用导航保留在工具栏，低频操作放菜单；不要把所有操作按钮堆在工具栏。
- 搜索、设置、书签、历史、下载、扩展和标签面板优先关闭面板，再处理网页后退。
- 返回顺序保持为：退出全屏/关闭弹窗与覆盖层/关闭查找栏/关闭当前面板/网页后退/关闭带 opener 的子标签并回到来源标签。
- 外部应用打开、系统分享、权限提示和文件选择必须由明确的系统边界处理，不要伪装成普通网页跳转。

扩展菜单保持分层：

- 主菜单中的 Extensions 是可展开入口。
- 点击扩展条目执行其 action 或打开 popup。
- 条目设置按钮和 `Manage extensions` 才进入管理页。
- 不要把“运行扩展”和“管理扩展”合并成同一个跳转。

## 标签页约束

- 每个标签拥有独立的 `BrowserTabRuntime` / `GeckoSession`；不能复用同一 session。
- 所有标签和 WebApp 共享 `GeckoRuntimeProvider` 的单例 runtime/profile，以共享 Cookie 和登录态。
- 标签持久化包含 session state、缩略图、选中项和 opener 关系；非当前标签保持惰性恢复。
- 新窗口/新标签请求必须尊重 `openNewTabsInCurrentTab`：
  - 关闭时创建带 opener 的新标签并切换过去。
  - 开启时在当前标签加载。
- “后台打开”创建带 opener 的标签但不切换；不要硬编码成总是新建或总是复用当前标签。
- 子标签关闭或返回时保留来源标签的页面状态、滚动位置和焦点语义。

标签面板：

- Card 模式是双列卡片并显示真实页面缩略图；List 模式是紧凑列表，不显示大缩略图。
- 模式图标表达 Card/List，不用标签数量冒充模式。
- GeckoView 缩略图使用 `capturePixels()`；失败时保留旧图或使用文字 fallback，不能用空白图覆盖。
- 缩略图顶端对齐，避免居中裁剪到页面空白区域。
- 滑动关闭是可撤销的暂存关闭，撤销时恢复原 tab/session/state/index；明确点击关闭按钮可以立即关闭。

## 内置 WebExtension、Bridge 与阅读模式

`internal-pages/` 是源码，`app/src/main/assets/` 是 Vite 生成产物：

```bash
pnpm --dir internal-pages typecheck
pnpm --dir internal-pages build
```

- 不要手改 `app/src/main/assets/**`；修改 `internal-pages/` 后重新构建。
- 首页通过 `window.hyperBrowser` 调用内置 WebExtension background，再由 native messaging 进入 Kotlin。
- 内置页面命令和普通网页内容脚本消息使用独立 allowlist，并校验 sender。
- 普通 `http(s)` 页面和第三方扩展页不能调用内置页面的特权命令。
- 页面路由与动作命令保持分离；不要恢复 `hyper://api/...`、`hyper://command/...` 或用 URL/hash reload 传业务数据。
- Native bridge 返回复杂结果时优先返回 JSON string，避免 GeckoView 嵌套 JSON 回调序列化问题。
- 书签、WebApp、launcher 布局写入必须经过 background 统一命令，不能由页面或 Kotlin handler 绕过同步层直接改业务 JSON。

阅读模式：

- `internal-pages/src/reader-content.ts` 必须保持独立 IIFE 产物，避免顶层声明污染网页全局作用域。
- 阅读视图使用封闭 Shadow DOM；不要把特权 bridge 注入任意网页。
- 阅读模式的期望状态和 ready/state 消息属于具体 session，导航后按当前页面可读性恢复，不能跨标签串状态。
- 普通链接语义应保持；不要为了阅读模式统一拦截所有锚点。

## GeckoView 与扩展

- `GeckoRuntimeProvider` 必须保持全局单例。
- XPI 下载可在 `Dispatchers.IO`；`webExtensionController.install/list/enable/disable/uninstall` 必须在 `Dispatchers.Main.immediate`。
- 安装权限由 runtime 的 `promptDelegate` 处理，并保留超时与失败结果；不能让 UI 永久停在 Installing。
- GeckoView 或 Android API 不支持的扩展能力必须明确失败，不能返回伪成功。
- 扩展的 action/popup、tabs、新窗口、下载和通知都应接入浏览器现有会话与系统能力，不建立只在管理页里生效的平行状态。
- Popup 内打开新页或选项页时，同时处理扩展 tab delegate 和 popup session 的 `NavigationDelegate.onNewSession`，新标签建立后关闭 popup。
- Popup 覆盖层不能遮挡其 GeckoView 的可交互区域。
- GeckoView 和 Android UI 回调的线程约束不可放宽；需要主线程的回调必须切回主线程。

调试 debug 包中的网页或扩展 popup 时，优先用桌面 Firefox `about:debugging` 检查 DOM、Console、Network 和窗口行为。`uiautomator` 只适合外层 Compose 层级和最终验收，不用于猜测 GeckoView 内部事件。

## 数据、同步与系统能力

- 用户数据继续通过仓库写入 app 私有 JSON；持久化写入使用 `AtomicFileWriter` 或等价的原子替换，不直接覆盖目标文件。
- 不要绕过 `BrowserProfileStore`、`DownloadStore`、`WebAppRepository`、`ExtensionRepository` 等仓库写入临时平行状态。
- WebDAV 同步的业务文件仅为 `bookmarks.json`、`webapps.json`、`launcher.json`。
- `shared/sync/src/sync-json-types.ts` 是同步 JSON 类型来源。
- 远端 `manifest.json` 只是摘要和调试索引，不是合并源。
- 合并、新旧判断、墓碑和自动同步调度放在 `shared/sync` 与 background；Android native 只提供系统能力和本地文件适配。
- 旧数据迁移做成独立迁移，不在同步热路径持续叠加 fallback。
- Gecko、扩展和 App 更新产生的下载复用 `DownloadStore` 与下载服务，不创建互不一致的 UI-only 队列。
- 媒体播放状态按标签/WebApp owner 隔离；不要恢复“任一页面播放就全局暂停其他页面”的隐式策略。

## 验证

按修改范围执行最小但真实的验证：

```bash
# Kotlin / Android
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain

# 内置首页、bridge、内容脚本或阅读模式
pnpm --dir internal-pages typecheck
pnpm --dir internal-pages build

# shared/sync
pnpm test:sync
```

- 扩展、下载、通知或 Android 系统集成改动，再运行 `./gradlew :app:lintDebug --console=plain`。
- UI 或真实设备行为改动完成构建、安装和启动后，Agent 可以直接使用 adb、`uiautomator` 和截图做真实设备验收，不需要等待用户再次授权。
- 自动验收至少核对 launcher、目标页面、菜单/面板、返回行为和最终持久化状态；坐标操作只在缺少稳定语义入口时使用，并在操作后重新确认页面状态。
- 不要只凭构建成功或中间按钮文案判断功能成功。
- 纯文档修改检查 `git diff --check`、文件路径和链接即可，不要求运行完整 Android 构建。

## 发布

- 发布元数据以 `app/build.gradle.kts` 的 `versionCode`/`versionName` 和 `CHANGELOG.md` 为准。
- tag 必须与 `versionName` 匹配。
- 带 `-` 的版本是 prerelease，只发布 GitHub prerelease，不更新 `update/stable.json`。
- 稳定版发布才更新 `update/stable.json`；当前不增加 beta 更新通道。
- `versionCode` 必须单调递增，包括 prerelease 升级到后续稳定版。
- 正式与预发布都必须使用同一持久化签名密钥；签名 secrets 缺失时停止发布，不能退回 debug 或临时签名。
- 具体发布步骤见 `docs/RELEASE.md`。

## 交付与 Git

- 保持修改聚焦，不重构无关文件，不回滚用户或其他 agent 的现有改动。
- 不提交 `.gradle/`、`.kotlin/`、`.playwright-cli/`、`app/build/`、`local.properties`、Android Studio 状态或临时截图。
- README 正式使用的产品截图可以放入 `docs/images/readme/`；仅用于本地验收的截图不要提交。
- 交付前检查 `git status --short` 和相关 diff。
- 用户没有明确要求时，不提交、不 push、不创建 tag 或 release。
