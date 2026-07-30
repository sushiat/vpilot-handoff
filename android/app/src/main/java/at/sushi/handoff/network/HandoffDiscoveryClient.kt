package at.sushi.handoff.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** The plugin's discovery reply body (HandoffDiscoveryListener, issue #15) -- port to connect on,
 *  plus the TLS certificate's fingerprint as a discovery-time hint. The hint isn't itself the
 *  trust decision (that happens against the certificate actually presented during the TLS
 *  handshake, see HandoffConnectionService), it just lets the app know what to expect without a
 *  separate round-trip. */
@Serializable
data class DiscoveryReply(val port: Int, val fingerprint: String)

private val discoveryReplyJson = Json { ignoreUnknownKeys = true }

data class DiscoveryResult(val host: String, val reply: DiscoveryReply)

/** Finds the plugin on the LAN by UDP broadcast, per docs/protocol.md's Discovery section, so
 *  the pilot doesn't have to type the PC's IP in by hand. Not guaranteed to work on every
 *  network (AP client isolation, broadcast-filtering routers) -- callers should fall back to a
 *  manually entered IP when this returns null. */
class HandoffDiscoveryClient {
    companion object {
        private const val DiscoveryPort = 48766
        private const val RequestText = "HANDOFF_DISCOVER"
        private const val TimeoutMillis = 2000L
    }

    suspend fun discover(): DiscoveryResult? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = TimeoutMillis.toInt()
                val request = RequestText.toByteArray()
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), DiscoveryPort))

                val buffer = ByteArray(256)
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                val body = String(reply.data, 0, reply.length)
                val parsed = runCatching { discoveryReplyJson.decodeFromString<DiscoveryReply>(body) }.getOrNull()
                val host = reply.address?.hostAddress
                if (parsed == null || host == null) return@use null
                DiscoveryResult(host, parsed)
            }
        } catch (e: SocketTimeoutException) {
            null
        }
    }
}
