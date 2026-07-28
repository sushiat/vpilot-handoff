package at.sushi.handoff.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.edit
import at.sushi.handoff.HandoffConnectionService
import at.sushi.handoff.HandoffState
import at.sushi.handoff.LayoutMode
import at.sushi.handoff.protocol.ClearPinnedControllerCommand
import at.sushi.handoff.protocol.PinControllerCommand
import at.sushi.handoff.protocol.RefreshFlightPlanCommand
import at.sushi.handoff.protocol.SendPrivateMessageCommand
import at.sushi.handoff.protocol.SendRadioMessageCommand
import at.sushi.handoff.protocol.SetCom1FrequencyCommand
import at.sushi.handoff.protocol.SetCom1StandbyFrequencyCommand
import at.sushi.handoff.protocol.SetCom2FrequencyCommand
import at.sushi.handoff.protocol.SetCom2StandbyFrequencyCommand
import at.sushi.handoff.protocol.SetSimbriefCredentialsCommand
import at.sushi.handoff.protocol.SetTransponderCodeCommand
import at.sushi.handoff.ui.chat.ChatOverlayWindow
import at.sushi.handoff.ui.chat.ChatPanelContent
import at.sushi.handoff.ui.dialogs.ComTuningDialog
import at.sushi.handoff.ui.dialogs.InlineNearbyAircraftDialog
import at.sushi.handoff.ui.dialogs.SettingsDialog
import at.sushi.handoff.ui.dialogs.XpdrDialog
import at.sushi.handoff.ui.theme.HandoffTheme

/** True only once [condition] has held continuously for [delayMs] -- used to distinguish "this
 *  data source is genuinely missing" from "still waiting on a normal fetch/poll cycle" (a plugin
 *  connection or feed poll takes a few seconds; flagging MISSING before that would be a false
 *  alarm). Resets to false immediately if [condition] goes false before the delay elapses, since
 *  a changed key restarts (and thereby cancels the pending) LaunchedEffect. */
@Composable
private fun rememberSustained(condition: Boolean, delayMs: Long): Boolean {
    var sustained by remember { mutableStateOf(false) }
    LaunchedEffect(condition) {
        if (condition) {
            kotlinx.coroutines.delay(delayMs)
            sustained = true
        } else {
            sustained = false
        }
    }
    return sustained
}

// docs/protocol.md: while still in progress, a backstop against a dropped `finished` message;
// once finished, how long the success/failure result stays visible before clearing -- long
// enough to actually read a failure (the more actionable case), shorter for a routine success.
private const val OperationInProgressTimeoutMs = 60_000L
private const val OperationSuccessLingerMs = 10_000L
private const val OperationFailureLingerMs = 30_000L

private fun operationTimeoutMs(state: at.sushi.handoff.OperationProgressState): Long = when {
    !state.message.finished -> OperationInProgressTimeoutMs
    state.message.success -> OperationSuccessLingerMs
    else -> OperationFailureLingerMs
}

/** Every entry of [operations] still within its own display window -- see operationTimeoutMs.
 *  Multiple operations (different operationIds, e.g. two SimBrief refreshes tapped in a row, or
 *  VatGlasses syncing alongside a future VatSpy sync) can be visible at once, each on its own
 *  independent clock. Ticks on a 1s loop rather than one-shot delayed checks per entry, since
 *  [operations] itself keeps changing (a new receivedAtMillis on every step of each still-running
 *  operation) while anything's actually in progress. Expired entries are dropped from
 *  HandoffState entirely (not just this composable's return value) so the underlying map doesn't
 *  grow unbounded across a long session. */
@Composable
private fun rememberVisibleOperations(operations: Map<String, at.sushi.handoff.OperationProgressState>): List<at.sushi.handoff.OperationProgressState> {
    var visible by remember(operations) { mutableStateOf(operations.values.toList()) }
    LaunchedEffect(operations) {
        while (operations.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val stillVisible = mutableListOf<at.sushi.handoff.OperationProgressState>()
            for (state in operations.values) {
                if (now - state.receivedAtMillis < operationTimeoutMs(state)) {
                    stillVisible.add(state)
                } else {
                    HandoffState.removeOperationProgress(state.message.operationId)
                }
            }
            visible = stillVisible
            if (stillVisible.isEmpty()) break
            kotlinx.coroutines.delay(1000)
        }
    }
    return visible
}

