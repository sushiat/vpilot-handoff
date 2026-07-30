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
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import at.sushi.handoff.network.CertTrustPolicy
import at.sushi.handoff.network.CertTrustStore
import at.sushi.handoff.network.HandoffDiscoveryClient
import at.sushi.handoff.network.HandoffWebSocketClient
import at.sushi.handoff.network.TrustDecision
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
import at.sushi.handoff.protocol.ServerMessage
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
    private var quitRequested = false
    private lateinit var client: HandoffWebSocketClient
    private lateinit var notificationManager: NotificationManager
    private lateinit var notifier: HandoffNotifier

    // Set immediately before each connect() call so onCertificateSeen (fired from inside the
    // WebSocket's onOpen) knows which host/port the just-verified certificate belongs to, for
    // display in the trust dialog (issue #15) -- HandoffWebSocketClient itself doesn't otherwise
    // surface these back out of connect().
    private var pendingConnectHost: String? = null
    private var pendingConnectPort: Int = 48765

    // Guards against showing/acting on any data pulled over a connection whose certificate
    // hasn't actually been trusted yet (issue #15) -- onCertificateSeen/onMessage fire on
    // HandoffWebSocketClient's OkHttp reader thread, respondToCertTrust fires from the UI
    // (CertificateTrustDialog) on the main thread, so both the flag and the queue are guarded by
    // this single lock rather than left as a plain var/mutableList raced between the two.
    private val trustLock = Any()
    private var connectionTrusted = false
    private val pendingMessages = mutableListOf<ServerMessage>()

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
            onMessage = { message -> onServerMessage(message) },
            onStateChanged = { connected -> onConnectionStateChanged(connected) },
            onCertificateSeen = { fingerprint, commonName -> onCertificateSeen(fingerprint, commonName) }
        )
        reconnectLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ActionQuit) {
            // Only the notification itself (see buildConnectionNotification) -- shown, and thus
            // tappable, only while fully backgrounded and hammering away with no pilot watching,
            // exactly the "I'm not flying for days, stop this" case. onDestroy (triggered by
            // stopSelf) handles the rest of the teardown -- cancelling jobs, closing the socket,
            // unregistering receivers -- and then, since quitRequested is set, kills the whole
            // process. A service-only stop isn't enough: MainActivity survives backgrounding
            // (it's merely stopped, not destroyed), so on relaunch Android just resumes that
            // existing Activity instead of recreating it -- and startConnectionService() only
            // runs from onCreate(), so the service would never come back. Killing the process
            // guarantees the next launch is a genuine fresh start.
            quitRequested = true
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
        if (quitRequested) {
            Process.killProcess(Process.myPid())
        }
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
                val target = resolveHost()
                if (target == null) {
                    Log.w("HandoffConn", "resolveHost() found nothing (no manual IP set and discovery got no reply) -- retrying in ${backoff}ms")
                    HandoffState.setConnectionStatus(ConnectionStatus.DISCONNECTED)
                } else {
                    val (host, port) = target
                    // Only updated on a successful resolve -- a later resolution failure (e.g.
                    // discovery briefly not answering) shouldn't blank out the last known-good
                    // address, which is still useful to see while the app quietly retries.
                    HandoffState.setResolvedHost(host)
                    pendingConnectHost = host
                    pendingConnectPort = port
                    // Reset per-attempt -- otherwise a leftover trusted flag/queued message from a
                    // previous connection could leak into this one before its own certificate has
                    // actually been evaluated.
                    synchronized(trustLock) {
                        connectionTrusted = false
                        pendingMessages.clear()
                    }
                    Log.i("HandoffConn", "attempting connection to $host:$port")
                    client.connect(host, port)
                    // Wait for a disconnect/failure signal before retrying; onStateChanged
                    // drives HandoffState directly, this loop only owns the retry timing.
                    while (HandoffState.connectionStatus.value == ConnectionStatus.CONNECTED ||
                        HandoffState.connectionStatus.value == ConnectionStatus.CONNECTING
                    ) {
                        delay(1_000)
                    }
                    Log.w("HandoffConn", "connection to $host:$port lost/never established -- retrying in ${backoff}ms")
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MaxBackoffMillis)
            }
        }
    }

    /** Manual IP always connects on the default port -- there's no discovery reply to read a port
     *  from in that path. Discovery's own reply carries the real port (and a fingerprint hint,
     *  see HandoffDiscoveryClient), previously parsed but unused; now both are threaded through. */
    private suspend fun resolveHost(): Pair<String, Int>? {
        val prefs = getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        prefs.getString(PrefKeyHost, null)?.let { return it to 48765 }
        val result = HandoffDiscoveryClient().discover() ?: return null
        return result.host to result.reply.port
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

    /** Runs the TOFU decision (issue #15) right after a successful TLS handshake -- OkHttp
     *  doesn't support pausing mid-handshake for a UI decision, so by this point the connection
     *  is already up; Matched marks it trusted immediately (onConnectionStateChanged, which fires
     *  right after this per HandoffWebSocketClient's callback ordering, then proceeds normally).
     *  FirstTrust/Changed instead push HandoffState's pendingCertTrust for CertificateTrustDialog
     *  to react to -- the socket stays open, but onServerMessage queues rather than displays
     *  anything it receives until [respondToCertTrust] resolves it, so nothing pulled over an
     *  as-yet-unconfirmed certificate reaches the UI. */
    private fun onCertificateSeen(fingerprint: String, commonName: String?) {
        val prefs = getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        val host = pendingConnectHost ?: return
        val port = pendingConnectPort
        when (val decision = CertTrustPolicy.evaluate(CertTrustStore.loadPinnedFingerprint(prefs), fingerprint)) {
            is TrustDecision.Matched -> synchronized(trustLock) { connectionTrusted = true }
            is TrustDecision.FirstTrust -> HandoffState.setPendingCertTrust(
                PendingCertTrust(host, port, commonName, decision.fingerprint, isChanged = false)
            )
            is TrustDecision.Changed -> HandoffState.setPendingCertTrust(
                PendingCertTrust(host, port, commonName, decision.newFingerprint, isChanged = true)
            )
        }
    }

    /** Called by CertificateTrustDialog once the pilot taps Trust or Cancel. On trust, the
     *  fingerprint is pinned, the connection is marked trusted, and anything queued while the
     *  prompt was up gets flushed into HandoffState/the UI in the order it actually arrived; on
     *  reject, the connection is torn down immediately (queued messages just get dropped with it)
     *  -- the next reconnectLoop attempt will re-present the same prompt rather than silently
     *  retrying with an untrusted/changed certificate. */
    fun respondToCertTrust(trust: Boolean) {
        val pending = HandoffState.pendingCertTrust.value ?: return
        HandoffState.setPendingCertTrust(null)
        if (trust) {
            val prefs = getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            CertTrustStore.savePinnedFingerprint(prefs, pending.fingerprint)
            markConnectionTrusted()
        } else {
            client.close()
        }
    }

    /** Flips the connection from "TLS is up but not yet trusted" to actually live: flushes
     *  whatever onServerMessage queued while the trust prompt was pending (in arrival order), then
     *  does what onConnectionStateChanged(true) would have done for an already-trusted
     *  (TrustDecision.Matched) connection -- mark CONNECTED and start the ping loop. Only called
     *  from the FirstTrust/Changed path; Matched never needs it since onConnectionStateChanged
     *  already saw connectionTrusted=true by the time it ran. */
    private fun markConnectionTrusted() {
        val queued = synchronized(trustLock) {
            connectionTrusted = true
            val copy = pendingMessages.toList()
            pendingMessages.clear()
            copy
        }
        queued.forEach(::handleServerMessage)
        HandoffState.setConnectionStatus(ConnectionStatus.CONNECTED)
        startPingLoop()
    }

    /** Entry point for every frame from HandoffWebSocketClient -- queues instead of displaying
     *  anything received before the certificate's trust decision resolves (issue #15), so a pilot
     *  who hasn't yet tapped Trust never sees live data pulled over a connection this app can't
     *  yet vouch for. */
    private fun onServerMessage(message: ServerMessage) {
        val handleNow = synchronized(trustLock) {
            if (connectionTrusted) true else { pendingMessages.add(message); false }
        }
        if (handleNow) handleServerMessage(message)
    }

    private fun handleServerMessage(message: ServerMessage) {
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
    }

    private fun onConnectionStateChanged(connected: Boolean) {
        Log.i("HandoffConn", "onConnectionStateChanged: connected=$connected")
        if (connected) {
            // Only actually surfaced as CONNECTED once the certificate is trusted -- for a
            // FirstTrust/Changed decision that's synchronized(trustLock) { connectionTrusted } =
            // false at this point (onCertificateSeen already ran and left it that way), so this
            // deliberately stays CONNECTING until respondToCertTrust -> markConnectionTrusted
            // flips it; reconnectLoop's own wait-loop treats CONNECTING as "still fine, keep
            // waiting" so it won't retry out from under a connection that's just awaiting a trust
            // decision.
            if (synchronized(trustLock) { connectionTrusted }) {
                HandoffState.setConnectionStatus(ConnectionStatus.CONNECTED)
                startPingLoop()
            }
        } else {
            HandoffState.setConnectionStatus(ConnectionStatus.DISCONNECTED)
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
