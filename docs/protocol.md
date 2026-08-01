# Handoff protocol

The WebSocket contract between the vPilot plugin (server) and any client (Android, or future
ports). This is the source of truth for message shapes — client implementations should
conform to this, not to whichever client's source happens to exist first.

## Compatibility

Plugin and client can now update independently — the plugin auto-updates itself (issue #34) on
its own schedule, and the Android app updates via Obtainium or a manual sideload notice, neither
tied to the other anymore. Historically both shipped from the same tagged release and were
always in lockstep (`release.yml`'s `validate` job still enforces matching version numbers at
release time); that guarantee no longer holds once either side is running, so version skew
between the two is a normal state to design for, not an edge case.

The contract is additive/backward-compatible by design, not version-gated — a client refusing to
connect over a version mismatch is deliberately not how this works. Both sides already parse
defensively (Android's JSON decoding uses `ignoreUnknownKeys = true`; the plugin's C# side reads
optional fields with null-coalescing), and that's meant to stay the actual contract going
forward: new fields are added nullable/optional with sensible defaults on the older side,
existing field meanings are never repurposed, and a field that needs to go away gets deprecated
(left in place but unused) for a release or two rather than dropped outright. New capabilities
show up as new message types or new optional fields, not changed shapes of old ones.

This works because there's exactly one plugin talking to one pilot's own paired device(s), not a
public API serving unknown third-party clients — the usual reason to hard-gate on version
(protecting against incompatible strangers) doesn't apply here. A hard refuse-to-connect gate
would also be a worse failure mode than a missing field: it could turn "the tablet hasn't
auto-updated yet" into "no controller list in the cockpit" at the worst possible moment. If a
truly unavoidable breaking change ever comes up, the fallback is a soft "your Handoff app may be
out of date" warning (same pattern as the plugin's own update-available prompt), not an outright
connection refusal — and even that's a one-off case-by-case call, not a general mechanism worth
building ahead of time.

## Changelog

Tracks changes to this document's message shapes specifically, not every plugin/app feature
release — see the top-level `CHANGELOG.md` for that. Only gets an entry here when something in
this contract actually changes shape; a release with no protocol changes adds nothing below.

### Unreleased

Issue #73 -- debug window refinements. New client→server `nameDebugSnapshot`. New server→client
`debugSnapshotNamed`. New nullable fields on the top-level `debug` object:
`lastWaypointAdvanceMechanism`, `lastWaypointAdvanceAt`, null unless debug mode is on (same
lifetime as the rest of `debug`). `attachDebugSnapshotScreenshot`'s `screenshotPngBase64` can now
optionally be a full-device capture rather than always view-scoped, per the pilot's own opt-in --
no shape change, just a clarified doc note (issue #73a).

### [0.2.0] - 2026-08-01

Issue #65 -- debug mode. New client→server `setDebugMode`, `saveDebugSnapshot`,
`attachDebugSnapshotScreenshot`. New server→client `debugSnapshotSaved`. New nullable fields:
`controllers[].debug`, `controllers.debug` (top-level), `subsystemStatus.systemsDebug` -- all
`null` unless debug mode is currently on, per this document's Compatibility section.

### [0.1.0] - 2026-07-31

Initial public release.

## Discovery

The plugin's LAN IP isn't known in advance, so it also listens for a UDP broadcast discovery
request on port `48766` — plain UDP, not mDNS/Bonjour, so no extra dependency is needed on
either side. A client broadcasts the ASCII text `HANDOFF_DISCOVER` to `255.255.255.255:48766`;
the plugin unicasts back `{"port":48765,"fingerprint":"AB:12:CD:34:..."}` to the sender. This
listener runs for the plugin's whole lifetime (not tied to the VATSIM connection), same as the
WebSocket server below, so a client can discover it even before the pilot connects.

`fingerprint` is the SHA-256 hash of the plugin's TLS certificate's public key, formatted as
uppercase colon-separated hex — see Connection below. It's included here purely as a
same-round-trip convenience; the actual trust-on-first-use decision is made against whatever
certificate is presented during the TLS handshake itself, not against this discovery hint.

Discovery isn't guaranteed to work on every network (some routers apply AP client isolation or
block broadcast traffic), so clients should keep a manually-entered IP as a fallback.

## Connection

The plugin listens on `wss://<pc-lan-ip>:48765/` (Fleck-based, TLS 1.2, plain TCP otherwise —
no HTTP handshake beyond the WebSocket upgrade, and no admin/URL-ACL setup needed on the
Windows side). Not yet configurable; a fixed port for v1.

The certificate is self-signed, generated (and cached across restarts) by the plugin on first
run — there's no CA involved, since this is a local, self-discovered pairing between one plugin
instance and one client instance, not a public-facing service. Its Subject CN is the Windows
machine name.

TLS alone only authenticates the *server* to the client — encryption plus "am I talking to the
right PC," not "is this the right pilot's device talking to me." The plugin will complete a TLS
handshake with anyone on the LAN; a client's certificate fingerprint check (below) catches a
spoofed/MITM server, but does nothing to stop an unrecognized client from opening a connection
and issuing commands. Device-level authorization (pairing code + bearer token, below) is what
actually gates that.

### Certificate pinning (silent)

The client pins the certificate's fingerprint (SHA-256 of the public key, uppercase
colon-separated hex) itself, the same TOFU model SSH uses for unknown hosts — but this
happens **silently**, as a side effect of a successful pairing (below), never as its own
prompt. A raw hash means nothing to the overwhelming majority of pilots installing this app, so
asking them to eyeball-compare one and tap "Trust" is nothing but a rubber stamp in practice — a
pairing code, read off the PC's own screen, is what actually proves you're pairing with the
right machine (see below).

