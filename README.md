# Hyper Browser

Hyper Browser 是一个 Android 原生浏览器和 WebApp 容器项目，使用 Kotlin、Jetpack Compose、Material3 和 GeckoView 构建。

它不是普通 Demo 浏览器。当前目标是做一个接近 Chrome Android 基础交互的浏览器壳：能打开普通网页、管理多标签、保存书签和历史、处理下载、把任意 URL 安装成类 App 容器，并支持 GeckoView WebExtension。

## 当前能力

- GeckoView 浏览器内核，普通浏览器标签和 WebApp 默认共享同一个 `GeckoRuntime` / profile。
- Chrome Android 风格主界面：主页、圆角地址栏、标签计数、三点菜单、搜索/地址输入页。
- 多标签管理，标签页支持 Card / List 两种模式；Card 模式使用 GeckoView 页面截图缩略图。
- 移动端链接打开策略：普通点击拦截 `target=_blank` 新窗口请求并在当前标签页直接跳转；长按链接时再提供“在新标签页打开”。
- 内置页面：主页、搜索、设置、WebApp 管理、书签、历史。
- 书签、历史、设置、下载记录、favicon 缓存和 WebApp 定义使用 App 私有目录 JSON/文件保存。
- 当前页面可安装为 WebApp，并支持创建 Android pinned shortcut。
- 独立 `WebAppActivity`，用于类 App 方式启动已安装网页。
- Android AMO 扩展搜索、XPI 安装、启用/禁用、卸载、扩展菜单 action 和 popup。
- 内置 WebExtension bridge，让内置 React 页面通过 `window.hyperBrowser` 调用 Kotlin 浏览器壳能力。
- GeckoView 媒体会话通知，支持从系统通知执行播放、暂停、前后跳转等媒体动作。
- 下载处理和下载列表页。
- 下拉刷新会结合 GeckoView 滚动状态和页面内部滚动容器状态，避免内部容器未到顶部时误触发刷新。

## 技术栈

- Android：Kotlin
- UI：Jetpack Compose / Material3
- 浏览器内核：Mozilla GeckoView `151.0.20260525130955`
- 构建：Gradle Wrapper
- Java：17
- Android SDK：`compileSdk 36` / `targetSdk 36` / `minSdk 26`
- 包名：`com.dadigua.hyperbrowser`
- 内置页面：React 19 + TypeScript + Vite
- JS 包管理：pnpm

## 目录结构

```text
.
├── app/
│   └── src/main/
│       ├── assets/
│       │   ├── apps.html
│       │   ├── bookmarks.html
│       │   ├── history.html
│       │   ├── home.html
│       │   ├── search.html
│       │   ├── settings.html
│       │   └── internal/
│       └── java/com/dadigua/hyperbrowser/
│           ├── HyperBrowserApp.kt
│           ├── browser/
│           ├── data/
│           ├── extensions/
│           ├── gecko/
│           ├── ui/browser/
│           ├── ui/webapp/
│           ├── ui/theme/
│           └── webapp/
├── internal-pages/
│   ├── public/
│   │   ├── background.js
│   │   ├── manifest.json
│   │   └── pull-refresh-content.js
│   ├── src/
│   │   ├── hyper-browser.ts
│   │   └── pages/
│   └── vite.config.ts
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew.bat
```

## 关键模块

- `HyperBrowserApp.kt`  
  全局 Application 入口，持有 `webApps` 和 `extensions` 仓库。
- `ui/browser/BrowserActivity.kt`  
  主浏览器 Activity，也是 launcher 入口；包含 toolbar、菜单、搜索页、标签页、下载页、书签/历史页、扩展页和内置页命令分发。
- `ui/webapp/WebAppActivity.kt`  
  独立 WebApp Activity，用于启动已安装的网页 App。
- `gecko/GeckoRuntimeProvider.kt`  
  全局单例 `GeckoRuntime` 配置，启用 remote debugging，并设置 WebExtension 安装/更新权限 prompt 策略。
- `gecko/GeckoSessionController.kt`  
  单个 tab/WebApp 的 `GeckoSession` 封装，负责导航、内置路由、崩溃恢复、下载请求、媒体 session、bridge 注册、下拉刷新状态，以及把 `target=_blank` / 新窗口请求收敛为当前标签页跳转。
- `gecko/GeckoBrowserView.kt`  
  Compose `AndroidView` 中的 GeckoView 包装和下拉刷新手势处理。
