package at.sushi.handoff.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Message shapes from docs/protocol.md. All fields camelCase, frequencies are vPilot's
 *  compressed-integer format (e.g. 123.725 MHz -> 23725), never plain MHz. */
sealed interface ServerMessage

@Serializable
data class Controller(
    val callsign: String,
    val frequency: Int,
    val latitude: Double,
    val longitude: Double,
    // Enrichment from the public VATSIM data feed (not IBroker, which exposes none of this) --
    // null until that feed's ~15s-lagged enrichment solidifies for this callsign.
    val cid: Int? = null,
    val name: String? = null,
    val facility: Int? = null,
    val rating: Int? = null,
    // Facility/airport display name (e.g. "Heathrow Tower"), plugin-composed from the
    // controller's own ATIS text or vatspy-data-project (docs/protocol.md, docs/controller-
    // ranking.md). Null whenever neither source yields anything confident -- falls back to
    // facilitySuffixName(callsign) client-side in that case.
    val stationName: String? = null,
    // Raw ATIS/info lines, unprocessed (VATSIM data feed's "text_atis") -- stationName above is
    // a derived summary of just the first line; this is the full text for the tune-menu's ATIS
    // panel. Null when the controller hasn't set one or the feed omits it for this callsign.
    val textAtis: List<String>? = null,
    // Priority-ranking flags -- see docs/protocol.md and docs/controller-ranking.md. All
    // server-authoritative: the client never re-derives one of these from other data it happens
    // to have (e.g. comparing frequency against radioState's standby fields) -- issue #18.
    val requestsContactMe: Boolean = false,
    val isCurrent: Boolean = false,
    val isContactMe: Boolean = false,
    // Three-flag design (issue #18, replacing the old isLikelyNextCandidate/isApproaching pair):
    // isHighlighted is relevance/visibility ("worth seeing"), independent of isNext/isLikelyNext.
    // isNext is confident and singular; isLikelyNext is the same signal confidence-capped (a
    // genuine tie, or route-relevance unconfirmed) -- render as a visibly softer variant of the
    // isNext badge (e.g. "NEXT?" vs "NEXT"), not an unrelated badge.
    val isHighlighted: Boolean = false,
    val isNext: Boolean = false,
    val isLikelyNext: Boolean = false,
    // Manual bookmark -- its own bucket, never a stand-in for isCurrent; can be true alongside
    // isCurrent/isStandbyTuned.
    val isPinned: Boolean = false,
    // Loaded into COM1 or COM2 standby.
    val isStandbyTuned: Boolean = false,
    // Active SELCAL alert -- unlike isContactMe, tuning the alerting frequency does NOT clear
    // this, only an explicit dismissSelcal command or the alert's own expiry does.
    val isSelcalActive: Boolean = false,
    // Issue #65 -- null unless debug mode is currently on (SetDebugModeCommand). Plain-language
    // explain data, not the ranking internals -- see docs/controller-ranking.md's "Debug explain
    // view" section.
    val debug: ControllerDebug? = null
)

/** Per-controller debug explain (issue #65, docs/protocol.md) -- only non-null while debug mode
 *  is on. Deliberately a plain-language summary, not the raw ranking internals (route anchor
 *  coordinates, VATGlasses/vatspy sector ids, tie-band math) -- those live only in the debug
 *  snapshot file, not on the wire. */
@Serializable
data class ControllerDebug(
    val bucket: Int,
    val bucketName: String,
    // Issue #68 -- docs/controller-ranking.md's lettered sub-rows for buckets 6/7/8 (6a-6e,
    // 7a-7c, 8a-8b) that actually produced this controller's flags, e.g. "6c" or "6a, 6e" when
    // both a highlight row and the next/likely-next row apply. Null for buckets 1-5 and 9, which
    // the table doesn't subdivide.
    val subBucket: String? = null,
    val reason: String,
    val distanceNm: Double? = null,
    val vatGlassesSectorMatch: Boolean = false,
    val vatSpyPolygonMatch: Boolean = false,
    val routeMatch: Boolean = false,
    val hysteresisState: String,
    val hysteresisPendingBucket: Int? = null,
    val hysteresisPendingSince: String? = null,
    val candidateRank: Int? = null
)

