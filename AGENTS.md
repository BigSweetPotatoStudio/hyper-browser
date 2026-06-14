# Hyper Browser 开发约定

## 项目定位

这是一个 Android 原生浏览器/WebApp 容器项目：

- 语言：Kotlin
- UI：Jetpack Compose / Material3
- 内核：GeckoView AAR
- 构建：Gradle Wrapper
- Java：17
- 包名：`com.dadigua.hyperbrowser`

核心目标不是普通 Demo 浏览器，而是一个支持任意 URL 安装成类 App 的 Android 浏览器容器，并逐步靠近 Chrome Android 的基础交互。

## 环境和命令

当前开发环境在 Windows 侧，默认 shell 是 PowerShell。不要混用 bash/zsh 语法。

常用命令：

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

本项目优先使用 Gradle Wrapper，不要依赖全局 Gradle。需要反复安装调试时可以直接使用：

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:installDebug --console=plain
```

默认交付方式：

- 修改代码后，默认只执行构建、安装 App、启动 App，并给出人工测试步骤
- 不要默认替用户执行 adb 点击、截图、uiautomator dump、坐标操作或完整自动化验收
- 只有当用户明确说“帮我自动测试”“帮我截图验证”“用 adb 验证”“你来点一下看看”等要求时，才执行自动化测试和截图验证
- 如果问题必须依赖真实设备状态才能定位，可以先说明需要自动测试的原因，再等待用户明确授权，除非用户当前请求本身已经要求调试真实设备行为

启动浏览器 Activity：

```powershell
adb shell am force-stop com.dadigua.hyperbrowser
adb shell am start -n com.dadigua.hyperbrowser/.ui.browser.BrowserActivity --es extra_url https://example.com
```

启动主入口：

```powershell
adb shell monkey -p com.dadigua.hyperbrowser 1
```

手动截图验证命令：

```powershell
adb shell screencap -p /sdcard/hyper.png
adb pull /sdcard/hyper.png screenshots\hyper.png
```

如果需要确认当前是不是本 App：

```powershell
adb shell dumpsys window | Select-String -Pattern "mCurrentFocus|mFocusedApp"
```

如果怀疑安装后进入了旧页面，先确认 launcher 解析结果：

```powershell
adb shell cmd package resolve-activity --brief com.dadigua.hyperbrowser
```

当前 launcher 必须是：

```text
com.dadigua.hyperbrowser/.ui.browser.BrowserActivity
```

## 代码结构

主要文件：

- `app/src/main/java/com/dadigua/hyperbrowser/HyperBrowserApp.kt`
  - 全局仓库入口：`webApps`、`extensions`
- `app/src/main/java/com/dadigua/hyperbrowser/ui/browser/BrowserActivity.kt`
  - 普通浏览器页面
  - 当前 launcher 入口
  - Chrome 风格顶部栏
  - 搜索/地址输入页
  - 标签页
  - 书签页
  - 历史记录页
  - 扩展管理页
- `app/src/main/java/com/dadigua/hyperbrowser/ui/webapp/WebAppActivity.kt`
  - WebApp 独立任务模式
- `app/src/main/java/com/dadigua/hyperbrowser/gecko/GeckoRuntimeProvider.kt`
  - 全局单例 `GeckoRuntime`
- `app/src/main/java/com/dadigua/hyperbrowser/gecko/GeckoSessionController.kt`
  - 单个 tab/WebApp 的 `GeckoSession` 封装
- `app/src/main/java/com/dadigua/hyperbrowser/browser/BrowserProfileStore.kt`
  - JSON 文件保存书签和历史
- `app/src/main/java/com/dadigua/hyperbrowser/webapp/WebAppRepository.kt`
  - WebApp 定义和 pinned shortcut
- `app/src/main/java/com/dadigua/hyperbrowser/extensions/ExtensionRepository.kt`
  - AMO 搜索、XPI 下载、GeckoView WebExtension 安装/启停/卸载
- `app/src/main/assets/`
  - Vite 生成的内置页面产物：`home.html`、`bookmarks.html`、`history.html`、`internal/`
- `internal-pages/`
  - 内置页面源码：React + TypeScript/TSX、Vite、WebExtension bridge 包装

## 设计方向

当前 UI 方向优先参考 Chrome Android，而不是 Firefox Android：

- 顶部栏：Home、圆角地址栏、标签计数、三点菜单
- 前进、后退、刷新、书签、历史、扩展、安装 WebApp 放进菜单
- 点击地址栏进入独立 omnibox 输入页
- 标签页使用 Chrome 风格顶部模式切换胶囊，支持 Card/List 两种查看方式

不要把所有浏览器操作按钮堆在顶部工具栏。

## 标签页页面约定

标签页面板顶部按钮语义必须清晰：

- 左侧按钮是返回/关闭标签页面板，不是新建标签
- 右侧按钮是新建标签
- 中间模式切换：
  - Card 模式图标使用卡片语义，不要用标签数量当模式图标
  - List 模式图标使用列表语义

Card/List 的展示行为必须分开：

- Card 模式：使用双列卡片布局，卡片内部显示网页截图缩略图
- List 模式：使用紧凑列表行，只显示 favicon/标题/URL/关闭按钮，不显示大卡片
- 标签数量仍显示在普通浏览器顶部栏的 tab count 入口里，不要混进 Card/List 模式图标

网页截图缩略图必须来自 GeckoView 真实页面内容：

- 进入标签页面板前，先等待当前 `GeckoView.capturePixels()` 回调，再切换到标签页 UI
- 不要只用 `View.drawToBitmap()` 作为 GeckoView 页面截图来源；GeckoView 内容由 compositor/surface 渲染，普通 View 截图容易是空白
- `capturePixels()` 失败时可以保留旧缩略图或显示 title/URL fallback，但不要用空白截图覆盖已有缩略图
- Card 缩略图要顶端对齐显示页面内容，避免居中裁剪后只看到页面空白区域

## 内置 HTML 页面与 HyperCommand

首页、书签页、历史页优先作为 GeckoView 内置页面开发，而不是 Compose 面板。源码使用 React + TypeScript/TSX，放在：

```text
internal-pages/
```

构建产物输出到：

```text
app/src/main/assets/
```

内置页源码修改后运行：

```powershell
pnpm --dir internal-pages build
```

Android 构建也会通过 Gradle 自动执行 `internal-pages` 的 pnpm install/build。

- `hyper://home` -> `resource://android/assets/home.html`
- `hyper://bookmarks` -> `resource://android/assets/bookmarks.html#data=...`
- `hyper://history` -> `resource://android/assets/history.html#data=...`