- `gecko/HyperBridge.kt`  
  内置 WebExtension native-message bridge，只接受可信内置页面 sender，普通网页只允许上报下拉刷新触摸状态。
- `extensions/ExtensionRepository.kt`  
  AMO 搜索、XPI 下载和安装、扩展启停/卸载、菜单 action、popup session 和扩展新标签请求。
- `browser/BrowserProfileStore.kt`  
  保存书签、历史和浏览器设置。
- `browser/DownloadHandler.kt` / `browser/DownloadStore.kt`  
  处理 GeckoView 下载请求、系统下载状态同步和下载记录。
- `browser/FaviconRepository.kt`  
  favicon 下载、缓存和 data URL 输出。
- `browser/BrowserMediaNotificationController.kt`  
  GeckoView 媒体会话到 Android 媒体通知的桥接。
- `webapp/WebAppRepository.kt`  
  WebApp 定义、图标解析和 pinned shortcut 集成。

## 环境要求

- Windows PowerShell。
- JDK 17。
- Android SDK，并且有可通过 `adb` 访问的真机或模拟器。
- pnpm，用于构建内置页面。

项目优先使用 Gradle Wrapper，不依赖全局 Gradle。

## 构建

构建 debug APK：

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

Android `preBuild` 会自动执行 `internal-pages` 的 pnpm install/build，并把产物输出到 `app/src/main/assets/`。

只构建内置页面：

```powershell
pnpm --dir internal-pages build
```

只检查内置页面 TypeScript 类型：

```powershell
pnpm --dir internal-pages typecheck
```

## 安装和启动

安装 debug APK：

```powershell
.\gradlew.bat :app:installDebug --console=plain
```

启动 App：

```powershell
adb shell monkey -p com.dadigua.hyperbrowser 1
```

启动浏览器 Activity 并打开指定 URL：

```powershell
adb shell am force-stop com.dadigua.hyperbrowser
adb shell am start -n com.dadigua.hyperbrowser/.ui.browser.BrowserActivity --es extra_url https://example.com
```

确认 launcher 入口：

```powershell
adb shell cmd package resolve-activity --brief com.dadigua.hyperbrowser
```

期望结果：

```text
com.dadigua.hyperbrowser/.ui.browser.BrowserActivity
```

## 内置页面

内置页面源码在 `internal-pages/`，构建产物输出到 `app/src/main/assets/`。Vite 当前会打包 6 个入口：

- `home.html`
- `search.html`
- `settings.html`
- `apps.html`
- `bookmarks.html`
- `history.html`

语义路由：

- `hyper://home`
- `hyper://search`
- `hyper://settings`
- `hyper://apps`
- `hyper://bookmarks`
- `hyper://history`

运行时页面通过内置 WebExtension 的 `moz-extension://...` base URL 加载，地址栏和历史展示仍映射为用户可读的 `hyper://...` URL。

不要手改这些生成文件：

```text
app/src/main/assets/*.html
app/src/main/assets/internal/
app/src/main/assets/background.js
app/src/main/assets/manifest.json
app/src/main/assets/pull-refresh-content.js
```

需要改内置页面时，修改 `internal-pages/` 源码，然后运行：

```powershell
pnpm --dir internal-pages build
```

## 内置 WebExtension Bridge

内置 bridge 由 `internal-pages/public/` 下的 built-in WebExtension 提供。

通信链路：

```text
内置页面 JS
  -> window.hyperBrowser
  -> browser.runtime.sendMessage(...)
  -> background.js
  -> browser.runtime.sendNativeMessage("hyperBrowser", ...)
  -> Kotlin HyperBridge
  -> BrowserActivity / GeckoSessionController
```

内置页面通过页面生命周期主动请求数据：

- `requestHomeData()`
- `requestBookmarksData()`
- `requestHistoryData()`

安全规则：

- 内置 WebExtension 页面可以发送浏览器命令，也可以请求书签、历史、设置、WebApp 等数据。
- 普通网页、第三方扩展页、`http` / `https` 页面不能调用特权浏览器命令。
- 普通网页只允许发送 `pullRefresh.touch`，用于辅助判断页面内部滚动容器是否已经到顶部。
- Kotlin bridge 返回给 WebExtension 的值使用 JSON string，避免 GeckoView 回调序列化问题。

## 扩展

扩展能力集中在 `ExtensionRepository.kt` 和 `BrowserActivity.kt`：