/** Reduces every currently-visible operation to the one icon state the footer's collapsed row
 *  has room for -- see [at.sushi.handoff.OperationIndicator]'s doc for what each value means.
 *  Null when nothing's visible at all. */
private fun combineOperationIndicator(visible: List<at.sushi.handoff.OperationProgressState>): at.sushi.handoff.OperationIndicator? {
    if (visible.isEmpty()) return null
    val anyRunning = visible.any { !it.message.finished }
    val anyFailed = visible.any { it.message.finished && !it.message.success }
    val anySucceeded = visible.any { it.message.finished && it.message.success }
    return when {
        anyRunning && anyFailed -> at.sushi.handoff.OperationIndicator.RUNNING_BAD
        anyRunning && anySucceeded -> at.sushi.handoff.OperationIndicator.RUNNING_GOOD
        anyRunning -> at.sushi.handoff.OperationIndicator.RUNNING_NEUTRAL
        anyFailed -> at.sushi.handoff.OperationIndicator.FAILURE
        else -> at.sushi.handoff.OperationIndicator.SUCCESS
    }
}

/** The app's whole screen: top bar, controller list, footer, and every dialog/overlay it can
 *  open -- replaces the old bottom-nav tab Scaffold entirely (see issue #13). */
@Composable
fun MainScreen() {
    val theme by HandoffState.theme.collectAsState()

    HandoffTheme(theme) {
        MainScreenContent()
    }
}

