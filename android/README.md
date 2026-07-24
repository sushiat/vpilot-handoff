# Handoff Android app

Native Kotlin, plain Gradle project (no Android-Studio-specific files) — works from
VS Code + a Kotlin extension via the terminal.

## Build

The Gradle wrapper (`gradlew`/`gradlew.bat`, pinned to Gradle 8.10.2) is checked in, so
`./gradlew assembleDebug` works directly — no local Gradle install needed. You do need:

- A JDK 17 (Android Gradle Plugin's supported version). Installed here via
  `scoop install openjdk17` (bucket `java`); point `JAVA_HOME` at it before running
  Gradle, e.g. `$env:JAVA_HOME = "$HOME\scoop\apps\openjdk17\current"`.
- The Android SDK (platform-tools, `platforms;android-35`, `build-tools;35.0.0`).
  Installed here via `scoop install android-clt` (main bucket) +
  `sdkmanager --licenses` + `sdkmanager "platform-tools" "platforms;android-35"
  "build-tools;35.0.0"`.
- `local.properties` (git-ignored, machine-specific) pointing `sdk.dir` at the SDK,
  e.g. `sdk.dir=C:/Users/<you>/scoop/apps/android-clt/current`.

Verified: `./gradlew assembleDebug` succeeds end-to-end with this setup.

## Structure

- `app/` — single module, `MainActivity` placeholder, `minSdk 26` (required for
  notification channels / foreground service, see root CLAUDE.md).
- OkHttp is declared as a dependency (unused for now) — the planned WebSocket client
  for talking to the plugin.