/** Plugin-wide debug context (issue #65, docs/protocol.md) -- the state per-controller [ControllerDebug] reasons are evaluated against. Null unless debug mode is on. */
@Serializable
data class RankingDebug(
    val phaseOfFlight: String,
    val hasTakenOffThisSession: Boolean,
    val ownshipLatitude: Double? = null,
    val ownshipLongitude: Double? = null,
    val ownshipAltitudeTrue: Double? = null,
    val ownshipAltitudeAgl: Double? = null,
    val ownshipGroundspeedKt: Double? = null,
    val ownshipHeadingTrue: Double? = null,
    val ownshipTrackTrue: Double? = null,
    val com1TunedCallsign: String? = null,
    val com2TunedCallsign: String? = null,
    val activeRouteWaypoint: String? = null,
    val lastPassedWaypoint: String? = null,
    // Bearing (true, 0-360)/distance from ownship's current position to each named waypoint
    // above -- null whenever ownship's position isn't known yet.
    val activeRouteWaypointBearingTrue: Double? = null,
    val activeRouteWaypointDistanceNm: Double? = null,
    val lastPassedWaypointBearingTrue: Double? = null,
    val lastPassedWaypointDistanceNm: Double? = null,
    val etaCalculationDetail: String? = null,
    // Issue #73c -- which mechanism last advanced the committed waypoint index: "alongTrackSweep"
    // (normal anchor-relative sweep) or "proximityCatchUp" (issue #66's fallback that recovers
    // sequencing after a direct-to desyncs the route anchor). Both null until the first advance.
    val lastWaypointAdvanceMechanism: String? = null,
    val lastWaypointAdvanceAt: String? = null
)

@Serializable
data class ControllersMessage(
    val type: String = "controllers",
    // Pre-sorted by the plugin's priority ranking (docs/protocol.md) -- render in list order,
    // don't re-sort client-side.
    val controllers: List<Controller>,
    // Ownship-level (not per-controller): minutes to the closest bucket-8-qualifying CTR sector,
    // available during level flight or climbing/descending above FL150 -- null otherwise.
    val etaMinutes: Double? = null,
    // Issue #65 -- null unless debug mode is currently on.
    val debug: RankingDebug? = null
) : ServerMessage

@Serializable
data class ChatEntry(
    val channel: String,
    val direction: String,
    val peer: String? = null,
    val from: String? = null,
    val text: String,
    val frequencies: List<Int>? = null,
    val timestamp: String
)

@Serializable
data class SelcalAlert(
    val from: String,
    val frequencies: List<Int>,
    val timestamp: String
)

@Serializable
data class ChatMessage(
    val type: String = "chat",
    val messages: List<ChatEntry>,
    val selcalAlerts: List<SelcalAlert>
) : ServerMessage

/** Two independent views of the flight plan (docs/protocol.md) -- surfaced side by side so the
 *  client can flag a mismatch instead of silently trusting one. [simbriefCallsign] is whatever
 *  was typed when the SimBrief OFP was generated (available pre-connection, no VATSIM dependency).
 *  [vatsimCallsign] is the live, authoritative callsign from the actual vPilot connection,
 *  cross-referenced against the public data feed for [vatsimOrigin]/[vatsimDestination] -- null
 *  until connected; once non-null while origin/destination are still null past the feed's ~15s
 *  poll window, that means connected but nothing filed on the network (worth flagging, not a
 *  transient state). */
@Serializable
data class FlightPlanMessage(
    val type: String = "flightPlan",
    val simbriefCallsign: String? = null,
    val simbriefOrigin: String? = null,
    val simbriefDestination: String? = null,
    val simbriefAlternate: String? = null,
    val vatsimCallsign: String? = null,
    val vatsimOrigin: String? = null,
    val vatsimDestination: String? = null,
    // Issue #68 -- the plugin's own on-ground sanity gate: true when ownship's position doesn't
    // match the filed origin's coordinates, even if simbrief*/vatsim* fully agree with each other
    // (both sides can confidently agree on the wrong airport). Distinct from the SimBrief/VATSIM
    // mismatch this class otherwise surfaces -- see docs/protocol.md.
    val originMismatch: Boolean = false,
    // True when the VATSIM feed entry found for our own live callsign carries a cid that doesn't
    // match our own connection's cid -- a callsign lookup alone can't tell "this is us" from
    // "this happens to have our callsign string" (lagged feed snapshot, collision window).
    // Informational only -- vatsimOrigin/vatsimDestination above are still used as normal.
    val vatsimCidMismatch: Boolean = false
) : ServerMessage

