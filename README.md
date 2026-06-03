# Hyper Browser

Hyper Browser is an Android native browser and WebApp container built with Kotlin, Jetpack Compose, Material3, and GeckoView.

The project is not a simple browser demo. Its goal is to provide a Chrome-Android-like browser shell that can open regular web pages, manage tabs, install arbitrary URLs as app-like containers, and support GeckoView WebExtensions.

## Features

- GeckoView-based browsing with shared runtime/profile state.
- Chrome-style browser chrome with address bar, tab count, home, menu, search/omnibox page, and tab tray.
- Multiple tabs, including card and list tab-tray modes.
- Bookmarks, history, downloads, favicon caching, and browser settings.
- Built-in pages for home, search, settings, bookmarks, history, and installed WebApps.
- Install current page as a WebApp and create pinned shortcuts.
- WebApp activity for independent app-like launches.
- AMO add-on search, XPI install, enable/disable/uninstall, extension action menu, and popup support.
- Internal WebExtension bridge for built-in pages and browser commands.
- Pull-to-refresh support that avoids triggering while an inner page scroll container can still scroll upward.

## Tech Stack

- Android native app: Kotlin
- UI: Jetpack Compose / Material3
- Browser engine: Mozilla GeckoView
- Build: Gradle Wrapper
- Java target: 17
- Package: `com.dadigua.hyperbrowser`
- Built-in page source: React + TypeScript + Vite
- JavaScript package manager: pnpm

## Repository Layout

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

Key native files:

- `app/src/main/java/com/dadigua/hyperbrowser/ui/browser/BrowserActivity.kt`  
  Main browser activity, toolbar, menus, tab tray, library pages, extension page, and browser screen composition.
- `app/src/main/java/com/dadigua/hyperbrowser/ui/webapp/WebAppActivity.kt`  
  Dedicated WebApp activity.
- `app/src/main/java/com/dadigua/hyperbrowser/gecko/GeckoRuntimeProvider.kt`  
  Shared GeckoRuntime configuration.
- `app/src/main/java/com/dadigua/hyperbrowser/gecko/GeckoSessionController.kt`  
  Per-tab GeckoSession controller, navigation, internal routes, recovery, bridge routing, and refresh state.
- `app/src/main/java/com/dadigua/hyperbrowser/gecko/GeckoBrowserView.kt`  
  Compose/AndroidView GeckoView wrapper and pull-to-refresh handling.
- `app/src/main/java/com/dadigua/hyperbrowser/gecko/HyperBridge.kt`  
  Built-in WebExtension native-message bridge.
- `app/src/main/java/com/dadigua/hyperbrowser/extensions/ExtensionRepository.kt`  
  AMO search, WebExtension install/control, menu actions, popups, and extension tab requests.
- `app/src/main/java/com/dadigua/hyperbrowser/browser/BrowserProfileStore.kt`  
  JSON-backed bookmarks, history, and settings.
- `app/src/main/java/com/dadigua/hyperbrowser/webapp/WebAppRepository.kt`  
  WebApp definitions and pinned shortcut integration.

## Requirements

- Windows PowerShell or another shell that can run the Gradle wrapper.
- JDK 17.
- Android SDK with a device or emulator available through `adb`.
- pnpm for built-in page builds.

The project uses the Gradle wrapper. Do not rely on a global Gradle install.

## Build

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

The Android build automatically runs the Vite build for `internal-pages` before packaging assets.

To build only the built-in pages:

```powershell
pnpm --dir internal-pages build
```

To type-check the built-in pages:

```powershell
pnpm --dir internal-pages typecheck
```

## Install and Launch

Install the debug APK:

```powershell
.\gradlew.bat :app:installDebug --console=plain
```

Launch the app:

```powershell
adb shell monkey -p com.dadigua.hyperbrowser 1
```

Launch the browser activity with a specific URL:

```powershell
adb shell am force-stop com.dadigua.hyperbrowser
adb shell am start -n com.dadigua.hyperbrowser/.ui.browser.BrowserActivity --es extra_url https://example.com
```

Check the launcher activity:

```powershell
adb shell cmd package resolve-activity --brief com.dadigua.hyperbrowser
```

Expected launcher:

```text
com.dadigua.hyperbrowser/.ui.browser.BrowserActivity
```

## Built-In Pages

Built-in pages are authored in `internal-pages/` and built into `app/src/main/assets/`.

Semantic routes:

- `hyper://home`
- `hyper://search`
- `hyper://settings`
- `hyper://apps`
- `hyper://bookmarks`
- `hyper://history`

Runtime loading uses the internal WebExtension page base URL, but the address bar maps it back to semantic `hyper://...` URLs.

Do not hand-edit generated files under:

```text
app/src/main/assets/home.html
app/src/main/assets/search.html
app/src/main/assets/settings.html
app/src/main/assets/bookmarks.html
app/src/main/assets/history.html
app/src/main/assets/apps.html
app/src/main/assets/internal/
```

Edit the source in `internal-pages/`, then run:

```powershell
pnpm --dir internal-pages build
```

## Internal WebExtension Bridge

The internal bridge is provided by the built-in WebExtension in `internal-pages/public/`.

Flow:

```text
built-in page JS
  -> window.hyperBrowser
  -> browser.runtime.sendMessage(...)
  -> background.js
  -> browser.runtime.sendNativeMessage("hyperBrowser", ...)
  -> Kotlin HyperBridge
  -> BrowserActivity / GeckoSessionController
```

Bridge rules:

- Built-in pages can send browser commands and request data.
- Ordinary web pages cannot call privileged browser commands.
- Ordinary `http`/`https` pages are only allowed to send the pull-refresh touch-state message used to avoid false refreshes inside inner scroll containers.

## Pull-To-Refresh Behavior

Pull-to-refresh is implemented in `GeckoBrowserView.kt`.

Refresh can start only when:

- The current page is not an internal `hyper://...` route.
- The touch starts near the upper part of the content area.
- The gesture is mostly vertical and downward.
- `GeckoView` itself cannot scroll upward.
- The touched DOM scroll container, if any, also cannot scroll upward.

The DOM scroll-container state is reported by `internal-pages/public/pull-refresh-content.js` through the internal WebExtension bridge. This avoids refreshing when a page uses an inner scrolling container instead of `body` scrolling.

## Data Storage

The first version uses simple JSON files in the app private directory instead of Room/DataStore:

- `browser_history.json`
- `browser_bookmarks.json`
- `browser_settings.json`
- `downloads.json`
- `web_apps.json`
- `installed_extensions.json`

## Development Notes

- Prefer `pnpm` over `npm`.
- Prefer the Gradle wrapper over global Gradle.
- Native app code is Kotlin + Compose.
- Built-in pages are React + TypeScript.
- GeckoView APIs that require a Handler thread should run on the main thread.
- Keep `GeckoRuntimeProvider` as a singleton so regular browser tabs and WebApps share cookies/profile state.
- Avoid broad UI rewrites when fixing browser behavior; most interaction logic is centralized in `BrowserActivity.kt`, `GeckoSessionController.kt`, and `GeckoBrowserView.kt`.

## Useful Commands

```powershell
# Build debug APK
.\gradlew.bat :app:assembleDebug --console=plain

# Install debug APK
.\gradlew.bat :app:installDebug --console=plain

# Build built-in pages
pnpm --dir internal-pages build

# Type-check built-in pages
pnpm --dir internal-pages typecheck

# Start app
adb shell monkey -p com.dadigua.hyperbrowser 1

# Start browser with URL
adb shell am start -n com.dadigua.hyperbrowser/.ui.browser.BrowserActivity --es extra_url https://example.com

# Check foreground app
adb shell dumpsys window | Select-String -Pattern "mCurrentFocus|mFocusedApp"
```

## Manual Smoke Test

After changing browser interaction code:

1. Build and install the debug APK.
2. Start the app.
3. Open `https://example.com`.
4. Tap the address bar and verify the search/omnibox page opens.
5. Open the three-dot menu and verify navigation, downloads, extensions, bookmarks, history, and settings entries are reachable.
6. Open tab tray and verify card/list modes.
7. Test pull-to-refresh on a page with normal body scrolling.
8. Test pull-to-refresh on a page with an inner scroll container; refresh should not trigger until that inner container is at the top.

## License

No license has been declared yet.
