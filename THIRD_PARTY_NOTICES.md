# Third-Party Notices

Hyper Browser is built on open-source Android, browser, and frontend components. This file is a human-readable summary, not a complete legal bill of materials.

Review Gradle and pnpm metadata for the exact dependency graph:

- `app/build.gradle.kts`
- `internal-pages/package.json`
- `internal-pages/pnpm-lock.yaml`

## Major Runtime Dependencies

- Mozilla GeckoView: browser engine and WebExtension runtime.
- AndroidX and Jetpack Compose: Android UI and app framework libraries.
- Kotlin and kotlinx.coroutines: Kotlin runtime and coroutine support.
- OkHttp: HTTP client used by app services.
- React, React DOM, TypeScript, Vite, and related tooling: internal page frontend implementation.
- JUnit and org.json: unit test and JSON support.

## Generated Artifacts

Files under `app/src/main/assets/` include built output generated from `internal-pages/`. Do not edit those bundles directly; update the source and rebuild with:

```powershell
pnpm --dir internal-pages build
```

## License Review Before Release

Before a public release, review dependency licenses from the current Gradle and pnpm lockfiles. If a new dependency requires attribution, add it here or to a dedicated NOTICE file before tagging the release.