@Composable
private fun MainScreenContent() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(HandoffConnectionService.PrefsName, android.content.Context.MODE_PRIVATE) }

    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration) {
        val split = isSplitScreen(context, configuration)
        HandoffState.setLayoutMode(if (split) LayoutMode.SPLIT else LayoutMode.FULLSCREEN)
        if (split) {
            HandoffState.setSplitSide(detectSplitSide(context))
        }
    }

    val controllers by HandoffState.controllers.collectAsState()
    val chat by HandoffState.chat.collectAsState()
    val radioState by HandoffState.radioState.collectAsState()
    val flightPlan by HandoffState.flightPlan.collectAsState()
    val connectionStatus by HandoffState.connectionStatus.collectAsState()
    // The VATSIM-filed plan (docs/protocol.md's flightPlan message) is the more authoritative
    // source once it exists -- SimBrief is just whatever was typed when the OFP was generated,
    // available pre-connection but with no guarantee it matches what's actually filed. Route
    // display prefers vatsim*, falling back to simbrief* only when the VATSIM side isn't known yet
    // (not connected, or feed not yet polled) -- same fallback order as the plugin's own ranking
    // route match.
    val displayOrigin = flightPlan.vatsimOrigin ?: flightPlan.simbriefOrigin
    val displayDestination = flightPlan.vatsimDestination ?: flightPlan.simbriefDestination
    // VATSIM: connected (callsign known) but the data feed still shows no filed plan -- ~15s poll
    // interval, so 20s covers a normal poll cycle without false-flagging.
    val vatsimMissing = rememberSustained(flightPlan.vatsimCallsign != null && flightPlan.vatsimOrigin == null, 20_000)
    // SimBrief: the plugin's own WebSocket connection is up (so it's had a chance to fetch) but no
    // SimBrief plan has ever come back -- no credentials set, wrong ones, or the API's unreachable.
    val simbriefMissing = rememberSustained(connectionStatus == at.sushi.handoff.ConnectionStatus.CONNECTED && flightPlan.simbriefOrigin == null, 20_000)
    // Once both sides are known, a mismatch means the SimBrief OFP and what's actually filed on
    // the network have diverged (stale OFP, re-filed after generating it, etc) -- worth flagging
    // just as much as forgetting to file at all, since it's the same "wrong info on frequency" risk.
    val flightPlanMismatch = flightPlan.vatsimOrigin != null && flightPlan.simbriefOrigin != null &&
        (flightPlan.vatsimOrigin != flightPlan.simbriefOrigin || flightPlan.vatsimDestination != flightPlan.simbriefDestination)
    val flightPlanWarning = flightPlanMismatch || vatsimMissing
    val defaultChannelSpacing by HandoffState.defaultChannelSpacing.collectAsState()
    val keypadBlockMode by HandoffState.keypadBlockMode.collectAsState()
    val pinnedCallsign by HandoffState.pinnedCallsign.collectAsState()
    val theme by HandoffState.theme.collectAsState()
    val layoutMode by HandoffState.layoutMode.collectAsState()
    val splitSide by HandoffState.splitSide.collectAsState()
    val nearbyAircraft by HandoffState.nearbyAircraft.collectAsState()
    val subsystemStatus by HandoffState.subsystemStatus.collectAsState()
    val resolvedHost by HandoffState.resolvedHost.collectAsState()
    val latencyMs by HandoffState.latencyMs.collectAsState()
    val keepScreenAwake by HandoffState.keepScreenAwake.collectAsState()
    val operationProgress by HandoffState.operationProgress.collectAsState()
    val visibleOperations = rememberVisibleOperations(operationProgress)
    val operationIndicator = combineOperationIndicator(visibleOperations)

    // View.keepScreenOn is the simple per-window equivalent of FLAG_KEEP_SCREEN_ON -- the docked,
    // wired-into-power cockpit use case wants the screen timeout disabled outright, not just a
    // wake lock kept alive in the background.
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(keepScreenAwake) { view.keepScreenOn = keepScreenAwake }

    var comDialogOpen by remember { mutableStateOf<Int?>(null) } // 1 or 2
    var xpdrDialogOpen by remember { mutableStateOf(false) }
    var settingsDialogOpen by remember { mutableStateOf(false) }
    var nearbyDialogOpen by remember { mutableStateOf(false) }
    var footerExpanded by remember { mutableStateOf(false) }
    var chatOpen by remember { mutableStateOf(false) }

    var openChatTabs by remember { mutableStateOf(listOf<String>()) }
    var activeChatTab by remember { mutableStateOf<String?>(null) } // null = RADIO
    var unreadByTab by remember { mutableStateOf(mapOf<String, Int>()) }
    var selcalDismissedTimestamp by remember { mutableStateOf<String?>(null) }

    val latestSelcalAlert = chat.selcalAlerts.maxByOrNull { it.timestamp }
    val selcalActive = latestSelcalAlert != null && latestSelcalAlert.timestamp != selcalDismissedTimestamp
    val selcalActiveCallsigns = if (selcalActive) setOf(latestSelcalAlert!!.from) else emptySet()

    fun send(command: at.sushi.handoff.protocol.ClientCommand) = HandoffConnectionService.instance?.sendCommand(command)

    fun openChatWith(callsign: String) {
        if (callsign !in openChatTabs) openChatTabs = openChatTabs + callsign
        activeChatTab = callsign
        chatOpen = true
    }

    // The nearby-aircraft dialog is folded in here (rendered as plain inline content layered
    // over the chat panel, not a system Dialog) rather than as a standalone top-level dialog --
    // it's only ever reached via this panel's own airplane icon, and this panel is sometimes
    // hosted inside the split-screen overlay's separate window, where a real Dialog would be
    // confined to this app's own narrow window slice and sit behind the overlay in z-order (see
    // NearbyAircraftDialog.kt). Rendering it here means it always ends up in the same window as
    // whatever triggered it, in both fullscreen and split-screen.
    val chatContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            ChatPanelContent(
                chat = chat,
                controllers = controllers.controllers,
                openTabs = openChatTabs,
                activeTab = activeChatTab,
                unreadByTab = unreadByTab,
                selcalActive = selcalActive,
                // The live vPilot connection callsign -- what other controllers actually see us
                // as on frequency -- not the SimBrief one, which has no guarantee of matching the
                // real connection (see docs/protocol.md's flightPlan message).
                ownCallsign = flightPlan.vatsimCallsign,
                onSelectTab = { activeChatTab = it },
                onCloseTab = { peer ->
                    openChatTabs = openChatTabs - peer
                    if (activeChatTab == peer) activeChatTab = null
                },
                onOpenNearbyDialog = { nearbyDialogOpen = true },
                onCollapse = if (layoutMode == LayoutMode.SPLIT) {
                    { chatOpen = false }
                } else null,
                onSend = { text ->
                    val tab = activeChatTab
                    if (tab == null) {
                        send(SendRadioMessageCommand(message = text))
                    } else {
                        send(SendPrivateMessageCommand(to = tab, message = text))
                    }
                },
                // Reference's chatPanelStyle always has a border facing the controller list/app:
                // fullscreen is always START (chat sits to the right of the list); split/overlay
                // mode faces whichever edge is adjacent to the app based on splitSide.
                borderSide = if (layoutMode == LayoutMode.FULLSCREEN) {
                    at.sushi.handoff.ui.chat.ChatPanelBorderSide.START
                } else if (splitSide == at.sushi.handoff.SplitSide.LEFT) {
                    at.sushi.handoff.ui.chat.ChatPanelBorderSide.START
                } else {
                    at.sushi.handoff.ui.chat.ChatPanelBorderSide.END
                }
            )
            if (nearbyDialogOpen) {
                InlineNearbyAircraftDialog(
                    aircraft = nearbyAircraft.aircraft,
                    onDismiss = { nearbyDialogOpen = false },
                    onOpenChatWith = { callsign -> openChatWith(callsign) }
                )
            }
        }
    }

    if (layoutMode == LayoutMode.SPLIT) {
        ChatOverlayHost(visible = chatOpen, splitSide = splitSide, themeMode = theme, content = chatContent)
    }

    val colors = at.sushi.handoff.ui.theme.LocalHandoffColors.current
    Row(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        // Only in split mode: the chat overlay is a separate WindowManager window sitting flush
        // against this Activity's own window edge -- Android/One UI renders each window's own
        // corners rounded, so without this the main pane's rounded corner on the side touching
        // the overlay leaves a visible gap between the two. Straighten just that touching edge
        // while the overlay is open; the far (outer, screen-facing) edge stays rounded, and with
        // the overlay closed there's nothing to butt up against, so all four corners round again.
        val mainPanelShape = if (layoutMode == LayoutMode.SPLIT) {
            if (chatOpen) {
                if (splitSide == at.sushi.handoff.SplitSide.LEFT) {
                    RoundedCornerShape(topStart = 16.dp, topEnd = 0.dp, bottomStart = 16.dp, bottomEnd = 0.dp)
                } else {
                    RoundedCornerShape(topStart = 0.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 16.dp)
                }
            } else {
                RoundedCornerShape(16.dp)
            }
        } else {
            RectangleShape
        }
        // Only in split mode: a static 8dp dead strip on the edge touching the chat overlay, with
        // no interactive content in it -- present whether chat is open or closed, so toggling chat
        // never resizes/reflows the real controls (no "jump"). Samsung One UI's split-screen
        // divider/rounded-corner rendering leaves a gap between the two apps' windows that sits
        // *outside* this app's own reported window bounds -- sitting the overlay flush against
        // those bounds still left it visible, so ChatOverlayHost's overlay window deliberately
        // extends past its own edge into this exact margin width (see ChatOverlayOuterOverlap) to
        // physically cover it. This margin is what makes that safe: guaranteed empty, so the
        // overlap can never land on a real control.
        val touchingMarginDp = if (layoutMode == LayoutMode.SPLIT) 8.dp else 0.dp
        Row(
            Modifier
                .fillMaxHeight()
                .let { if (layoutMode == LayoutMode.FULLSCREEN) it.width(440.dp) else it.fillMaxSize() }
                .clip(mainPanelShape)
                .background(colors.panel)
        ) {
            if (layoutMode == LayoutMode.SPLIT && splitSide == at.sushi.handoff.SplitSide.RIGHT) {
                Box(Modifier.width(touchingMarginDp).fillMaxHeight())
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
            TopBar(
                radioState = radioState,
                // Blank until there's actually been any chat activity -- defaulting to "RADIO"
                // unconditionally (even with nothing ever received) was misleading.
                lastMessageLabel = activeChatTab
                    ?: "RADIO".takeIf { chat.messages.isNotEmpty() || chat.selcalAlerts.isNotEmpty() },
                unreadCount = unreadByTab.values.sum(),
                onSwapCom1 = {
                    val active = radioState.com1Frequency
                    val standby = radioState.com1StandbyFrequency
                    if (standby != null) send(SetCom1FrequencyCommand(megahertz = at.sushi.handoff.protocol.RadioFrequency.toMegahertz(standby)))
                    if (active != null) send(SetCom1StandbyFrequencyCommand(megahertz = at.sushi.handoff.protocol.RadioFrequency.toMegahertz(active)))
                },
                onSwapCom2 = {
                    val active = radioState.com2Frequency
                    val standby = radioState.com2StandbyFrequency
                    if (standby != null) send(SetCom2FrequencyCommand(megahertz = at.sushi.handoff.protocol.RadioFrequency.toMegahertz(standby)))
                    if (active != null) send(SetCom2StandbyFrequencyCommand(megahertz = at.sushi.handoff.protocol.RadioFrequency.toMegahertz(active)))
                },
                onOpenCom1Dialog = { comDialogOpen = 1 },
                onOpenCom2Dialog = { comDialogOpen = 2 },
                onOpenXpdrDialog = { xpdrDialogOpen = true },
                // Unconditional: chatOpen is simply unused/harmless in fullscreen mode (the
                // overlay host below is only ever invoked while layoutMode == SPLIT), so there's
                // no need to gate the toggle itself on layoutMode -- doing so previously meant a
                // tap that landed while the (heuristic, possibly momentarily unstable)
                // layoutMode read something other than SPLIT would silently no-op instead of
                // toggling.
                onToggleChat = { chatOpen = !chatOpen }
            )

            ControllerList(
                modifier = Modifier.weight(1f),
                controllers = controllers.controllers,
                com1Active = radioState.com1Frequency,
                com2Active = radioState.com2Frequency,
                com1Standby = radioState.com1StandbyFrequency,
                com2Standby = radioState.com2StandbyFrequency,
                pinnedCallsign = pinnedCallsign,
                selcalActiveCallsigns = selcalActiveCallsigns,
                onTogglePin = { callsign ->
                    if (pinnedCallsign == callsign) {
                        HandoffState.setPinnedCallsign(null)
                        send(ClearPinnedControllerCommand())
                    } else {
                        HandoffState.setPinnedCallsign(callsign)
                        send(PinControllerCommand(callsign = callsign))
                    }
                },
                onOpenChatWith = { callsign -> openChatWith(callsign) },
                onTuneCom1Active = { freq -> send(SetCom1FrequencyCommand(megahertz = at.sushi.handoff.protocol.RadioFrequency.toMegahertz(freq))) },
                onTuneCom2Active = { freq -> send(SetCom2FrequencyCommand(megahertz = at.sushi.handoff.protocol.RadioFrequency.toMegahertz(freq))) },
                onTuneCom1Standby = { freq -> send(SetCom1StandbyFrequencyCommand(megahertz = at.sushi.handoff.protocol.RadioFrequency.toMegahertz(freq))) },
                onTuneCom2Standby = { freq -> send(SetCom2StandbyFrequencyCommand(megahertz = at.sushi.handoff.protocol.RadioFrequency.toMegahertz(freq))) },
                onDismissSelcal = { callsign ->
                    selcalDismissedTimestamp = latestSelcalAlert?.timestamp
                    send(at.sushi.handoff.protocol.DismissSelcalCommand(callsign = callsign))
                }
            )

            FooterStatusBar(
                connectionStatus = connectionStatus,
                origin = displayOrigin,
                destination = displayDestination,
                flightPlanWarning = flightPlanWarning,
                flightPlanMismatch = flightPlanMismatch,
                activeCallsign = flightPlan.vatsimCallsign,
                simbriefOrigin = flightPlan.simbriefOrigin,
                simbriefDestination = flightPlan.simbriefDestination,
                simbriefMissing = simbriefMissing,
                vatsimOrigin = flightPlan.vatsimOrigin,
                vatsimDestination = flightPlan.vatsimDestination,
                vatsimMissing = vatsimMissing,
                address = resolvedHost,
                subsystemStatus = subsystemStatus,
                operationIndicator = operationIndicator,
                visibleOperations = visibleOperations,
                latencyMs = latencyMs,
                expanded = footerExpanded,
                keepScreenAwake = keepScreenAwake,
                onToggleExpanded = { footerExpanded = !footerExpanded },
                onRefresh = { send(RefreshFlightPlanCommand()) },
                onOpenSettings = { settingsDialogOpen = true },
                // Not persisted -- HandoffConnectionService re-derives this from the live
                // charging state at startup and forces it on when a charger connects; a manual
                // toggle here only holds until the next one of those triggers.
                onToggleKeepScreenAwake = { HandoffState.setKeepScreenAwake(!keepScreenAwake) }
            )
            }
            if (layoutMode == LayoutMode.SPLIT && splitSide == at.sushi.handoff.SplitSide.LEFT) {
                Box(Modifier.width(touchingMarginDp).fillMaxHeight())
            }
        }

        if (layoutMode == LayoutMode.FULLSCREEN) {
            Column(Modifier.fillMaxHeight().fillMaxSize()) {
                chatContent()
            }
        }
    }

    comDialogOpen?.let { comNumber ->
        ComTuningDialog(
            comNumber = comNumber,
            defaultSpacing = defaultChannelSpacing,
            keypadBlockMode = keypadBlockMode,
            onDismiss = { comDialogOpen = null },
            onSetActive = { mhz ->
                send(if (comNumber == 1) SetCom1FrequencyCommand(megahertz = mhz) else SetCom2FrequencyCommand(megahertz = mhz))
            },
            onSetStandby = { mhz ->
                send(if (comNumber == 1) SetCom1StandbyFrequencyCommand(megahertz = mhz) else SetCom2StandbyFrequencyCommand(megahertz = mhz))
            }
        )
    }

    if (xpdrDialogOpen) {
        XpdrDialog(
            onDismiss = { xpdrDialogOpen = false },
            onSetCode = { code -> send(SetTransponderCodeCommand(transponderCode = code)) }
        )
    }

    if (settingsDialogOpen) {
        SettingsDialog(
            connectionStatus = connectionStatus,
            initialHost = prefs.getString(HandoffConnectionService.PrefKeyHost, "") ?: "",
            initialSimbriefUserId = prefs.getString(HandoffConnectionService.PrefKeySimbriefUserId, "") ?: "",
            initialSimbriefUsername = prefs.getString(HandoffConnectionService.PrefKeySimbriefUsername, "") ?: "",
            initialTheme = theme,
            initialChannelSpacing = defaultChannelSpacing,
            initialKeypadBlockMode = keypadBlockMode,
            onDismiss = { settingsDialogOpen = false },
            onQuit = {
                context.stopService(android.content.Intent(context, HandoffConnectionService::class.java))
                (context as? android.app.Activity)?.finishAndRemoveTask()
            },
            onSave = { host, simbriefUserId, simbriefUsername, newTheme, newSpacing, newKeypadMode ->
                prefs.edit {
                    putString(HandoffConnectionService.PrefKeyHost, host)
                    putString(HandoffConnectionService.PrefKeySimbriefUserId, simbriefUserId)
                    putString(HandoffConnectionService.PrefKeySimbriefUsername, simbriefUsername)
                    putString(HandoffConnectionService.PrefKeyTheme, newTheme.name)
                    putString(HandoffConnectionService.PrefKeyChannelSpacing, newSpacing.name)
                    putString(HandoffConnectionService.PrefKeyKeypadBlockMode, newKeypadMode.name)
                }
                HandoffState.setTheme(newTheme)
                HandoffState.setDefaultChannelSpacing(newSpacing)
                HandoffState.setKeypadBlockMode(newKeypadMode)
                HandoffConnectionService.instance?.reconnectNow()
                send(SetSimbriefCredentialsCommand(simbriefUserId = simbriefUserId, simbriefUsername = simbriefUsername))
                send(RefreshFlightPlanCommand())
            }
        )
    }

}

