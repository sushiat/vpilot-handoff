# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
once it has its first release.

## [Unreleased]

### Added

- Plugin: controller-ranking redesign (issue #18) replacing the two-flag
  `isLikelyNextCandidate`/`isApproaching` system with three flags --
  `isHighlighted` (relevance/visibility), `isNext` (confident, singular), and
  `isLikelyNext` (the same signal confidence-capped when genuinely tied or
  route-relevance is unconfirmed) -- driven by 9 explicit ranking buckets
  instead of an ad-hoc tier walk. Ground-mode (AGL<50ft) relevance spans
  DEL/GND/TWR/APP/CTR together (flight-plan match, VATGlasses polygon
  containment where available else a radius fallback, CTR horizontal-only);
  airborne splits into TWR/APP (concentric radii, route/heading-projected
  "entering" prediction, altitude-ceiling-gated) and CTR (lateral+vertical
  satisfied-or-converging prediction, band-anchor tie detection at 10%, plus
  an independent ETA readout during level flight or a high-altitude climb/
  descent). Full bucket-by-bucket design in `docs/controller-ranking.md`.
- Plugin: `HandoffController`/`HandoffControllerStateModel`, a single unified
  controller-state model replacing the separate `Controller`/
  `ControllerStateModel`/`ContactMeModel`/`SelcalActiveModel` split --
  immutable copy-on-write records holding every ranking-relevant flag
  (current-tuned, standby-tuned, contact-me + expiry, SELCAL-active +
  expiry, pinned, hidden + disconnect timestamp) on one record per callsign.
  Disconnect is hide-then-expire (5-minute grace window), not instant
  removal, so a brief FSD/network blip no longer wipes an outstanding
  contact-me/pin/SELCAL.
- Plugin/Android: pinning (`pinController`/`clearPinnedController`) now
  supports multiple simultaneously-pinned controllers -- each is set/cleared
  independently by its own callsign (`clearPinnedController` now also takes
  a `callsign`), never touching any other pinned callsign. Previously only
  one controller could be pinned at a time.
- Plugin: `HandoffWebSocketServer`'s `controllers` broadcast is decoupled
  onto a fixed ~1s timer instead of firing on every internal ranking
  recompute (which stays fully event-driven/reactive) -- avoids a full
  re-serialization/Android recomposition every time SimConnect-driven
  telemetry ticks, even when nothing meaningfully changed.
- Plugin: spatial dead-band ("flapping protection") guarding against a
  candidate rapidly toggling flags when sitting right at a geometric
  boundary -- a numeric version (radius fallbacks, tie-bands: joins at the
  real threshold, only leaves once `DeadbandExitMultiplier` past it) and a
  polygon-containment version (`VatGlassesSectorLookup.
  DistanceToPolygonBoundaryNm`, a new nearest-point-on-polygon-boundary
  primitive: stays contained until genuinely past the boundary edge by a
  fixed margin, not the instant the boolean point-in-polygon check flips).
- Plugin: `VatGlassesOwnershipResolver.ResolveOnlineControllers` now
  returns every distinct online controller matching a sector's ownership
  chain instead of just the first -- fixes a real flight-test bug where
  several same-FIR CTR positions sharing an identical prefix/type (e.g.
  Sweden Control's M2/M4/M5/M6/M7/M8/MY, all "ESMM"+CTR) could resolve to
  the wrong one when more than one was online at once. Callers now feed
  every match into the existing tie-detection instead of the resolver
  silently guessing.
- Android: distinct tuned-frequency colors for COM1 (teal) vs. COM2 (rose),
  and a dedicated dimmed shade for standby-tuned rows -- previously both
  radios and the standby state shared one color, making it hard to tell at
  a glance which radio a row belonged to.
- Android: row text color (black vs. white) is now decided from the actual
  rendered background's real perceptual lightness (sRGB relative luminance)
  rather than the nominal OKLCH lightness value fed into the color
  formula -- a flat threshold on the nominal input didn't account for how
  much hue/chroma shift a color's real perceived brightness.
- Android: the pin icon tilts 45 degrees when a controller is pinned, in
  addition to the existing color change.
- Plugin: `RankedController.StationName` (issue #11) -- a human station
  display name (e.g. "Bremen Radar" for `EDWW_N_CTR`), preferring a name
  extracted from the controller's own live ATIS/info text
  (`VatAtisStationNameExtractor`, patterns confirmed against a live VATSIM
  feed scan) and falling back to a name composed from vatspy-data-project's
  FIR/airport names plus a region-aware suffix table
  (`VatSpyStationNaming`/`VatSpyDataModel`). Also adds a second, coarser
  vatspy FIR-polygon fallback tier -- VATGlasses polygon, else vatspy, else
  distance -- to the CTR-tier ranking buckets (6d, 8a, and bucket 9's CTR
  ordering) for regions VATGlasses doesn't cover. Raw ATIS text is also
  carried onto the wire (`textAtis`) for future client UI.
- Android: controller-row tune-menu redesigned around ATIS text -- the
  COM1/COM2/STBY/STBY grid switches from 2x2 to a single 4-column row when
  the controller's ATIS has a long line, showing the full (scrollable) ATIS
  text below the grid; the dialog's background/border/text now mirror the
  row's own facility color instead of a generic surface color.
- Plugin/Android: new `setCom1ActiveAndStandbyFrequency`/
  `setCom2ActiveAndStandbyFrequency` command sets active and standby
  together in one round trip -- used for a "transfer" (activate a
  just-tuned frequency while preserving the previously-active one into
  standby, matching real flip-flop avionics like the Garmin G3000 GTC's
  XFER key) and for the top-bar tap-to-swap, replacing two separate
  commands that visibly landed over a second apart due to
  `Handoff.RadioHost`'s per-command SimConnect settle-wait.
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
- Plugin: raw ownship telemetry (on-ground, ground speed, AGL, vertical speed, heading,
  latitude, longitude) gathered by `Handoff.RadioHost` via a second, independently-polled
  SimConnect data definition (3s cadence, separate from the radio poll's 1s), reported to the
  plugin over the existing IPC pipe as a new `ownshipTelemetry` message and exposed as
  `RadioStateModel.Telemetry`. Telemetry plumbing only, toward future phase-of-flight and
  controller-priority-ranking work (see #7, #8, #9) — no classification logic or protocol/
  WebSocket changes yet.
- Plugin: controller priority ranking (`ControllerRankingModel`), re-sorting the full
  controller list `ControllerStateModel` already reports (nothing hidden) by: currently-tuned
  frequency or a manual pin, an outstanding "contact me" private-message request
  (`ContactMeModel`, 5-minute lazy expiry), the standard DEL→GND→TWR→APP/DEP→CTR chain tier
  relative to the current one (`ControllerTier`), a flight-plan route match (origin
  pre-departure, destination once airborne), and finally distance to ownship
  (`Shared/GeoDistance`) as a last-resort tiebreak, with hysteresis on that distance tiebreak
  to avoid flapping on momentary GPS noise. Cid/name/facility/rating enrichment comes from the
  public VATSIM data feed (`VatsimDataFeedClient`/`VatsimDataFeedModel`), since `IBroker`
  exposes none of it. The `controllers` WebSocket message gains `cid`/`name`/`facility`/
  `rating`/`requestsContactMe`/`isCurrent`/`isContactMe`/`isLikelyNextCandidate`/
  `isApproaching` fields, plus new `pinController`/`clearPinnedController` client commands for
  the manual override, documented in `docs/protocol.md`. `isApproaching` is a distance/heading
  "closing in on this station" signal (only set when nothing is currently tuned/pinned) for
  GND/TWR/APP, using plain thresholds plus a heading-vs-bearing check for APP beyond its inner
  radius (`GeoDistance.InitialBearingDegrees`/`AngularDifferenceDegrees`) — not computed for
  DEL (already well-served by route match) or CTR (a single lat/lon can't represent a FIR's
  real shape; needs actual sector geometry, see #11).
- Android: `Controller` decodes the full enriched `controllers` message (issue #8) —
  `cid`/`name`/`facility`/`rating`/`requestsContactMe`/`isCurrent`/`isContactMe`/
  `isLikelyNextCandidate`/`isApproaching` — with no UI changes yet; the list already renders in
  the server-sent (pre-sorted) order.
- Android: full native UI implementing issue #13's design doc, replacing every placeholder
  tab screen. `MainScreen` (top bar + controller list + footer status drawer, owning
  dialog/overlay visibility), `TopBar` (COM1/COM2/XPDR active row, standby row, tap-to-swap/
  tap-to-tune, Mode C and unread-message badges), `ControllerList` (facility-colored rows,
  badge stacking, tap-anywhere tune popover with COM/STBY grid and SELCAL dismiss),
  `FooterStatusBar` (connection/flight-plan status line, expandable subsystem-health and
  flight-plan-detail drawer), `ComTuningDialog`/`XpdrDialog` (numeric keypads with live
  channel-spacing-grid validation and snap-to-nearest-valid on commit —
  `util/ChannelSpacing.kt`, unit tested against the 25kHz/8.33kHz grids), `SettingsDialog`
  (SimBrief/Appearance/Plugin Connection/Channel Spacing/Frequency Keypad, Credits/Contribute
  attribution, wide two-column layout collapsing to one column in narrow split-screen),
  `NearbyAircraftDialog`, and the chat panel (`ChatPanelContent`, tab strip, SELCAL alerts
  merged into the RADIO tab's timestamped message list with hard-cut flashing, radio messages
  mentioning the pilot's own live vPilot callsign highlighted). Custom OKLCH-derived light/dark/
  system theme (`ui/theme/`), real Roboto Mono bundled for every frequency/code readout (not
  the generic monospace font, which renders 0 and O identically).
- Android: split-screen chat is a `SYSTEM_ALERT_WINDOW` overlay window
  (`ChatOverlayWindow`/`ChatOverlayHost`) that genuinely extends over the neighboring app's
  screen area, rather than being confined to this app's own window bounds — real
  multi-window detection (`layoutMode`/`splitSide`) replaces the design doc's demo-only toggle.
  Fullscreen mode instead shows chat as a persistent side panel.
- Android: background notifications (`HandoffNotifier`) for incoming private messages and
  "contact me" requests while the app isn't in the foreground; a keep-screen-awake control
  that tracks live battery charging state by default rather than a persisted preference.
- Plugin: `NearbyAircraftModel`, closing the "nearby aircraft" gap flagged in issue #13 — a
  new `nearbyAircraft` server message (callsign/type/distance, closest first) derived from the
  VATSIM data feed's pilot positions plus ownship telemetry.
- Plugin: `SelcalActiveModel`, clearing an active SELCAL alert once the pilot is genuinely
  tuned to the alerting frequency (not just a manual dismiss), plus a new `dismissSelcal`
  client command wired to the Android controller row's tune-popover.
- Plugin: `PilotSessionModel` captures the live, authoritative callsign/CID from
  `IBroker.NetworkConnected` — the callsign actually used for the VATSIM connection, distinct
  from the SimBrief OFP's callsign, which can't be verified against what's actually flying.
  `VatsimDataFeedClient`/`VatsimDataFeedModel` now also parse the feed's `pilots[]` section,
  cross-referencing the pilot's own callsign against it for the actually-filed VATSIM flight
  plan. The `flightPlan` message now sends `simbrief*` and `vatsim*` fields side by side so a
  client can detect a mismatch (or "connected but never filed") instead of blindly trusting the
  SimBrief OFP — surfaced in the Android footer's status drawer with color-coded warnings.
  `ControllerRankingModel`'s route match now prefers the VATSIM-filed plan when available,
  falling back to SimBrief otherwise.
- Plugin: `isHighlighted` field on `controllers` entries — a no-badge, ranking-neutral
  "worth rendering full color" signal, currently set only for an airborne/in-range CTR station
  and a route-matched ATIS, both tiers that `isLikelyNextCandidate`/`isApproaching` otherwise
  never touch.
- `docs/protocol.md`: new `operationProgress` message — a generic, reusable event stream (not
  resendable full state like every other message) for the plugin to report step-by-step status
  on a slow background operation, plus a client-side ~60s no-update timeout as a backstop for a
  dropped `finished` signal.
- Plugin: `OperationProgressModel`, broadcasting `operationProgress` over the WebSocket
  (`HandoffWebSocketServer`), and `VatGlassesDataModel`/`VatGlassesDataClient`, syncing the
  VATGlasses sector/boundary dataset (`github.com/lennycolton/vatglasses-data`) to a local disk
  cache at startup, reporting per-file sync progress through it — phase 1 of issue #9 (data
  acquisition only; point-in-polygon geometry and ranking integration are a follow-up).
- Android: a spinning progress indicator driven by `operationProgress`, reusing the footer's
  flight-plan-warning icon slot when collapsed and moving to its own status line in the
  expanded drawer (e.g. "Updating VatGlasses file 12/24") when open.

### Changed

- Plugin: the "Quit Handoff" button/confirmation dialog removed from
  `SettingsDialog` -- the foreground-service notification's own "Quit"
  action already covers this, and better (no need to open Settings first).
- Android: private-chat "nearby aircraft" list shows more rows (6, up from
  4) before scrolling.
- Plugin: dropped CTR's proximity-based `isLikelyNextCandidate` fallback — with no sector
  geometry yet (see #11), it could flag an unrelated, distant CTR controller as "next" purely
  by closest-lat/lon, regardless of phase of flight or actual range. CTR now only ever earns
  `isLikelyNextCandidate` via a genuine flight-plan route match; the proximity signal moved to
  the new cosmetic `isHighlighted` field instead (see Added).

### Fixed

- Android: a dropped plugin connection could go silently unrecoverable with
  no diagnostic trace at all (`HandoffWebSocketClient`'s `onFailure`/
  `onClosed` logged nothing). Added logging across the connection/reconnect
  path so a future drop is actually debuggable.
- Plugin: `isPinned` was never actually wired into the Android row-color
  decision -- a plain pinned row with no other flag fell through to the
  same desaturated "unrelated station" look as an untouched row.
