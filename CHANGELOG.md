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
- Plugin: ownship radio state (COM1/COM2 tuned frequency, read and remote-settable; Mode C
  transponder state, read-only), independent of `IBroker` since vPilot's plugin API has no
  ownship telemetry at all. Split across two processes: `Handoff.RadioHost` (a new x64
  console app owning the actual `CTrue.FsConnect`/SimConnect connection) and
  `RadioStateModel` in the plugin itself (a named-pipe IPC client that spawns and talks to
  it). Required because vPilot's own process is x86 (confirmed by direct inspection) while
  every available modern SimConnect wrapper's native binary is x64-only — an in-process x64
  SimConnect connection inside the plugin (briefly attempted, including bundling it into a
  single file via Costura.Fody) cannot load into vPilot at all. Shared wire-format and pure
  conversion/validation logic lives in `plugin/Shared/`.
- VS Code: `plugin: deploy` task, copying the built plugin DLL and the `Handoff.RadioHost`
  helper's output folder to a `VPILOT_PLUGINS_DIR`-configured Plugins folder (builds first
  via `dependsOn`).
- `docs/protocol.md`: the WebSocket contract (controllers, chat, radio state; remote chat
  send and COM1/COM2 tuning), now filled in.
- Plugin: `HandoffWebSocketServer`, serving `docs/protocol.md` over a Fleck-hosted WebSocket
  (`ws://0.0.0.0:48765`) — raw TCP sockets rather than `HttpListener`, so binding to a
  LAN-reachable address needs no admin rights or `netsh` URL-ACL setup. Started in
  `HandoffPlugin.Initialize`, independent of the VATSIM connection. Message building/parsing
  lives in `ProtocolMessages`, unit tested.
- Plugin: `HandoffDiscoveryListener`, a UDP responder (port `48766`) so the Android app can
  find the plugin's LAN IP without it being typed in by hand — plain UDP, no mDNS/Bonjour
  dependency. Documented in `docs/protocol.md`'s new "Discovery" section.
- Android: foundation for the actual client app, replacing the placeholder `MainActivity`.
  `HandoffWebSocketClient` (OkHttp) and `HandoffDiscoveryClient` (UDP broadcast, falling back
  to a manually entered IP) talk to the plugin; `HandoffConnectionService`, a foreground
  service, owns the connection so it survives the app losing foreground, with
  reconnect-with-backoff and a persistent status notification. `HandoffState` exposes live
  controllers/chat/radio state as `StateFlow`s to a Jetpack Compose UI (`Controllers`/`Chat`/
  `Radio`/`Settings` tabs) letting the pilot see live state and send chat/set frequencies from
  the app. Message (de)serialization via `kotlinx.serialization`, unit tested on the JVM.
  Still missing: the `SYSTEM_ALERT_WINDOW` chat-heads overlay described in issue #1 — planned
  as a follow-up.
- Plugin: flight plan integration via the SimBrief API (`FlightPlan`/`FlightPlanModel`/
  `SimBriefClient`) — `IBroker` has no flight-plan members at all, so this is the only source
  for callsign/origin/destination/alternate. A SimBrief user ID is tried first (falling back
  to username, which has occasionally caused lookup issues) and both are persisted locally so
  the plugin can re-fetch on its own next startup without the Android app needing to reconnect
  first. New `flightPlan` broadcast plus `setSimbriefCredentials`/`refreshFlightPlan` client
  commands, documented in `docs/protocol.md`.
- Android: basic callsign/origin→destination display on the Controllers screen with a manual
  refresh button, plus SimBrief user ID/username fields on the Settings screen. Alternate
  airport is fetched/stored by the plugin but not yet surfaced in the app.
