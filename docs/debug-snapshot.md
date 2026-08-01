# Debug snapshot file

Issue #65's `saveDebugSnapshot` command (`docs/protocol.md`) writes a full point-in-time dump of
everything the plugin knows to
`%LOCALAPPDATA%\Handoff\debug-snapshots\<timestamp>-<snapshotId>.json` (the plugin's own data
directory, distinct from vPilot's own install -- see `CLAUDE.md`'s Resolved section), with an
optional `<timestamp>-<snapshotId>.png` alongside it if the client sends
`attachDebugSnapshotScreenshot` afterward.

This file is **not** a wire message -- it isn't covered by `docs/protocol.md`'s
additive/backward-compatible rules and is free to change shape whenever the underlying
implementation changes, independent of any client. `DebugSnapshotService`
(`plugin/DebugSnapshotService.cs`) assembles it by calling one `BuildDebugSnapshot()`-style method
per subsystem model, each reading its own private fields directly -- "the same way any other
debugger would," per the issue's own framing -- rather than promoting that state to a protocol
contract.

## Top-level shape (`FullDebugSnapshot`, `plugin/FullDebugSnapshot.cs`)

- **Meta**: `snapshotId`, `timestampUtc`, `pluginVersion`, `appVersion` (the Android
  `versionName`, sent by the client since the plugin doesn't otherwise know it). Worth checking
  first -- since the plugin and app now update independently (`docs/protocol.md`'s
  Compatibility section), a version mismatch here can explain a bug report that doesn't reproduce
  on a dev's own matched pair.
- **Connection**: `vatsimCallsign`/`vatsimCid` (`PilotSessionModel`, the live `IBroker`-sourced
  value, not SimBrief's).
- **`computedControllers`** (`RankedController[]`): the full `controllers` message as it would
  currently be sent, including every controller's `debug` object (`docs/protocol.md`) -- not just
  the top N, since the ones left in bucket 9 are exactly what's interesting when something isn't
  dropping.
- **`rankingContext`** (`RankingDebugExplain`): the plugin-wide `controllers.debug` object.
- **`ranking`** (`RankingSnapshot`, `plugin/RankingSnapshot.cs`): the ranking internals that
  don't exist anywhere else --
  - `routeAnchorLatitude`/`routeAnchorLongitude`, `committedWaypointIndex`,
    `pendingWaypointIndex`/`pendingWaypointName`/`pendingWaypointSince`, `naturalWaypointIndex` --
    the abeam-point sequencer's raw state (issue #22/#66's stuck-sequencer investigation is
    exactly what this is for: a frozen `routeAnchorLatitude`/`routeAnchorLongitude` while ownship
    is demonstrably elsewhere is visible directly here).
  - `remainingWaypointProjection`: every waypoint from `committedWaypointIndex` onward, each with
    its `latitude`/`longitude` and the sweep's own `legDistanceNm`/`alongTrackNm` breakdown --
    `alongTrackNm < legDistanceNm` for a waypoint ownship has obviously already overflown
    confirms a stale-anchor bug directly from the file.
  - `routeInvalidatedByDiversion`/`pendingDiversionDestination`: the diversion latch -- once
    tripped it silently drops route-intersection prediction back to heading-ray-cast for the rest
    of the session, which otherwise looks like an unrelated route-tracking bug.
  - `tierChainHysteresis`: every tier's committed leader / pending challenger / pending-since,
    not just the currently-visible ones (bucket 9's "Flapping protection",
    `docs/controller-ranking.md`).
- **`controllerState`** (`ControllerStateDebugSnapshot`): the raw pre-ranking controller list,
  **including currently-hidden** (grace-window) stations, plus pinned/contact-me/SELCAL pending
  counts -- separates "why is X missing entirely" from "why is X ranked wrong."
- **`radio`** (`RadioDebugSnapshot`): `RadioStateModel`'s full `RadioState` + `OwnshipTelemetry`,
  plus the `Handoff.RadioHost`/SimConnect connection flags.
- **`vatsimFeed`** (`VatsimFeedDebugSnapshot`): feed connection state, controller/pilot counts,
  last poll timestamp, last error.
- **`flightPlan`** (`FlightPlanDebugSnapshot`): the current `FlightPlan`, fetch state, last fetch
  attempt timestamp, last error, and whether SimBrief credentials are present -- **never the
  credential values themselves** (unlike the VATSIM `cid`/`name`/`facility`/`rating` embedded
  elsewhere in this file, which are already public VATSIM data, SimBrief credentials are not).
- **`vatGlasses`**/**`vatSpy`** (`VatGlassesDebugSnapshot`/`VatSpyDebugSnapshot`): which region
  files/boundaries are actually loaded in memory right now (not just "the last sync reported
  success") and the cached commit SHA.
- **`pairing`** (`PairingDebugSnapshot`): paired-device count and whether a pairing code is
  currently active -- **never token hashes or the code itself**, both are live secrets.
- **`authenticatedSocketCount`**: currently-authenticated WebSocket connections.
- **`activeOperations`**: whatever `OperationProgressModel.ActiveOperations` shows at that
  instant (e.g. a VatGlasses sync still mid-flight when the snapshot was taken).

## What's deliberately *not* here

- No remote/cloud upload -- local file only, matching this project's LAN-only trust model.
- No plaintext secrets (SimBrief credentials, pairing codes, paired-client tokens) -- see the
  per-section notes above.
- The screenshot (if sent) is saved as-is, unparsed/unvalidated, purely as "what the pilot was
  looking at" context alongside the JSON.
