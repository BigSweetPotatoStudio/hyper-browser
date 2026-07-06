# Privacy Policy

Hyper Browser is an Android browser and WebApp container. This document explains what the app stores locally, what network requests it can make, and what permissions are used.

## Data Stored On Device

Hyper Browser stores browser data in the app-private storage area on the device:

- Browsing history.
- Bookmarks.
- Browser settings.
- Download records.
- WebApp definitions and shortcut metadata.
- Installed extension metadata.
- Cached favicons and WebApp icons.
- Temporary app-update download state.

This data is intended to stay on the device unless the user exports, shares, backs up, or transfers app data through Android or device-management tools.

## Network Requests

Hyper Browser makes network requests when the user browses websites or uses browser features:

- Website requests made by GeckoView.
- Favicon and icon downloads for visited pages and installed WebApps.
- Android AMO requests for extension search and XPI downloads.
- GitHub release index and APK downloads for in-app updates.
- Any network requests made by installed WebExtensions or opened websites.

The project does not currently operate its own analytics, telemetry, advertising, or account server.

## Permissions

The Android app may request or declare these permissions:

- `INTERNET` and `ACCESS_NETWORK_STATE`: open websites and fetch browser resources.
- `POST_NOTIFICATIONS`: show download, update, and media playback notifications.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: keep downloads, updates, and media controls active when needed.
- `REQUEST_INSTALL_PACKAGES`: install app updates downloaded from GitHub Releases after user confirmation.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: open Android battery settings when the user enables background playback-related behavior.
- `WRITE_EXTERNAL_STORAGE` on Android 9 and below: support older Android download behavior.
- `INSTALL_SHORTCUT`: request pinned shortcuts for installed WebApps.

## Browser Content And Extensions

Opened websites and installed extensions are separate third-party code and content. They may collect data under their own policies. Extension permissions are handled through GeckoView WebExtension APIs; users should install extensions only from sources they trust.

## Chrome Companion Extension

The Hyper Browser Companion extension stores its settings and synced browser data in Chrome extension local storage. This can include launcher layout, saved WebApp definitions, bookmark records, cached page icons, a generated device identifier, and optional WebDAV connection settings.

When the user opens the extension popup or saves the current page, the extension reads the active tab title, URL, and page icon metadata so the page can be added as a WebApp or bookmark. It can also create and update bookmarks in a user-selected Hyper Browser bookmark folder.

WebDAV sync is optional and uses a server configured by the user. If enabled, the extension sends synced WebApp, bookmark, and launcher JSON files to that WebDAV server and reads them back for merge. WebDAV usernames and passwords are stored locally in Chrome extension storage and are used only for requests to the configured WebDAV server.

The project does not operate an analytics, advertising, telemetry, or account service for the companion extension. Data handled by the extension is not sent to the project maintainers unless the user separately shares it.

## Backups

The app keeps browser profile files in private app storage. Review `app/src/main/res/xml/backup_rules.xml` and `app/src/main/res/xml/data_extraction_rules.xml` before changing backup behavior for a release.

## Contact

For privacy or security concerns, use the repository security reporting path described in `SECURITY.md`.