地址栏必须显示语义 URL，例如 `hyper://home`、`hyper://bookmarks`、`hyper://history`，不要向用户暴露 `resource://android/assets/...`。

内置页面中的浏览器操作通过 `window.hyperBrowser` 发出，bridge 源码统一放在：

```text
internal-pages/src/hyper-browser.ts
```

不要手改 `app/src/main/assets/home.html`、`bookmarks.html`、`history.html` 或生成的 `internal/` bundle；这些文件由 Vite 生成。

不要再使用 `hyper://api/...` 或 `hyper://command/...` 作为内置页面 API。内置页面必须作为内置 WebExtension 页面加载。页面 JS 只调用 `window.hyperBrowser`；包装层通过 `browser.runtime.sendMessage(...)` 发给 `background.js`，再由 `background.js` 调用 `browser.runtime.sendNativeMessage("hyperBrowser", ...)` 进入 Kotlin bridge。

Kotlin 侧要把页面路由和页面命令分开：

- `HyperRoute` 只表示可进入 Gecko 历史栈的内置页面：`Home`、`Bookmarks`、`History`
- `HyperCommand` 只表示内置页面发给浏览器壳的动作，并按功能域分组：`Search`、`Bookmarks`、`History`、`Panel`

`GeckoSessionController` 只负责安全校验和解析：