A later mismatch between the pinned fingerprint and what's presented (a swapped/rogue server
now answering on the same IP, or a legitimately reinstalled plugin) forces the full
pairing-code flow again, even if the client is still holding an otherwise-valid bearer token —
the certificate identity changed, so the client can no longer assume the token's issuer is who
it used to be.

### Device authorization (pairing code + bearer token)

No application data — not `controllers`, not `chat`, nothing — is sent to a socket until it's
authenticated. An unauthenticated socket is entirely mute from the plugin's side; there is no
reason to prepare or send anything to a client the plugin doesn't yet recognize.

The client's first message on every connection MUST be `authenticate`:

```json
{"type": "authenticate", "token": "<previously-issued bearer token>", "deviceId": "<stable per-install id>"}
{"type": "authenticate", "pairingCode": "123456", "deviceId": "<stable per-install id>"}
{"type": "authenticate", "deviceId": "<stable per-install id>"}
```

Send `token` if the client already holds one for this exact pinned certificate fingerprint.
Send `pairingCode` once the pilot has read one off the plugin's on-screen pairing window and
typed it into the client. Send neither (bare `authenticate`) to mean "I have nothing yet, tell
me what you need" — this is also what a client should send on its very first-ever connection to
a given plugin, before any pairing code has been entered.

