# Release Checklist

This checklist is for publishing installable Hyper Browser APKs through GitHub Releases and the in-app updater.

## Source Of Truth

Release metadata comes from two files:

- `app/build.gradle.kts`: Android `versionCode` and `versionName`.
- `CHANGELOG.md`: user-facing release notes.

When the user asks to publish a new version, update both before tagging. The release workflow reads those files and then:

- type-checks internal pages,
- runs Android unit tests,
- builds release split APKs,
- signs APKs with GitHub Actions secrets,
- publishes GitHub Release notes from `CHANGELOG.md`,
- generates `update/stable.json`,
- commits `update/stable.json` back to `main`.

CI release automation runs on `ubuntu-latest`. Release helper scripts are Node `.mjs` files so the same logic works in GitHub Linux CI and on the local Windows checkout.

## Versioning

Update the app version in `app/build.gradle.kts`:

```kotlin
versionCode = 6
versionName = "0.1.5"
```

Use a monotonically increasing `versionCode`. The in-app updater compares remote `versionCode` against the installed package.

The tag must match `versionName`:

```text
versionName "0.1.5" -> tag v0.1.5
```

If they do not match, CI fails before publishing.

### Prerelease Versions

Use a hyphenated `versionName` for prereleases:

```kotlin
versionCode = 7
versionName = "0.1.6-beta.1"
```

The matching tag is:

```text
v0.1.6-beta.1
```

Prereleases are published as GitHub prereleases for manual testing, but they do not update `update/stable.json` and are not shown by the in-app stable update check.

If a tester manually installs a prerelease, the next stable release must use a higher `versionCode` so Android can upgrade it normally:

```kotlin
// prerelease
versionCode = 7
versionName = "0.1.6-beta.1"

// next stable
versionCode = 8
versionName = "0.1.6"
```

## Changelog

Add a `CHANGELOG.md` section for the version before tagging:

```markdown
## 0.1.5 - 2026-06-08

- Fix ...
- Add ...
- Improve ...
```

The release workflow extracts that section. It uses the Markdown body for GitHub Release notes and a plain-text version for `update/stable.json.notes`.

Keep the notes user-facing. Avoid internal implementation-only details unless they affect users or maintainers.

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

## Publish A Release

Commit the version bump and changelog, then create and push a version tag:

```powershell
git tag v0.1.5
git push origin main
git push origin v0.1.5
```

The `Android Release APK` workflow runs on `v*` tags. For a tag release it publishes:

```text
HyperBrowser-arm64-v8a-release.apk
HyperBrowser-armeabi-v7a-release.apk
HyperBrowser-x86_64-release.apk
```

For stable tags such as `v0.1.6`, it then updates `update/stable.json` on `main` with:

- `versionCode`,
- `versionName`,
- changelog-derived `notes`,
- release URL,
- one signed APK asset per ABI,
- SHA-256 hashes,
- file sizes.

For prerelease tags such as `v0.1.6-beta.1` or `v0.1.6-rc.1`, it marks the GitHub Release as prerelease and skips `update/stable.json`.

You can also run the workflow manually from GitHub Actions, but tagged releases are preferred for public builds. Manual `ci-*` releases do not update the stable in-app update index.

## CI Release Scripts

The CI calls:

```bash
node scripts/extract-changelog-notes.mjs --version "0.1.5"
node scripts/update-stable-index.mjs \
  --version-code 6 \
  --version-name "0.1.5" \
  --tag "v0.1.5" \
  --notes-file ".release/update-notes.txt"
```

You normally do not need to run these manually. They are kept in the repo so the CI behavior is reviewable and reproducible.

## Manual Smoke Test

Install the matching ABI APK on a device and test:

1. Launch Hyper Browser.
2. Open `https://example.com`.
3. Use the address bar search flow.
4. Open the tab tray and switch Card/List modes.
5. Open bookmarks, history, settings, and WebApps pages.
6. Start and complete a normal file download.
7. If extensions are installed, open the menu, expand Extensions, and open a popup.
8. Check Settings -> Update against the published `update/stable.json`.
