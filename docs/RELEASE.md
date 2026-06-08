# Release Checklist

This checklist is for publishing installable Hyper Browser APKs through GitHub Releases and the in-app updater.

## Versioning

Update the app version in `app/build.gradle.kts`:

```kotlin
versionCode = 5
versionName = "0.1.4"
```

Use a monotonically increasing `versionCode`. The in-app updater compares remote `versionCode` against the installed package.

## Required GitHub Secrets

The release workflow requires a persistent Android signing key:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Do not publish release APKs signed with a temporary key. A temporary key breaks future upgrades because Android requires the old and new APK signatures to match.

## Local Verification

Run these commands before tagging:

```powershell
pnpm --dir internal-pages install --frozen-lockfile
pnpm --dir internal-pages typecheck
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleRelease --console=plain
```

Expected local release outputs:

```text
app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk
app/build/outputs/apk/release/app-x86_64-release-unsigned.apk
```

The GitHub workflow signs these unsigned APKs and uploads:

```text
HyperBrowser-arm64-v8a-release.apk
HyperBrowser-armeabi-v7a-release.apk
HyperBrowser-x86_64-release.apk
```

## Publish A Release

Create and push a version tag:

```powershell
git tag v0.1.4
git push origin v0.1.4
```

The `Android Release APK` workflow runs on `v*` tags and publishes a GitHub Release.

You can also run the workflow manually from GitHub Actions, but tagged releases are preferred for public builds.

## Update `update/stable.json`

After the GitHub Release uploads signed APKs, update `update/stable.json` with all supported ABI assets.

If the signed APKs are available under `app/build/outputs/apk/release/signed/`, generate the index with:

```powershell
.\scripts\update-stable-index.ps1 `
  -VersionCode 5 `
  -VersionName "0.1.4" `
  -Notes "Short user-facing release note."
```

The script expects these files:

```text
app/build/outputs/apk/release/signed/HyperBrowser-arm64-v8a-release.apk
app/build/outputs/apk/release/signed/HyperBrowser-armeabi-v7a-release.apk
app/build/outputs/apk/release/signed/HyperBrowser-x86_64-release.apk
```

It writes `update/stable.json` with GitHub release URLs, SHA-256 hashes, and file sizes.

Manual shape:

```json
{
  "channel": "stable",
  "versionCode": 5,
  "versionName": "0.1.4",
  "minSdk": 26,
  "notes": "Short user-facing release note.",
  "releaseUrl": "https://github.com/BigSweetPotatoStudio/hyper-browser/releases/tag/v0.1.4",
  "assets": [
    {
      "abi": "arm64-v8a",
      "url": "https://github.com/BigSweetPotatoStudio/hyper-browser/releases/download/v0.1.4/HyperBrowser-arm64-v8a-release.apk",
      "sha256": "...",
      "sizeBytes": 0
    },
    {
      "abi": "armeabi-v7a",
      "url": "https://github.com/BigSweetPotatoStudio/hyper-browser/releases/download/v0.1.4/HyperBrowser-armeabi-v7a-release.apk",
      "sha256": "...",
      "sizeBytes": 0
    },
    {
      "abi": "x86_64",
      "url": "https://github.com/BigSweetPotatoStudio/hyper-browser/releases/download/v0.1.4/HyperBrowser-x86_64-release.apk",
      "sha256": "...",
      "sizeBytes": 0
    }
  ]
}
```

Compute SHA-256 on Windows:

```powershell
Get-FileHash .\HyperBrowser-arm64-v8a-release.apk -Algorithm SHA256
```

Compute file size on Windows:

```powershell
(Get-Item .\HyperBrowser-arm64-v8a-release.apk).Length
```

Commit and push the `update/stable.json` change after verifying the URLs, hashes, and sizes.

## Manual Smoke Test

Install the matching ABI APK on a device and test:

1. Launch Hyper Browser.
2. Open `https://example.com`.
3. Use the address bar search flow.
4. Open the tab tray and switch Card/List modes.
5. Open bookmarks, history, settings, and WebApps pages.
6. Start and complete a normal file download.
7. If extensions are installed, open the menu, expand Extensions, and open a popup.
8. Check Settings -> Update with a higher `update/stable.json` version in a test branch or local test setup.

## Release Notes

Release notes should include:

- User-visible changes.
- Known limitations.
- Minimum Android version.
- APK architecture selection instructions.
- Whether the build is alpha, beta, or stable.