`deviceId` is optional but recommended: a stable identifier for this specific app install (the
Android client uses `Settings.Secure.ANDROID_ID` — no special permission needed, and it resets
along with the client's own local storage on uninstall, which is exactly when its old token
stops being relevant anyway). When a `pairingCode` pairing succeeds and `deviceId` was sent, the
plugin drops any of its previously paired-client entries that share the same `deviceId` before
adding the new one — otherwise every re-pair from the same physical device (e.g. after the
plugin's certificate changed and forced a re-pair) leaves a stale, never-cleaned-up entry behind
forever. Not a real IMEI or any hardware identifier — those need permissions this app has no
business asking for, and aren't actually more correct here than an app-generated install id.

The plugin replies with `authResult`:

```json
{"type": "authResult", "success": true, "token": "<newly-issued bearer token>"}
{"type": "authResult", "success": false, "reason": "pairingRequired"}
{"type": "authResult", "success": false, "reason": "invalidCode"}
```

- `success: true` — the socket is now authenticated. A fresh bearer token accompanies it; the
  plugin persists a hash of that token (not the plaintext) against a list of paired clients —
  multiple devices can be paired to one plugin at once, there's no hard single-device limit.
  The client should persist this token and send it on every future connection instead of
  re-pairing.
- `reason: "pairingRequired"` — the plugin doesn't recognize the presented token (missing,
  unknown, or revoked). It now displays a short-lived numeric pairing code in a small on-screen
  window (so a pilot who's never heard of a debug console can still see it); the client should
  show its own "enter the code shown on the PC" prompt and resend `authenticate` with
  `pairingCode` once the pilot has typed it in.
- `reason: "invalidCode"` — the submitted `pairingCode` didn't match the plugin's currently
  displayed one (mistyped, or it expired — codes are only valid for a few minutes). The client
  should let the pilot retry.

Once a socket is authenticated, the plugin immediately sends a `controllers`, a `chat`, and a
`radioState` message — the client's full current state, with no need to wait for the next
change. After that, each message type is re-sent in full (not as an incremental diff) whenever
its backing state changes. This is deliberately simple: resending full state is cheap on a LAN
and avoids an entire class of missed-message/reconnect bugs that incremental delivery would
introduce.

`controllers` is the one exception to "resent whenever its backing state changes": the
ranking recompute itself stays fully event-driven/reactive (any controller/radio/flight-plan/
VATSIM-feed change triggers an immediate recompute), but the *wire broadcast* of the result is
decoupled onto a fixed ~1-second timer instead. Diffing "did anything meaningful change" isn't
tractable for SimConnect-driven fields (distance/heading/altitude feed the bucket 6-9 geometry
continuously) without just running the full computation anyway, so the broadcast simply goes
out on a steady cadence rather than being triggered per-change.

All JSON fields are camelCase. All frequencies are vPilot's compressed-integer format
throughout the protocol (e.g. `123.725` MHz → `23725`) — never plain MHz. All timestamps are
ISO 8601 UTC.

## Server → client messages

### `controllers`

The full current controller list, pre-sorted by the plugin's priority ranking (see below).
Nothing is ever hidden except a recently-disconnected station within its brief grace window
(an FSD blip, not a real disconnect -- see `docs/controller-ranking.md`) -- every other
connected station appears exactly once, just reordered, with boolean flags for the Android app
to colour-code/badge by. Broadcast on a fixed ~1-second cadence rather than per-change (see
"Connection" above).

```json
{
  "type": "controllers",
  "etaMinutes": null,
  "debug": null,
  "controllers": [
    {
      "callsign": "EGLL_TWR",
      "frequency": 23725,
      "latitude": 51.4775,
      "longitude": -0.4614,
      "cid": 1234567,
      "name": "John Smith",
      "facility": 4,
      "rating": 5,
      "stationName": "Heathrow Tower",
      "textAtis": ["Heathrow Tower", "INITIAL CLIMB AS CHARTED chartfox.org/EGKK", "Submit feedback at vats.im/atcfb"],
      "requestsContactMe": false,
      "isCurrent": true,
      "isContactMe": false,
      "isHighlighted": false,
      "isNext": false,
      "isLikelyNext": false,
      "isPinned": false,
      "isStandbyTuned": false,
      "isSelcalActive": false,
      "debug": null
    }
  ]
}
```

`cid`/`name`/`facility`/`rating` come from the public VATSIM data feed (not `IBroker`, which
doesn't expose them) and are `null` until that feed's ~15s-lagged enrichment solidifies for a
given callsign. `facility` is VATSIM's own enum (`2=DEL, 3=GND, 4=TWR, 5=APP/DEP, 6=CTR`);
`rating` is display-only, never used in ranking.

`stationName` is a facility/airport display name (e.g. "Heathrow Tower" for `EGLL_TWR`). Two
sources, in preference order (issue #11): the controller's own live ATIS/info text (the public
VATSIM data feed's `text_atis`) when it parses cleanly into a name (`VatAtisStationNameExtractor`
-- the controller's own live self-description, preferred when present and confidently parsed),
else a name composed from vatspy-data-project's FIR/airport names plus a small
suffix-by-tier-and-region table (`VatSpyStationNaming.ComposeDisplayName`) -- see
docs/controller-ranking.md's "vatspy station names and FIR-polygon fallback" section. `null`
whenever neither source yields anything confident -- clients should keep the callsign-suffix
parsing fallback (Tower/Ground/Delivery/etc.) for those cases rather than assuming this field is
always populated.

`textAtis` is the controller's raw ATIS/info lines, unprocessed (the VATSIM data feed's own
`text_atis` array, multi-line) -- `stationName` above is a derived summary of just its first
line; this is the full text for richer client UI to show later (e.g. a COM-tune-menu detail
panel -- not yet built on the Android side as of this field's addition). `null` whenever the
controller hasn't set one or the feed omits the field for that callsign.

Ranking order is entirely a plugin-side decision -- clients must render the list in exactly the
order received and never re-sort or re-tag client-side. Every flag below is computed and sent
by the plugin; the client only ever reads them, it never re-derives a badge from other data it
happens to have (e.g. comparing a controller's frequency against `radioState`'s own standby
fields to guess `isStandbyTuned` itself). The ranking algorithm (9 numbered "buckets" -- tuned,
standby, contact-me, SELCAL, pinned, then ground/TWR-APP/CTR relevance, then everything else) is
documented in full in `docs/controller-ranking.md`, not here: it can change between plugin
versions without any client update needed, as long as the fields below keep meaning what they
say.

The boolean fields are what clients actually consume, each driving its own badge/highlight:

- `isCurrent`: this is the tuned controller (COM1 and COM2 can each independently match a
  different online station -- both get `isCurrent`). A manually pinned controller does **not**
  set this (see `pinController` below) -- pinning must never displace whatever's actually tuned.
- `isStandbyTuned`: loaded into COM1 or COM2 standby, ready to swap to active the moment a
  handoff comes.
- `isContactMe`: this controller sent an outstanding "contact me" request.
- `isSelcalActive`: a currently-active SELCAL alert. Unlike `isContactMe`, tuning the alerting
  frequency does **not** clear this -- only an explicit `dismissSelcal` or the alert's own expiry
  does (see `dismissSelcal` below).
- `isPinned`: a manual bookmark (see `pinController`/`clearPinnedController` below) -- its own
  ranking bucket, never a stand-in for `isCurrent`. Persists even if the pinned station becomes
  current/standby (both flags can be true at once); only cleared by an explicit unpin or the
  controller going offline past its hidden-expiry window.
- `isHighlighted`: relevance/visibility -- "worth seeing," independent of whether it's the one to
  contact next. Driven by flight-plan match, proximity, or VATGlasses sector polygon
  containment/convergence, depending on tier and phase of flight -- see
  `docs/controller-ranking.md` buckets 6-8 for the exact criteria.
- `isNext`: confident and actionable -- exactly one qualifying candidate, unambiguous.
- `isLikelyNext`: the same underlying signal as `isNext` but confidence-capped, either because
  multiple candidates are genuinely tied, or because route-relevance itself is unconfirmed (not
  on the flight plan) even when the geometry is unambiguous. Clients should render this as a
  visibly softer/less certain variant of the `isNext` badge (e.g. "NEXT?" vs "NEXT"), not an
  unrelated badge.

`etaMinutes` is a top-level field on the message itself (not per-controller) -- an estimate of
minutes remaining to the closest bucket-8-qualifying CTR sector, available during level flight
(any altitude) or while climbing/descending above FL150, `null` otherwise (including whenever
nothing currently qualifies for bucket 8 at all).

#### `debug` (issue #65)

Both the top-level `debug` object and each controller's own `debug` object are `null` unless the
pilot has turned on debug mode this session (`setDebugMode` below) -- see
`docs/controller-ranking.md`'s "Debug explain view" section for the full field reference and how
each field maps back to the numbered buckets. Deliberately plain-language, not the ranking
internals (route anchor coordinates, VATGlasses/vatspy sector ids, tie-band math) -- those live
only in the debug snapshot file (`saveDebugSnapshot` below), not on the wire.

```json
{
  "debug": {
    "phaseOfFlight": "cruise",
    "hasTakenOffThisSession": true,
    "ownshipLatitude": 51.02,
    "ownshipLongitude": 1.87,
    "ownshipAltitudeTrue": 45000,
    "ownshipAltitudeAgl": 44700,
    "ownshipGroundspeedKt": 461,
    "ownshipHeadingTrue": 68,
    "ownshipTrackTrue": 68,
    "com1TunedCallsign": null,
    "com2TunedCallsign": null,
    "activeRouteWaypoint": "KONAN",
    "lastPassedWaypoint": "GASBA",
    "activeRouteWaypointBearingTrue": 355,
    "activeRouteWaypointDistanceNm": 55.3,
    "lastPassedWaypointBearingTrue": 178,
    "lastPassedWaypointDistanceNm": 12.3,
    "etaCalculationDetail": "No bucket-8 candidate currently qualifies.",
    "lastWaypointAdvanceMechanism": "alongTrackSweep",
    "lastWaypointAdvanceAt": "2026-08-01T10:15:32Z"
  }
}
```

`lastWaypointAdvanceMechanism`/`lastWaypointAdvanceAt` (issue #73c) -- which mechanism last
advanced the committed waypoint index, and when: `"alongTrackSweep"` for the normal
anchor-relative along-track sweep, `"proximityCatchUp"` for the issue #66 proximity fallback that
recovers sequencing after a direct-to desyncs the route anchor. Both `null` until the first
advance of the session.

Per controller:

```json
{
  "debug": {
    "bucket": 8,
    "bucketName": "Airborne CTR relevance",
    "subBucket": "8a",
    "reason": "Bucket 8a (Airborne CTR relevance): VATGlasses sector containment, 47.2nm -- IsHighlighted only.",
    "distanceNm": 47.2,
    "vatGlassesSectorMatch": true,
    "vatSpyPolygonMatch": false,
    "routeMatch": false,
    "hysteresisState": "stable",
    "hysteresisPendingBucket": null,
    "hysteresisPendingSince": null,
    "candidateRank": null
  }
}
```

### `chat`

The full chat log and SELCAL alert list, resent whenever a new message (incoming or
outgoing) or alert arrives.

```json
{
  "type": "chat",
  "messages": [
    {
      "channel": "private",
      "direction": "incoming",
      "peer": "EGLL_TWR",
      "text": "cleared for takeoff",
      "frequencies": null,
      "timestamp": "2026-07-25T10:15:30Z"
    },
    {
      "channel": "radio",
      "direction": "incoming",
      "peer": null,
      "from": "EGLL_TWR",
      "text": "traffic in the pattern, report final",
      "frequencies": [23725],
      "timestamp": "2026-07-25T10:16:05Z"
    }
  ],
  "selcalAlerts": [
    {"from": "EGLL_TWR", "frequencies": [23725], "timestamp": "2026-07-25T10:16:00Z"}
  ]
}
```

`channel` is one of `private` | `radio` | `broadcast`. `direction` is `incoming` | `outgoing`.
`peer` is the other party's callsign for `private`/`broadcast`, `null` for `radio` (the
frequency identifies the channel instead). `from` is the transmitting station's callsign for
incoming `radio` messages, `null` otherwise (private/broadcast already carry this in `peer`).
`frequencies` is populated only for `radio` messages and SELCAL alerts, `null` otherwise.

### `radioState`

Ownship COM1/COM2 active + standby tuned frequency, transponder code, Mode C state, and
COM1/COM2 transmit/receive-select state, resent whenever any of them change.

```json
{
  "type": "radioState",
  "com1Frequency": 23725,
  "com2Frequency": null,
  "com1StandbyFrequency": 21000,
  "com2StandbyFrequency": null,
  "modeCEnabled": false,
  "transponderCode": 1200,
  "com1TransmitEnabled": true,
  "com2TransmitEnabled": false,
  "com1ReceiveEnabled": true,
  "com2ReceiveEnabled": false
}
```

All frequency fields are `null` until the first SimConnect read completes (or if the
SimConnect helper process isn't running/connected). `transponderCode` is a plain decimal
squawk (e.g. `1200`), not BCD -- that encoding is purely a SimConnect-boundary detail on the
plugin side.

`com1TransmitEnabled`/`com2TransmitEnabled` and `com1ReceiveEnabled`/`com2ReceiveEnabled` are the
audio panel's transmit/receive *selection* state (SimConnect's `COM TRANSMIT:n`/`COM RECEIVE:n`),
not a live "audio currently playing" indicator -- no such live signal is exposed anywhere (not by
IBroker, not by SimConnect; VATSIM voice runs entirely inside vPilot's own internal audio engine,
which has no exposed hooks at all). Transmit is normally mutually exclusive between COM1/COM2
(real avionics only let one COM be the transmitter at a time), but the plugin doesn't validate or
enforce that -- it just forwards whatever the sim reports. Receive is genuinely independent per
COM: both `true` at once is a normal "listening on both" state. Both transmit and both receive
fields can be `false` at once too (radio/avionics powered off, or before the first SimConnect
read completes). See `selectCom1Transmitter`/`selectCom2Transmitter` and
`setCom1ReceiveEnabled`/`setCom2ReceiveEnabled` below for the client commands that change
these.

### `flightPlan`

Two independent views of the flight plan, both surfaced so the client can flag a mismatch
instead of silently trusting one:

- `simbrief*`: fetched from the SimBrief API (`IBroker` has no flight-plan members at all -- see
  CLAUDE.md). Available before the pilot even connects to VATSIM, since it doesn't depend on the
  connection -- this is what ranking's route match falls back to when the VATSIM one below isn't
  available yet. All `simbrief*` fields are `null` until the first successful fetch.
- `vatsim*`: the pilot's actual filed VATSIM flight plan, found by cross-referencing
  `vatsimCallsign` (the live callsign from `IBroker.NetworkConnected` -- the callsign actually
  typed into vPilot's connect dialog, not whatever was typed when the SimBrief OFP was generated)
  against the public data feed's `pilots[]`. This is the more authoritative source once it
  exists, and is what ranking's route match prefers when available.

Resent whenever any of the three changes: a SimBrief fetch (startup, or triggered by
`refreshFlightPlan`) succeeds, the VATSIM connection's callsign changes, or the public data
feed's next poll (~15s interval) lands.

```json
{
  "type": "flightPlan",
  "simbriefCallsign": "BAW123",
  "simbriefOrigin": "EGLL",
  "simbriefDestination": "KJFK",
  "simbriefAlternate": "KBOS",
  "vatsimCallsign": "BAW123",
  "vatsimOrigin": "EGLL",
  "vatsimDestination": "KJFK",
  "originMismatch": false,
  "vatsimCidMismatch": false
}
```

`simbriefAlternate` is fetched and stored for future use but not yet surfaced in the Android app
(there's no VATSIM-side equivalent surfaced here, though the feed's `flight_plan.alternate` does
exist).

`vatsimCallsign` is `null` until connected. Once it's non-null but `vatsimOrigin`/
`vatsimDestination` are still `null`, that means the pilot is connected but the data feed has no
filed plan for them yet -- either it hasn't polled since connecting (transient, within ~15s), or
they genuinely haven't filed on the network at all. Clients should treat a *sustained* instance
of this (past the transient poll-lag window) as worth flagging -- forgetting to file is a real,
recurring mistake ("sorry, but you didn't file a flight plan" from Delivery), not just a stale
feed. A mismatch between `simbrief*` and `vatsim*` (once both are known) is also worth flagging --
it means the SimBrief OFP and what's actually filed on the network have diverged.

`originMismatch` (issue #68) is a distinct, narrower warning from the SimBrief/VATSIM disagreement
above -- it's the plugin's own on-ground sanity gate: while the aircraft is on the ground and
hasn't yet taken off this session, if ownship's position is more than ~8nm from the *filed origin's
own coordinates*, the loaded plan clearly doesn't match where the aircraft is physically sitting (a
stale plan left over from a previous flight, wrong airport picked, etc). This can be `true` even
when `simbrief*` and `vatsim*` fully agree with each other -- both sides can confidently agree on
the wrong airport. It's a per-tick condition, not a latch: it clears the instant the plan is
refreshed to match reality, the aircraft is repositioned, or the aircraft takes off. Resent on the
same periodic cadence as `diversionPending` (not just on the three `flightPlan`-triggering
`Changed` events above), since it can flip every tick as telemetry updates.

`vatsimCidMismatch` is narrower still. `vatsimOrigin`/`vatsimDestination` above come from looking
up the data feed's `pilots[]` by our own live callsign (`PilotSessionModel.Callsign`) -- a lookup
that can't distinguish "this feed entry is genuinely us" from "this feed entry merely has our
callsign string" (a lagged snapshot mid-reconnect, a callsign collision window). The feed also
carries a `cid` per pilot, same as it does per controller; when the looked-up entry's `cid`
disagrees with our own connection's cid, that's a real red flag the callsign match alone can't
catch. Purely informational -- the looked-up plan is still used for route matching exactly as
before, no fallback behavior changes. Resent on the same cadence as `originMismatch` above, for
the same reason (it depends on the feed poll and our own connection state, not just a SimBrief
refetch).

### `diversionPending`

A destination change the plugin just noticed on the VATSIM data feed (`vatsimDestination` above
changing mid-session), awaiting pilot confirmation before it's treated as a real diversion. Until
confirmed, the plugin keeps using the originally filed route for its own approach/convergence
prediction (issue #22's route-projected checks) rather than silently dropping it the instant the
feed shows a new arrival airport -- a feed hiccup or ATC typo shouldn't blow away route tracking
without the pilot getting a say. Resent whenever it changes (same resendable-snapshot shape as
`flightPlan`/`radioState`, not a one-shot like `operationProgress`).

```json
{"type": "diversionPending", "destination": "EDDF"}
```

`destination` is `null` whenever nothing is pending. A client should show a confirm/dismiss
prompt ("Confirm diversion to EDDF?") whenever this transitions from `null` to non-null, and
clear it whenever this transitions back to `null` (whether from `confirmDiversion`,
`dismissDiversion`, or another client's action -- see below).

### `nearbyAircraft`

Other traffic within 20nm of ownship, closest first -- feeds the chat panel's "start chat with
a nearby aircraft" dialog (issue #13). Resent whenever the underlying aircraft list or ownship
position changes.

```json
{
  "type": "nearbyAircraft",
  "aircraft": [
    {"callsign": "BAW123", "aircraftType": "B738", "distanceNm": 6.2}
  ]
}
```

Built from `IBroker`'s `AircraftAdded`/`AircraftUpdated`/`AircraftDeleted` events (real-time,
no feed lag), not the VATSIM data feed -- `IBroker` only ever reports *other* traffic, so no
ownship self-filtering is needed. `distanceNm` is computed against ownship's own position from
`RadioStateModel`'s telemetry (SimConnect via `Handoff.RadioHost`); the list is empty until
that position is available. `aircraftType` is `IBroker`'s type code and may be `null`.

### `subsystemStatus`

Per-subsystem connection health plus the plugin version, for the footer's expandable status
drawer (issue #13). Resent whenever any of the underlying signals change.

```json
{
  "type": "subsystemStatus",
  "radioHostConnected": true,
  "simulatorConnected": true,
  "vatsimDataFeedConnected": true,
  "simbriefFetched": false,
  "pluginVersion": "0.1.0",
  "systemsDebug": null
}
```

`radioHostConnected` is whether the plugin's IPC pipe to `Handoff.RadioHost` is currently up.
`simulatorConnected` is whether `Handoff.RadioHost` has reported a SimConnect-sourced radio
state this session -- an approximation (it can lag a real sim disconnect until the next
`NetworkDisconnected`/`SessionEnded` reset), good enough for a status indicator, not meant as a
hard guarantee. `vatsimDataFeedConnected` reflects the most recent VATSIM data feed poll.
`simbriefFetched` is whether a SimBrief fetch has ever succeeded this session. `pluginVersion`
is a static string for now (`"0.1.0"`) until the plugin has a real versioning scheme.

`systemsDebug` (issue #65) is `null` unless debug mode is on -- the debug overlay's "Systems"
section, one plain-language health line per non-ranking subsystem (radio/SimConnect, VATSIM
feed, SimBrief, VATGlasses/vatspy data loading, pairing/connection), so a non-ranking bug (a
stalled feed, SimBrief never fetching, pairing flapping) is visible from the same window without
needing a full snapshot just to notice something's off:

```json
{
  "systemsDebug": {
    "radioHostConnected": true,
    "simulatorConnected": true,
    "lastTelemetryAt": "2026-08-01T12:34:56Z",
    "vatsimFeedConnected": true,
    "vatsimFeedLastPollAt": "2026-08-01T12:34:50Z",
    "simbriefFetchedSuccessfully": true,
    "simbriefLastError": null,
    "vatGlassesLoadedRegionCount": 42,
    "vatSpyBoundaryCount": 380,
    "pairedClientCount": 1,
    "authenticatedSocketCount": 1,
    "activeOperationCount": 0
  }
}
```

This deliberately stays lean (rides this message's existing low-frequency broadcast, not the
1s `controllers` cadence) -- the exhaustive per-subsystem detail (last errors, cache state,
raw counts) lives only in the debug snapshot file, same split as `controllers.debug` above.

### `operationProgress`

Step-by-step status for an in-progress background plugin operation (e.g. the VatGlasses
sector-data sync, see issue #9) -- for a status line the Android footer's expandable
drawer can show while the plugin is busy with something slow enough to be worth
surfacing. Unlike every message above, this is **not** resendable full state -- each
message is one step of a specific operation, closer in spirit to `pong` than to
`controllers`/`radioState`/etc. It's sent only while an operation is actually running (or
its very last step), not resent on every unrelated state change.

```json
{"type": "operationProgress", "operationId": "vatGlassesSync-3fa85f6457174562b3fc2c963f66afa6", "status": "Updating VatGlasses file 12/24", "finished": false, "success": true}
```

`operationId` identifies one *invocation* of an operation, not an operation *type* --
deliberately generic (not specific to any one feature, so future long-running plugin
operations reuse this same message instead of each growing their own) and, within that,
unique per attempt: a `{type}-{guid}` string, minted fresh every time that operation
starts, not a shared constant per type. This matters for anything a pilot can trigger
repeatedly (e.g. tapping the SimBrief refresh button several times in a row) -- two
overlapping runs of the same operation type get two different ids, so one's `finished`
message can never be mistaken for the other's, and a client can have several of the same
*type* visible at once without them clobbering each other. Clients should therefore treat
every `operationId` as its own independent, unrelated-to-any-other tracked operation, keyed
by the full id string, not by whatever prefix it happens to start with.

`status` is a human-readable string for direct display -- the server owns the exact
wording, no client-side formatting/pluralization needed. `finished` is `true` on an
operation's last message ("end of update"). `success` is only meaningful once `finished`
is `true` (`true` while an operation is still in progress carries no meaning either way)
-- it's what drives a client's success/failure icon, so it doesn't have to guess by
pattern-matching `status` text, which is free-form and can change wording independently.

Clients should be ready to display **more than one operation at once** -- there's no
queue or serialization on the plugin side, so e.g. VatGlasses's startup sync and a
SimBrief refresh could genuinely overlap. Given limited screen space, combining several
simultaneously-visible operations into one summary indicator (as the Android client does
for its collapsed footer icon) is a reasonable approach: keep spinning while anything's
still running, only settle on a plain success/failure icon once nothing is.

Clients should keep showing the finished result for a little while rather than clearing
it the instant `finished` arrives, so the pilot actually gets to see whether it succeeded
-- a few seconds for success, longer for a failure (worth lingering on since it's the more
actionable case), is a reasonable default; the plugin doesn't prescribe an exact duration.

If a client connects while an operation is already in progress, the plugin immediately
sends its current status so the client doesn't have to wait for the next step to know
something's happening.

**Clients should apply their own 60-second timeout while an operation is still in
progress** (`finished: false`): if no further `operationProgress` for an `operationId` a
client still considers active arrives within 60s of the last one, treat the operation as
abandoned and clear the indicator locally -- a backstop for a dropped `finished` message
(e.g. a disconnect mid-sync), not something the plugin guarantees. This timeout doesn't
apply once a `finished` message has actually arrived -- that's governed by the
success/failure linger duration above instead.

### `debugSnapshotSaved`

Issue #65 -- reply to a `saveDebugSnapshot` command, sent only to the requesting client once the
file write completes. `path` is the full local path on the plugin's machine (informational --
the Android app can't read it directly, this is for display/troubleshooting only). This is also
the client's cue to now send the optional `attachDebugSnapshotScreenshot` for the same
`snapshotId`, rather than racing to capture one the instant the button was tapped.

```json
{"type": "debugSnapshotSaved", "snapshotId": "b3f1...", "path": "C:\\Users\\pilot\\AppData\\Local\\Handoff\\debug-snapshots\\20260801-123456-b3f1....json"}
```

### `debugSnapshotNamed`

Issue #73b -- reply to a `nameDebugSnapshot` command, sent only to the requesting client.
`success: false` covers both an unknown/expired `snapshotId` (past `saveDebugSnapshot`'s
`ScreenshotCorrelationWindow`, 10 minutes) and a rename I/O failure -- either way the original
`<timestamp>-<snapshotId>.json`/`.png` files are left exactly as they already were, never
partially renamed or corrupted. `error` is a short human-readable reason, present only when
`success` is `false`.

```json
{"type": "debugSnapshotNamed", "snapshotId": "b3f1...", "success": true}
{"type": "debugSnapshotNamed", "snapshotId": "b3f1...", "success": false, "error": "Unknown or expired snapshotId."}
```

## Client → server messages

### `sendPrivateMessage`

```json
{"type": "sendPrivateMessage", "to": "EGLL_TWR", "message": "wilco"}
```

### `sendRadioMessage`

Sent on whatever frequency is currently tuned/transmitting — the plugin has no way to target
a specific frequency for an outgoing radio message (matches `IBroker.SendRadioMessage`'s own
behavior).

```json
{"type": "sendRadioMessage", "message": "request pushback"}
```

### `setCom1Frequency` / `setCom2Frequency` / `setCom1StandbyFrequency` / `setCom2StandbyFrequency`

Remote-tune COM1/COM2, active or standby. `megahertz` is a plain decimal MHz value (not the
compressed-integer format used everywhere else in this protocol) since this is the one place
the client constructs a frequency value itself, rather than echoing one already sent by the
server, and plain MHz is what a frequency-entry UI naturally produces. Must be within the
civil VHF airband (118.000–136.990); out-of-range values are rejected (dropped, no error
response) by the plugin.

Active-frequency writes go through MSFS's `COM_RADIO_SET_HZ`/`COM2_RADIO_SET_HZ` SimConnect
client events on the plugin side, not a direct SimVar write — a raw write on
`COM ACTIVE FREQUENCY` is silently ignored by most aircraft avionics, which continuously
re-assert their own active frequency. Client events are treated the same as a physical
knob turn and are honored.

```json
{"type": "setCom1Frequency", "megahertz": 123.725}
{"type": "setCom2Frequency", "megahertz": 118.3}
{"type": "setCom1StandbyFrequency", "megahertz": 121.9}
{"type": "setCom2StandbyFrequency", "megahertz": 121.9}
```

### `setCom1ActiveAndStandbyFrequency` / `setCom2ActiveAndStandbyFrequency`

Sets active and standby together as one round trip -- e.g. a "transfer" (activate a just-typed
frequency while preserving whatever was previously active into standby, matching real
flip-flop avionics like the Garmin G3000 GTC's XFER key -- entry always lands in standby first,
and "activate" is that standby write plus a transfer swap, never a bare overwrite of active) or
a plain COM1/COM2 active↔standby swap. `megahertz` is the new active frequency,
`standbyMegahertz` the new standby frequency; both plain decimal MHz, same range/validation as
the single-field commands above.

Prefer this over sending two separate `setComXFrequency`/`setComXStandbyFrequency` commands for
this kind of paired update: the plugin forwards each command to `Handoff.RadioHost` as an
independent queued operation, and each one blocks that queue for its own ~1.1s SimConnect
settle-wait before the next command is even dequeued -- two separate commands land the two
writes over a second apart, even though the underlying SimConnect events themselves are
near-instant. This command transmits both events back-to-back with a single settle-wait,
landing them together.

```json
{"type": "setCom1ActiveAndStandbyFrequency", "megahertz": 123.725, "standbyMegahertz": 121.9}
{"type": "setCom2ActiveAndStandbyFrequency", "megahertz": 118.3, "standbyMegahertz": 121.9}
```

### `setTransponderCode`

Sets the squawk code. `transponderCode` is a plain decimal 4-digit code, each digit 0-7 (the
civil transponder code range); out-of-range values are rejected (dropped, no error response)
by the plugin.

```json
{"type": "setTransponderCode", "transponderCode": 1200}
```

### `selectCom1Transmitter` / `selectCom2Transmitter`

Selects which COM is the transmitter, via MSFS's `COM1_TRANSMIT_SELECT`/
`COM2_TRANSMIT_SELECT` SimConnect events (the same events
`RadioStateModel.SelectCom1Transmitter`/`SelectCom2Transmitter` already use for the
plugin-internal capability added in issue #20). Real avionics only let one COM transmit at
a time, but as with the `radioState` fields themselves, the plugin doesn't enforce or
validate that -- it just forwards the event. Carries no fields of its own.

```json
{"type": "selectCom1Transmitter"}
{"type": "selectCom2Transmitter"}
```

### `setCom1ReceiveEnabled` / `setCom2ReceiveEnabled`

Sets whether that COM's receiver is enabled, via MSFS's `COM1_RECEIVE_SELECT`/
`COM2_RECEIVE_SELECT` SimConnect events. Independent per COM -- both `true` at once is a
normal "listening on both" state (see `radioState`'s `com1ReceiveEnabled`/
`com2ReceiveEnabled` below).

```json
{"type": "setCom1ReceiveEnabled", "enabled": true}
{"type": "setCom2ReceiveEnabled", "enabled": false}
```

### `setSimbriefCredentials`

Persists the SimBrief user ID and/or username the plugin should fetch with -- a full
overwrite of whatever was persisted before, including clearing a field by sending `null`.
Doesn't trigger a fetch itself; send a `refreshFlightPlan` afterward for that (the Android
Settings screen's "Save & refresh" button sends both, back to back). Persisting here (rather
than only holding this in memory) is what lets the plugin re-fetch on its own next startup,
before the Android app has necessarily connected.

```json
{"type": "setSimbriefCredentials", "simbriefUserId": "123456", "simbriefUsername": null}
```

### `refreshFlightPlan`

Triggers a SimBrief fetch using whatever credentials are currently persisted (see
`setSimbriefCredentials`) -- the user ID is tried first (SimBrief usernames have occasionally
caused lookup issues), falling back to the username if the ID is blank or its fetch fails.
Carries no fields of its own; a no-op (logged, not an error) if no credentials have ever been
persisted.

```json
{"type": "refreshFlightPlan"}
```

### `pinController` / `clearPinnedController`

Marks a specific controller as pinned -- a bookmark that keeps it ranked prominently in the
`controllers` message (its own bucket, just below contact-me/SELCAL), until cleared or the
controller goes offline past its hidden-expiry window. Multiple controllers can be pinned at
once; each is set/cleared independently by its own callsign, never touching any other pinned
callsign -- only the pilot's own explicit unpin (or the controller going offline past expiry)
ever clears one, never automatic replacement. Does **not** set `isCurrent` and never displaces
whatever's actually tuned -- pinning is a separate signal from "current" (issue #17).
`clearPinnedController` takes the same `callsign` field as `pinController`.

```json
{"type": "pinController", "callsign": "EGLL_TWR"}
{"type": "clearPinnedController", "callsign": "EGLL_TWR"}
```

### `dismissSelcal`

Clears a controller's currently-active SELCAL alert (see Ranking order above and the `chat`
message's `selcalAlerts`), dropping it out of the ranking priority it gets while active. This is
the *only* way to clear an alert short of its own expiry -- there's no tune-match auto-clear,
since real SELCAL requires the pilot to already be tuned to the alerting frequency (just with the
volume down) for the pulse to reach the aircraft at all, so being tuned proves nothing about
whether the alert's been seen.

```json
{"type": "dismissSelcal", "callsign": "EGLL_CTR"}
```

### `confirmDiversion` / `dismissDiversion`

Responds to a `diversionPending` prompt -- no payload, since only one destination can be pending
at a time. `confirmDiversion` treats it as a real diversion (drops the filed route from approach
prediction, same as the plugin's old unconditional behavior). `dismissDiversion` treats it as a
false alarm and keeps using the filed route, without re-prompting for that same destination
again. Both are no-ops if nothing is currently pending.

```json
{"type": "confirmDiversion"}
{"type": "dismissDiversion"}
```

### `ping` / `pong`

Client-initiated latency probe for the footer's detail line (issue #13) -- the server has no
authoritative clock worth reporting, so this simply echoes back a client-supplied timestamp for
the client to diff against its own send time, rather than the plugin computing latency itself.

```json
{"type": "ping", "clientTimestamp": 1234567890}
```

The plugin replies directly to the sender only (not broadcast to other connected clients):

```json
{"type": "pong", "clientTimestamp": 1234567890, "serverTimestamp": 1234567891}
```

`clientTimestamp`/`serverTimestamp` are epoch milliseconds. `serverTimestamp` is informational
only; latency is `(time pong received) - clientTimestamp`.

### `setDebugMode`

Issue #65 -- toggles the plugin's diagnostic debug mode on/off for the rest of this plugin
session (in-memory, not persisted across a plugin restart, not part of `subsystemStatus`/settings
storage). While on, every `controllers` broadcast includes the extra `debug` fields (see above)
and `subsystemStatus` includes `systemsDebug`; while off, the plugin skips computing that data
entirely, not just omitting it from the wire. Global to the plugin, not per-client, if multiple
clients are paired.

```json
{"type": "setDebugMode", "enabled": true}
```

### `saveDebugSnapshot`

Triggers a full point-in-time dump of everything the plugin knows -- every subsystem, not just
ranking -- written synchronously to
`%LOCALAPPDATA%\Handoff\debug-snapshots\<timestamp>-<snapshotId>.json` (the plugin's own data
directory, distinct from vPilot's own install). `snapshotId` is a client-generated GUID
correlating this request with the `debugSnapshotSaved` reply and any later
`attachDebugSnapshotScreenshot`. `appVersion` is the Android `versionName` -- the plugin doesn't
otherwise know it, and capturing both plugin and app version in the file matters now that the two
update independently (see Compatibility above).

```json
{"type": "saveDebugSnapshot", "snapshotId": "b3f1...", "appVersion": "1.4.0"}
```

The snapshot file's own shape isn't part of this wire protocol (it's not a message, and is free
to change shape independently of this document's compatibility rules) -- see
`docs/debug-snapshot.md`.

### `attachDebugSnapshotScreenshot`

Sent only after `debugSnapshotSaved` comes back for the same `snapshotId` -- a separate, later
round trip, not bundled into `saveDebugSnapshot` itself (so the snapshot write isn't delayed
waiting on screenshot capture/encoding). `screenshotPngBase64` is, **by default**, a
**view-scoped** capture of the Handoff app's own window only -- the tablet normally runs
split-screen next to another EFB app, and a full-display capture would otherwise pull in content
that has nothing to do with a Handoff bug report. Issue #73a adds an explicit opt-in: the pilot
can check a "full-device screenshot" box in the debug window's title bar, which grants
`MediaProjection` consent (a system prompt, requested once when the box is checked, not per
snapshot) and captures the whole display instead -- useful when seeing the neighboring EFB app
(charts, performance tool) alongside Handoff's state actually helps diagnosis. The plugin has no
way to tell which kind of capture it received; both arrive the same shape on this field. Optional
either way: a client that doesn't implement this, or a pilot who dismisses before capture
completes, still leaves a fully valid JSON-only snapshot; the plugin doesn't wait for this and
doesn't treat its absence as an error. Saved alongside the JSON as `<timestamp>-<snapshotId>.png`;
the plugin doesn't parse or validate the image contents.

```json
{"type": "attachDebugSnapshotScreenshot", "snapshotId": "b3f1...", "screenshotPngBase64": "iVBORw0KG..."}
```

### `nameDebugSnapshot`

Issue #73b -- attaches a pilot-chosen name to an already-saved snapshot, strictly after the fact:
sent only once the pilot has typed a name into the inline field that appears after
`debugSnapshotSaved` comes back, never blocking or delaying the save itself (the timing of the
original capture matters for what it's snapshotting). Looks up the same `snapshotId` correlation
`saveDebugSnapshot`/`attachDebugSnapshotScreenshot` already use (`ScreenshotCorrelationWindow`, 10
minutes -- confirmed long enough to allow for typing a name while flying), stores `name` as a new
field inside the JSON, and renames both the `.json` and (if it exists yet) the `.png` file by
appending a sanitized, length-truncated version of `name` to their existing
`<timestamp>-<snapshotId>` stem. If the correlation window has expired or the rename fails for any
I/O reason, the original files are left exactly as they were -- never a reason to lose or corrupt
an already-saved snapshot over a cosmetic rename failing. See `debugSnapshotNamed` above for the
reply.

```json
{"type": "nameDebugSnapshot", "snapshotId": "b3f1...", "name": "sequencing lag near KONAN"}
```
