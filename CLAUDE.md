# Handoff

vPilot companion app: live VATSIM controller list + two-way chat on an Android EFB (home cockpit, tablet runs alongside other EFB apps, like charts or performance tools). Two components talking over a LAN WebSocket connection.

## Components

- `plugin/` — C# vPilot plugin, **.NET Framework 4.8** (not modern .NET — vPilot loads plugins in-process via reflection, and the host process itself is .NET Framework, so the plugin runtime is a hard constraint, not a choice). Implements `IPlugin` from `RossCarlson.Vatsim.Vpilot.Plugins`, referenced via a local `HintPath` into the user's vPilot install (that DLL is not redistributed in this repo). Embeds a local HTTP/WebSocket server (candidates: EmbedIO or HttpListener+Fleck) that the Android app connects to.
- `android/` — native Kotlin app (not a WebView/PWA — native chosen specifically for proper notification channels and reliable background WebSocket handling via a foreground service, which a browser tab can't do well).
- `docs/protocol.md` — the WebSocket contract. Treat this as the source of truth for message shapes if implementing any client (Android or a future iOS port), not whichever client's source happens to exist first.
- `docs/controller-ranking.md` — the full `RankedController` flag/criteria reference (`IsLikelyNextCandidate` vs `IsApproaching`, VATGlasses sector/boundary geometry parameters). See issue #8 for the base ranking design and issue #9 for the VATGlasses upgrade.

## Key architectural decisions (see repo issues for full reasoning)

- **Two independent Windows-side data sources feed the plugin's state**: vPilot's `IBroker` (controllers, chat, flight plan) and a separate SimConnect connection (own-aircraft position/speed/AGL/tuned COM frequency — `IBroker` does not expose ownship telemetry, only network traffic of other aircraft).
- **Controller list & chat** come entirely from `IBroker`: `ControllerAdded/Deleted/FrequencyChanged/LocationChanged` build the live station list; `PrivateMessageReceived`/`RadioMessageReceived`/`SendPrivateMessage`/`SendRadioMessage` give two-way chat (this plugin can send, not just relay — unlike push-notification-only plugins like vPilot-Pushover).
- **Flight plan**: `IBroker` has no flight-plan surface at all (confirmed against the full `RossCarlson.Vatsim.Vpilot.Plugins.xml` SDK doc — no `FlightPlanReceived` event, no `RequestFlightPlan()` method; that was speculative before the plugin skeleton existed and is now settled). Two independent sources instead: SimBrief API (available pre-connection, since it's not tied to the VATSIM session — what ranking falls back to when the source below isn't available yet) and the public VATSIM data feed's `pilots[]` (the pilot's own callsign, from `IBroker.NetworkConnected`, looked up in the feed — the actually-filed plan, preferred once available; also lets the client flag a SimBrief/VATSIM mismatch, or a forgotten-to-file plan, before Delivery does). See `docs/protocol.md`'s `flightPlan` message and `PilotSessionModel`/`ControllerRankingModel`.
- **Phase-of-flight detection** runs off SimConnect data (on-ground, groundspeed, AGL, vertical speed) to classify: parked / taxi-out / takeoff-climb / cruise / approach / landing-taxi-in. A "has taken off yet this session" flag disambiguates pre-departure vs post-arrival parked states, which look identical to raw sensors.
- **Controller priority ranking** is anchored on whatever frequency is actually tuned (matched via SimConnect `COM ACTIVE FREQUENCY` against `IBroker` controller frequencies, same compressed-integer format used in both APIs), not a predicted handoff moment:
  1. Currently-tuned controller (pinned)
  2. Same-category peers (callsign's last `_`-delimited token — handles split ground frequencies at large airports with zero airport-layout modeling)
  3. Next category in the standard VATSIM top-down chain for the current phase (DEL→GND→TWR→APP/DEP→CTR)
  4. Everything else
  Includes hysteresis (~10-15s) to avoid flapping, and a manual override so the pilot can pin a controller regardless of what the heuristic thinks.
- **Android interruption model**: explicitly *not* using the system Bubbles API (disliked its UX) and *not* trying to force split-screen (not possible for a third-party app to trigger without prior user-initiated pairing — Android intentionally disallows this since Android 10). Instead: a self-drawn `SYSTEM_ALERT_WINDOW` overlay (classic "chat heads" pattern), fully custom UI, viable because this is a sideloaded app with no Play Store policy constraints. Manual split-screen (user-initiated once) is a nice-to-have, not something the app can trigger itself.
- **Repo structure**: monorepo for now (single dev iterating on both sides of one protocol). Split `android/` (and future `ios/`) into their own repos once/if independent contributors join who don't want the Windows/.NET toolchain — splitting later is cheap (history-preserving), merging diverged repos back together later is not, so default to the reversible choice.

## Open items to verify empirically once the plugin skeleton exists

- Does `FlightPlanReceived`/`RequestFlightPlan()` still function post-removal of vPilot's in-client filing UI?
- Confirm plugin folder is `%LOCALAPPDATA%\vPilot\Plugins` for the dev's vPilot install, and that no stray `RossCarlson.Vatsim.Vpilot.Plugins.dll`/`.xml` copies exist there (known FSLabs-installer bug that breaks plugin loading).