/** Shows/hides the [ChatOverlayWindow] as a side effect of [visible] -- the overlay is a real
 *  WindowManager window, not part of this composable's own layout, so it's managed imperatively
 *  rather than declaratively positioned like everything else in this file. */
@Composable
private fun ChatOverlayHost(
    visible: Boolean,
    splitSide: at.sushi.handoff.SplitSide,
    themeMode: at.sushi.handoff.ThemeMode,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val overlay = remember { ChatOverlayWindow(context) }
    val currentContent = rememberUpdatedState(content)
    val currentThemeMode = rememberUpdatedState(themeMode)

    DisposableEffect(visible, splitSide) {
        if (visible) {
            // Fixed 360dp, per the reference's own `chatPanelStyle` ("width:360px" when split) --
            // not "fill whatever's left of the screen." That was solving a problem the design
            // never had: the panel is a constant width regardless of how large the neighbor app's
            // share of the screen is.
            val basePanelWidthPx = with(density) { 360.dp.roundToPx() }
            // The gap Samsung One UI's split-screen divider/rounded-corner rendering leaves
            // between the two apps' windows turns out to live *outside* the main app's own
            // reported window bounds (WindowManager.currentWindowMetrics) -- sitting flush against
            // ownBounds (no overlap) still left a visible sliver, so the overlay does need to
            // extend past that boundary to physically cover it. This is safe now specifically
            // because MainScreen reserves a matching dead 24dp margin (touchingMarginDp) with no
            // interactive content on the main panel's own touching edge -- the overlay overlapping
            // into that guaranteed-empty zone can never cover a real control, unlike the first
            // attempt at this (before that margin existed), which did.
            val overlapPx = with(density) { ChatOverlayOuterOverlap.roundToPx() }
            val panelWidthPx = basePanelWidthPx + overlapPx
            // Positioned immediately adjacent to this app's own window, using its actual absolute
            // on-screen bounds -- anchoring to the display's far edge (Gravity.END) put this
            // panel *past* the split-screen neighbor app instead of next to this app.
            val ownBounds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
            } else {
                android.graphics.Rect(0, 0, 0, 0)
            }
            val xOffsetPx = if (splitSide == at.sushi.handoff.SplitSide.LEFT) {
                ownBounds.right - overlapPx
            } else {
                ownBounds.left - basePanelWidthPx
            }
            overlay.show(panelWidthPx, xOffsetPx) {
                // A WindowManager-attached ComposeView is its own separate composition root, so
                // it does NOT inherit the CompositionLocals (including LocalHandoffColors) from
                // MainScreen's composition -- without re-establishing HandoffTheme here, this
                // content silently fell back to the CompositionLocal's default (always light).
                // The overlap margin is filled with a plain panel-colored spacer before the real
                // (bordered) chat content, so the visible chat width/border position looks exactly
                // like a flush 360dp panel -- only the window's physical extent is wider.
                at.sushi.handoff.ui.theme.HandoffTheme(currentThemeMode.value) {
                    ChatOverlayContent(splitSide, currentContent.value)
                }
            }
        } else {
            overlay.hide()
        }
        onDispose { overlay.hide() }
    }
}

