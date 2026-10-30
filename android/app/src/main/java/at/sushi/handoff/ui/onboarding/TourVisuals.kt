package at.sushi.handoff.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.ConnectionStatus
import at.sushi.handoff.demo.DemoData
import at.sushi.handoff.protocol.Controller
import at.sushi.handoff.ui.chat.ChatPanelContent
import at.sushi.handoff.ui.dialogs.ConnectNetworkDialog
import at.sushi.handoff.ui.main.ControllerRow
import at.sushi.handoff.ui.main.FooterStatusBar
import at.sushi.handoff.ui.main.MessageIcon
import androidx.compose.ui.platform.LocalConfiguration
import at.sushi.handoff.ui.main.TopBar
import at.sushi.handoff.ui.main.isPhoneClassDevice
import at.sushi.handoff.ui.main.RadioControlsPreview
import at.sushi.handoff.ui.theme.LocalHandoffColors
import at.sushi.handoff.ui.main.AppHeaderRow
import at.sushi.handoff.ui.theme.oklch
import androidx.compose.foundation.clickable
import kotlin.math.roundToInt

// Every visual below is built from the app's real composables fed with DemoData's sample state, never
// screenshots, so the tour can't drift from the app (issue #205). The top and status bars are laid
// out at a fixed design width and scaled down to fit, which keeps the numbered callouts, placed in
// that design space, on their controls whatever the dialog's width.
private val BarDesignWidth = 500.dp

@Composable
fun TourVisual(page: TourPage) {
    // Pages 1 and 4 are drawn differently on a phone (narrow, portrait) than on a tablet: the same device test the
    // rest of the app uses, i.e. the physical display, not the window, so a tablet in split-screen stays a tablet.
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val phone = remember(configuration) { isPhoneClassDevice(context, configuration) }
    when (page) {
        TourPage.WELCOME -> WelcomeVisual(phone)
        TourPage.GETTING_STARTED -> GettingStartedVisual()
        TourPage.CONTROLLERS -> ControllerRowsVisual(listOf("LOWW_GND", "LOWW_TWR", "LOWW_APP"))
        TourPage.RADIOS -> if (phone) RadiosVisual() else RadiosFullVisual()
        TourPage.TRANSPONDER_MESSAGES -> TransponderMessagesVisual()
        TourPage.NEVER_MISS -> ControllerRowsVisual(listOf("LOWW_DEL", "LOVV_CTR"))
        TourPage.CHAT -> ChatVisual()
        TourPage.STATUS_BAR -> StatusBarVisual(
            expanded = true,
            clipHeight = ExpandedBarVisibleHeight,
            designWidth = ExpandedBarDesignWidth,
            maxScale = 1.5f,
            callouts = listOf(1 to (26.dp to 12.dp), 2 to KeepAwakeCorner)
        )
        TourPage.VATSIM_CONNECTION -> VatsimConnectionVisual()
        TourPage.STARTUP_WINDOW -> StartupWindowVisual()
        TourPage.ALL_SET -> ReplayHintVisual()
    }
}

/** A numbered callout: 22dp accent circle. */
@Composable
fun CalloutBadge(number: Int, modifier: Modifier = Modifier) {
    val colors = LocalHandoffColors.current
    Box(modifier.size(22.dp).background(colors.accent, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            number.toString(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            // No font padding and a centred, trimmed line box: otherwise the digit sits low in the circle.
            style = TextStyle(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both)
            )
        )
    }
}

/** Lays [content] out at [designWidth] and scales it uniformly down to the available width, never above
 *  [maxScale] (1 = natural size). */
@Composable
private fun ScaledToWidth(designWidth: Dp, maxScale: Float = 1f, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().clipToBounds(), contentAlignment = Alignment.TopCenter) {
        val scale = minOf(maxScale, maxWidth / designWidth)
        Layout(content = { Box(Modifier.requiredWidth(designWidth)) { content() } }) { measurables, _ ->
            val placeable = measurables.first().measure(androidx.compose.ui.unit.Constraints())
            val width = (placeable.width * scale).roundToInt()
            val height = (placeable.height * scale).roundToInt()
            layout(width, height) {
                placeable.placeWithLayer(0, 0) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
    }
}

/** The top bar's whole header line on a card, with the "?" button that replays the tour: the real composable (not
 *  a drawing), so it can't drift from the header. Taps are swallowed, like the chat page. */
@Composable
private fun ReplayHintVisual() {
    val colors = LocalHandoffColors.current
    ScaledToWidth(designWidth = 340.dp, maxScale = 1.5f) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.panel, RoundedCornerShape(12.dp))
                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                // The header row carries 8dp of padding above it and none below (it hangs from the top of the
                // screen), so the card pads the bottom by that much more to centre the row vertically.
                .padding(top = 12.dp, bottom = 20.dp)
        ) {
            AppHeaderRow(onShowTour = {})
            Box(
                Modifier.matchParentSize().clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    onClick = {}
                )
            )
        }
    }
}

