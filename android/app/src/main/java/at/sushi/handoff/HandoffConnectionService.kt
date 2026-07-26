package at.sushi.handoff

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import at.sushi.handoff.network.HandoffDiscoveryClient
import at.sushi.handoff.network.HandoffWebSocketClient
import at.sushi.handoff.protocol.ChatMessage
import at.sushi.handoff.protocol.ClientCommand
import at.sushi.handoff.protocol.ControllersMessage
import at.sushi.handoff.protocol.FlightPlanMessage
import at.sushi.handoff.protocol.NearbyAircraftMessage
import at.sushi.handoff.protocol.PingCommand
import at.sushi.handoff.protocol.PongMessage
import at.sushi.handoff.protocol.RadioStateMessage
import at.sushi.handoff.protocol.SubsystemStatusMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Foreground service so the WebSocket connection to the plugin survives the app losing
 *  foreground -- the whole point of Handoff is being usable while another EFB app is in front.
 *  Owns the connection and pushes every decoded frame into HandoffState; the UI just observes
 *  HandoffState's flows. */
class HandoffConnectionService : Service() {
    companion object {
        const val PrefsName = "handoff_prefs"
        const val PrefKeyHost = "server_ip"
        const val PrefKeySimbriefUserId = "simbrief_user_id"
        const val PrefKeySimbriefUsername = "simbrief_username"
        const val PrefKeyTheme = "theme_mode"
        const val PrefKeyChannelSpacing = "default_channel_spacing"
        const val PrefKeyKeypadBlockMode = "keypad_block_mode"
        private const val ChannelId = "handoff_connection"
        private const val NotificationId = 1
        private const val MinBackoffMillis = 2_000L
        private const val MaxBackoffMillis = 30_000L
        private const val PingIntervalMillis = 10_000L

        /** Same-process access for the UI to send commands / trigger a reconnect -- no
         *  bindService/Messenger needed since Service and Activity share the process. */
        var instance: HandoffConnectionService? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob())
    private var connectionJob: Job? = null
    private var pingJob: Job? = null
    private lateinit var client: HandoffWebSocketClient
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        loadPersistedUiSettings()
        client = HandoffWebSocketClient(
            onMessage = { message ->
                when (message) {
                    is ControllersMessage -> HandoffState.update(message)
                    is ChatMessage -> HandoffState.update(message)
                    is RadioStateMessage -> HandoffState.update(message)
                    is FlightPlanMessage -> HandoffState.update(message)
                    is NearbyAircraftMessage -> HandoffState.update(message)
                    is SubsystemStatusMessage -> HandoffState.update(message)
                    is PongMessage -> HandoffState.setLatencyMs(System.currentTimeMillis() - message.clientTimestamp)
                }
            },
            onStateChanged = { connected -> onConnectionStateChanged(connected) }
        )
        startForeground(NotificationId, buildNotification("Connecting…"))
        reconnectLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        connectionJob?.cancel()
        pingJob?.cancel()
        client.close()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Called by the settings screen after the server IP changes, to reconnect immediately
     *  rather than waiting for the current backoff delay to elapse. */
    fun reconnectNow() {
        connectionJob?.cancel()
        reconnectLoop()
    }

    fun sendCommand(command: ClientCommand) {
        client.send(command)
    }

    private fun reconnectLoop() {
        connectionJob = scope.launch {
            var backoff = MinBackoffMillis
            while (true) {
                HandoffState.setConnectionStatus(ConnectionStatus.CONNECTING)
                val host = resolveHost()
                if (host == null) {
                    HandoffState.setConnectionStatus(ConnectionStatus.DISCONNECTED)
                } else {
                    client.connect(host)
                    // Wait for a disconnect/failure signal before retrying; onStateChanged
                    // drives HandoffState directly, this loop only owns the retry timing.
                    while (HandoffState.connectionStatus.value == ConnectionStatus.CONNECTED ||
                        HandoffState.connectionStatus.value == ConnectionStatus.CONNECTING
                    ) {
                        delay(1_000)
                    }
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MaxBackoffMillis)
            }
        }
    }

    private suspend fun resolveHost(): String? {
        val prefs = getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        prefs.getString(PrefKeyHost, null)?.let { return it }
        return HandoffDiscoveryClient().discoverHost()
    }

    /** These are local-only UI settings (never pushed by the server) that SettingsDialog
     *  persists -- loaded once here so HandoffState reflects the user's last choice from app
     *  start, rather than resetting to defaults every launch. */
    private fun loadPersistedUiSettings() {
        val prefs = getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        prefs.getString(PrefKeyTheme, null)?.let { name ->
            runCatching { ThemeMode.valueOf(name) }.getOrNull()?.let(HandoffState::setTheme)
        }
        prefs.getString(PrefKeyChannelSpacing, null)?.let { name ->
            runCatching { ChannelSpacing.valueOf(name) }.getOrNull()?.let(HandoffState::setDefaultChannelSpacing)
        }
        prefs.getString(PrefKeyKeypadBlockMode, null)?.let { name ->
            runCatching { KeypadBlockMode.valueOf(name) }.getOrNull()?.let(HandoffState::setKeypadBlockMode)
        }
    }

    private fun onConnectionStateChanged(connected: Boolean) {
        HandoffState.setConnectionStatus(if (connected) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED)
        notificationManager.notify(NotificationId, buildNotification(if (connected) "Connected" else "Disconnected"))
        if (connected) {
            startPingLoop()
        } else {
            pingJob?.cancel()
            HandoffState.setLatencyMs(null)
        }
    }

    /** App-level ping/pong (docs/protocol.md) for the footer's latency readout -- distinct from
     *  OkHttp's own WebSocket-protocol ping (HandoffWebSocketClient's pingInterval), which keeps
     *  the connection alive but doesn't surface RTT through OkHttp's public listener API. */
    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(PingIntervalMillis)
                client.send(PingCommand(clientTimestamp = System.currentTimeMillis()))
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(ChannelId, "Handoff connection", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, ChannelId)
            .setContentTitle("Handoff")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
}