/** A destination change the plugin just noticed on the VATSIM data feed (see [FlightPlanMessage]'s
 *  [FlightPlanMessage.vatsimDestination]), awaiting pilot confirmation before it's treated as a
 *  real diversion (docs/protocol.md). [destination] is null whenever nothing is pending -- a
 *  client should show a confirm/dismiss prompt whenever this transitions from null to non-null,
 *  and dismiss it whenever this transitions back to null (whether from this device's own
 *  [ConfirmDiversionCommand]/[DismissDiversionCommand] or another connected client's). */
@Serializable
data class DiversionPendingMessage(
    val type: String = "diversionPending",
    val destination: String? = null
) : ServerMessage

@Serializable
data class RadioStateMessage(
    val type: String = "radioState",
    val com1Frequency: Int? = null,
    val com2Frequency: Int? = null,
    val com1StandbyFrequency: Int? = null,
    val com2StandbyFrequency: Int? = null,
    val modeCEnabled: Boolean,
    val transponderCode: Int? = null,
    // Audio panel's transmit/receive-select state (SimConnect's COM TRANSMIT:n/COM RECEIVE:n),
    // not a live "audio currently playing" indicator -- see docs/protocol.md. Transmit is
    // normally mutually exclusive between COM1/COM2 but the plugin doesn't enforce that; receive
    // is genuinely independent per COM (both true at once is a normal "listening on both" state).
    // Changed via SelectCom1TransmitterCommand/SelectCom2TransmitterCommand/
    // SetCom1ReceiveEnabledCommand/SetCom2ReceiveEnabledCommand (issue #29's MIC/MON buttons).
    val com1TransmitEnabled: Boolean = false,
    val com2TransmitEnabled: Boolean = false,
    val com1ReceiveEnabled: Boolean = false,
    val com2ReceiveEnabled: Boolean = false
) : ServerMessage

@Serializable
data class NearbyAircraft(
    val callsign: String,
    val aircraftType: String? = null,
    val distanceNm: Double
)

@Serializable
data class NearbyAircraftMessage(
    val type: String = "nearbyAircraft",
    val aircraft: List<NearbyAircraft>
) : ServerMessage

@Serializable
data class SubsystemStatusMessage(
    val type: String = "subsystemStatus",
    val radioHostConnected: Boolean = false,
    val simulatorConnected: Boolean = false,
    val vatsimDataFeedConnected: Boolean = false,
    val simbriefFetched: Boolean = false,
    val pluginVersion: String? = null,
    // Issue #88 -- the plugin's current update-interval tier ("fast"/"normal"/"slow"). Carried on
    // every connect (and resent on change), so the Settings tier selector reflects the
    // plugin-persisted value on every reconnect. Null from an older plugin that predates the field.
    val updateInterval: String? = null,
    // Issue #65 -- null unless debug mode is currently on. Lean, plain-language per-subsystem
    // health lines for the debug overlay's "Systems" section -- the exhaustive per-subsystem
    // detail lives only in the debug snapshot file, not here.
    val systemsDebug: SystemsDebug? = null
) : ServerMessage

@Serializable
data class SystemsDebug(
    val radioHostConnected: Boolean = false,
    val simulatorConnected: Boolean = false,
    val lastTelemetryAt: String? = null,
    val vatsimFeedConnected: Boolean = false,
    val vatsimFeedLastPollAt: String? = null,
    val simbriefFetchedSuccessfully: Boolean = false,
    val simbriefLastError: String? = null,
    val vatGlassesLoadedRegionCount: Int = 0,
    val vatSpyBoundaryCount: Int = 0,
    val pairedClientCount: Int = 0,
    val authenticatedSocketCount: Int = 0,
    val activeOperationCount: Int = 0
)

