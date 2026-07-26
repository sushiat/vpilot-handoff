# Handoff protocol

The WebSocket contract between the vPilot plugin (server) and any client (Android, or future
ports). This is the source of truth for message shapes — client implementations should
conform to this, not to whichever client's source happens to exist first.

## Discovery

The plugin's LAN IP isn't known in advance, so it also listens for a UDP broadcast discovery
request on port `48766` — plain UDP, not mDNS/Bonjour, so no extra dependency is needed on
either side. A client broadcasts the ASCII text `HANDOFF_DISCOVER` to `255.255.255.255:48766`;
the plugin unicasts back `{"port":48765}` to the sender. This listener runs for the plugin's
whole lifetime (not tied to the VATSIM connection), same as the WebSocket server below, so a
client can discover it even before the pilot connects.

Discovery isn't guaranteed to work on every network (some routers apply AP client isolation or
block broadcast traffic), so clients should keep a manually-entered IP as a fallback.

## Connection

The plugin listens on `ws://<pc-lan-ip>:48765/` (Fleck-based, plain TCP — no HTTP handshake
beyond the WebSocket upgrade, and no admin/URL-ACL setup needed on the Windows side). Not yet
configurable; a fixed port for v1.

On connect, the server immediately sends a `controllers`, a `chat`, and a `radioState`
message — the client's full current state, with no need to wait for the next change. After
that, each message type is re-sent in full (not as an incremental diff) whenever its backing
state changes. This is deliberately simple: resending full state is cheap on a LAN and avoids
an entire class of missed-message/reconnect bugs that incremental delivery would introduce.

All JSON fields are camelCase. All frequencies are vPilot's compressed-integer format
throughout the protocol (e.g. `123.725` MHz → `23725`) — never plain MHz. All timestamps are
ISO 8601 UTC.

## Server → client messages

### `controllers`

The full current controller list, pre-sorted by the plugin's priority ranking (see below) and
resent whenever any controller is added/removed/changes frequency/location, the pilot's tuned
COM frequency changes, the flight plan changes, a "contact me" request starts/expires, or the
VATSIM data feed enrichment updates. Nothing is ever hidden -- every connected station appears
exactly once, just reordered, with boolean flags for the Android app to colour-code by (full
saturation on relevant entries, paler on the rest -- no text labels needed, pilots already read
VATSIM facility conventions).

```json
{
  "type": "controllers",
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
      "stationName": null,
      "requestsContactMe": false,
      "isCurrent": true,
      "isContactMe": false,
      "isLikelyNextCandidate": false,
      "isApproaching": false,
      "isHighlighted": false
    }
  ]
}
```

`cid`/`name`/`facility`/`rating` come from the public VATSIM data feed (not `IBroker`, which
doesn't expose them) and are `null` until that feed's ~15s-lagged enrichment solidifies for a
given callsign. `facility` is VATSIM's own enum (`2=DEL, 3=GND, 4=TWR, 5=APP/DEP, 6=CTR`);
`rating` is display-only, never used in ranking.

`stationName` is a facility/airport display name (e.g. "Heathrow Tower" for `EGLL_TWR`),
expected to be VatSpy-sourced -- see issue #13. Always `null` for now; no VatSpy integration
exists yet. Until it's populated, clients should keep parsing just the facility-suffix word
from the callsign (Tower/Ground/Delivery/etc.), not depend on this field being non-null.

Ranking order is entirely a plugin-side decision -- clients must render the list in exactly the
order received and never re-sort client-side. The algorithm (tier chain, route matching,
distance, SELCAL/contact-me priority, etc.) is documented as an implementation detail in the
plugin's `ControllerRankingModel.cs`, not here: it can change between plugin versions without any
client update needed, as long as the fields below keep meaning what they say.

The boolean fields are what clients actually consume, each driving its own badge/highlight:

- `isCurrent`: this is the tuned (or manually pinned) controller.
- `isContactMe`: this controller sent an outstanding "contact me" request.
- `isLikelyNextCandidate`: the plugin's best guess at which controller the pilot will want to
  contact next.
- `isApproaching`: only ever `true` when nothing is currently tuned/pinned (flying uncontrolled)
  -- the pilot is closing in on this station's range.
- `isHighlighted`: a softer, no-badge-implied "worth rendering prominently" signal -- unlike
  `isLikelyNextCandidate` it never affects ranking order, and unlike `isApproaching` it isn't
  gated on nothing being tuned. **Currently only ever set for two tiers, nothing else:**
  - **CTR**: gated on being airborne and within a bounded range (no real sector geometry exists
    yet -- issue #11 -- so this is a rough proximity cutoff, not a modeled FIR boundary).
  - **ATIS**: gated on the callsign's ICAO prefix matching the route airport (origin
    pre-departure, destination after takeoff) -- the same route-match logic used for
    DEL/GND/TWR/APP/DEP elsewhere, just applied to a tier (`Other`) that both
    `isLikelyNextCandidate`'s tier walk and `isApproaching` otherwise skip entirely.
  
  Every other tier already gets equivalent treatment through `isLikelyNextCandidate` or
  `isApproaching`, so `isHighlighted` is always `false` for them.

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
frequency identifies the channel instead). `frequencies` is populated only for `radio`
messages and SELCAL alerts, `null` otherwise.

### `radioState`

Ownship COM1/COM2 active + standby tuned frequency, transponder code, and Mode C state, resent
whenever any of them change.

```json
{
  "type": "radioState",
  "com1Frequency": 23725,
  "com2Frequency": null,
  "com1StandbyFrequency": 21000,
  "com2StandbyFrequency": null,
  "modeCEnabled": false,
  "transponderCode": 1200
}
```

All frequency fields are `null` until the first SimConnect read completes (or if the
SimConnect helper process isn't running/connected). `transponderCode` is a plain decimal
squawk (e.g. `1200`), not BCD -- that encoding is purely a SimConnect-boundary detail on the
plugin side.

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
  "vatsimDestination": "KJFK"
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
  "pluginVersion": "0.1.0"
}
```

`radioHostConnected` is whether the plugin's IPC pipe to `Handoff.RadioHost` is currently up.
`simulatorConnected` is whether `Handoff.RadioHost` has reported a SimConnect-sourced radio
state this session -- an approximation (it can lag a real sim disconnect until the next
`NetworkDisconnected`/`SessionEnded` reset), good enough for a status indicator, not meant as a
hard guarantee. `vatsimDataFeedConnected` reflects the most recent VATSIM data feed poll.
`simbriefFetched` is whether a SimBrief fetch has ever succeeded this session. `pluginVersion`
is a static string for now (`"0.1.0"`) until the plugin has a real versioning scheme.

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

### `setTransponderCode`

Sets the squawk code. `transponderCode` is a plain decimal 4-digit code, each digit 0-7 (the
civil transponder code range); out-of-range values are rejected (dropped, no error response)
by the plugin.

```json
{"type": "setTransponderCode", "transponderCode": 1200}
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

Manual override: forces a specific controller to rank 0 / `isCurrent` in the `controllers`
message, regardless of what the tuned-frequency heuristic would pick, until cleared or the
controller goes offline. `clearPinnedController` carries no fields of its own.

```json
{"type": "pinController", "callsign": "EGLL_TWR"}
{"type": "clearPinnedController"}
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
