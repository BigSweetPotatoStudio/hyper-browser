# Contributing

Hyper Browser is an Android app built with Kotlin, Jetpack Compose, GeckoView, and React-based internal pages.

## Development Setup

Required tools:

- Windows PowerShell.
- JDK 17.
- Android SDK with API 36 installed.
- Android device or emulator reachable through `adb`.
- pnpm.

Install internal page dependencies:

```powershell
pnpm --dir internal-pages install --frozen-lockfile
```

Build the debug APK:

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

Install and launch:

```powershell
.\gradlew.bat :app:installDebug --console=plain
adb shell monkey -p com.dadigua.hyperbrowser 1
```

## Quality Checks

Run these before opening a pull request:

```powershell
pnpm --dir internal-pages typecheck
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

For release-facing changes, also run:

```powershell
.\gradlew.bat :app:assembleRelease --console=plain
```

## Generated Files

Internal page source lives under `internal-pages/`. Build output is copied into `app/src/main/assets/`.

Do not edit generated files under `app/src/main/assets/internal/` by hand. Change the TypeScript, TSX, CSS, or public asset source under `internal-pages/`, then run:

```powershell
pnpm --dir internal-pages build
```

## Pull Request Guidelines

- Keep changes focused.
- Explain behavior changes and manual test coverage.
- Include screenshots only when they help review UI changes.
- Avoid unrelated formatting churn.
- Do not commit local build output such as `.gradle/`, `.kotlin/`, `.playwright-cli/`, `app/build/`, or `screenshots/`.

## Browser-Specific Review Checklist

For browser interaction changes, check:

- Address/search input flow.
- Back button behavior.
- Tab tray Card/List modes.
- `target=_blank` handling.
- Bookmarks and history pages.
- Extensions menu expansion and popup behavior.
- Download list and update download state.
- `hyper://` internal page routing.
- WebApp launch and pinned shortcut behavior when relevant.