/** One step of an in-progress background plugin operation (docs/protocol.md) -- unlike every
 *  other ServerMessage here, this is NOT resendable full state, it's an event stream (closer to
 *  [PongMessage] than to [ControllersMessage]). [operationId] is deliberately generic, not tied
 *  to any one feature -- see docs/protocol.md. [finished] is the "end of update" signal; clients
 *  should also apply their own ~60s timeout while still in progress, as a backstop for a dropped
 *  finished message. [success] is only meaningful once [finished] is true -- drives which
 *  icon/linger-duration a client shows (see HandoffState.OperationProgressState). */
@Serializable
data class OperationProgressMessage(
    val type: String = "operationProgress",
    val operationId: String,
    val status: String,
    val finished: Boolean,
    val success: Boolean = true
) : ServerMessage

@Serializable
data class PongMessage(
    val type: String = "pong",
    val clientTimestamp: Long,
    val serverTimestamp: Long
) : ServerMessage

/** Reply to an AuthenticateCommand (issue #15's device-authorization layer, docs/protocol.md).
 *  [token] is only present when a *new* one was just issued (a successful pairing-code
 *  exchange) -- validating an already-known token gets [success] with no token, nothing new to
 *  persist. [reason] is only meaningful when [success] is false: "pairingRequired" means the
 *  plugin doesn't recognize the presented token (or none was sent) and is now showing a pairing
 *  code on its own screen; "invalidCode" means the submitted pairingCode didn't match. */
/** Reply to a SaveDebugSnapshotCommand (issue #65), sent once the plugin's file write completes.
 *  [path] is the local path on the plugin's machine, informational only (the Android app can't
 *  read it directly). Also the client's cue to now send AttachDebugSnapshotScreenshotCommand for
 *  the same [snapshotId], rather than racing to capture one the instant the button was tapped. */
@Serializable
data class DebugSnapshotSavedMessage(
    val type: String = "debugSnapshotSaved",
    val snapshotId: String,
    val path: String
) : ServerMessage

/** Reply to a NameDebugSnapshotCommand (issue #73b). [error] is only present when [success] is
 *  false -- either the snapshotId's correlation window expired (10 min, same as the
 *  attachDebugSnapshotScreenshot window) or the rename itself hit an I/O error; either way the
 *  original files are left exactly as they were. */
@Serializable
data class DebugSnapshotNamedMessage(
    val type: String = "debugSnapshotNamed",
    val snapshotId: String,
    val success: Boolean,
    val error: String? = null
) : ServerMessage

@Serializable
data class AuthResultMessage(
    val type: String = "authResult",
    val success: Boolean,
    val token: String? = null,
    val reason: String? = null,
    // Issue #80 -- the plugin's currently-persisted SimBrief credentials, sent only on a fresh
    // pairing-code success (never the token reconnect path), so a freshly-paired client can adopt
    // them without the pilot re-typing them. Both null on every other authResult.
    val simbriefUserId: String? = null,
    val simbriefUsername: String? = null
) : ServerMessage

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true // the "type" discriminator field has a default value per subtype
}

/** Decodes a server->client frame by its "type" field. Returns null for unrecognized types
 *  rather than throwing, since docs/protocol.md may grow new message types this client
 *  doesn't know about yet. */
fun decodeServerMessage(text: String): ServerMessage? {
    val element = json.parseToJsonElement(text).jsonObject
    return when (element["type"]?.jsonPrimitive?.content) {
        "controllers" -> json.decodeFromJsonElement<ControllersMessage>(element)
        "chat" -> json.decodeFromJsonElement<ChatMessage>(element)
        "radioState" -> json.decodeFromJsonElement<RadioStateMessage>(element)
        "flightPlan" -> json.decodeFromJsonElement<FlightPlanMessage>(element)
        "diversionPending" -> json.decodeFromJsonElement<DiversionPendingMessage>(element)
        "nearbyAircraft" -> json.decodeFromJsonElement<NearbyAircraftMessage>(element)
        "subsystemStatus" -> json.decodeFromJsonElement<SubsystemStatusMessage>(element)
        "operationProgress" -> json.decodeFromJsonElement<OperationProgressMessage>(element)
        "pong" -> json.decodeFromJsonElement<PongMessage>(element)
        "authResult" -> json.decodeFromJsonElement<AuthResultMessage>(element)
        "debugSnapshotSaved" -> json.decodeFromJsonElement<DebugSnapshotSavedMessage>(element)
        "debugSnapshotNamed" -> json.decodeFromJsonElement<DebugSnapshotNamedMessage>(element)
        else -> null
    }
}

