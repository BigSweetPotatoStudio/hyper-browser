# Changelog

All notable public release changes should be documented here.

This project uses GitHub Releases for packaged APKs. The changelog should match the release notes attached to each tag.

## Unreleased

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
