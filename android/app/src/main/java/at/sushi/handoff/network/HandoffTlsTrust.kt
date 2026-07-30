package at.sushi.handoff.network

import java.net.InetAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedTrustManager

/** TLS trust config for HandoffWebSocketClient's OkHttpClient (issue #15). The plugin's
 *  certificate is self-signed -- the system trust store would reject it outright -- so this
 *  TrustManager accepts whatever is presented rather than validating a CA chain. That is *not*
 *  the actual security boundary: the real trust-on-first-use decision (matched/first-trust/
 *  changed) happens afterward, synchronously in HandoffConnectionService right after the socket
 *  opens, using the certificate this class captures (see [lastPeerCertificate] and
 *  HandoffWebSocketClient.onCertificateSeen). This class exists purely to let the handshake
 *  itself succeed and to capture the presented certificate -- it is not itself the security
 *  boundary.
 *
 *  The hostnameVerifier always accepts too -- there's no real DNS hostname to check for a LAN IP
 *  connection; identity here is established by fingerprint pinning, not hostname/CA validation. */
object HandoffTlsTrust {
    // X509ExtendedTrustManager, not the older X509TrustManager -- Android/Conscrypt only wires a
    // plain X509TrustManager's verified chain into checkServerTrusted, not necessarily into the
    // SSLSession itself, and (per below) OkHttp's own Handshake.peerCertificates can't be trusted
    // anyway -- this class captures the chain directly instead of relying on the session for it.
    val trustManager = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("No certificate presented")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
            checkServerTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            checkServerTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val rawSslSocketFactory: SSLSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), null)
    }.socketFactory

    /** The most recently presented server certificate -- set by an
     *  [javax.net.ssl.HandshakeCompletedListener] attached to every socket this factory creates.
     *
     *  Confirmed on-device (issue #15) that this is necessary, not just defensive: OkHttp's own
     *  `Response.handshake?.peerCertificates` came back an empty list on this app's target
     *  devices even though the handshake itself succeeded with a real certificate (TLS_1_2,
     *  ECDHE_RSA) -- OkHttp's internal `Handshake.get(session)` call raced ahead of Conscrypt
     *  actually attaching the verified chain to the `SSLSession`. A `checkServerTrusted` log
     *  during that same investigation showed the correct chain arriving with `chainSize=1`, and a
     *  handshake-completed listener on the same socket read `peerCertificates.size=1` correctly a
     *  few milliseconds *before* OkHttp's own onOpen callback reported an empty one -- so this
     *  reads it directly instead of trusting OkHttp's copy.
     *
     *  A plain `var` (not per-connection-keyed) is safe here because this app is only ever
     *  connected to one plugin instance at a time (HandoffWebSocketClient's own doc comment: "one
     *  connection at a time"), so there's never more than one handshake in flight to race against
     *  -- as long as [reset] is called before each new connection attempt, so a stale value from a
     *  previous connection can never be misread as this one's certificate if this one's handshake
     *  somehow fails to populate it (see HandoffWebSocketClient.connect/onOpen: a still-null value
     *  after the handshake completes is treated as "capture failed," and the connection is
     *  aborted rather than silently proceeding without a verified certificate). */
    @Volatile
    var lastPeerCertificate: X509Certificate? = null
        private set

    fun reset() {
        lastPeerCertificate = null
    }

    private fun instrument(socket: Socket): Socket {
        val sslSocket = socket as SSLSocket
        sslSocket.addHandshakeCompletedListener { event ->
            lastPeerCertificate = event.session.peerCertificates.firstOrNull() as? X509Certificate
        }
        return sslSocket
    }

    val sslSocketFactory: SSLSocketFactory = object : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = rawSslSocketFactory.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = rawSslSocketFactory.supportedCipherSuites

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            instrument(rawSslSocketFactory.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String?, port: Int): Socket =
            instrument(rawSslSocketFactory.createSocket(host, port))

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            instrument(rawSslSocketFactory.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            instrument(rawSslSocketFactory.createSocket(host, port))

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            instrument(rawSslSocketFactory.createSocket(address, port, localAddress, localPort))
    }

    val hostnameVerifier = HostnameVerifier { _, _ -> true }
}
