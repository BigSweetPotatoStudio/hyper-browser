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

启动浏览器 Activity：

```powershell
adb shell am force-stop com.dadigua.hyperbrowser
adb shell am start -n com.dadigua.hyperbrowser/.ui.browser.BrowserActivity --es extra_url https://example.com
```

启动主入口：

```powershell
adb shell monkey -p com.dadigua.hyperbrowser 1
```

截图验证：

```powershell
adb shell screencap -p /sdcard/hyper.png
adb pull /sdcard/hyper.png screenshots\hyper.png
```

如果需要确认当前是不是本 App：

```powershell
adb shell dumpsys window | Select-String -Pattern "mCurrentFocus|mFocusedApp"
```

## 代码结构

主要文件：

- `app/src/main/java/com/dadigua/hyperbrowser/HyperBrowserApp.kt`
  - 全局仓库入口：`webApps`、`extensions`
- `app/src/main/java/com/dadigua/hyperbrowser/MainActivity.kt`
  - 首页、WebApp 列表、扩展入口
- `app/src/main/java/com/dadigua/hyperbrowser/ui/browser/BrowserActivity.kt`
  - 普通浏览器页面
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

## 设计方向

当前 UI 方向优先参考 Chrome Android，而不是 Firefox Android：

- 顶部栏：Home、圆角地址栏、标签计数、三点菜单
- 前进、后退、刷新、书签、历史、扩展、安装 WebApp 放进菜单
- 点击地址栏进入独立 omnibox 输入页
- 标签页使用 Chrome 风格两列卡片和顶部模式切换胶囊

不要把所有浏览器操作按钮堆在顶部工具栏。

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

## 数据存储

首版不用 Room，使用 app 私有目录 JSON 文件：

- `browser_history.json`
- `browser_bookmarks.json`
- `web_apps.json`
- `installed_extensions.json`

保持结构简单，后续需要迁移再引入 Room/DataStore。

## 验证重点

每次改浏览器主交互后至少验证：

- `.\gradlew.bat assembleDebug`
- `adb install -r app\build\outputs\apk\debug\app-debug.apk`
- 打开 `https://example.com`
- 地址栏点击进入搜索页
- 三点菜单能打开
- 系统返回键不会直接退桌面
- 标签页入口能打开
- 书签/历史/扩展入口能打开

返回键行为要求：

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

截图只用于本地验证，默认不提交。

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
- UI 改动必须用 adb 截图确认真实手机效果，不能只说代码看起来对