@Composable
private fun WelcomeVisual(phone: Boolean) {
    // Top-aligned so the two rounded tiles share one line whatever their captions do (the right title wraps on
    // a phone); the arrow column is as tall as a tile and centres its content on it.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
        DeviceTile(listOf(Icons.Filled.Computer), "Sim PC", "vPilot + plugin")
        // Labelled so nobody expects this to go over the internet: both devices must share one LAN. Kept short so
        // the three columns still fit on a phone.
        Column(Modifier.height(DeviceTileHeight), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = LocalHandoffColors.current.accent, modifier = Modifier.size(36.dp))
            Text(
                // "LAN" on a phone, where the longer label crowds the three columns.
                if (phone) "LAN" else "SAME NETWORK",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = LocalHandoffColors.current.textMuted
            )
        }
        DeviceTile(listOf(Icons.Filled.TabletAndroid, Icons.Filled.PhoneAndroid), "Tablet or phone", "Handoff")
    }
}

private val DeviceTileHeight = 72.dp

@Composable
private fun DeviceTile(icons: List<androidx.compose.ui.graphics.vector.ImageVector>, title: String, subtitle: String) {
    val colors = LocalHandoffColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.height(DeviceTileHeight).widthIn(min = 72.dp).background(colors.panel, RoundedCornerShape(16.dp)).border(1.dp, colors.border, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                icons.forEachIndexed { i, icon ->
                    // Several devices share one tile: the first at full size, the rest a step smaller.
                    Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(if (i == 0) 40.dp else 28.dp))
                }
            }
        }
        // Centred and width-capped so a long title ("Tablet or phone") wraps under its tile on a narrow screen.
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.text, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 100.dp))
        Text(subtitle, fontSize = 12.sp, color = colors.textMuted, textAlign = TextAlign.Center)
    }
}

private const val PluginReleasesUrl = "https://github.com/sushiat/vpilot-handoff/releases/latest"

/** The design's two panels, side by side: numbered steps for a real vPilot, and what demo mode is
 *  (tinted purple so it reads as the secondary path). Stacked instead when the panel is too narrow
 *  for two columns, e.g. on a phone. */