sealed interface ClientCommand {
    fun encode(): String
}

@Serializable
data class SendPrivateMessageCommand(
    val type: String = "sendPrivateMessage",
    val to: String,
    val message: String
) : ClientCommand {
    override fun encode() = json.encodeToString(SendPrivateMessageCommand.serializer(), this)
}

@Serializable
data class SendRadioMessageCommand(
    val type: String = "sendRadioMessage",
    val message: String
) : ClientCommand {
    override fun encode() = json.encodeToString(SendRadioMessageCommand.serializer(), this)
}

@Serializable
data class SetCom1FrequencyCommand(
    val type: String = "setCom1Frequency",
    val megahertz: Double
) : ClientCommand {
    override fun encode() = json.encodeToString(SetCom1FrequencyCommand.serializer(), this)
}

@Serializable
data class SetCom2FrequencyCommand(
    val type: String = "setCom2Frequency",
    val megahertz: Double
) : ClientCommand {
    override fun encode() = json.encodeToString(SetCom2FrequencyCommand.serializer(), this)
}

@Serializable
data class SetCom1StandbyFrequencyCommand(
    val type: String = "setCom1StandbyFrequency",
    val megahertz: Double
) : ClientCommand {
    override fun encode() = json.encodeToString(SetCom1StandbyFrequencyCommand.serializer(), this)
}

@Serializable
data class SetCom2StandbyFrequencyCommand(
    val type: String = "setCom2StandbyFrequency",
    val megahertz: Double
) : ClientCommand {
    override fun encode() = json.encodeToString(SetCom2StandbyFrequencyCommand.serializer(), this)
}

/** Combined active+standby write in one round trip -- e.g. a "transfer" (activate a just-tuned
 *  frequency while preserving whatever was previously active into standby, matching real
 *  flip-flop avionics like the G3000 GTC's XFER key) or a plain swap. Prefer this over sending
 *  separate SetComXFrequencyCommand/SetComXStandbyFrequencyCommand calls for that kind of
 *  paired update -- the plugin queues and settle-waits each command independently, so two
 *  separate commands land the writes over a second apart even though the underlying SimConnect
 *  events are near-instant. */
@Serializable
data class SetCom1ActiveAndStandbyFrequencyCommand(
    val type: String = "setCom1ActiveAndStandbyFrequency",
    val megahertz: Double,
    val standbyMegahertz: Double
) : ClientCommand {
    override fun encode() = json.encodeToString(SetCom1ActiveAndStandbyFrequencyCommand.serializer(), this)
}

@Serializable
data class SetCom2ActiveAndStandbyFrequencyCommand(
    val type: String = "setCom2ActiveAndStandbyFrequency",
    val megahertz: Double,
    val standbyMegahertz: Double
) : ClientCommand {
    override fun encode() = json.encodeToString(SetCom2ActiveAndStandbyFrequencyCommand.serializer(), this)
}

@Serializable
data class SetTransponderCodeCommand(
    val type: String = "setTransponderCode",
    val transponderCode: Int
) : ClientCommand {
    override fun encode() = json.encodeToString(SetTransponderCodeCommand.serializer(), this)
}

/** Selects COM1 as the transmitter (real avionics only let one COM transmit at a time, but the
 *  plugin doesn't enforce that -- see docs/protocol.md). Carries no fields of its own. */
@Serializable
data class SelectCom1TransmitterCommand(
    val type: String = "selectCom1Transmitter"
) : ClientCommand {
    override fun encode() = json.encodeToString(SelectCom1TransmitterCommand.serializer(), this)
}

@Serializable
data class SelectCom2TransmitterCommand(
    val type: String = "selectCom2Transmitter"
) : ClientCommand {
    override fun encode() = json.encodeToString(SelectCom2TransmitterCommand.serializer(), this)
}

/** Sets COM1's receive-select state -- independent of COM2's, both true at once is a normal
 *  "listening on both" state (docs/protocol.md). */
@Serializable
data class SetCom1ReceiveEnabledCommand(
    val type: String = "setCom1ReceiveEnabled",
    val enabled: Boolean
) : ClientCommand {
    override fun encode() = json.encodeToString(SetCom1ReceiveEnabledCommand.serializer(), this)
}

