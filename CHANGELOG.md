# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
once it has its first release.

## [Unreleased]

### Added

- Project scaffold: `plugin/` (.NET Framework 4.8, buildable via `dotnet build`) and
  `android/` (native Kotlin, plain Gradle project) skeletons, no application logic yet.
- VS Code multi-root workspace (`Handoff.code-workspace`) with build tasks for both
  projects, plus recommended extensions.
- CI: build workflow (`.github/workflows/build.yml`) verifying both projects still
  build on push/PR, using a hand-reconstructed public-API-only stub of the
  non-redistributable vPilot plugin DLL for the plugin build.
- Plugin: `ControllerStateModel`, an in-memory live controller list built from
  `IBroker`'s `ControllerAdded/Deleted/FrequencyChanged/LocationChanged` events, wired
  up in `HandoffPlugin.Initialize`. First xUnit test project
  (`plugin/Handoff.Plugin.Tests/`), now run in CI.
- Plugin: `ChatModel`, an in-memory chat log and SELCAL alert list built from `IBroker`'s
  `PrivateMessageReceived/RadioMessageReceived/BroadcastMessageReceived/SelcalAlertReceived`
  events plus outgoing `SendPrivateMessage`/`SendRadioMessage` calls, wired up in
  `HandoffPlugin.Initialize`.
- Plugin: `RadioStateModel`, a SimConnect-based connection for ownship radio state --
  COM1/COM2 tuned frequency (read and remote-settable) and Mode C transponder state
  (read-only), independent of `IBroker` since vPilot's plugin API has no ownship telemetry
  at all. Uses the `CTrue.FsConnect` NuGet package; plugin now builds `x64`-only.
