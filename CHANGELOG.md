# Changelog

All notable public release changes should be documented here.

This project uses GitHub Releases for packaged APKs. The changelog should match the release notes attached to each tag.

## Unreleased

## 2.0.0 - 2026-07-07

- Added safer confirmation flows for WebApp deletion from the home launcher and browser menu.
- Added a close-all-tabs action with confirmation, plus persisted Card/List tab-tray mode.
- Improved shared URL cleanup and WebApp-first search suggestions.
- Refined launcher delete confirmation wording and refreshed the bundled home page assets.

## 2.0.0-beta.1 - 2026-07-07

- Added safer confirmation flows for WebApp deletion from the home launcher and browser menu.
- Added a close-all-tabs action with confirmation, plus persisted Card/List tab-tray mode.
- Improved shared URL cleanup and WebApp-first search suggestions.
- Refined launcher delete confirmation wording and refreshed the bundled home page assets.

## 0.1.8-beta.3 - 2026-06-26

- Reworked WebDAV sync around shared JSON and operation-log handling across Android built-in pages, launcher layout, bookmarks, WebApps, and the companion extension.
- Improved launcher and WebApp sync restore, folder renaming, layout ordering, install/uninstall cleanup, and icon recovery.
- Added explicit WebDAV sync direction controls and clearer sync settings.
- Improved bookmark sync for Chrome folder changes, URL fragments, tombstones, and local favicon caching.
- Added global website display mode controls and a WebApp shortcut install action.
- Improved the browser toolbar address display with page title and URL context.

## 0.1.8-beta.2 - 2026-06-23

- Fixed GitHub Actions collection of WXT companion extension ZIPs for release uploads.
- Added WXT-based companion extension builds for Chrome and Firefox.
- Added GitHub Actions packaging for companion extension ZIP artifacts and prerelease uploads.
- Removed future `armeabi-v7a` release APK builds; upcoming releases target `arm64-v8a` phones and `x86_64` environments.

## 0.1.8-beta.1 - 2026-06-23

- Added WXT-based companion extension builds for Chrome and Firefox.
- Added GitHub Actions packaging for companion extension ZIP artifacts and prerelease uploads.
- Removed future `armeabi-v7a` release APK builds; upcoming releases target `arm64-v8a` phones and `x86_64` environments.

## 0.1.7 - 2026-06-22

- Added WebApp search results and direct WebApp launching from search.
- Added download record search and Library shortcuts for bookmarks, history, downloads, and WebApps.
- Added confirmation flows for deleting WebApps and bookmarks, clearing history, and uninstalling extensions.
- Added HTTP authentication, Gecko prompt handling, and language settings for localized built-in pages.
- Improved fullscreen video rotation, X/Twitter Google sign-in compatibility, extension state persistence, and Android Autofill handling.
- Improved bookmark and WebApp editing with URL validation, explicit custom search save behavior, retry and clear actions, clearer search suggestion scope, and safer bridge handling.

## 0.1.7-beta.3 - 2026-06-21

- Added WebApp search results and direct WebApp launching from search.
- Added download record search and Library shortcuts for bookmarks, history, downloads, and WebApps.
- Added confirmation flows for deleting WebApps and bookmarks, clearing history, and uninstalling extensions.
- Improved bookmark and WebApp editing with URL validation and explicit custom search save behavior.
- Improved built-in browser pages with retry and clear actions, clearer search suggestion scope, and safer bridge handling.

## 0.1.7-beta.2 - 2026-06-18

- Added HTTP authentication and Gecko prompt handling for protected sites and browser permission flows.
- Added language settings and localized built-in browser pages.
- Improved fullscreen video rotation so fullscreen playback follows device sensors even when system rotation is locked.

## 0.1.7-beta.1 - 2026-06-18

- Improved X/Twitter Google sign-in compatibility for popup-based account flows.
- Fixed extension enable/disable state so disabled add-ons stay disabled after restarting the browser.
- Moved address editing into a dedicated search page so the browser toolbar no longer keeps a persistent text input over web content.
- Improved Android Autofill focus handling for web content and kept the dedicated search page controls compact.

## 0.1.6 - 2026-06-17

- Added DNS over HTTPS settings, automatic ECH attempts when DoH is enabled, HTTPS-Only controls, and standard/strict/no tracking protection levels.
- Changed DNS over HTTPS to be off by default for new installs while preserving existing user choices.
- Improved DoH provider editing with an explicit save action, validation, and clearer unsaved state.
- DoH changes now attempt runtime reload and tell users that ECH and some Gecko settings are most complete after restarting the app.
- Improved address-bar security indicators for HTTP, HTTPS, and enhanced HTTPS states.
- Improved WebApp icon recovery so installed shortcuts keep existing icons, recover missing favicon files, and refresh when newer site icons are available.
- Added automatic update checks when opening Settings.
- Added local backup support for bookmarks and installed WebApps.
- Improved video fullscreen handling so fullscreen playback requests rotate and hide browser chrome more like Chrome.
- Removed Picture-in-Picture support while fullscreen playback behavior is stabilized.
- Added prerelease publishing support: hyphenated version tags now create GitHub prereleases for manual testing without updating the stable in-app update index.

## 0.1.5 - 2026-06-11

- Release workflow now uses Linux-friendly Node scripts to extract notes from `CHANGELOG.md`, publish GitHub Release notes, generate `update/stable.json`, and commit the stable index back to `main` automatically.
- Improved the background video playback enhancement setting for more reliable page behavior while Hyper Browser is in the background.
- Refined the extension install flow.

## 0.1.4 - 2026-06-08

- Added open-source project documentation: license, privacy policy, security policy, contribution guide, and release checklist.
- Tightened release workflow expectations around type checks, unit tests, and persistent signing keys.
- Disabled Gecko remote debugging for non-debuggable builds.
- Fixed a TypeScript nullability issue in the internal Settings update UI.

## 0.1.3

- Added and refined app-update flows through GitHub Release metadata.
- Improved media playback notification behavior across browser tabs and WebApps.
- Improved IME avoidance for bottom input fields.
- Added shared image and link context menus.
