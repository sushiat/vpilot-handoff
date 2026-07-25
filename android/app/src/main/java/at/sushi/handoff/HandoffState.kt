package at.sushi.handoff

import at.sushi.handoff.protocol.ChatMessage
import at.sushi.handoff.protocol.ControllersMessage
import at.sushi.handoff.protocol.RadioStateMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

/** In-process shared state between HandoffConnectionService (writer) and the Compose UI
 *  (reader) -- no bindService/Messenger IPC needed since both run in the same process. */
object HandoffState {
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _controllers = MutableStateFlow(ControllersMessage(controllers = emptyList()))
    val controllers: StateFlow<ControllersMessage> = _controllers.asStateFlow()

    private val _chat = MutableStateFlow(ChatMessage(messages = emptyList(), selcalAlerts = emptyList()))
    val chat: StateFlow<ChatMessage> = _chat.asStateFlow()

    private val _radioState = MutableStateFlow(RadioStateMessage(modeCEnabled = false))
    val radioState: StateFlow<RadioStateMessage> = _radioState.asStateFlow()

    fun setConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
    }

    fun update(message: ControllersMessage) {
        _controllers.value = message
    }

    fun update(message: ChatMessage) {
        _chat.value = message
    }

    fun update(message: RadioStateMessage) {
        _radioState.value = message
    }
}
