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
    // VatSpy-sourced facility/airport display name (e.g. "Heathrow Tower") -- always null until
    // that integration exists on the plugin side. Falls back to facilitySuffixName(callsign)
    // client-side until then; see docs/protocol.md.
    val stationName: String? = null,
    // Priority-ranking flags -- see docs/protocol.md. Not yet used for anything beyond
    // decoding: the list arrives pre-sorted by the plugin, so rendering it in order is all
    // that's needed for now; colour-coding by these flags is a follow-up.
    val requestsContactMe: Boolean = false,
    val isCurrent: Boolean = false,
    val isContactMe: Boolean = false,
    val isLikelyNextCandidate: Boolean = false,
    val isApproaching: Boolean = false
)

@Serializable
data class ControllersMessage(
    val type: String = "controllers",
    // Pre-sorted by the plugin's priority ranking (docs/protocol.md) -- render in list order,
    // don't re-sort client-side.
    val controllers: List<Controller>
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

@Serializable
data class FlightPlanMessage(
    val type: String = "flightPlan",
    val callsign: String? = null,
    val origin: String? = null,
    val destination: String? = null,
    val alternate: String? = null
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

/** Forces `callsign` to rank 0 / isCurrent in the next controllers message, overriding the
 *  tuned-frequency heuristic, until cleared or the controller goes offline. */
@Serializable
data class PinControllerCommand(
    val type: String = "pinController",
    val callsign: String
) : ClientCommand {
    override fun encode() = json.encodeToString(PinControllerCommand.serializer(), this)
}

/** Carries no fields of its own. */
@Serializable
data class ClearPinnedControllerCommand(
    val type: String = "clearPinnedController"
) : ClientCommand {
    override fun encode() = json.encodeToString(ClearPinnedControllerCommand.serializer(), this)
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