@Composable
private fun GettingStartedVisual() {
    val colors = LocalHandoffColors.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val connectCard: @Composable (Modifier) -> Unit = { modifier ->
        OptionCard(modifier) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(colors.ok, CircleShape))
                Text("Connect to vPilot", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.text)
            }
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Step(1) {
                    Text("Install the Handoff plugin in vPilot", fontSize = 14.sp, color = colors.textMuted)
                    Text(
                        "Download it from GitHub",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accent,
                        modifier = Modifier.clickable { uriHandler.openUri(PluginReleasesUrl) }.padding(vertical = 4.dp)
                    )
                }
                Step(2) { Text("Connect tablet and PC to the same home network", fontSize = 14.sp, color = colors.textMuted) }
                Step(3) {
                    Text(
                        "The app finds the plugin on its own; if not, enter the PC's IP in Settings",
                        fontSize = 14.sp,
                        color = colors.textMuted
                    )
                }
            }
        }
    }
    val demoCard: @Composable (Modifier) -> Unit = { modifier ->
        OptionCard(
            modifier,
            background = if (colors.isDark) oklch(0.26f, 0.04f, 300f) else oklch(0.95f, 0.03f, 300f),
            border = if (colors.isDark) oklch(0.40f, 0.08f, 300f) else oklch(0.85f, 0.06f, 300f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "DEMO",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    color = Color(0xFF2A0A3A),
                    modifier = Modifier.background(oklch(0.78f, 0.1f, 300f), RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 4.dp)
                )
                // Not bold: demo mode is the way to look around, not the app's main feature.
                Text("No vPilot? Try demo mode", fontSize = 14.sp, color = colors.text)
            }
            Text(
                "Fills the app with simulated controllers and chat so you can explore every feature without a flight sim. No setup needed.",
                fontSize = 14.sp,
                color = if (colors.isDark) oklch(0.82f, 0.02f, 300f) else colors.textMuted,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 440.dp) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                connectCard(Modifier.weight(1f).fillMaxHeight())
                demoCard(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                connectCard(Modifier.fillMaxWidth())
                demoCard(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun OptionCard(
    modifier: Modifier = Modifier,
    background: Color = LocalHandoffColors.current.panel,
    border: Color = LocalHandoffColors.current.border,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .background(background, RoundedCornerShape(16.dp))
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) { content() }
}

@Composable
private fun Step(number: Int, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalHandoffColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(22.dp).background(colors.accentBg, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            Text(number.toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.accent)
        }
        Column(Modifier.weight(1f)) { content() }
    }
}

/** The real controller row, fed the demo-mode stations (selected by callsign), with the demo radios
 *  so the TUNED/STBY badges line up exactly as they do in demo mode. */
@Composable
private fun ControllerRowsVisual(callsigns: List<String>) {
    val radio = remember { DemoData.radioState() }
    val controllers = remember { DemoData.controllers(System.currentTimeMillis()).controllers }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        callsigns.forEach { callsign ->
            val controller: Controller = controllers.first { it.callsign == callsign }
            ControllerRow(
                controller = controller,
                etaMinutes = null,
                callsignColWidth = 104.dp,
                frequencyTextWidth = 84.dp,
                com1Active = radio.com1Frequency,
                com2Active = radio.com2Frequency,
                com1Standby = radio.com1StandbyFrequency,
                com2Standby = radio.com2StandbyFrequency,
                onTogglePin = {},
                onOpenChat = {},
                onTuneCom1Active = {},
                onTuneCom2Active = {},
                onTuneCom1Standby = {},
                onTuneCom2Standby = {},
                onDismissSelcal = {},
                interactive = false
            )
        }
    }
}

@Composable
private fun DemoTopBar() {
    val radio = remember { DemoData.radioState() }
    TopBar(
        radioState = radio,
        lastMessageLabel = null,
        unreadCount = 0,
        hasDirectedUnread = false,
        com1Callsign = "LOWW_GND",
        com2Callsign = "LOWW_D_ATIS",
        com1StandbyCallsign = "LOWW_TWR",
        com2StandbyCallsign = "LOWW_APP",
        onSwapCom1 = {},
        onSwapCom2 = {},
        onOpenCom1Dialog = {},
        onOpenCom2Dialog = {},
        onOpenXpdrDialog = {},
        onToggleMic = {},
        onToggleMon = {},
        onToggleChat = {}
    )
}

// Tablet radios page: the whole top bar at its 500dp wide layout, with the four callouts at the corners of the
// controls (14dp content padding; MIC/MON column 64dp, XPDR/MSG column 104dp, 8dp column gaps, two ~74dp rows).
// Estimated from the layout constants in TopBar.kt, so recheck them if that layout's sizes change.
private val CalloutRow1Y = 38.dp
private val CalloutRow2Y = 118.dp
private val ComRightX = 154.dp
private val MicMonRightX = 226.dp

@Composable
private fun RadiosFullVisual() {
    ScaledToWidth(BarDesignWidth) {
        Box {
            DemoTopBar()
            Callout(1, ComRightX, CalloutRow1Y)
            Callout(2, ComRightX, CalloutRow2Y)
            Callout(3, MicMonRightX, CalloutRow1Y)
            Callout(4, MicMonRightX, CalloutRow2Y)
        }
    }
}

// Phone radios page: just COM1 and the audio column, laid out at a fixed design width and scaled to the panel (up to 1.4x so the
// buttons stay readable on a tablet). The badges are attached to the controls themselves, see
// RadioControlsPreview, so they cannot drift off them.
private val RadiosDesignWidth = 300.dp

@Composable
private fun RadiosVisual() {
    val radio = remember { DemoData.radioState() }
    ScaledToWidth(RadiosDesignWidth, maxScale = 1.4f) {
        RadioControlsPreview(
            radioState = radio,
            com1Callsign = "LOWW_GND",
            com1StandbyCallsign = "LOWW_TWR",
            badge = { number -> CalloutBadge(number) }
        )
    }
}

/** A callout centred on the top-right corner of a control. */
@Composable
private fun Callout(number: Int, cornerX: Dp, cornerY: Dp) {
    CalloutBadge(number, Modifier.offset(x = cornerX - 11.dp, y = cornerY - 11.dp))
}