@Serializable
data class SetCom2ReceiveEnabledCommand(
    val type: String = "setCom2ReceiveEnabled",
    val enabled: Boolean
) : ClientCommand {
    override fun encode() = json.encodeToString(SetCom2ReceiveEnabledCommand.serializer(), this)
}

@Serializable
data class SetSimbriefCredentialsCommand(
    val type: String = "setSimbriefCredentials",
    val simbriefUserId: String? = null,
    val simbriefUsername: String? = null
) : ClientCommand {
    override fun encode() = json.encodeToString(SetSimbriefCredentialsCommand.serializer(), this)
}

/** Issue #88 -- selects the update-interval tier ("fast"/"normal"/"slow"). Persisted plugin-side
 *  and applied live to the SimConnect polls and WebSocket broadcast cadence. The wire value is a
 *  lowercase string (see UpdateInterval.wire), deliberately decoupled from the Kotlin enum's names.
 *  The plugin echoes the current tier back via subsystemStatus.updateInterval. */
@Serializable
data class SetUpdateIntervalCommand(
    val type: String = "setUpdateInterval",
    val interval: String
) : ClientCommand {
    override fun encode() = json.encodeToString(SetUpdateIntervalCommand.serializer(), this)
}

/** Carries no fields -- the plugin fetches using whatever credentials were last sent via
 *  SetSimbriefCredentialsCommand (and persisted on its side). */
@Serializable
data class RefreshFlightPlanCommand(
    val type: String = "refreshFlightPlan"
) : ClientCommand {
    override fun encode() = json.encodeToString(RefreshFlightPlanCommand.serializer(), this)
}

/** Marks `callsign` as pinned (isPinned) -- its own ranking bucket, never displacing isCurrent --
 *  until cleared or the controller goes offline past its hidden-expiry window. Multiple
 *  controllers can be pinned at once; each is set/cleared independently, never touching any
 *  other pinned callsign -- only the pilot's own explicit unpin clears one. */
@Serializable
data class PinControllerCommand(
    val type: String = "pinController",
    val callsign: String
) : ClientCommand {
    override fun encode() = json.encodeToString(PinControllerCommand.serializer(), this)
}

/** Clears `callsign`'s pin specifically -- never any other pinned controller's. */
@Serializable
data class ClearPinnedControllerCommand(
    val type: String = "clearPinnedController",
    val callsign: String
) : ClientCommand {
    override fun encode() = json.encodeToString(ClearPinnedControllerCommand.serializer(), this)
}

/** Clears `callsign`'s active SELCAL alert plugin-side, dropping it out of the ranking priority
 *  it gets while active (docs/protocol.md). There's no tune-match auto-clear on the plugin side --
 *  real SELCAL requires the pilot to already be tuned to the alerting frequency (volume down) for
 *  the pulse to arrive at all, so this explicit command is the only way to clear it short of its
 *  own expiry. */
@Serializable
data class DismissSelcalCommand(
    val type: String = "dismissSelcal",
    val callsign: String
) : ClientCommand {
    override fun encode() = json.encodeToString(DismissSelcalCommand.serializer(), this)
}

/** Responds to a [DiversionPendingMessage] prompt -- no payload, since only one destination can
 *  be pending at a time. Treats it as a real diversion; the plugin drops the filed route from
 *  its own approach/convergence prediction. No-op if nothing is currently pending. */
@Serializable
data class ConfirmDiversionCommand(
    val type: String = "confirmDiversion"
) : ClientCommand {
    override fun encode() = json.encodeToString(ConfirmDiversionCommand.serializer(), this)
}

/** Responds to a [DiversionPendingMessage] prompt as a false alarm -- the plugin keeps using the
 *  filed route as before, and won't re-prompt for that same destination again. No-op if nothing
 *  is currently pending. */
@Serializable
data class DismissDiversionCommand(
    val type: String = "dismissDiversion"
) : ClientCommand {
    override fun encode() = json.encodeToString(DismissDiversionCommand.serializer(), this)
}

/** Latency probe for the footer's detail line -- the plugin echoes clientTimestamp back in a
 *  PongMessage; latency is (time pong received) - clientTimestamp, computed client-side. */
