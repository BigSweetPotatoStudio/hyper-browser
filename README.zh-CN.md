# Hyper Browser

Hyper Browser 是一个 Android 原生浏览器和 WebApp 容器项目，使用 Kotlin、Jetpack Compose、Material3 和 GeckoView 构建。

这个项目不是普通 Demo 浏览器。它的目标是做一个接近 Chrome Android 基础交互的浏览器壳：可以打开普通网页、管理多标签、把任意 URL 安装成类 App 容器，并支持 GeckoView WebExtension。

## 功能

- 基于 GeckoView 的浏览器内核，普通浏览器和 WebApp 共享 runtime/profile。
- Chrome Android 风格浏览器界面：地址栏、标签数量、主页、菜单、搜索/地址输入页、标签页。
- 多标签管理，标签页支持 Card 和 List 两种模式。
- 书签、历史、下载、favicon 缓存和浏览器设置。
- 内置页面：主页、搜索、设置、书签、历史、WebApp 管理。
- 将当前页面安装成 WebApp，并支持创建桌面 pinned shortcut。
- 独立 WebApp Activity，用于类 App 方式启动网页。
- AMO 扩展搜索、XPI 安装、启用/禁用、卸载、扩展菜单 action 和 popup。
- 内置 WebExtension bridge，用于内置页面和 Kotlin 浏览器壳通信。
- 下拉刷新支持内部滚动容器判断，避免页面内部容器还能滚动时误触发浏览器刷新。

## 技术栈

- Android 原生：Kotlin
- UI：Jetpack Compose / Material3
- 浏览器内核：Mozilla GeckoView
- 构建：Gradle Wrapper
- Java：17
- 包名：`com.dadigua.hyperbrowser`
- 内置页面：React + TypeScript + Vite
- JS 包管理：pnpm

## 目录结构

```text
.
├── app/
│   └── src/main/
│       ├── java/com/dadigua/hyperbrowser/
│       │   ├── HyperBrowserApp.kt
│       │   ├── browser/
│       │   ├── extensions/
│       │   ├── gecko/
│       │   ├── ui/browser/
│       │   ├── ui/webapp/
│       │   └── webapp/
│       └── assets/
│           ├── home.html
│           ├── search.html
│           ├── settings.html
│           ├── bookmarks.html
│           ├── history.html
│           ├── apps.html
│           └── internal/
├── internal-pages/
│   ├── public/
│   │   ├── manifest.json
│   │   ├── background.js
│   │   └── pull-refresh-content.js
│   └── src/
│       ├── hyper-browser.ts
│       └── pages/
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew.bat
```

关键原生文件：

- `app/src/main/java/com/dadigua/hyperbrowser/ui/browser/BrowserActivity.kt`  
  主浏览器 Activity，包含 toolbar、菜单、标签页、书签/历史/下载/扩展页面和主界面组合逻辑。
- `app/src/main/java/com/dadigua/hyperbrowser/ui/webapp/WebAppActivity.kt`  
  独立 WebApp Activity。
- `app/src/main/java/com/dadigua/hyperbrowser/gecko/GeckoRuntimeProvider.kt`  
  全局单例 GeckoRuntime 配置。
- `app/src/main/java/com/dadigua/hyperbrowser/gecko/GeckoSessionController.kt`  
  单个标签页的 GeckoSession 控制器，负责导航、内置路由、崩溃恢复、bridge 分发和下拉刷新状态。
- `app/src/main/java/com/dadigua/hyperbrowser/gecko/GeckoBrowserView.kt`  
  Compose/AndroidView 中的 GeckoView 包装和下拉刷新手势处理。
- `app/src/main/java/com/dadigua/hyperbrowser/gecko/HyperBridge.kt`  
  内置 WebExtension native-message bridge。
- `app/src/main/java/com/dadigua/hyperbrowser/extensions/ExtensionRepository.kt`  
  AMO 搜索、WebExtension 安装/控制、菜单 action、popup 和扩展新标签请求。