/** How far the overlay window extends past the chat panel's own logical 360dp width, over the
 *  main app's edge -- matches MainScreen's touchingMarginDp exactly, so the overlap is guaranteed
 *  to land entirely within that dead, content-free strip rather than needing to be separately
 *  tuned/guessed. */
private val ChatOverlayOuterOverlap = 8.dp

/** The overlay window's actual content: a plain panel-colored spacer filling the extra
 *  [ChatOverlayOuterOverlap] margin on the side touching the main app, then the real chat content
 *  at its normal 360dp width -- so the visible chat panel (border, width) looks exactly like it
 *  did before the window was widened to mask the OS's own corner rendering. */
@Composable
private fun ChatOverlayContent(splitSide: at.sushi.handoff.SplitSide, content: @Composable () -> Unit) {
    val colors = at.sushi.handoff.ui.theme.LocalHandoffColors.current
    Row(Modifier.fillMaxSize()) {
        if (splitSide == at.sushi.handoff.SplitSide.LEFT) {
            Box(Modifier.width(ChatOverlayOuterOverlap).fillMaxHeight().background(colors.panel))
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            content()
        }
        if (splitSide == at.sushi.handoff.SplitSide.RIGHT) {
            Box(Modifier.width(ChatOverlayOuterOverlap).fillMaxHeight().background(colors.panel))
        }
    }
}

