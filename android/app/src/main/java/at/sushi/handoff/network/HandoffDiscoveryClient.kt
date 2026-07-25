package at.sushi.handoff.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

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

    suspend fun discoverHost(): String? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = TimeoutMillis.toInt()
                val request = RequestText.toByteArray()
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), DiscoveryPort))

                val buffer = ByteArray(64)
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                reply.address.hostAddress
            }
        } catch (e: SocketTimeoutException) {
            null
        }
    }
}
