package at.sushi.handoff

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import at.sushi.handoff.network.HandoffDiscoveryClient
import at.sushi.handoff.network.HandoffWebSocketClient
import at.sushi.handoff.notifications.HandoffNotifier
import at.sushi.handoff.protocol.ChatMessage
import at.sushi.handoff.protocol.ClientCommand
import at.sushi.handoff.protocol.ControllersMessage
import at.sushi.handoff.protocol.FlightPlanMessage
import at.sushi.handoff.protocol.NearbyAircraftMessage
import at.sushi.handoff.protocol.OperationProgressMessage
import at.sushi.handoff.protocol.PingCommand
import at.sushi.handoff.protocol.PongMessage
import at.sushi.handoff.protocol.RadioStateMessage
import at.sushi.handoff.protocol.SubsystemStatusMessage
import at.sushi.handoff.ui.theme.RowColorThemeStore
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
        const val PrefKeyHideTunedControllers = "hide_tuned_controllers"
        private const val ChannelId = "handoff_connection"
        private const val NotificationId = 1
        // Handled in onStartCommand -- the notification's "Quit" action (only ever shown/tapped
        // while backgrounded, see buildConnectionNotification) targets this Service with this
        // action rather than a broadcast, since the intent needs to actually reach this specific
        // running instance to tear it down.
        private const val ActionQuit = "at.sushi.handoff.action.QUIT"
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
    private lateinit var notifier: HandoffNotifier

    private val appVisibilityObserver = object : DefaultLifecycleObserver {
        // ON_START/ON_STOP on the process-wide lifecycle fire once whenever *any* Activity of
        // this app becomes visible/fully invisible -- true for both fullscreen and split-screen
        // (Android only stops an Activity once it's completely covered/backgrounded), which is
        // exactly "not visible at all" per the user's notification rule, without needing to poll
        // window bounds or track individual Activities ourselves. Also drives the persistent
        // "Running in the background" notification's actual visibility: while the app is on
        // screen at all, that notification has nothing left to tell the pilot (they're already
        // looking at live connection state), so it's hidden via stopForeground -- the service
        // itself keeps running throughout, this only demotes it from "foreground" back to
        // "started" and removes the notification along with that. startForeground is called
        // again on the way back to fully backgrounded, since Android requires an active
        // notification for a service to legitimately keep running once nothing's visible.
        override fun onStart(owner: LifecycleOwner) {
            HandoffState.setAppVisible(true)
            ServiceCompat.stopForeground(this@HandoffConnectionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }

        override fun onStop(owner: LifecycleOwner) {
            HandoffState.setAppVisible(false)
            startForeground(NotificationId, buildConnectionNotification())
        }
    }

    // The tablet is normally docked and wired into power for the whole flight, so "keep screen
    // awake" should just track that rather than needing to be a persisted user choice: on
    // battery it defaults off (don't drain a device that isn't charging), on a charger it
    // defaults on, and plugging in later turns it on even if it had been off -- the user's own
    // manual toggle in between is left alone otherwise (this never forces it back off).
    private val powerConnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            HandoffState.setKeepScreenAwake(true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationManager = getSystemService(NotificationManager::class.java)
        notifier = HandoffNotifier(this)
        createConnectionNotificationChannel()
        notifier.createChannels()
        // Static and silent -- Android requires an active notification for a foreground service
        // to keep running at all (the WebSocket connection surviving backgrounding is the whole
        // point of this being a service, see the class doc), but the user doesn't want it calling
        // attention to itself or updating with connection status. MIN importance + no further
        // notify() calls keeps it collapsed and silent in the shade.
        //
        // Must happen before addObserver below, not after -- ProcessLifecycleOwner dispatches
        // the current state synchronously to a newly added observer, so on a cold launch (the
        // process is typically already STARTED by the time this service initializes, racing
        // MainActivity's own onStart) appVisibilityObserver.onStart fires immediately and calls
        // stopForeground. If that ran before this startForeground call even happened, it'd be a
        // no-op on a service that hasn't entered the foreground state yet -- and this call would
        // then unconditionally post the notification right afterward with nothing left to hide
        // it until the next real background/foreground transition, leaving it stuck showing on
        // a fresh launch even though the app is already on screen.
        startForeground(NotificationId, buildConnectionNotification())
        ProcessLifecycleOwner.get().lifecycle.addObserver(appVisibilityObserver)
        registerReceiver(powerConnectedReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED))
        HandoffState.setKeepScreenAwake(isCharging())
        loadPersistedUiSettings()
        client = HandoffWebSocketClient(
            onMessage = { message ->
                when (message) {
                    is ControllersMessage -> { HandoffState.update(message); notifier.onControllersUpdate(message) }
                    is ChatMessage -> { HandoffState.update(message); notifier.onChatUpdate(message) }
                    is RadioStateMessage -> HandoffState.update(message)
                    is FlightPlanMessage -> HandoffState.update(message)
                    is NearbyAircraftMessage -> HandoffState.update(message)
                    is SubsystemStatusMessage -> HandoffState.update(message)
                    is OperationProgressMessage -> HandoffState.update(message)
                    is PongMessage -> HandoffState.setLatencyMs(System.currentTimeMillis() - message.clientTimestamp)
                }
            },
            onStateChanged = { connected -> onConnectionStateChanged(connected) }
        )
        reconnectLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ActionQuit) {
            // Only the notification itself (see buildConnectionNotification) -- shown, and thus
            // tappable, only while fully backgrounded and hammering away with no pilot watching,
            // exactly the "I'm not flying for days, stop this" case. onDestroy (triggered by
            // stopSelf) handles the rest of the teardown -- cancelling jobs, closing the socket,
            // unregistering receivers.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        connectionJob?.cancel()
        pingJob?.cancel()
        client.close()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appVisibilityObserver)
        unregisterReceiver(powerConnectedReceiver)
        instance = null
        super.onDestroy()
    }

    /** ACTION_BATTERY_CHANGED is a sticky broadcast -- registering for it with a null receiver
     *  returns the current battery state synchronously instead of waiting for the next change,
     *  which is what makes this usable as a one-shot "is it charging right now" check at
     *  startup. EXTRA_PLUGGED is 0 when on battery, non-zero for AC/USB/wireless. */
    private fun isCharging(): Boolean {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return plugged != 0
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
                    Log.w("HandoffConn", "resolveHost() found nothing (no manual IP set and discovery got no reply) -- retrying in ${backoff}ms")
                    HandoffState.setConnectionStatus(ConnectionStatus.DISCONNECTED)
                } else {
                    // Only updated on a successful resolve -- a later resolution failure (e.g.
                    // discovery briefly not answering) shouldn't blank out the last known-good
                    // address, which is still useful to see while the app quietly retries.
                    HandoffState.setResolvedHost(host)
                    Log.i("HandoffConn", "attempting connection to $host")
                    client.connect(host)
                    // Wait for a disconnect/failure signal before retrying; onStateChanged
                    // drives HandoffState directly, this loop only owns the retry timing.
                    while (HandoffState.connectionStatus.value == ConnectionStatus.CONNECTED ||
                        HandoffState.connectionStatus.value == ConnectionStatus.CONNECTING
                    ) {
                        delay(1_000)
                    }
                    Log.w("HandoffConn", "connection to $host lost/never established -- retrying in ${backoff}ms")
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
        HandoffState.setHideTunedControllers(prefs.getBoolean(PrefKeyHideTunedControllers, false))
        // Issue #21 -- resolves the persisted active row-color theme id against the saved list /
        // built-in presets, falling back to DefaultRowColorPalette (RowColorThemeStore's own
        // default) if unset/deleted.
        HandoffState.setRowColorPalette(RowColorThemeStore.resolveActivePalette(prefs))
    }

    private fun onConnectionStateChanged(connected: Boolean) {
        Log.i("HandoffConn", "onConnectionStateChanged: connected=$connected")
        HandoffState.setConnectionStatus(if (connected) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED)
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

    private fun createConnectionNotificationChannel() {
        val channel = NotificationChannel(ChannelId, "Handoff background connection", NotificationManager.IMPORTANCE_MIN)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildConnectionNotification(): Notification {
        val quitIntent = Intent(this, HandoffConnectionService::class.java).setAction(ActionQuit)
        val quitPendingIntent = PendingIntent.getService(this, 0, quitIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, ChannelId)
            .setContentTitle("Handoff")
            .setContentText("Running in the background")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            // Not flying for a while and don't want this quietly retrying in the background --
            // right here is the one moment this notification is actually visible at all (see
            // appVisibilityObserver), so it's the most discoverable place to offer a way out.
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Quit", quitPendingIntent)
            .build()
    }
}
