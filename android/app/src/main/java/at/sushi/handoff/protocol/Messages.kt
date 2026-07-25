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
    val longitude: Double
)

@Serializable
data class ControllersMessage(
    val type: String = "controllers",
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
data class RadioStateMessage(
    val type: String = "radioState",
    val com1Frequency: Int? = null,
    val com2Frequency: Int? = null,
    val modeCEnabled: Boolean
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
