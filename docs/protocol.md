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

The full current controller list, resent whenever any controller is added, removed, or
changes frequency/location.

```json
{
  "type": "controllers",
  "controllers": [
    {"callsign": "EGLL_TWR", "frequency": 23725, "latitude": 51.4775, "longitude": -0.4614}
  ]
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

Ownship COM1/COM2 tuned frequency and Mode C transponder state, resent whenever any of them
change.

```json
{
  "type": "radioState",
  "com1Frequency": 23725,
  "com2Frequency": null,
  "modeCEnabled": false
}
```

`com1Frequency`/`com2Frequency` are `null` until the first SimConnect read completes (or if
the SimConnect helper process isn't running/connected).

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

### `setCom1Frequency` / `setCom2Frequency`

Remote-tune COM1/COM2. `megahertz` is a plain decimal MHz value (not the compressed-integer
format used everywhere else in this protocol) since this is the one place the client
constructs a frequency value itself, rather than echoing one already sent by the server, and
plain MHz is what a frequency-entry UI naturally produces. Must be within the civil VHF
airband (118.000–136.990); out-of-range values are rejected (dropped, no error response) by
the plugin.

```json
{"type": "setCom1Frequency", "megahertz": 123.725}
{"type": "setCom2Frequency", "megahertz": 118.3}
```

## Not yet in this protocol

Flight plan, phase-of-flight, and controller priority ranking are all still open per
`CLAUDE.md` — this protocol will grow new message types for them once those pieces of the
plugin's state model exist. Don't design a client against fields that aren't listed above.
