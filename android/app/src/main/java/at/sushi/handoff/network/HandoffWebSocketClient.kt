package at.sushi.handoff.network

import android.util.Log
import at.sushi.handoff.protocol.ClientCommand
import at.sushi.handoff.protocol.ServerMessage
import at.sushi.handoff.protocol.decodeServerMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** Thin wrapper around an OkHttp WebSocket for the docs/protocol.md contract. One connection
 *  at a time; call [connect] again (after [close]) to reconnect to a different host. */
class HandoffWebSocketClient(
    private val onMessage: (ServerMessage) -> Unit,
    private val onStateChanged: (connected: Boolean) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null

    fun connect(host: String, port: Int = 48765) {
        val request = Request.Builder().url("ws://$host:$port/").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStateChanged(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                decodeServerMessage(text)?.let(onMessage)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStateChanged(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStateChanged(false)
            }
        })
    }

    fun send(command: ClientCommand) {
        val json = command.encode()
        val socket = webSocket
        if (socket == null) {
            Log.w("HandoffWS", "send() called with no active WebSocket: $json")
            return
        }
        val enqueued = socket.send(json)
        Log.d("HandoffWS", "send($json) enqueued=$enqueued")
    }

    fun close() {
        webSocket?.close(1000, null)
        webSocket = null
    }
}