/** Full-physical-display width vs. this Activity's own current window width, in real pixels.
 *  Uses `WindowManager.maximumWindowMetrics`/`currentWindowMetrics` (API 30+) -- NOT the legacy
 *  `defaultDisplay.getRealMetrics`, which turned out to be a real bug here: on this test device
 *  (Samsung, multi-window) it reported a "full display width" of 1906px against an actual 1800px
 *  display, so the derived overlay width was wider than the entire screen and physically covered
 *  the app's own MSG button -- the toggle button "worked" (correctly flipped state) but the tap
 *  meant to close it never reached the Activity at all, since the oversized invisible overlay
 *  intercepted it first. `maximumWindowMetrics`/`currentWindowMetrics` are the officially correct,
 *  non-deprecated way to get these bounds and don't have this failure mode. */
private fun displayAndOwnWidthPx(context: Context, configuration: Configuration): Pair<Int, Int> {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        val fullWidthPx = windowManager.maximumWindowMetrics.bounds.width()
        val ownWidthPx = windowManager.currentWindowMetrics.bounds.width()
        fullWidthPx to ownWidthPx
    } else {
        @Suppress("DEPRECATION")
        val displayMetrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        val fullWidthPx = displayMetrics.widthPixels
        val ownWidthPx = configuration.screenWidthDp * displayMetrics.densityDpi / 160
        fullWidthPx to ownWidthPx
    }
}