- `app/src/main/java/com/dadigua/hyperbrowser/browser/BrowserProfileStore.kt`  
  JSON 形式保存书签、历史和浏览器设置。
- `app/src/main/java/com/dadigua/hyperbrowser/webapp/WebAppRepository.kt`  
  WebApp 定义、图标和 pinned shortcut 集成。

## 环境要求

- Windows PowerShell，或其他能运行 Gradle Wrapper 的 shell。
- JDK 17。
- Android SDK，并且有可通过 `adb` 访问的真机或模拟器。
- pnpm，用于构建内置页面。

项目优先使用 Gradle Wrapper，不依赖全局 Gradle。

## 构建

构建 debug APK：

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

Android 构建会在打包前自动构建 `internal-pages`，并把产物输出到 `app/src/main/assets/`。

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

内置页面源码在 `internal-pages/`，构建产物输出到 `app/src/main/assets/`。

语义路由：

- `hyper://home`
- `hyper://search`
- `hyper://settings`
- `hyper://apps`
- `hyper://bookmarks`
- `hyper://history`

运行时页面实际通过内置 WebExtension 的 base URL 加载，但地址栏会映射回用户可读的 `hyper://...` URL。

不要手改这些生成文件：

```text
app/src/main/assets/home.html
app/src/main/assets/search.html
app/src/main/assets/settings.html
app/src/main/assets/bookmarks.html
app/src/main/assets/history.html
app/src/main/assets/apps.html
app/src/main/assets/internal/
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

安全规则：

- 内置页面可以发送浏览器命令，也可以请求书签、历史、设置等数据。
- 普通网页不能调用特权浏览器命令。
- 普通 `http`/`https` 页面只允许发送下拉刷新的触摸滚动状态，用来避免内部滚动容器场景误刷新。

## 下拉刷新逻辑

下拉刷新实现位于 `GeckoBrowserView.kt`。

只有同时满足这些条件才会启动刷新：

- 当前页面不是内部 `hyper://...` 页面。
- 手势从内容区域上方附近开始。
- 手势主要是向下的纵向移动。
- `GeckoView` 自己已经不能继续向上滚动。
- 当前触摸点所在的 DOM 内部滚动容器也不能继续向上滚动。

DOM 内部滚动容器状态由 `internal-pages/public/pull-refresh-content.js` 上报。它会根据触摸点调用 `elementFromPoint`，向上查找可滚动父容器，并检查 `scrollTop`。这样可以处理 `body` 不滚、内部容器滚动的网页结构。

## 数据存储

当前版本没有使用 Room/DataStore，而是使用 App 私有目录下的 JSON 文件：

- `browser_history.json`
- `browser_bookmarks.json`
- `browser_settings.json`
- `downloads.json`
- `web_apps.json`
- `installed_extensions.json`

## 开发注意事项

- JS 包管理优先使用 `pnpm`，不要默认用 `npm`。
- Android 构建优先使用 Gradle Wrapper。
- 原生 App 代码是 Kotlin + Compose。
- 内置页面是 React + TypeScript。
- GeckoView 对线程要求严格，需要 Handler 的 API 应在主线程调用。
- `GeckoRuntimeProvider` 必须保持单例，这样普通浏览器标签和 WebApp 才能共享 Cookie/profile。
- 修浏览器交互时不要做大范围 UI 重构，核心逻辑通常集中在 `BrowserActivity.kt`、`GeckoSessionController.kt` 和 `GeckoBrowserView.kt`。

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
6. 打开标签页，确认 Card/List 模式可切换。
7. 在普通 `body` 滚动页面测试下拉刷新。
8. 在内部滚动容器页面测试下拉刷新；内部容器没到顶部时不应触发刷新，到顶部后才允许触发刷新。

## License

当前还没有声明 License。
