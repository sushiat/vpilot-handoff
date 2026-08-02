# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- Plugin installer (issue #95): `BouncyCastle.Crypto.dll` -- a hard runtime dependency for
  `HandoffCertificateStore`'s TLS cert generation -- was missing from the installer's
  `[Files]` list entirely, so a genuinely fresh install never actually got it. Uninstall
  also left an empty `RadioHost\` subfolder behind in the Plugins folder. Both fixed:
  the DLL is now installed and tracked, and `RadioHost\` is removed on uninstall.

## [0.3.0] - 2026-08-02

### Added

- Android: a bidirectional plugin/app version-mismatch dialog (issue #87). On
  every connect, the app compares the plugin's reported version
  (`subsystemStatus.pluginVersion`) against its own, in both directions, and
  — if they differ — shows resolution steps tailored to which side is behind:
  restart vPilot to apply an already-downloaded plugin update (or wait for
  the auto-updater), or re-sideload/update via Obtainium for a stale app.
  Dismiss is remembered per exact version pair, so it stays quiet until
  either side actually changes.
- Plugin: the auto-update flow now gives the pilot visible feedback inside vPilot
  (issue #85). The silent installer runs with `/SILENT` instead of `/VERYSILENT`,
  so Inno Setup's install progress window is shown while it waits for vPilot to
  close and applies the update — still zero clicks and no wizard pages (the
  changelog `InfoBeforeFile` page stays suppressed). And the first plugin load
  after an update shows a branded "Handoff updated to {version}" confirmation
  (a modeless window in the same style as the pairing/update-prompt dialogs),
  driven off the existing `update-applied.json` marker — previously that update
  detection was reported only to the Android app and vPilot's `/dbgwin`, invisible
  during a normal startup. The install-confirm dialog's copy now also explains
  that the update finishes once vPilot is closed.
- An adjustable update-interval setting (Fast/Normal/Slow, issue #88) for the
  plugin's radio/telemetry SimConnect poll cadences and the WebSocket
  broadcast to the Android app. Persisted plugin-side and applied live (no
  restart), edited from a new "Update interval" selector in the Android
  Settings dialog — same save/persist pattern as the existing SimBrief
  credentials setting. The SimBrief user ID and username fields in Settings
  now sit side by side to make room for the new selector without introducing
  scroll on typical tablet widths.

### Fixed

- Plugin: `pluginVersion` sent to the Android client was a hardcoded `"0.1.0"`
  literal, disconnected from `Handoff.Plugin.csproj`'s `<Version>` — it stayed
  stuck at `"0.1.0"` even after the 0.2.0 release, misleading the app's
  bottom-drawer version display (issue #86). Now read at runtime from the
  plugin assembly's `AssemblyInformationalVersion` (auto-populated from
  `<Version>` at build time), so it stays in sync with releases automatically.

## [0.2.0] - 2026-08-02

### Changed

- Plugin installer refinements (issue #79): the setup `.exe` and the Windows
  Apps/Add-Remove-Programs entry now carry the Handoff icon
  (`plugin/Assets/handoff.ico`, rasterized from a new committed
  `handoff.svg`); the installer's own uninstaller (`unins000.exe`/`.dat`) and
  icon now live in a dedicated `%LOCALAPPDATA%\Handoff` folder instead of the
  vPilot Plugins folder, keeping the latter clean of everything but the plugin
  (the auto-update marker still lands next to the plugin DLL, and a stale
  pre-0.1.1 `unins000.*` left in the Plugins folder is cleaned up on upgrade);
  and non-silent installs now show the release's changelog on the previously
  blank wizard page (`InfoBeforeFile`, fed by the extracted release notes
  pandoc-rendered to RTF in CI, a plain-text fallback locally). Silent
  auto-updates are unaffected.
- Android: a fresh install now defaults to 8.33 kHz channel spacing instead of
  25 kHz (issue #80) — the modern European standard, so a new user no longer has
  to change it by hand. Only seeds a never-configured install; any existing saved
  channel-spacing preference is left untouched.

### Added

- Plugin/Android: a freshly-paired tablet auto-adopts the plugin's already-stored
  SimBrief credentials instead of making the pilot re-type them (issue #80). The
  plugin includes its persisted `simbriefUserId`/`simbriefUsername` in the
  `authResult` message **only on the pairing-code success path** — never the token
  (routine reconnect) path or any failure path, so credentials are pushed down
  exactly once, right after pairing. The client then reconciles: adopt the
  plugin's values when the tablet has none, no-op when they already match, and
  when the tablet holds *different* credentials keep the tablet's and push them
  back up (`setSimbriefCredentials` + `refreshFlightPlan`) so both sides converge
  on the tablet's value with no silent data loss.

## [0.1.0] - 2026-08-01

### Added

- Android: bucket 8c's CTR ETA readout (issue #71) is now shown to the pilot
  as an "ETA {n}m" badge on the controller list, attached to whichever row
  already carries NEXT/NEXT? — `ControllersMessage.etaMinutes` was already
  decoded and unit-tested but had no pilot-facing home until now.
- Plugin: an on-ground, pre-takeoff sanity gate (issue #68) — if ownship's
  position is more than ~8nm from the filed origin's own coordinates
  (newly parsed from SimBrief's `origin.pos_lat`/`pos_long`), the loaded
  plan is flagged as not matching where the aircraft is physically sitting
  (a stale plan left over from a previous flight, wrong airport picked,
  etc) and dropped from route-projected approach/CTR prediction until it's
  refreshed or the aircraft is repositioned. Surfaced to the Android app as
  a "WRONG ORIGIN" row in the bottom drawer, alongside the existing
  MISSING/mismatch warnings. `TakeoffAglThresholdFeet` lowered from 3000 to
  200 so this (and the existing origin->destination route-airport flip)
  actually fires for GA flights that may never climb past ~1000ft AGL, and
  `_hasTakenOffThisSession` now resets on a genuine SimBrief plan change
  (different origin/destination) while still on the ground, so a GA
  flight's next leg in the same session starts back at origin-route logic.
- Plugin: `ControllerDebugExplain` now includes a `subBucket` field (e.g.
  `"6c"`, or `"6a, 6e"` when a controller is both highlighted via one row
  and flagged `IsNext`/`IsLikelyNext` via another) mapping buckets 6/7/8
  back onto `docs/controller-ranking.md`'s lettered sub-rows (6a-6e, 7a-7c,
  8a-8b), shown in the Android debug window and saved debug snapshots.
- Plugin: the VATSIM data feed's `cid` is now parsed for filed flight plans
  (`pilots[]`, same field already read for controllers) and compared
  against our own live connection's cid — a callsign lookup alone can't
  tell "this is us" from "this feed entry merely has our callsign string"
  (a lagged snapshot mid-reconnect, a collision window). Surfaced as a
  "CID MISMATCH" row in the Android bottom drawer; purely informational,
  doesn't change which plan is used for route matching.
- Plugin: COM1/2 transmit/receive and Mode C are now gated on
  `CIRCUIT NAVCOM1 ON` (issue #55) — some aircraft's own systems don't
  reset `COM TRANSMIT`/`COM RECEIVE`/`TRANSPONDER STATE` to off when
  power is cut, so those are no longer trusted on their own; all of them
  are forced off together whenever that circuit reads unpowered.
- Android: the MSG button's unread badge is wired up (issue #32) — per-tab
  unread counts now increment on incoming radio/broadcast and private
  messages, cleared on switching to a tab or opening the chat panel to view
  the currently-active one (fullscreen counts a tab as "viewed" whenever
  it's on screen, not just while a since-unused `chatOpen` toggle happens
  to be true — that flag only ever meant something for the split-screen
  overlay). A tab with an unread message directed at the pilot (a private
  message, or a radio message mentioning `ownCallsign`) now flashes the MSG
  badge orange/hazard-yellow every 500ms, reusing the same hard-cut flash
  cadence as the controller list's contact-me row flash (both now share
  `rememberFlashPhaseA` from `HandoffTheme.kt`); undirected/ambient unread
  stays a static blue. Also re-centers the unread count on the message
  bubble icon's own body rather than its full bounding box — the SVG path's
  bottom-left tail was pulling the box's geometric center down-left of
  where the bubble visually reads as centered.
- Plugin: `ControllerRankingModel.RemainingWaypoints` (feeding the route-
  projected approach/convergence checks in buckets 7c/8) replaced with
  abeam-point geometric waypoint sequencing (issue #22), the same technique
  real FMS use for direct-to legs — a persisted, only-ever-advancing
  waypoint index tested against the great-circle course from a fixed
  anchor, instead of picking the nearest waypoint by raw distance every
  tick. Fixes a direct-to clearance that cuts a corner close to a bypassed
  waypoint still reading as "nearest" and projecting the remaining route
  through a stale leg. Reuses the existing commit/pending/hysteresis
  pattern to absorb momentary abeam-plane crossings mid-turn (e.g. holding
  patterns) without committing to them — the previously-tried heading-vs-
  bearing check broke exactly that case and was reverted.
- Plugin/Android: destination changes seen on the VATSIM feed no longer
  silently drop the filed route from approach prediction the instant
  they're noticed — they now arm a pending-confirmation state
  (`ControllerRankingModel.PendingDiversionDestination`, new
  `diversionPending` WebSocket message) and the route keeps being used
  until the pilot actually confirms via new `confirmDiversion`/
  `dismissDiversion` commands. Android shows a "Confirm diversion?" dialog
  (`DiversionConfirmDialog`, mirroring the existing pairing-code prompt's
  state-driven pattern) whenever one comes in; dismissing keeps the filed
  route and won't re-prompt for that same destination again.
- Plugin: reads COM1/COM2 transmit/receive-select state from SimConnect
  (`COM TRANSMIT:1/2`, `COM RECEIVE:1/2`) and broadcasts it in the `radioState`
  WebSocket message as `com1/2TransmitEnabled`/`com1/2ReceiveEnabled` (issue
  #20). Also adds plugin-internal write capability (SimConnect events +
  RadioHost IPC + `RadioStateModel` methods) for selecting the active
  transmitter and toggling per-COM receive, ready for a future client command
  once the app-side control UI is designed — not yet exposed over the
  WebSocket protocol or in the Android app.
- Android: controller row-color theme editor (issue #21) — a "🎨" entry point
  in Settings' Appearance section opens a dialog to customize the 6 facility
  hues (DEL/GND/TWR/APP-DEP/CTR/ATIS), the text-contrast threshold, where
  default (non-highlighted) rows sit on a white↔highlight↔black continuum,
  and an extra dark-theme-only darkening offset applied to every row. Ships
  with Default plus deuteranopia-safe/protanopia-safe presets, and supports
  multiple named saved themes (SharedPreferences-persisted JSON, local to the
  device). Preview cards render through the same real row-coloring/badge
  logic as the live list (not flat swatches), and hue selection uses a
  drag-around hue wheel instead of a linear slider. COM1/COM2 tuned stay
  fixed teal/rose constants, not user-editable — same as the contact-me
  alert yellow and SELCAL red.
- Android: controller list groups (tuned / other flagged-highlighted / plain,
  see `docs/controller-ranking.md`'s buckets) get extra spacing between them
  for a clearer at-a-glance separation, and a "Hide tuned" checkbox (next to
  the "CONTROLLERS · N" count, persisted) hides `isCurrent`/`isStandbyTuned`
  rows — once a station is actually tuned, chat with it happens over the
  radio, not this app's private chat. Pinned rows stay visible either way.
- Android: the COM1/COM2 top-bar buttons (active and standby) show the
  currently-tuned station's callsign as a small line below the frequency,
  Garmin-style — looked up by frequency match against the live controller
  list, blank (not collapsed) when nothing matches so all three buttons in a
  row stay height-aligned.
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
- Plugin: auto-updater (issue #34) — checks this repo's GitHub releases once at plugin startup
  (own background thread, not tied to VATSIM connect, so a pilot setting up the sim notices
  before committing to a session), downloads a newer `Handoff-Setup-*.exe` installer and
  verifies it against GitHub's own per-asset sha256 digest, then asks the pilot to confirm via
  a branded local Windows dialog (`HandoffUpdatePromptWindow`, sharing `HandoffPairingWindow`'s
  logo/header chrome) before launching it silently. The same installer
  (`plugin/installer/Handoff-Setup.iss`, built with Inno Setup) now also handles first-time
  install — no admin rights either way, since it resolves the Plugins folder from
  `HKCU\Software\vPilot\Install_Dir` and waits for vPilot to exit before copying files —
  replacing the old manual zip-extract-into-Plugins-folder instructions entirely. Update
  progress and a one-shot "updated to X.Y.Z" notice after an upgrade reuse the existing
  `operationProgress` message, no new wire format needed.
- Android: a startup check against the same GitHub release for a newer app version, shown as a
  dismissible notice for manually sideloaded installs and suppressed entirely when Obtainium
  (which already handles updates) is detected as the installing package
  (`getInstallSourceInfo`/`getInstallerPackageName`).
- `docs/protocol.md`: new Compatibility section documenting that the contract stays
  additive/backward-compatible by design rather than version-gated now that the plugin and
  Android app can update independently of each other, plus a Changelog section tracking future
  message-shape changes.
- Plugin/Android: session-only debug mode (issue #65) — a hidden 7-tap toggle on the Settings
  dialog's title (no visible affordance until it fires, so it isn't randomly discoverable)
  sends `setDebugMode`, after which the version string in the top bar opens a draggable
  `SYSTEM_ALERT_WINDOW` overlay showing live per-controller ranking explain data (bucket,
  reason, distance, VATGlasses/vatspy match, hysteresis state) plus plugin-wide context
  (phase of flight, ownship telemetry, route waypoints with bearing/distance from ownship,
  ETA detail) and a lean always-visible "Systems" column (radio/SimConnect, VATSIM feed,
  SimBrief, VATGlasses/vatspy load state, pairing/connection health). A "Save debug snapshot"
  button (`saveDebugSnapshot`/`debugSnapshotSaved`) dumps a full point-in-time JSON of every
  plugin subsystem — including the abeam-point route sequencer's raw anchor/waypoint-projection
  state and VATGlasses/vatspy containment detail that never rides the wire — to
  `%LOCALAPPDATA%\Handoff\debug-snapshots\`, with an optional view-scoped screenshot
  (`attachDebugSnapshotScreenshot`, `PixelCopy` against this app's own window only, never a
  full-display capture) saved alongside it. New nullable `controllers[].debug`,
  `controllers.debug`, and `subsystemStatus.systemsDebug` wire fields, all `null` unless debug
  mode is on. See `docs/debug-snapshot.md` for the snapshot file's full shape.
- Debug window refinements (issue #73), found while using the above to chase down a
  waypoint-sequencing lag: (a) an opt-in "Full-device" checkbox in the debug window's title
  bar requests `MediaProjection` consent once per check (not per snapshot) and captures the
  whole display instead of just Handoff's own window — useful for seeing a neighboring
  split-screen EFB app alongside Handoff's state. Requires Android 14+'s foreground-service
  mediaProjection type, added to `HandoffConnectionService`'s existing `dataSync` type rather
  than standing up a second service. (b) Snapshots can now be named after the fact — once a
  save round trip completes, the save button swaps for an inline name field; submitting sends
  a new `nameDebugSnapshot` command (`debugSnapshotNamed` reply) that stores the name in the
  JSON and renames both the `.json`/`.png` files, reusing the existing 10-minute
  `ScreenshotCorrelationWindow`; a "Skip" button next to "Save name" dismisses the field without
  naming it, leaving the snapshot exactly as saved. (c) The debug window's Route line now shows
  which mechanism (`ControllerRankingModel.SequenceRemainingWaypoints`) last advanced the
  committed waypoint index — the normal along-track sweep or issue #66's proximity catch-up
  fallback — and when, surfaced in both the live view and the snapshot file.

[Unreleased]: https://github.com/sushiat/vpilot-handoff/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/sushiat/vpilot-handoff/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/sushiat/vpilot-handoff/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/sushiat/vpilot-handoff/releases/tag/v0.1.0
