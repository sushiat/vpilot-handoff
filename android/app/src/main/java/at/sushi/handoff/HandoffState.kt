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

/** One tracked operation's latest message plus the wall-clock time it was received, so the UI can
 *  apply its own display-duration timeout (docs/protocol.md): while `message.finished` is false,
 *  a ~60s "haven't heard from this operation in a while" backstop independent of a `finished`
 *  message ever arriving; once `message.finished` is true, a much shorter linger so the pilot
 *  actually gets to see the success/failure result before it's cleared. See MainScreen.kt's
 *  rememberVisibleOperations and FooterStatusBar's use of it.
 *
 *  Multiple operations can be active/lingering at once (docs/protocol.md's operationId is
 *  per-invocation, not per-operation-type -- e.g. tapping SimBrief refresh twice in a row is two
 *  separate ids), so HandoffState.operationProgress is keyed by operationId rather than holding
 *  a single value; see FooterStatusBar for how several are combined into the one collapsed-row
 *  icon there's room for. */
data class OperationProgressState(val message: OperationProgressMessage, val receivedAtMillis: Long)

/** The one combined icon state the footer's collapsed row has room for, when several operations
 *  are visible at once -- see MainScreen.kt's combineOperationIndicator for how a list of
 *  [OperationProgressState] reduces to one of these. RUNNING_NEUTRAL/GOOD/BAD are all still a
 *  spinner, just tinted differently depending on what's known about the operations still
 *  in-flight alongside it; SUCCESS/FAILURE only apply once nothing's running anymore. */
enum class OperationIndicator { RUNNING_NEUTRAL, RUNNING_GOOD, RUNNING_BAD, SUCCESS, FAILURE }

/** In-process shared state between HandoffConnectionService (writer) and the Compose UI
 *  (reader) -- no bindService/Messenger IPC needed since both run in the same process. */
object HandoffState {
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    // The host actually used for the current/last connection attempt -- may come from either
    // the persisted manual-IP setting or HandoffDiscoveryClient's UDP broadcast (see
    // HandoffConnectionService.resolveHost), so this is the only correct source for an "address
    // in use" display; reading the manual-IP preference directly shows null forever for anyone
    // relying on discovery, even while genuinely connected. Deliberately NOT cleared by
    // clearLiveServerState() below -- unlike a flight plan or tuned frequency, this describes
    // configuration ("where are we even trying to connect"), not live telemetry that goes stale
    // the moment the link drops, so it stays useful to see even while disconnected.
    private val _resolvedHost = MutableStateFlow<String?>(null)
    val resolvedHost: StateFlow<String?> = _resolvedHost.asStateFlow()

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

    // Keyed by operationId -- see OperationProgressState's doc for why this is a map, not a
    // single value. Entries are never removed here purely on `finished`; the UI (which alone
    // knows the current wall-clock time on every recomposition) is what decides an entry's
    // fully expired and calls removeOperationProgress. Cleared wholesale on disconnect, same as
    // every other plugin-pushed value.
    private val _operationProgress = MutableStateFlow<Map<String, OperationProgressState>>(emptyMap())
    val operationProgress: StateFlow<Map<String, OperationProgressState>> = _operationProgress.asStateFlow()

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
        if (status == ConnectionStatus.DISCONNECTED) clearLiveServerState()
    }

    fun setResolvedHost(host: String?) {
        _resolvedHost.value = host
    }

    /** Resets every plugin-pushed snapshot back to its default the moment the WebSocket link is
     *  confirmed gone (HandoffWebSocketClient's onClosed/onFailure) -- otherwise stale data (a
     *  flight plan, tuned frequencies, the last known controller list) keeps rendering as
     *  current indefinitely, with nothing on screen indicating it stopped being live. No extra
     *  "has it been down a while" debounce -- connectionStatus itself already only reaches
     *  DISCONNECTED once the socket is genuinely gone, so there's no flicker risk to guard
     *  against. Chat history is deliberately left alone -- it's a log worth keeping across a
     *  reconnect, unlike the rest of this snapshot. */
    private fun clearLiveServerState() {
        _controllers.value = ControllersMessage(controllers = emptyList())
        _radioState.value = RadioStateMessage(modeCEnabled = false)
        _flightPlan.value = FlightPlanMessage()
        _nearbyAircraft.value = NearbyAircraftMessage(aircraft = emptyList())
        _subsystemStatus.value = SubsystemStatusMessage()
        _operationProgress.value = emptyMap()
        _latencyMs.value = null
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
        // Always stored, even when finished=true -- the UI decides how long to keep showing a
        // finished result (success/failure linger, see rememberVisibleOperations) rather than
        // this being cleared the instant it arrives.
        _operationProgress.value = _operationProgress.value + (message.operationId to OperationProgressState(message, System.currentTimeMillis()))
    }

    /** Called by the UI once an operation's display window has fully elapsed -- keeps this map
     *  from growing forever across a long session as many short-lived operations (e.g. repeated
     *  SimBrief refreshes) come and go. */
    fun removeOperationProgress(operationId: String) {
        _operationProgress.value = _operationProgress.value - operationId
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