@Serializable
data class PingCommand(
    val type: String = "ping",
    val clientTimestamp: Long
) : ClientCommand {
    override fun encode() = json.encodeToString(PingCommand.serializer(), this)
}

/** Must be the first message sent on every connection (docs/protocol.md, issue #15) -- the
 *  plugin sends no application data at all to a socket until it's authenticated. Send [token]
 *  if one is already stored for this exact pinned certificate fingerprint; send [pairingCode]
 *  once the pilot has read one off the plugin's on-screen pairing window and typed it in; send
 *  neither ("I have nothing yet") on a first-ever connection to a given plugin, which just
 *  triggers the plugin to show its pairing window without needing a code guess.
 *
 *  [deviceId] is this install's stable identifier (Settings.Secure.ANDROID_ID -- no permission
 *  needed, resets on uninstall along with this app's own token/pin storage) so a successful
 *  pairing lets the plugin recognize "this is the same physical device re-pairing" and drop its
 *  old paired-client entry instead of accumulating a new one every time (e.g. every forced
 *  re-pair after the plugin's certificate changes). */
@Serializable
data class AuthenticateCommand(
    val type: String = "authenticate",
    val token: String? = null,
    val pairingCode: String? = null,
    val deviceId: String? = null
) : ClientCommand {
    override fun encode() = json.encodeToString(AuthenticateCommand.serializer(), this)
}

/** Toggles the plugin's session-only debug mode (issue #65) -- not persisted plugin-side across
 *  a restart, global to the plugin (not per-client) if multiple clients are paired. While on,
 *  [ControllersMessage.debug]/[Controller.debug]/[SubsystemStatusMessage.systemsDebug] populate. */
@Serializable
data class SetDebugModeCommand(
    val type: String = "setDebugMode",
    val enabled: Boolean
) : ClientCommand {
    override fun encode() = json.encodeToString(SetDebugModeCommand.serializer(), this)
}

/** Triggers a full point-in-time dump of every plugin subsystem to a local JSON file
 *  (docs/debug-snapshot.md). [snapshotId] is a client-generated GUID correlating this request
 *  with the [DebugSnapshotSavedMessage] reply and any later [AttachDebugSnapshotScreenshotCommand].
 *  [appVersion] is this app's own versionName -- the plugin doesn't otherwise know it. */
@Serializable
data class SaveDebugSnapshotCommand(
    val type: String = "saveDebugSnapshot",
    val snapshotId: String,
    val appVersion: String
) : ClientCommand {
    override fun encode() = json.encodeToString(SaveDebugSnapshotCommand.serializer(), this)
}

/** Sent only after a [DebugSnapshotSavedMessage] for the same [snapshotId] -- a separate, later
 *  round trip, not bundled into [SaveDebugSnapshotCommand] itself. [screenshotPngBase64] must be
 *  a view-scoped capture of this app's own window only (PixelCopy/View.draw against the Handoff
 *  activity's root view), never a full-display capture -- the tablet normally runs split-screen
 *  next to another EFB app, and a full-display capture would pull in unrelated content. Optional
 *  -- a dismissed/backgrounded capture attempt still leaves a fully valid JSON-only snapshot. */
@Serializable
data class AttachDebugSnapshotScreenshotCommand(
    val type: String = "attachDebugSnapshotScreenshot",
    val snapshotId: String,
    val screenshotPngBase64: String
) : ClientCommand {
    override fun encode() = json.encodeToString(AttachDebugSnapshotScreenshotCommand.serializer(), this)
}

/** Attaches a pilot-chosen [name] to an already-saved snapshot, strictly after the fact (issue
 *  #73b) -- sent only once the pilot has typed a name into the inline field that appears after
 *  [DebugSnapshotSavedMessage] comes back, never blocking or delaying the save itself. Reuses the
 *  same [snapshotId] correlation [SaveDebugSnapshotCommand]/[AttachDebugSnapshotScreenshotCommand]
 *  already use. See [DebugSnapshotNamedMessage] for the reply. */
@Serializable
data class NameDebugSnapshotCommand(
    val type: String = "nameDebugSnapshot",
    val snapshotId: String,
    val name: String
) : ClientCommand {
    override fun encode() = json.encodeToString(NameDebugSnapshotCommand.serializer(), this)
}