@Composable
private fun TransponderMessagesVisual() {
    val colors = LocalHandoffColors.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val radio = remember { DemoData.radioState() }
        Column(
            Modifier
                .width(140.dp)
                .background(colors.panelAlt, RoundedCornerShape(10.dp))
                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text("XPDR", fontSize = 12.sp, color = colors.textMuted)
            Text(radio.transponderCode.toString().padStart(4, '0'), fontSize = 23.sp, fontWeight = FontWeight.Bold, color = colors.text)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MessageState("Nothing new", unread = 0, directed = false)
            MessageState("Radio chatter", unread = 3, directed = false)
            MessageState("For you", unread = 1, directed = true)
        }
    }
}

@Composable
private fun MessageState(label: String, unread: Int, directed: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MessageIcon(unreadCount = unread, iconSize = 40.dp, countFontSize = 17.sp, hasDirectedUnread = directed)
        Text(label, fontSize = 12.sp, color = LocalHandoffColors.current.textMuted, textAlign = TextAlign.Center)
    }
}

// The aircraft button's top-right area in the 400dp-wide chat panel: the tab strip's row has 8dp
// horizontal / 6dp vertical padding, with the 48dp icon button at its end (no collapse button here).
// Nudged in from the true corner so the 22dp badge stays inside the panel's bounds.
private val ChatDesignWidth = 400.dp
private val NearbyButtonCorner = 386.dp to 14.dp

/** The real chat panel on the demo conversation, with a callout on the easy-to-miss nearby-aircraft
 *  button. Taps are swallowed so the compose field can't pop the keyboard inside the tour. */
@Composable
private fun ChatVisual() {
    val colors = LocalHandoffColors.current
    val chat = remember { DemoData.chat(System.currentTimeMillis()) }
    val controllers = remember { DemoData.controllers(System.currentTimeMillis()).controllers }
    ScaledToWidth(ChatDesignWidth) {
        Box {
            Box(
                Modifier
                    .height(300.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            ) {
                ChatPanelContent(
                    chat = chat,
                    controllers = controllers,
                    openTabs = listOf("LOWW_DEL"),
                    activeTab = null,
                    unreadByTab = mapOf("LOWW_DEL" to 1),
                    selcalActive = false,
                    ownCallsign = DemoData.OwnCallsign,
                    onSelectTab = {},
                    onCloseTab = {},
                    onOpenNearbyDialog = {},
                    onCollapse = null,
                    onSend = {}
                )
                Box(
                    Modifier.matchParentSize().clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        onClick = {}
                    )
                )
            }
            // Outside the clipped box so the rounded corner and the scaling box's bounds can't trim it.
            Callout(1, NearbyButtonCorner.first, NearbyButtonCorner.second)
        }
    }
}

private val VdgsDesignWidth = 560.dp

// The VDGS site's own dark palette, taken from its page (not the app theme): the tour shows that page.
private val VdgsCard = Color(0xFF141A27)
private val VdgsBorder = Color(0xFF232B3B)
private val VdgsLabel = Color(0xFF64748B)
private val VdgsYellow = Color(0xFFFACC15)
private val VdgsGreen = Color(0xFF86EFAC)
private val VdgsGreenBorder = Color(0xFF15803D)
private val VdgsGreenBg = Color(0xFF06180E)
private val VdgsHeaderHeight = 48.dp
private val VdgsCellHeight = 72.dp

/** How far back the demo slot is anchored so "now" falls inside its start-up window (the demo's own slot
 *  is anchored ahead, so the chip walks through the phases; the tour always shows the green case). */
private const val InWindowAnchorOffsetMillis = 7 * 60_000L

/** The status bar's start-up chip, and what the pilot sees on the VDGS page while the window is open:
 *  the green banner above the read-only flight-data grid. The TOBT entry form the real page has beside
 *  the grid is left out of the tour (the demo-mode page has it). Drawn natively in the app's own
 *  colours, filled from the demo flight. */
@Composable
private fun StartupWindowVisual() {
    val slot = remember { DemoData.cdmSlot(System.currentTimeMillis() - InWindowAnchorOffsetMillis) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusBarVisual(expanded = false, withCdm = true, callouts = listOf(1 to (CdmChipCorner)))
        ScaledToWidth(VdgsDesignWidth) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VdgsWindowOpenBanner()
                VdgsPanel(slot, Modifier.fillMaxWidth())
            }
        }
    }
}

