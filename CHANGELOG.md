# Changelog

All notable public release changes should be documented here.

This project uses GitHub Releases for packaged APKs. The changelog should match the release notes attached to each tag.

## Unreleased

- Added private tabs backed by GeckoView private mode; private tabs are excluded from history, thumbnails, session-state persistence, and tab restore.
- Added browser menu actions for sharing the current page, copying the current link, and finding text in the current page.
- Added a Settings privacy action to clear browsing data, including Gecko site data/cache/permissions plus local history and favicon cache.
- Added Downloads page actions to cancel active downloads, retry failed or canceled downloads, and clear completed/failed/canceled records without deleting saved files.
- Fixed favicon discovery so non-http icon URLs such as `data:` values are ignored before OkHttp download attempts.
- Fixed address bar taps so the text field reliably enters edit mode and opens the soft keyboard.
- Release workflow now uses Linux-friendly Node scripts to extract notes from `CHANGELOG.md`, publish GitHub Release notes, generate `update/stable.json`, and commit the stable index back to `main` automatically.

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