- `hyper://home`、`hyper://bookmarks`、`hyper://history` 解析为 `HyperRoute`
- 内置页面加载真实 URL 时使用内置 WebExtension 的 `moz-extension://.../home.html`、`bookmarks.html`、`history.html`
- 地址栏和历史语义仍映射为 `hyper://home`、`hyper://bookmarks`、`hyper://history`，不要把 `moz-extension://...` 暴露给用户
- bridge 消息必须校验 sender：只接受 URL 属于内置 WebExtension baseUrl 的 sender
- 普通网页、第三方扩展页、`https://`、`http://` 页面不能调用或伪造 `window.hyperBrowser` 的 native bridge
- Kotlin bridge 返回给 WebExtension 的值优先用 JSON string；不要直接返回嵌套 `JSONObject` / `JSONArray`，避免 GeckoView 回调序列化失败
- WebExtension 页面受 CSP 约束，不要写内联 `<script>`；页面逻辑放到 `internal-pages/src/pages/*.tsx`

内置 HTML 页面必须由页面生命周期主动请求数据，不要依赖外层 Compose 猜时机预塞数据：

- `home.tsx` 初次加载时，如果 URL hash 里没有 `data`，调用 `window.hyperBrowser.requestHomeData()`
- `bookmarks.tsx` 初次加载时，如果 URL hash 里没有 `data`，调用 `window.hyperBrowser.requestBookmarksData()`
- `history.tsx` 初次加载时，如果 URL hash 里没有 `data`，调用 `window.hyperBrowser.requestHistoryData()`
- `request*Data()` 必须返回 Promise，并由 bridge 直接返回 JSON 数据；不要通过 URL 跳转、hash 二次加载或 fallback 到旧协议取数据

移动端内置页要求：

- HTML 模板必须设置 `viewport`：`width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover`
- 表单控件字体不小于 16px，避免移动端聚焦时触发缩放
- 页面 CSS 应禁用横向溢出，使用 safe-area padding，并保持触控目标尺寸接近原生控件

书签和历史数据变化后不能通过重载页面刷新：

- 不要使用 `?v=<timestamp>` 这类 cache busting 刷新方案
- 书签删除、编辑，历史删除、清空，都应由 HTML 页面更新本地 JS state 和 DOM，保留滚动位置
- Kotlin 收到对应 bridge 消息后只更新 JSON/store，不要重新 `loadBookmarks()` 或 `loadHistory()`
- 如果需要跨页面实时同步，后续再设计专门通道；v1 只要求下次进入页面时读取最新 JSON

## Iceraven 菜单复刻要点

Iceraven/Fenix 的扩展不是普通跳转项，而是主菜单里的可展开入口：

- 主菜单中有 `ExtensionsMenuItem`
- 点击后展开扩展子菜单
- 浏览器页面有可用扩展时，子菜单列出当前页面可用/已启用的 WebExtension
- 点击扩展条目本身必须触发 WebExtension action/popup，例如 uBlock 要打开自己的 popup 页面
- 扩展条目右侧的 `Settings` 才进入扩展管理/设置入口
- 子菜单底部有 `Manage extensions`
- 如果还没有可管理扩展，则显示 `Discover more extensions`

本项目菜单应保持这个层级，而不是只放一个 `Extensions` 跳转。当前基础结构：

- 顶部导航行：Back / Forward / Reload
- 工具组：New tab / Bookmark this page / Install as WebApp
- 扩展组：Extensions 行显示 enabled/installed 状态，点击展开
- 展开后列出已启用扩展，例如 `uBlock Origin`
- 点击 `uBlock Origin` 应打开 uBlock popup，不要跳到扩展管理页
- 点击扩展条目右侧 `Settings` 或底部 `Manage extensions` 才进入管理页
- 展开底部提供 `Manage extensions`
- 浏览记录和书签放在后续 library 组里