/** The page's green "in your start-up window" banner, with its time-left countdown on the right. Fonts
 *  are a step smaller than the real page's so the whole sentence fits on one line above the grid. */
@Composable
private fun VdgsWindowOpenBanner() {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(VdgsGreenBg, shape)
            .border(1.dp, VdgsGreenBorder, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = VdgsGreen, modifier = Modifier.size(18.dp))
        Text(
            "You are in your start-up window. Request start-up from ATC if online.",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = VdgsGreen,
            modifier = Modifier.weight(1f)
        )
        Text("05:59 left", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VdgsGreen, maxLines = 1)
    }
}

@Composable
private fun VdgsPanel(slot: at.sushi.handoff.util.CdmSlotDisplay, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val dash = "- - - -"
    val cells = listOf(
        "CALLSIGN" to DemoData.OwnCallsign,
        "EOBT" to (slot.eobt ?: dash),
        "TOBT" to (slot.tobt ?: dash),
        "TSAT" to (slot.tsat ?: dash),
        "TAXI TIME" to (slot.exot?.toString() ?: dash),
        "CTOT" to (slot.ctot ?: dash),
        "REASON" to (slot.reason ?: dash),
        "ATFCM" to (slot.atfcmStatus ?: dash),
        "SID" to (slot.sid ?: dash)
    )
    Column(modifier.background(VdgsCard, shape).border(1.dp, VdgsBorder, shape)) {
        Row(
            Modifier.fillMaxWidth().height(VdgsHeaderHeight).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("FLIGHT DATA", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = VdgsLabel)
            Box(Modifier.weight(1f))
            // Shown on the real page whenever the pilot is not on the network with an A-CDM link.
            Text(
                "A-CDM DISCONNECTED",
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = VdgsLabel,
                modifier = Modifier
                    .border(1.dp, VdgsBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        androidx.compose.material3.HorizontalDivider(color = VdgsBorder)
        cells.chunked(3).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) androidx.compose.material3.HorizontalDivider(color = VdgsBorder)
            Row(Modifier.fillMaxWidth().height(VdgsCellHeight)) {
                row.forEachIndexed { colIndex, (label, value) ->
                    if (colIndex > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(VdgsBorder))
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = VdgsLabel)
                        Text(
                            value,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = at.sushi.handoff.ui.theme.RobotoMono,
                            color = VdgsYellow,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/** The status bar with its plug icon, and the form the plug opens (the real connect dialog's content,
 *  prefilled from the demo flight, drawn inline and shrunk so the page isn't cut off). */
@Composable
private fun VatsimConnectionVisual() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusBarVisual(expanded = false, callouts = listOf(1 to (PlugCorner)))
        ScaledToWidth(designWidth = 320.dp, maxScale = 0.8f) {
            ConnectNetworkDialog(
                initialCallsign = DemoData.OwnCallsign,
                initialAircraftType = "A320",
                initialSelcal = "",
                operations = emptyMap(),
                onSend = {},
                onDismiss = {},
                preview = true
            )
        }
    }
}

/** The expanded drawer is tall, so the status bar page shows the bar at its normal size but only down to roughly
 *  its first four status lines (the face row is 64dp, each line about 26dp with its gap), ending in a wavy torn
 *  edge so it reads as "there is more below". A little of the fifth line shows under the wave on purpose. */
private val ExpandedBarVisibleHeight = 190.dp

/** Laid out at about a phone's width (so the bar's own narrow layout is used, as on the real phone) rather than
 *  the 500dp the other bar pages use: shrinking 500dp onto a phone made the text unreadably small. */
private val ExpandedBarDesignWidth = 340.dp

/** Height of the torn edge at [x]: a sine wave of the given [amplitude] and [length] around [baseline]. One
 *  place for the clip shape and the line drawn along it, so the two can't drift apart. */
private fun waveY(x: Float, baseline: Float, amplitude: Float, length: Float): Float {
    val angle = (x / length) * (2f * Math.PI.toFloat())
    return baseline + (amplitude * kotlin.math.sin(angle))
}

private val WaveHeight = 10.dp
private val WaveLength = 26.dp

/** A rectangle whose bottom edge is a sine wave, like a torn page: [waveHeight] peak to peak, one wave per [waveLength]. */
private class WavyBottomShape(private val waveHeight: Dp = WaveHeight, private val waveLength: Dp = WaveLength) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): Outline {
        val amplitude = with(density) { waveHeight.toPx() } / 2f
        val length = with(density) { waveLength.toPx() }
        val baseline = size.height - amplitude
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            var x = size.width
            while (x >= 0f) {
                lineTo(x, waveY(x, baseline, amplitude, length))
                x -= 2f
            }
            close()
        }
        return Outline.Generic(path)
    }
}

// Same estimate-from-layout caveat as the radio callouts: the plug sits second of the three 36dp icons
// that end the 500dp status bar (14dp padding, 2dp gaps); the start-up chip ends 8dp before them.
private val PlugCorner = 448.dp to 14.dp

// The keep-screen-awake button is the first of the three icons ending the bar. This page lays the bar out at
// ExpandedBarDesignWidth (340dp), below the width where the bar uses its roomy 36dp icons, so they are 30dp
// each here: settings ends 14dp from the edge, the plug and this button follow 32dp apart. Top is the 1dp
// divider plus half of what is left of the 64dp row.
private val KeepAwakeCorner = 262.dp to 18.dp
private val CdmChipCorner = 366.dp to 12.dp

@Composable
private fun StatusBarVisual(
    expanded: Boolean,
    callouts: List<Pair<Int, Pair<Dp, Dp>>>,
    withCdm: Boolean = false,
    // When set, only this much of the top is shown, ending in a wavy edge (see ExpandedBarVisibleHeight).
    clipHeight: Dp? = null,
    designWidth: Dp = BarDesignWidth,
    maxScale: Float = 1f
) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
    val flightPlan = remember { DemoData.flightPlan() }
    // Only the start-up page asks for the chip; it shows the in-window (green) case, see InWindowAnchorOffsetMillis.
    val cdm = remember(withCdm) { if (withCdm) DemoData.cdmSlot(System.currentTimeMillis() - InWindowAnchorOffsetMillis) else null }
    val subsystem = remember(version) { DemoData.subsystemStatus().copy(pluginVersion = version) }
    ScaledToWidth(designWidth, maxScale) {
        Box(Modifier.fillMaxWidth()) {
            // The bar is laid out at its full height (unbounded) inside a box cut to clipHeight, so what is cut
            // off is simply not shown rather than squeezed.
            Box(
                if (clipHeight != null) Modifier.fillMaxWidth().height(clipHeight).clip(WavyBottomShape()) else Modifier.fillMaxWidth()
            ) {
                Box(
                    if (clipHeight != null) Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true) else Modifier.fillMaxWidth()
                ) {
            FooterStatusBar(
                connectionStatus = ConnectionStatus.CONNECTED,
                demoModeEnabled = false,
                origin = flightPlan.vatsimOrigin,
                destination = flightPlan.vatsimDestination,
                flightPlanWarning = false,
                flightPlanMismatch = false,
                originMismatch = false,
                vatsimCidMismatch = false,
                activeCallsign = DemoData.OwnCallsign,
                simbriefOrigin = flightPlan.simbriefOrigin,
                simbriefDestination = flightPlan.simbriefDestination,
                simbriefMissing = false,
                vatsimOrigin = flightPlan.vatsimOrigin,
                vatsimDestination = flightPlan.vatsimDestination,
                vatsimMissing = false,
                cdmSlot = cdm,
                address = "192.168.1.42",
                subsystemStatus = subsystem,
                operationIndicator = null,
                visibleOperations = emptyList(),
                latencyMs = 12,
                expanded = expanded,
                keepScreenAwake = true,
                onToggleExpanded = {},
                onRefresh = {},
                onRefreshVatsimFeed = {},
                onRefreshCdm = {},
                onOpenSettings = {},
                onOpenCdm = {},
                onToggleKeepScreenAwake = {},
                networkControlAvailable = true,
                onConnectNetwork = {},
                onDisconnectNetwork = {}
            )
                }
            }
            // The torn edge needs a line of its own: the cut-off part and the page behind it are nearly the same colour.
            if (clipHeight != null) {
                val edgeColor = LocalHandoffColors.current.textMuted
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(clipHeight)) {
                    val amplitude = WaveHeight.toPx() / 2f
                    val length = WaveLength.toPx()
                    val baseline = size.height - amplitude
                    val edge = Path().apply {
                        moveTo(0f, baseline)
                        var x = 0f
                        while (x <= size.width) {
                            lineTo(x, waveY(x, baseline, amplitude, length))
                            x += 2f
                        }
                    }
                    drawPath(edge, edgeColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
                }
            }
            callouts.forEach { (number, corner) -> Callout(number, corner.first, corner.second) }
        }
    }
}
