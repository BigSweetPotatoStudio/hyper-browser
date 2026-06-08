# Security Policy

## Supported Versions

Security fixes target the latest public GitHub Release and the current `main` branch.

Pre-release, debug, or locally built APKs may contain behavior that is not intended for stable users.

## Reporting A Vulnerability

Please do not open a public issue for exploitable vulnerabilities.

Preferred reporting path:

1. Use GitHub Security Advisories for this repository.
2. If advisories are not enabled, contact the maintainer through the GitHub repository owner profile and include `Hyper Browser security report` in the subject or first line.

Useful information to include:

- Affected version or commit.
- Android version and device model.
- Whether the issue affects normal browsing, WebApp mode, downloads, updates, WebExtension installation, WebExtension popup handling, or the internal `hyper://` pages.
- Reproduction steps.
- Expected impact.

## Security-Sensitive Areas

Pay special attention to:

- Native bridge access from built-in pages and WebExtensions.
- `moz-extension://` and `hyper://` URL mapping.
- WebExtension install, permission, popup, and options-page handling.
- Download and app-update file handling.
- `FileProvider` URI exposure.
- External Android intents.
- Browser history, bookmarks, downloaded files, and private app storage.

## Release Builds

Release APKs must be signed with the persistent project release key. Do not publish APKs signed with a temporary CI key, because users will not be able to upgrade safely from that build.