复刻 Iceraven 时可以参考本地研究副本：

```text
D:\projects\iceraven-browser\app\src\main\java\org\mozilla\fenix\components\menu\compose\MainMenu.kt
```

重点看 `ExtensionsMenuItem`、`Addons`、`WebExtensionMenuItems`、`MoreExtensionsMenuItem`。

## GeckoView 注意事项

`GeckoRuntimeProvider` 必须保持单例。普通浏览器和 WebApp 默认共享同一个 runtime/profile，以共享 Cookie/登录态。

`GeckoSessionController` 当前每个 tab 一个 `GeckoSession`。多标签 UI 不要复用同一个 session，否则会互相抢页面。

WebExtension 管理 API 的线程要求很严格：

- XPI 下载可以在 `Dispatchers.IO`
- `webExtensionController.install(...)`
- `list()`
- `enable(...)`
- `disable(...)`
- `uninstall(...)`

这些 GeckoView 调用必须切到 `Dispatchers.Main.immediate`。否则会出现：

```text
Must be ran on a thread with a Handler!
```

不要把 GeckoView 扩展安装放进纯 IO `withContext(Dispatchers.IO)` 里。

WebExtension 安装还需要处理 GeckoView 权限提示。否则 XPI 下载完成后，UI 可能一直停在 `Installing...`：

- 在 `GeckoRuntimeProvider` 中设置 `webExtensionController.promptDelegate`
- install prompt 默认允许需要的 host/nativeMessaging 权限
- update/optional prompt 默认允许
- 安装过程要加超时保护，避免 UI 永久卡住

需要自动验证扩展安装状态时，不要只看按钮文案。可以读取 app 私有 JSON：

```powershell
adb shell run-as com.dadigua.hyperbrowser cat files/installed_extensions.json
```

如果用户明确要求自动验证，也要打开三点菜单确认扩展入口显示 enabled 数量，并展开看到扩展条目。

需要自动验证扩展 popup 时，不能只看到菜单条目。点击 uBlock 这类扩展后，`uiautomator` 应能看到 popup WebView 内容，例如：

- `uBlock Origin - Example Domain`
- `在此页面已拦截`
- `已连接的域名`
- `Close`

popup 容器高度必须覆盖 WebExtension 内容的可交互区域。不要让外层遮罩覆盖 GeckoView 的底部区域，否则会出现“能看到 popup 但点击内部按钮无效或直接关闭”的问题。

扩展 popup 内部打开控制面板/选项页时，行为要像浏览器扩展：在浏览器中新建 tab 打开 `moz-extension://.../dashboard.html#...` 这类页面，并关闭当前 popup。实现上不能只接 `WebExtension.TabDelegate.onNewTab`；popup 自己的 `GeckoSession` 也要接 `NavigationDelegate.onNewSession`，并在必要时拦截导航到 `optionsPageUrl` 的 `onLoadRequest`。

调试 GeckoView 内部页面或扩展 popup 时，不要长期靠 adb 坐标盲点。当前 `GeckoRuntimeProvider` 只会在 debuggable 构建中启用 Gecko remote debugging；调试 debug 包时优先用桌面 Firefox 打开 `about:debugging` 连接 Android 设备，检查目标页面/popup 的 DOM、Console、Network、click 事件和 `window.open` / `browser.tabs.create` 行为。`uiautomator` 适合确认外层 Compose 层级、文本和最终验收，不适合定位 GeckoView 内部事件是否触发。

## 数据存储

首版不用 Room，使用 app 私有目录 JSON 文件：

- `browser_history.json`
- `browser_bookmarks.json`
- `web_apps.json`
- `installed_extensions.json`

保持结构简单，后续需要迁移再引入 Room/DataStore。

