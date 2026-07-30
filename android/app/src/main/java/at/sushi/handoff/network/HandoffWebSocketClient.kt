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
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/** Thin wrapper around an OkHttp WebSocket for the docs/protocol.md contract. One connection
 *  at a time; call [connect] again (after [close]) to reconnect to a different host.
 *
 *  [onCertificateSeen] fires right after a successful TLS handshake (issue #15) with the
 *  presented certificate's fingerprint + Subject CN (the plugin's Windows machine name, baked
 *  into the cert at generation time) -- the caller (HandoffConnectionService) is what actually
 *  runs the trust-on-first-use decision against it; HandoffTlsTrust's TrustManager only lets the
 *  self-signed handshake succeed in the first place.
 *
 *  Reads the certificate from [HandoffTlsTrust.lastPeerCertificate], not from
 *  `response.handshake?.peerCertificates` -- confirmed on-device that OkHttp's own Handshake can
 *  come back with an empty peerCertificates list even on a fully successful handshake (see
 *  HandoffTlsTrust's doc comment for the investigation). If no certificate is available by the
 *  time [onOpen] runs -- capture genuinely failed, not just "still in flight" (see
 *  [awaitPeerCertificate]) -- the connection is aborted rather than treated as trusted: a TLS
 *  connection this app can't attribute to a specific certificate is exactly the case TOFU pinning
 *  exists to catch, so failing open here would defeat the entire point of issue #15. */
class HandoffWebSocketClient(
    private val onMessage: (ServerMessage) -> Unit,
    private val onStateChanged: (connected: Boolean) -> Unit,
    private val onCertificateSeen: (fingerprint: String, commonName: String?) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .sslSocketFactory(HandoffTlsTrust.sslSocketFactory, HandoffTlsTrust.trustManager)
        .hostnameVerifier(HandoffTlsTrust.hostnameVerifier)
        .build()
    private var webSocket: WebSocket? = null

    fun connect(host: String, port: Int = 48765) {
        // Cleared before every attempt -- otherwise a handshake that somehow failed to populate
        // lastPeerCertificate would read back the *previous* connection's certificate instead of
        // noticing the capture failed (see HandoffTlsTrust.reset's doc comment).
        HandoffTlsTrust.reset()
        val request = Request.Builder().url("wss://$host:$port/").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val cert = awaitPeerCertificate()
                if (cert == null) {
                    Log.w("HandoffWS", "onOpen: no certificate captured after handshake -- aborting rather than connecting unverified")
                    webSocket.close(1000, "certificate capture failed")
                    onStateChanged(false)
                    return
                }
                Log.i("HandoffWS", "onOpen: connected to wss://$host:$port/")
                onCertificateSeen(sha256Fingerprint(cert), subjectCommonName(cert))
                onStateChanged(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                decodeServerMessage(text)?.let(onMessage)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("HandoffWS", "onClosed: code=$code reason=$reason")
                onStateChanged(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // The one place a silent connection death was actually invisible before -- no
                // reason, no exception, nothing -- which made a real dropped-connection incident
                // undebuggable after the fact (2026-07-28 flight-test session). t.message alone is
                // usually enough (e.g. "Connection reset", "failed to connect", timeout), response
                // is often null (most failures happen before/without a real HTTP response).
                Log.w("HandoffWS", "onFailure: ${t.javaClass.simpleName}: ${t.message} (response=${response?.code})")
                onStateChanged(false)
            }
        })
    }

    /** [HandoffTlsTrust.lastPeerCertificate] is set by a [javax.net.ssl.HandshakeCompletedListener]
     *  that fires asynchronously -- on-device it consistently completed a few milliseconds before
     *  OkHttp's own onOpen callback, but that's not a documented guarantee, so this polls briefly
     *  rather than assuming it's already there the instant onOpen runs. Still bounded (0.5s total)
     *  and still fails closed -- this is about tolerating a benign scheduling race, not about
     *  waiting indefinitely for a certificate that's never coming. */
    private fun awaitPeerCertificate(): X509Certificate? {
        repeat(25) {
            HandoffTlsTrust.lastPeerCertificate?.let { return it }
            Thread.sleep(20)
        }
        return HandoffTlsTrust.lastPeerCertificate
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
