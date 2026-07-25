package at.sushi.handoff.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
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
import at.sushi.handoff.ui.dialogs.NearbyAircraftDialog
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

    val chatContent: @Composable () -> Unit = {
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
    }

    if (layoutMode == LayoutMode.SPLIT) {
        ChatOverlayHost(visible = chatOpen, splitSide = splitSide, content = chatContent)
    }

    Row(Modifier.fillMaxSize()) {
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
                onToggleChat = { if (layoutMode == LayoutMode.SPLIT) chatOpen = !chatOpen }
            )

            ControllerList(
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

    if (nearbyDialogOpen) {
        NearbyAircraftDialog(
            onDismiss = { nearbyDialogOpen = false },
            onOpenChatWith = { callsign -> openChatWith(callsign) }
        )
    }
}

/** Shows/hides the [ChatOverlayWindow] as a side effect of [visible] -- the overlay is a real
 *  WindowManager window, not part of this composable's own layout, so it's managed imperatively
 *  rather than declaratively positioned like everything else in this file. */
@Composable
private fun ChatOverlayHost(visible: Boolean, splitSide: at.sushi.handoff.SplitSide, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val overlay = remember { ChatOverlayWindow(context) }
    val currentContent = rememberUpdatedState(content)

    DisposableEffect(visible, splitSide) {
        if (visible) {
            // Own-window width in split-screen already reflects this app's current share of the
            // display (Configuration.screenWidthDp updates live in multi-window); mirroring that
            // width for the overlay approximates a symmetric split without needing the
            // androidx.window WindowMetrics API for exact neighbor bounds.
            val panelWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
            overlay.show(panelWidthPx, splitSide) { currentContent.value() }
        } else {
            overlay.hide()
        }
        onDispose { overlay.hide() }
    }
}

/** Heuristic split-screen detection: compares this Activity's current window width (which
 *  Configuration.screenWidthDp already reflects live in multi-window mode) against the physical
 *  display's full width. No androidx.window dependency needed for this approximation -- exact
 *  neighbor-window bounds aren't actually used anywhere (see ChatOverlayHost's own comment), so
 *  the simpler deprecated Display API is enough here. */
@Suppress("DEPRECATION")
private fun isSplitScreen(context: Context, configuration: Configuration): Boolean {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val displayMetrics = DisplayMetrics()
    windowManager.defaultDisplay.getRealMetrics(displayMetrics)
    val fullWidthPx = displayMetrics.widthPixels
    val ownWidthPx = configuration.screenWidthDp * displayMetrics.densityDpi / 160
    return ownWidthPx < fullWidthPx * 0.95f
}
