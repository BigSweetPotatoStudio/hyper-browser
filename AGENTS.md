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

## 设计方向

当前 UI 方向优先参考 Chrome Android，而不是 Firefox Android：

- 顶部栏：Home、圆角地址栏、标签计数、三点菜单
- 前进、后退、刷新、书签、历史、扩展、安装 WebApp 放进菜单
- 点击地址栏进入独立 omnibox 输入页
- 标签页使用 Chrome 风格两列卡片和顶部模式切换胶囊

不要把所有浏览器操作按钮堆在顶部工具栏。

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

安装状态验证不要只看按钮文案。可以读取 app 私有 JSON：

```powershell
adb shell run-as com.dadigua.hyperbrowser cat files/installed_extensions.json
```

也要打开三点菜单确认扩展入口显示 enabled 数量，并展开看到扩展条目。

扩展 popup 验证不能只看到菜单条目。点击 uBlock 这类扩展后，`uiautomator` 应能看到 popup WebView 内容，例如：

- `uBlock Origin - Example Domain`
- `在此页面已拦截`
- `已连接的域名`
- `Close`

## 数据存储

首版不用 Room，使用 app 私有目录 JSON 文件：

- `browser_history.json`
- `browser_bookmarks.json`
- `web_apps.json`
- `installed_extensions.json`

保持结构简单，后续需要迁移再引入 Room/DataStore。

## 验证重点

每次改浏览器主交互后至少验证：

- `.\gradlew.bat :app:assembleDebug --console=plain`
- `.\gradlew.bat :app:installDebug --console=plain`
- launcher 解析到 `.ui.browser.BrowserActivity`
- 打开 `https://example.com`
- 地址栏点击进入搜索页
- 三点菜单能打开
- 三点菜单里的 `Extensions` 行能展开
- 已安装插件能出现在菜单扩展子项里
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

菜单 UI 可用 `uiautomator` 快速验证文本和 bounds：

```powershell
adb shell input tap 1320 300
adb exec-out uiautomator dump /dev/tty
```

如果当前在标签页面板，先按返回回到浏览器正文，再点三点菜单。
