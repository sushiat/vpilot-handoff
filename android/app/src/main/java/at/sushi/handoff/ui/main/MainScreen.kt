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
        HandoffState.setLayoutMode(if (isSplitScreen(context, configuration)) LayoutMode.SPLIT else LayoutMode.FULLSCREEN)
    }

    val controllers by HandoffState.controllers.collectAsState()
    val chat by HandoffState.chat.collectAsState()
    val radioState by HandoffState.radioState.collectAsState()
    val flightPlan by HandoffState.flightPlan.collectAsState()
    val connectionStatus by HandoffState.connectionStatus.collectAsState()
    val defaultChannelSpacing by HandoffState.defaultChannelSpacing.collectAsState()
    val keypadBlockMode by HandoffState.keypadBlockMode.collectAsState()
    val pinnedCallsign by HandoffState.pinnedCallsign.collectAsState()
    val theme by HandoffState.theme.collectAsState()
    val layoutMode by HandoffState.layoutMode.collectAsState()
    val splitSide by HandoffState.splitSide.collectAsState()

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
                }
            )
            if (nearbyDialogOpen) {
                InlineNearbyAircraftDialog(
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
        Column(Modifier.fillMaxHeight().let { if (layoutMode == LayoutMode.FULLSCREEN) it.width(440.dp) else it.fillMaxSize() }) {
            TopBar(
                radioState = radioState,
                lastMessageLabel = activeChatTab ?: "RADIO",
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
                onDismissSelcal = { selcalDismissedTimestamp = latestSelcalAlert?.timestamp }
            )

            FooterStatusBar(
                connectionStatus = connectionStatus,
                origin = flightPlan.origin,
                destination = flightPlan.destination,
                address = prefs.getString(HandoffConnectionService.PrefKeyHost, null),
                expanded = footerExpanded,
                onToggleExpanded = { footerExpanded = !footerExpanded },
                onRefresh = { send(RefreshFlightPlanCommand()) },
                onOpenSettings = { settingsDialogOpen = true }
            )
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
            val panelWidthPx = with(density) { 360.dp.roundToPx() }
            // Positioned immediately adjacent to this app's own window, using its actual absolute
            // on-screen bounds -- anchoring to the display's far edge (Gravity.END) put this
            // panel *past* the split-screen neighbor app instead of next to this app.
            val ownBounds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
            } else {
                android.graphics.Rect(0, 0, 0, 0)
            }
            val xOffsetPx = if (splitSide == at.sushi.handoff.SplitSide.LEFT) {
                ownBounds.right
            } else {
                ownBounds.left - panelWidthPx
            }
            overlay.show(panelWidthPx, xOffsetPx) {
                // A WindowManager-attached ComposeView is its own separate composition root, so
                // it does NOT inherit the CompositionLocals (including LocalHandoffColors) from
                // MainScreen's composition -- without re-establishing HandoffTheme here, this
                // content silently fell back to the CompositionLocal's default (always light).
                at.sushi.handoff.ui.theme.HandoffTheme(currentThemeMode.value) {
                    currentContent.value()
                }
            }
        } else {
            overlay.hide()
        }
        onDispose { overlay.hide() }
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
