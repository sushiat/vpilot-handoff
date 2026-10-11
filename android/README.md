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

### Distribution channels (issue #153)

Two product flavors, same code, same `applicationId`/versionCode/signing key:

- `sideload` -- GitHub Releases, Obtainium, flightsim.to. Everything on.
- `play` -- the Google Play build; its risky features are switched off (see
  `app/src/play/` and [docs/play-release.md](../docs/play-release.md)).

Gradle task names carry the flavor: `./gradlew assembleSideloadDebug` /
`testSideloadDebugUnitTest` (plain `assembleDebug`/`test` build/run both), the debug APKs land at
`app/build/outputs/apk/<flavor>/debug/app-<flavor>-debug.apk`, and the Play bundle is
`./gradlew bundlePlayRelease`.

## Structure

- `app/` — single module, `minSdk 26` (required for notification channels / foreground
  service, see root CLAUDE.md). Jetpack Compose UI (`MainActivity` + `ui/`), one
  `Controllers`/`Chat`/`Radio`/`Settings` tab each.
- `protocol/` — `docs/protocol.md`'s message shapes as `kotlinx.serialization` data classes,
  plus a `type`-field decode helper for incoming frames and `RadioFrequency` (mirrors
  `plugin/Shared/RadioFrequency.cs`'s compressed-integer <-> MHz conversion).
- `network/` — `HandoffWebSocketClient` (OkHttp) talks the actual protocol;
  `HandoffDiscoveryClient` finds the plugin's LAN IP via the UDP broadcast described in
  `docs/protocol.md`'s Discovery section.
- `HandoffConnectionService` — foreground service owning the WebSocket connection so it
  survives the app losing foreground (see root CLAUDE.md's interruption model), with
  reconnect-with-backoff and a persistent status notification. `HandoffState` exposes live
  state to the UI as `StateFlow`s; same-process, so no `bindService`/Messenger IPC.
- Server address: auto-detected via UDP broadcast (`SettingsScreen`'s "Auto-detect" button),
  falling back to a manually entered IP (`SharedPreferences`-backed) if discovery doesn't
  reach the plugin (some routers block broadcast traffic or apply AP client isolation).
- Not yet built: the `SYSTEM_ALERT_WINDOW` chat-heads overlay from issue #1 — this is just
  the in-app foundation (WebSocket client + foreground service + basic screens).
