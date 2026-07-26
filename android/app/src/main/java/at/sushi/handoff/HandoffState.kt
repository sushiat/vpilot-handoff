package at.sushi.handoff

import at.sushi.handoff.protocol.ChatMessage
import at.sushi.handoff.protocol.ControllersMessage
import at.sushi.handoff.protocol.FlightPlanMessage
import at.sushi.handoff.protocol.NearbyAircraftMessage
import at.sushi.handoff.protocol.OperationProgressMessage
import at.sushi.handoff.protocol.RadioStateMessage
import at.sushi.handoff.protocol.SubsystemStatusMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

enum class ThemeMode { LIGHT, DARK, SYSTEM }

enum class ChannelSpacing { KHZ_25, KHZ_8_33 }

enum class KeypadBlockMode { BLOCK_INVALID, ALLOW_ALL }

/** In-Activity layout mode -- whether the app currently believes it's sharing the screen with
 *  another app (split) or has the whole display (fullscreen). Real detection lives wherever
 *  MainActivity queries window bounds; this just holds the result (and lets a debug build
 *  override it for testing, mirroring the design doc's own demo toggle). */
enum class LayoutMode { SPLIT, FULLSCREEN }

enum class SplitSide { LEFT, RIGHT }

/** An in-progress operationProgress message plus the wall-clock time it was received, so the UI
 *  can apply its own ~60s "haven't heard from this operation in a while" timeout independent of
 *  a `finished` message ever arriving (docs/protocol.md) -- see FooterStatusBar's use of this. */
data class OperationProgressState(val message: OperationProgressMessage, val receivedAtMillis: Long)

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

    private val _flightPlan = MutableStateFlow(FlightPlanMessage())
    val flightPlan: StateFlow<FlightPlanMessage> = _flightPlan.asStateFlow()

    private val _nearbyAircraft = MutableStateFlow(NearbyAircraftMessage(aircraft = emptyList()))
    val nearbyAircraft: StateFlow<NearbyAircraftMessage> = _nearbyAircraft.asStateFlow()

    private val _subsystemStatus = MutableStateFlow(SubsystemStatusMessage())
    val subsystemStatus: StateFlow<SubsystemStatusMessage> = _subsystemStatus.asStateFlow()

    // Null when no operation is active. Set on every non-finished operationProgress message,
    // cleared immediately on a finished one -- the ~60s no-update timeout (a backstop for a
    // dropped finished message) is applied by the UI reading receivedAtMillis, not here.
    private val _operationProgress = MutableStateFlow<OperationProgressState?>(null)
    val operationProgress: StateFlow<OperationProgressState?> = _operationProgress.asStateFlow()

    // Round-trip time from the last ping/pong exchange (see HandoffConnectionService), null
    // until the first pong arrives or after a disconnect.
    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs: StateFlow<Long?> = _latencyMs.asStateFlow()

    // Locally-tracked UI settings -- never pushed by the server, persisted to SharedPreferences
    // by whatever screen changes them (SettingsDialog, ComTuningDialog's per-instance override).
    private val _theme = MutableStateFlow(ThemeMode.SYSTEM)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _defaultChannelSpacing = MutableStateFlow(ChannelSpacing.KHZ_25)
    val defaultChannelSpacing: StateFlow<ChannelSpacing> = _defaultChannelSpacing.asStateFlow()

    private val _keypadBlockMode = MutableStateFlow(KeypadBlockMode.BLOCK_INVALID)
    val keypadBlockMode: StateFlow<KeypadBlockMode> = _keypadBlockMode.asStateFlow()

    // HandoffConnectionService sets this from the live charging state at startup (on battery:
    // off, on a charger: on) and forces it on whenever a charger connects -- not a persisted user
    // preference, see the service's powerConnectedReceiver. This placeholder default only matters
    // for the brief window before that first check runs. MainScreen applies the result to the
    // window via View.keepScreenOn.
    private val _keepScreenAwake = MutableStateFlow(true)
    val keepScreenAwake: StateFlow<Boolean> = _keepScreenAwake.asStateFlow()

    // Mirrors the last pinController/clearPinnedController call, for optimistic row highlighting
    // before the next controllers resend confirms it.
    private val _pinnedCallsign = MutableStateFlow<String?>(null)
    val pinnedCallsign: StateFlow<String?> = _pinnedCallsign.asStateFlow()

    // Whether any of this app's Activities are currently started (i.e. on-screen at all --
    // fullscreen or split-screen both count as "visible"; only a fully backgrounded/covered app
    // is not). Driven by ProcessLifecycleOwner in HandoffConnectionService.onCreate. Notifications
    // for contact-me/SELCAL/incoming messages only fire while this is false -- the user doesn't
    // want notifications competing with what's already on screen.
    private val _appVisible = MutableStateFlow(true)
    val appVisible: StateFlow<Boolean> = _appVisible.asStateFlow()

    private val _layoutMode = MutableStateFlow(LayoutMode.FULLSCREEN)
    val layoutMode: StateFlow<LayoutMode> = _layoutMode.asStateFlow()

    private val _splitSide = MutableStateFlow(SplitSide.LEFT)
    val splitSide: StateFlow<SplitSide> = _splitSide.asStateFlow()

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

    fun update(message: FlightPlanMessage) {
        _flightPlan.value = message
    }

    fun update(message: NearbyAircraftMessage) {
        _nearbyAircraft.value = message
    }

    fun update(message: SubsystemStatusMessage) {
        _subsystemStatus.value = message
    }

    fun update(message: OperationProgressMessage) {
        _operationProgress.value = if (message.finished) null else OperationProgressState(message, System.currentTimeMillis())
    }

    fun setLatencyMs(millis: Long?) {
        _latencyMs.value = millis
    }

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
    }

    fun setDefaultChannelSpacing(spacing: ChannelSpacing) {
        _defaultChannelSpacing.value = spacing
    }

    fun setKeypadBlockMode(mode: KeypadBlockMode) {
        _keypadBlockMode.value = mode
    }

    fun setKeepScreenAwake(enabled: Boolean) {
        _keepScreenAwake.value = enabled
    }

    fun setPinnedCallsign(callsign: String?) {
        _pinnedCallsign.value = callsign
    }

    fun setAppVisible(visible: Boolean) {
        _appVisible.value = visible
    }

    fun setLayoutMode(mode: LayoutMode) {
        _layoutMode.value = mode
    }

    fun setSplitSide(side: SplitSide) {
        _splitSide.value = side
    }
}
