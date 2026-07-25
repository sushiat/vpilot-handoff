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
    // Priority-ranking flags -- see docs/protocol.md. Not yet used for anything beyond
    // decoding: the list arrives pre-sorted by the plugin, so rendering it in order is all
    // that's needed for now; colour-coding by these flags is a follow-up.
    val requestsContactMe: Boolean = false,
    val isCurrent: Boolean = false,
    val isContactMe: Boolean = false,
    val isLikelyNextCandidate: Boolean = false
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