private fun isSplitScreen(context: Context, configuration: Configuration): Boolean {
    val (fullWidthPx, ownWidthPx) = displayAndOwnWidthPx(context, configuration)
    return ownWidthPx < fullWidthPx * 0.95f
}

/** Which half of the physical display this Activity's own window currently occupies -- this was
 *  previously never actually detected at runtime (HandoffState.splitSide sat at its hardcoded
 *  LEFT default forever), so docking the app on the right used LEFT-side math everywhere
 *  (overlay x-offset, corner rounding), producing nonsense positioning. Compares the window's own
 *  horizontal center against the full display's center; below API 30 (no per-window bounds
 *  available) this can't be determined, so it falls back to the existing LEFT default. */
private fun detectSplitSide(context: Context): at.sushi.handoff.SplitSide {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return at.sushi.handoff.SplitSide.LEFT
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val fullBounds = windowManager.maximumWindowMetrics.bounds
    val ownBounds = windowManager.currentWindowMetrics.bounds
    val ownCenterX = (ownBounds.left + ownBounds.right) / 2
    val fullCenterX = (fullBounds.left + fullBounds.right) / 2
    return if (ownCenterX <= fullCenterX) at.sushi.handoff.SplitSide.LEFT else at.sushi.handoff.SplitSide.RIGHT
}