## 发布与预发布

正式发布和预发布都走 `.github/workflows/android-release.yml`。发布元数据以两个文件为准：

- `app/build.gradle.kts`：维护 `versionCode` 和 `versionName`
- `CHANGELOG.md`：维护对应版本的用户可读发布说明

正式版本使用普通语义版本号，例如：

```kotlin
versionCode = 8
versionName = "0.1.6"
```

正式版本 tag 必须匹配 `versionName`：

```text
versionName "0.1.6" -> tag v0.1.6
```

正式版本发布后 CI 会生成 GitHub Release，并更新 `update/stable.json`。App 内检查更新只读取 `update/stable.json`，不会直接查询 GitHub Releases。

预发布版本只用于 GitHub Releases 页面手动下载安装测试，不作为 App 内稳定更新通道。预发布版本使用带 `-` 的 `versionName`，例如：

```kotlin
versionCode = 7
versionName = "0.1.6-beta.1"
```

对应 tag：

```text
v0.1.6-beta.1
```

CI 看到 `versionName` 包含 `-` 时，应发布为 GitHub prerelease，并跳过 `update/stable.json`。不要让预发布污染稳定更新索引。

如果测试用户手动安装了预发布，下一个正式版本必须使用更高的 `versionCode`，这样测试用户才能通过正常 Android 安装/更新路径升级到正式版：

```kotlin
// prerelease
versionCode = 7
versionName = "0.1.6-beta.1"

// next stable
versionCode = 8
versionName = "0.1.6"
```

当前不做 App 内预发布通道；不要新增 `beta.json` 或设置里的更新通道，除非用户之后明确要求。

## 验证和交付重点

每次改浏览器主交互后，默认完成：

- `.\gradlew.bat :app:assembleDebug --console=plain`
- `.\gradlew.bat :app:installDebug --console=plain`
- 启动 App 到可测试状态
- 给用户一份简洁人工测试清单，说明应该点哪里、看到什么结果

只有用户明确要求自动测试时，才继续执行以下 adb/uiautomator 验收：

- launcher 解析到 `.ui.browser.BrowserActivity`
- 打开 `https://example.com`
- 地址栏点击进入搜索页
- 三点菜单能打开
- 三点菜单里的 `Extensions` 行能展开
- 已安装插件能出现在菜单扩展子项里
- 系统返回键不会直接退桌面
- 标签页入口能打开
- 书签/历史/扩展入口能打开

人工测试时，返回键行为要求：

- 搜索页、书签页、历史页、扩展页、标签页：返回关闭当前面板
- 普通网页页：返回执行网页后退
- 不应一按返回直接退出到桌面，除非后续明确实现退出确认

## Git 和忽略文件

仓库已经初始化。不要提交以下内容：

- `.gradle/`
- `.kotlin/`
- `.playwright-cli/`
- `app/build/`
- `screenshots/`
- `local.properties`
- Android Studio 本地状态

截图只用于本地验证，默认不提交。除非用户明确要求自动验证，否则不要主动生成截图。

提交前建议运行：

```powershell
git status --short
.\gradlew.bat assembleDebug
```

## 编辑约束

- 手工改文件优先用 `apply_patch`
- 不要用 Python 写文件，除非是大规模机械处理且确有必要
- 不要随意重构无关文件
- 不要回滚用户或其他 agent 已做的改动
- UI 改动默认构建、安装、启动 App，并给用户人工测试步骤；不要默认用 adb 截图确认真实手机效果
- 用户明确要求自动测试或截图验证时，才使用 adb 截图、uiautomator、坐标点击等方式确认真实手机效果

菜单 UI 可用 `uiautomator` 快速验证文本和 bounds：

```powershell
adb shell input tap 1320 300
adb exec-out uiautomator dump /dev/tty
```

如果当前在标签页面板，先按返回回到浏览器正文，再点三点菜单。
