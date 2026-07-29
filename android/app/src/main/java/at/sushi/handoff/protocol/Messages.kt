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
    val isSelcalActive: Boolean = false
)

@Serializable
data class ControllersMessage(
    val type: String = "controllers",
    // Pre-sorted by the plugin's priority ranking (docs/protocol.md) -- render in list order,
    // don't re-sort client-side.
    val controllers: List<Controller>,
    // Ownship-level (not per-controller): minutes to the closest bucket-8-qualifying CTR sector,
    // available during level flight or climbing/descending above FL150 -- null otherwise.
    val etaMinutes: Double? = null
) : ServerMessage

@Serializable
data class ChatEntry(
    val channel: String,
    val direction: String,
    val peer: String? = null,
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
    val vatsimDestination: String? = null
) : ServerMessage

@Serializable
data class RadioStateMessage(
    val type: String = "radioState",
    val com1Frequency: Int? = null,
    val com2Frequency: Int? = null,
    val com1StandbyFrequency: Int? = null,
    val com2StandbyFrequency: Int? = null,
    val modeCEnabled: Boolean,
    val transponderCode: Int? = null
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
    val pluginVersion: String? = null
) : ServerMessage

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
        "nearbyAircraft" -> json.decodeFromJsonElement<NearbyAircraftMessage>(element)
        "subsystemStatus" -> json.decodeFromJsonElement<SubsystemStatusMessage>(element)
        "operationProgress" -> json.decodeFromJsonElement<OperationProgressMessage>(element)
        "pong" -> json.decodeFromJsonElement<PongMessage>(element)
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

@Serializable
data class SetSimbriefCredentialsCommand(
    val type: String = "setSimbriefCredentials",
    val simbriefUserId: String? = null,
    val simbriefUsername: String? = null
) : ClientCommand {
    override fun encode() = json.encodeToString(SetSimbriefCredentialsCommand.serializer(), this)
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

/** Latency probe for the footer's detail line -- the plugin echoes clientTimestamp back in a
 *  PongMessage; latency is (time pong received) - clientTimestamp, computed client-side. */
@Serializable
data class PingCommand(
    val type: String = "ping",
    val clientTimestamp: Long
) : ClientCommand {
    override fun encode() = json.encodeToString(PingCommand.serializer(), this)
}
