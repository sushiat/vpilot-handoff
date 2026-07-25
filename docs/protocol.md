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
      "requestsContactMe": false,
      "isCurrent": true,
      "isContactMe": false,
      "isLikelyNextCandidate": false,
      "isApproaching": false
    }
  ]
}
```

`cid`/`name`/`facility`/`rating` come from the public VATSIM data feed (not `IBroker`, which
doesn't expose them) and are `null` until that feed's ~15s-lagged enrichment solidifies for a
given callsign. `facility` is VATSIM's own enum (`2=DEL, 3=GND, 4=TWR, 5=APP/DEP, 6=CTR`);
`rating` is display-only, never used in ranking.

Ranking order: the currently-tuned controller (or a manually pinned one, see
`pinController` below) first, then any controller with an outstanding "contact me" request, then
the rest grouped by the standard top-down chain (DEL→GND→TWR→APP/DEP→CTR) relative to the
current tier, each tier internally sorted by flight-plan route match then distance to ownship.
`isLikelyNextCandidate` is `true` on every controller in whichever tier is immediately next in
the chain -- however many that is, not a fixed count.

`isApproaching` is only ever `true` when nothing is currently tuned/pinned (i.e. flying
uncontrolled) -- a "you're closing in on this station" signal for GND (on the ground, within
10nm), TWR (airborne, within 20nm), and APP (airborne; within 40nm counts regardless of
heading, 40-50nm only counts if ownship's heading is within 45° of the bearing to the
station). Not computed for DEL (already well-served by route match) or CTR (a single lat/lon
can't represent a FIR's real shape -- needs actual sector geometry, see issue #11).

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

The pilot's filed flight plan, fetched from the SimBrief API (`IBroker` has no flight-plan
members at all -- see CLAUDE.md). Resent whenever a fetch (startup or triggered by
`refreshFlightPlan`) succeeds. All fields are `null` until the first successful fetch.

```json
{
  "type": "flightPlan",
  "callsign": "BAW123",
  "origin": "EGLL",
  "destination": "KJFK",
  "alternate": "KBOS"
}
```

`alternate` is fetched and stored for future use but not yet surfaced in the Android app.

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

## Not yet in this protocol

Phase-of-flight is still open per `CLAUDE.md` — this protocol will grow a new message type for
it once that piece of the plugin's state model exists. Don't design a client against fields that
aren't listed above.
