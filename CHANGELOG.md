# Changelog

All notable public release changes should be documented here.

This project uses GitHub Releases for packaged APKs. The changelog should match the release notes attached to each tag.

## Unreleased

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