- 从 Android AMO catalog 搜索扩展。
- 下载并安装 XPI。
- 启用、禁用和卸载已安装扩展。
- 主菜单中的 `Extensions` 行可展开，已启用扩展作为子项显示。
- 点击扩展条目本身触发 WebExtension action/popup。
- 条目右侧 `Settings` 和底部 `Manage extensions` 才进入扩展管理。
- popup 内部打开 options/control panel 时，会在浏览器中新建 tab 打开对应 `moz-extension://...` 页面。

GeckoView WebExtension 管理 API 对线程要求严格，需要 Handler 的调用应切到 `Dispatchers.Main.immediate`。

## 链接和新标签策略

作为手机浏览器，Hyper Browser 默认不让网页的 `target=_blank` 自动打散用户当前上下文。普通点击链接时，如果页面尝试打开新窗口或新标签，`GeckoSessionController.onNewSession(...)` 会拦截请求，并把目标 URL 直接加载到当前标签页。

需要新标签时使用移动端更明确的操作：长按链接打开链接菜单，再选择“在新标签页打开”。这能减少网页随意弹出新标签带来的跳转混乱，同时保留用户主动多标签浏览的能力。

## 数据存储

当前版本没有使用 Room/DataStore，而是使用 App 私有目录下的 JSON 文件和 favicon 目录：

- `browser_history.json`
- `browser_bookmarks.json`
- `browser_settings.json`
- `browser_downloads.json`
- `web_apps.json`
- `installed_extensions.json`
- `favicons/`

调试已安装扩展状态时可读取：

```powershell
adb shell run-as com.dadigua.hyperbrowser cat files/installed_extensions.json
```

## 开发注意事项

- JS 包管理优先使用 `pnpm`，不要默认用 `npm`。
- Android 构建优先使用 Gradle Wrapper。
- 原生 App 代码是 Kotlin + Compose。
- 内置页面是 React + TypeScript。
- `GeckoRuntimeProvider` 必须保持单例，这样普通浏览器标签和 WebApp 才能共享 Cookie/profile。
- 修浏览器交互时不要做大范围无关重构，核心逻辑通常集中在 `BrowserActivity.kt`、`GeckoSessionController.kt`、`GeckoBrowserView.kt` 和 `ExtensionRepository.kt`。
- 调试 GeckoView 内容、扩展 popup 或内置页 DOM 时，优先使用 Firefox `about:debugging` 连接 Android 设备；`uiautomator` 更适合确认 Compose 外层文本和 bounds。

## 常用命令

```powershell
# 构建 debug APK
.\gradlew.bat :app:assembleDebug --console=plain

# 安装 debug APK
.\gradlew.bat :app:installDebug --console=plain

# 构建内置页面
pnpm --dir internal-pages build

# 检查内置页面类型
pnpm --dir internal-pages typecheck

# 启动 App
adb shell monkey -p com.dadigua.hyperbrowser 1

# 打开指定 URL
adb shell am start -n com.dadigua.hyperbrowser/.ui.browser.BrowserActivity --es extra_url https://example.com

# 检查当前前台窗口
adb shell dumpsys window | Select-String -Pattern "mCurrentFocus|mFocusedApp"
```

## 手工 Smoke Test

修改浏览器主交互后，建议手工检查：

1. 构建并安装 debug APK。
2. 启动 App。
3. 打开 `https://example.com`。
4. 点击地址栏，确认能进入搜索/地址输入页。
5. 打开三点菜单，确认导航、下载、扩展、书签、历史、设置入口可用。
6. 展开三点菜单里的 `Extensions`，确认已启用扩展作为子项显示。
7. 打开标签页，确认 Card/List 模式可切换。
8. 打开 `hyper://home`、`hyper://bookmarks`、`hyper://history`、`hyper://apps`、`hyper://settings`，确认内置页可以加载和返回。
9. 点击带 `target=_blank` 的链接，确认不会自动新开标签，而是在当前标签页跳转。
10. 长按普通链接，确认菜单里可以选择“在新标签页打开”。
11. 在普通页面测试返回键：搜索页、书签页、历史页、扩展页、标签页应先关闭面板；普通网页页应优先执行网页后退。
12. 在普通 body 滚动页面和内部滚动容器页面测试下拉刷新，内部容器未到顶部时不应触发浏览器刷新。

## License

当前还没有声明 License。
