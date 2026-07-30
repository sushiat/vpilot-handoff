package at.sushi.handoff.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import at.sushi.handoff.R
import at.sushi.handoff.protocol.RadioFrequency
import at.sushi.handoff.protocol.RadioStateMessage
import at.sushi.handoff.ui.theme.FacilityColors
import at.sushi.handoff.ui.theme.LocalHandoffColors
import at.sushi.handoff.ui.theme.RobotoMono
import at.sushi.handoff.ui.theme.nearBlackText
import at.sushi.handoff.ui.theme.oklch
import at.sushi.handoff.ui.theme.perceptualLightness

/** The main screen's top bar: the app's own header ("Handoff" wordmark + "by sushi.at" +
 *  version, per issue #13's Assets section) above the button panel.
 *
 *  Issue #29's redesign, round 2: rather than one continuous formula trying to interpolate font
 *  sizes and column widths smoothly across the whole ~266-500dp real width range (measured/agreed
 *  bounds -- see the [[project_topbar_width_bounds]] memory and the "band study" design at
 *  https://claude.ai/design/p/4a31d761-f4df-4de9-86b5-9027e6580ef6), the panel now switches
 *  between 3 discrete, purpose-built layouts ("bands") at fixed breakpoints:
 *  - [TopBarWide] (>=500dp): the familiar 4-column grid (COM1|MIC|COM2|XPDR / STBY|MON|STBY|MSG),
 *    roomy sizes, MSG shows the last sender's name.
 *  - [TopBarCompact] (380-499dp): same 4-column shape, tighter type/padding, MSG drops to icon+
 *    count only.
 *  - [TopBarMinimum] (266-379dp): a genuinely different shape -- COM1/COM2 each become their own
 *    card with active+standby stacked inside, and XPDR/MIC/MON/MSG move to a second row of 4
 *    equal icon-first buttons.
 *  Within a band, only COM1/COM2's own column width flexes as the bar resizes -- font sizes and
 *  fixed-column widths are constants per band, not computed. This is deliberately simpler than
 *  the single-continuous-formula approach tried first: that required simultaneously solving for
 *  fixed-column ratios, a shared font-size ceiling, and wrap decisions across the *entire* range
 *  at once, and every fix for one width band tended to regress another (MIC/MON's font capped to
 *  avoid looking oversized on a narrow bar also capped it on a wide one; a "1+2" that needed to
 *  stack to fit at 266dp was still stacking pointlessly at 700dp). Three small, independent
 *  problems beats one hard one.
 *
 *  MIC/MON render as small color-coded pill badges (COM1 teal / COM2 rose, from the same
 *  [FacilityColors.TUNED_HUE]/[FacilityColors.COM2_TUNED_HUE] already used for tuned-controller
 *  row colors) instead of plain text -- MON shows either one badge (matching whichever COM is
 *  transmitting) or two side-by-side badges (one per COM, in each one's own color) when
 *  listening to both. */
@Composable
fun TopBar(
    radioState: RadioStateMessage,
    lastMessageLabel: String?,
    unreadCount: Int,
    // The station currently tuned on each COM, if any -- shown as a small third line under the
    // frequency (Garmin-style: freq, then station identifier below it). Looked up by the caller
    // from the live controller list (a frequency match, not the isCurrent flag -- see
    // MainScreen.kt) since TopBar itself has no access to that list.
    com1Callsign: String?,
    com2Callsign: String?,
    com1StandbyCallsign: String?,
    com2StandbyCallsign: String?,
    onSwapCom1: () -> Unit,
    onSwapCom2: () -> Unit,
    onOpenCom1Dialog: () -> Unit,
    onOpenCom2Dialog: () -> Unit,
    onOpenXpdrDialog: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleMon: () -> Unit,
    onToggleChat: () -> Unit
) {
    val colors = LocalHandoffColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.panel)
    ) {
        AppHeaderRow()

        val com1Value = radioState.com1Frequency?.let(RadioFrequency::format) ?: "---.---"
        val com2Value = radioState.com2Frequency?.let(RadioFrequency::format) ?: "---.---"
        val stby1Value = radioState.com1StandbyFrequency?.let(RadioFrequency::format) ?: "---.---"
        val stby2Value = radioState.com2StandbyFrequency?.let(RadioFrequency::format) ?: "---.---"
        val xpdrValue = radioState.transponderCode?.toString()?.padStart(4, '0') ?: "----"

        // txCom is null only before the first SimConnect read completes (or the radio host isn't
        // connected) -- neither com1TransmitEnabled nor com2TransmitEnabled true yet.
        val txCom = when {
            radioState.com1TransmitEnabled -> 1
            radioState.com2TransmitEnabled -> 2
            else -> null
        }
        val monListeningBoth = when (txCom) {
            1 -> radioState.com2ReceiveEnabled
            2 -> radioState.com1ReceiveEnabled
            else -> false
        }
        // MON shows two badges (one per COM, each in that COM's own color) when listening to
        // both; otherwise both MIC and MON show the same single badge -- whichever COM is
        // currently transmitting.
        val micBadges = listOf(micMonBadgeFor(txCom, colors.border, colors.textMuted))
        val monBadges = if (monListeningBoth) {
            listOf(comBadge(1), comBadge(2))
        } else {
            micBadges
        }

        val isRadioTab = lastMessageLabel == "RADIO"
        val msgDisplayValue = if (isRadioTab) {
            radioState.com1Frequency?.let(RadioFrequency::format) ?: "---.---"
        } else {
            lastMessageLabel ?: ""
        }

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            when {
                maxWidth < 380.dp -> TopBarMinimum(
                    com1Value = com1Value, com2Value = com2Value,
                    stby1Value = stby1Value, stby2Value = stby2Value,
                    xpdrValue = xpdrValue, modeCEnabled = radioState.modeCEnabled,
                    com1Callsign = com1Callsign, com2Callsign = com2Callsign,
                    com1StandbyCallsign = com1StandbyCallsign, com2StandbyCallsign = com2StandbyCallsign,
                    micBadges = micBadges, monBadges = monBadges,
                    unreadCount = unreadCount,
                    onSwapCom1 = onSwapCom1, onSwapCom2 = onSwapCom2,
                    onOpenCom1Dialog = onOpenCom1Dialog, onOpenCom2Dialog = onOpenCom2Dialog,
                    onOpenXpdrDialog = onOpenXpdrDialog,
                    onToggleMic = onToggleMic, onToggleMon = onToggleMon, onToggleChat = onToggleChat
                )
                maxWidth < WideBandMinContentWidth -> TopBarCompact(
                    com1Value = com1Value, com2Value = com2Value,
                    stby1Value = stby1Value, stby2Value = stby2Value,
                    xpdrValue = xpdrValue, modeCEnabled = radioState.modeCEnabled,
                    com1Callsign = com1Callsign, com2Callsign = com2Callsign,
                    com1StandbyCallsign = com1StandbyCallsign, com2StandbyCallsign = com2StandbyCallsign,
                    micBadges = micBadges, monBadges = monBadges,
                    unreadCount = unreadCount,
                    onSwapCom1 = onSwapCom1, onSwapCom2 = onSwapCom2,
                    onOpenCom1Dialog = onOpenCom1Dialog, onOpenCom2Dialog = onOpenCom2Dialog,
                    onOpenXpdrDialog = onOpenXpdrDialog,
                    onToggleMic = onToggleMic, onToggleMon = onToggleMon, onToggleChat = onToggleChat
                )
                else -> TopBarWide(
                    com1Value = com1Value, com2Value = com2Value,
                    stby1Value = stby1Value, stby2Value = stby2Value,
                    xpdrValue = xpdrValue, modeCEnabled = radioState.modeCEnabled,
                    com1Callsign = com1Callsign, com2Callsign = com2Callsign,
                    com1StandbyCallsign = com1StandbyCallsign, com2StandbyCallsign = com2StandbyCallsign,
                    micBadges = micBadges, monBadges = monBadges,
                    unreadCount = unreadCount, msgDisplayValue = msgDisplayValue,
                    onSwapCom1 = onSwapCom1, onSwapCom2 = onSwapCom2,
                    onOpenCom1Dialog = onOpenCom1Dialog, onOpenCom2Dialog = onOpenCom2Dialog,
                    onOpenXpdrDialog = onOpenXpdrDialog,
                    onToggleMic = onToggleMic, onToggleMon = onToggleMon, onToggleChat = onToggleChat
                )
            }
        }
        androidx.compose.material3.HorizontalDivider(color = colors.border)
    }
}

// internal, not private: FooterStatusBar reuses this same width to decide when to drop its own
// "Connected"/"Disconnected" label -- kept distinct from the band breakpoints above (which are
// this file's own concern) so both bars agree on what counts as "narrow" for that unrelated
// purpose without this file's band thresholds having to double as a public contract.
internal val NarrowTopBarThreshold = 375.dp

// The design study's WIDE band starts at 500dp of *content* width. MainScreen.kt's fullscreen
// panel is pinned to 500dp of *outer* width (see its own comment there), but this BoxWithConstraints
// measures maxWidth *after* the 14dp-per-side padding below is applied to it, i.e. content width --
// so fullscreen's actual 500dp panel only ever delivers 500dp - 28dp = 472dp of content width here.
// Without this adjustment fullscreen always landed in COMPACT, never the roomier WIDE band it was
// meant to guarantee (found on-device: fullscreen visibly showed COMPACT's tighter icon-only MSG
// button, not WIDE's sender-name line).
private val WideBandMinContentWidth = 472.dp

private data class MicMonBadge(val text: String, val color: Color)

/** COM1 gets teal, COM2 gets rose -- the exact same [FacilityColors.TUNED_HUE]/
 *  [FacilityColors.COM2_TUNED_HUE] already used for tuned-controller row colors elsewhere in the
 *  app, so MIC/MON's badges read as "the same COM1/COM2 identity" as the rest of the UI instead
 *  of introducing a second, unrelated color pair. */
private fun comBadge(com: Int): MicMonBadge {
    val hue = if (com == 1) FacilityColors.TUNED_HUE else FacilityColors.COM2_TUNED_HUE
    return MicMonBadge(com.toString(), FacilityColors.fullColor(hue).bg)
}

private fun micMonBadgeFor(txCom: Int?, neutralBg: Color, neutralText: Color): MicMonBadge =
    txCom?.let { comBadge(it) } ?: MicMonBadge("-", neutralBg)

@Composable
private fun AppHeaderRow() {
    val colors = LocalHandoffColors.current
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 14.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            painterResource(R.drawable.ic_handoff_mark),
            contentDescription = null,
            tint = colors.textMuted,
            modifier = Modifier.size(18.dp)
        )
        Text("Handoff", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.textMuted)
        Text("by sushi.at", fontSize = 12.sp, color = colors.textMuted.copy(alpha = 0.85f))
        Box(Modifier.weight(1f))
        Text(
            "v$versionName",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = RobotoMono,
            color = colors.textMuted.copy(alpha = 0.6f)
        )
    }
}

/** Disables legacy Android font-padding (the same fix already used elsewhere in this app, e.g.
 *  BadgePill/RatingBadge) since the default per-line leading was reading as a distractingly wide
 *  gap directly under the frequency value (feedback: "much tighter"). */
@Composable
private fun StationLine(text: String, color: Color, fontSize: TextUnit) {
    Text(
        text,
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
        fontFamily = RobotoMono,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
    )
}

/** Renders a "DDD.DDD"-shaped frequency value at [fontSize], splitting onto a second line at the
 *  decimal point if the whole value doesn't measure as fitting on one line within
 *  [availableWidth] -- never more than 2 lines. A plain code with no decimal point (the XPDR
 *  squawk) has no split point at all and must never be split -- rendered single-line/no-wrap
 *  instead, full stop, even if it doesn't fit.
 *
 *  This measurement is a defensive fallback now that each band's font sizes are fixed constants
 *  chosen to comfortably fit that band's own column widths -- it shouldn't normally trigger, but
 *  keeps a long callsign/edge-case width from silently dropping half the value (found the hard
 *  way at the ~266dp measured minimum-usable width -- see git history). */
@Composable
private fun FrequencyValueText(
    value: String,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    color: Color,
    availableWidth: Dp
) {
    val dotIndex = value.indexOf('.')
    if (dotIndex < 0) {
        Text(
            value, fontSize = fontSize, fontWeight = fontWeight, fontFamily = RobotoMono, color = color,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fitsOnOneLine = remember(value, fontSize, fontWeight, availableWidth, density) {
        val style = TextStyle(fontSize = fontSize, fontWeight = fontWeight, fontFamily = RobotoMono)
        val availableWidthPx = with(density) { availableWidth.roundToPx() }
        textMeasurer.measure(value, style).size.width <= availableWidthPx
    }

    val noFontPadding = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
    if (fitsOnOneLine) {
        Text(
            value, fontSize = fontSize, fontWeight = fontWeight, fontFamily = RobotoMono, color = color,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, style = noFontPadding
        )
    } else {
        Column {
            Text(
                value.substring(0, dotIndex + 1), fontSize = fontSize, fontWeight = fontWeight,
                fontFamily = RobotoMono, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                style = noFontPadding
            )
            Text(
                value.substring(dotIndex + 1), fontSize = fontSize, fontWeight = fontWeight,
                fontFamily = RobotoMono, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                style = noFontPadding
            )
        }
    }
}

@Composable
private fun ButtonLabel(text: String, fontSize: TextUnit, alpha: Float, modifier: Modifier = Modifier) {
    val colors = LocalHandoffColors.current
    Text(
        text,
        modifier = modifier,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.06f.em,
        color = colors.textMuted.copy(alpha = alpha),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
    )
}

/** A small color-coded pill for MIC/MON's transmit/receive state -- see [comBadge]. */
@Composable
private fun MicMonBadgeView(badge: MicMonBadge, fontSize: TextUnit, paddingH: Dp, paddingV: Dp) {
    Box(
        Modifier
            .background(badge.color, RoundedCornerShape(4.dp))
            .padding(horizontal = paddingH, vertical = paddingV),
        contentAlignment = Alignment.Center
    ) {
        Text(
            badge.text,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontFamily = RobotoMono,
            color = Color.White,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

@Composable
private fun MicMonBadgeRow(badges: List<MicMonBadge>, fontSize: TextUnit, paddingH: Dp, paddingV: Dp, gap: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        badges.forEach { MicMonBadgeView(it, fontSize, paddingH, paddingV) }
    }
}

/** Mode C indicator, band A style: a small colored "C" letter next to the XPDR label. Hidden
 *  entirely when Mode C is off (matches the design study -- simpler than the old always-shown,
 *  differently-colored-when-off badge). */
@Composable
private fun ModeCLetter(modeCEnabled: Boolean, fontSize: TextUnit) {
    if (!modeCEnabled) return
    val colors = LocalHandoffColors.current
    Text(
        // RobotoMono.kt only bundles up to a Bold (700) weight font file -- there's no bundled
        // Black/ExtraBold asset to render a genuinely bulkier "C" glyph. FontWeight.Black still
        // has an effect: Compose's default fontSynthesis synthetically thickens the matched Bold
        // face further rather than just clamping to it, which is the "bulkier" bump asked for
        // without adding a new font file.
        "C", fontSize = fontSize, fontWeight = FontWeight.Black, letterSpacing = 0.06f.em,
        color = colors.accent, fontFamily = RobotoMono
    )
}

/** Mode C indicator, band B/C style: a small colored dot. Hidden entirely when Mode C is off. */
@Composable
private fun ModeCDot(modeCEnabled: Boolean, size: Dp) {
    if (!modeCEnabled) return
    val colors = LocalHandoffColors.current
    Box(Modifier.size(size).background(colors.accent, CircleShape))
}

// The design study's own MSG icon -- a plain, single-fill speech-bubble path (SVG
// "M4 4h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H8l-4 4V6a2 2 0 0 1 2-2z"), not a stock Material icon.
// Parsed directly from that path string rather than hand-translated, so it matches exactly.
// This matters: both `Icons.AutoMirrored.Filled.Message` and `.Chat` have internal white detail
// lines baked into the glyph itself, which clashed with a white count number painted on top of
// them (the number was there, just camouflaged against the icon's own lines -- only visible
// zoomed into a render, not at a glance). This path has no internal detail to clash with, so the
// count can sit directly on it, matching the design study's own treatment instead of needing an
// extra circle backdrop (feedback: the circle "looks daft").
private val MessageBubbleIcon: ImageVector by lazy {
    val nodes = PathParser().parsePathString("M4 4h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H8l-4 4V6a2 2 0 0 1 2-2z").toNodes()
    ImageVector.Builder(name = "MessageBubble", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(nodes, fill = SolidColor(Color.Black))
        .build()
}

/** The message icon with the unread count painted directly on top of it -- see
 *  [MessageBubbleIcon]'s doc for why that's safe to do here (unlike a stock Material icon).
 *  Three solid (never dimmed) bubble colors, transcribed from the design study's own
 *  `msgIconColor`/`msgCountColor` logic: no unread -> the theme's own text color (i.e. whatever
 *  already reads as "opposite of the panel background" in each theme -- the design study's own
 *  pale-grey value was a light-theme-only constant that went invisible against this app's near
 *  identical light-theme card background, and would have been just as wrong the other way round
 *  against a dark card); unread directed at the pilot (a private message, or a radio message
 *  mentioning our callsign) -> orange; unread not directed at us -> blue. The count's own color
 *  isn't a fixed per-state choice either -- it's [perceptualLightness] of the actual bubble color,
 *  same black-vs-white contrast decision [at.sushi.handoff.ui.theme.controllerRowColors] already
 *  uses for row text, so this can't silently drift out of sync if the bubble hues are ever
 *  retuned (or the no-unread color swaps between light/dark theme).
 *
 *  TODO: [hasDirectedUnread] has no real data source yet -- unreadCount aggregation
 *  (`unreadByTab` in MainScreen.kt) is itself still a stub that's never incremented, so this
 *  always renders as "unread, not directed" (blue) whenever unreadCount > 0. Wiring up per-tab
 *  unread tracking plus a directed-at-me check (private-message tabs, or a radio message
 *  matching ownCallsign, mirroring ChatPanelContent's existing `mentionsUs` logic) is follow-up
 *  work, not part of this pass. */
@Composable
private fun MessageIcon(
    unreadCount: Int,
    iconSize: Dp,
    countFontSize: TextUnit,
    modifier: Modifier = Modifier,
    hasDirectedUnread: Boolean = false
) {
    val colors = LocalHandoffColors.current
    val hasUnread = unreadCount > 0
    val iconColor = when {
        !hasUnread -> colors.text
        hasDirectedUnread -> oklch(0.70f, 0.17f, 45f)
        else -> oklch(0.68f, 0.14f, 250f)
    }
    val countColor = if (perceptualLightness(iconColor) < 54f) Color.White else nearBlackText
    Box(modifier.size(iconSize), contentAlignment = Alignment.Center) {
        Icon(MessageBubbleIcon, contentDescription = "Messages", tint = iconColor, modifier = Modifier.size(iconSize))
        Text(
            unreadCount.toString(),
            fontSize = countFontSize,
            fontWeight = FontWeight.Bold,
            color = countColor,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

// ============================================================================================
// Band A -- WIDE (>=500dp): the familiar 4-column grid, roomy sizes.
// ============================================================================================

// Font sizes bumped +3sp across the board from the design study's own px values -- feedback
// after the first on-device check: the study's HTML/CSS px sizes read noticeably smaller once
// translated to Android sp/dp than they looked in the browser mockup, leaving visible empty
// space in every button. valueFontSize is shared by COM/STBY *and* XPDR within a band (was
// already true here; kept explicit so the two can never quietly drift apart again).
private object WideSizes {
    val fixedMicMonWidth = 64.dp
    val fixedXpdrMsgWidth = 104.dp
    val rowGap = 6.dp
    val colGap = 8.dp
    val buttonPaddingH = 10.dp
    val buttonPaddingV = 8.dp
    val labelFontSize = 12.sp
    val valueFontSize = 23.sp
    val stationFontSize = 12.5.sp
    val modeCFontSize = 13.5.sp
    val micMonBadgeFontSize = 18.sp
    val micMonBadgePaddingH = 7.dp
    val micMonBadgePaddingV = 3.dp
    val micMonBadgeGap = 6.dp
    val msgIconSize = 32.dp
    val msgCountFontSize = 15.sp
    val msgNameFontSize = 15.sp
}

@Composable
private fun TopBarWide(
    com1Value: String, com2Value: String, stby1Value: String, stby2Value: String,
    xpdrValue: String, modeCEnabled: Boolean,
    com1Callsign: String?, com2Callsign: String?, com1StandbyCallsign: String?, com2StandbyCallsign: String?,
    micBadges: List<MicMonBadge>, monBadges: List<MicMonBadge>,
    unreadCount: Int, msgDisplayValue: String,
    onSwapCom1: () -> Unit, onSwapCom2: () -> Unit,
    onOpenCom1Dialog: () -> Unit, onOpenCom2Dialog: () -> Unit, onOpenXpdrDialog: () -> Unit,
    onToggleMic: () -> Unit, onToggleMon: () -> Unit, onToggleChat: () -> Unit
) {
    val s = WideSizes
    // Two independent rows, each syncing height only *within* itself via the standard
    // height(IntrinsicSize.Min) + fillMaxHeight idiom -- no cross-row height sync (that used to
    // go through a custom EqualHeightRows Layout using multi-content intrinsics, which caused a
    // real bug: WideMsgButton's label and name text silently failed to render inside it, only the
    // bare icon showed, even though the exact same composable rendered correctly in isolation).
    // Each band now uses fixed, symmetric font sizes per row (COM1/COM2 mirror STBY1/STBY2
    // exactly; MIC mirrors MON) rather than dynamically wrapping content, so the two rows land at
    // the same natural height without needing to force it.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(s.rowGap)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(s.colGap)) {
            WideComButton(Modifier.weight(1f).fillMaxHeight(), "COM1", com1Value, large = true, callsign = com1Callsign, onClick = onSwapCom1)
            WideMicMonButton(Modifier.width(s.fixedMicMonWidth).fillMaxHeight(), "MIC", micBadges, onToggleMic)
            WideComButton(Modifier.weight(1f).fillMaxHeight(), "COM2", com2Value, large = true, callsign = com2Callsign, onClick = onSwapCom2)
            WideXpdrButton(Modifier.width(s.fixedXpdrMsgWidth).fillMaxHeight(), xpdrValue, modeCEnabled, onOpenXpdrDialog)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(s.colGap)) {
            WideComButton(Modifier.weight(1f).fillMaxHeight(), "STBY", stby1Value, large = false, callsign = com1StandbyCallsign, onClick = onOpenCom1Dialog)
            WideMicMonButton(Modifier.width(s.fixedMicMonWidth).fillMaxHeight(), "MON", monBadges, onToggleMon)
            WideComButton(Modifier.weight(1f).fillMaxHeight(), "STBY", stby2Value, large = false, callsign = com2StandbyCallsign, onClick = onOpenCom2Dialog)
            WideMsgButton(Modifier.width(s.fixedXpdrMsgWidth).fillMaxHeight(), unreadCount, msgDisplayValue, onToggleChat)
        }
    }
}

@Composable
private fun RowScope.WideComButton(modifier: Modifier, label: String, value: String, large: Boolean, callsign: String?, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    val s = WideSizes
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.background(colors.panelAlt, shape).border(1.dp, colors.border, shape).clickable(onClick = onClick)
            .padding(horizontal = s.buttonPaddingH, vertical = s.buttonPaddingV)
    ) {
        ButtonLabel(label, s.labelFontSize, if (large) 0.7f else 0.6f)
        FrequencyValueText(
            value = value, fontSize = s.valueFontSize,
            fontWeight = if (large) FontWeight.Bold else FontWeight.Medium,
            color = colors.text.copy(alpha = if (large) 1f else 0.75f),
            availableWidth = 200.dp // generous; band A's flex columns are always wide enough that this fallback rarely engages
        )
        StationLine(callsign ?: "", colors.textMuted, s.stationFontSize)
    }
}

@Composable
private fun RowScope.WideXpdrButton(modifier: Modifier, value: String, modeCEnabled: Boolean, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    val s = WideSizes
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.background(colors.panelAlt, shape).border(1.dp, colors.border, shape).clickable(onClick = onClick)
            .padding(horizontal = s.buttonPaddingH, vertical = s.buttonPaddingV)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ButtonLabel("XPDR", s.labelFontSize, 0.7f)
            ModeCLetter(modeCEnabled, s.modeCFontSize)
        }
        FrequencyValueText(value, s.valueFontSize, FontWeight.Bold, colors.text, availableWidth = s.fixedXpdrMsgWidth - s.buttonPaddingH * 2)
    }
}

@Composable
private fun RowScope.WideMicMonButton(modifier: Modifier, label: String, badges: List<MicMonBadge>, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    val s = WideSizes
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.background(colors.panelAlt, shape).border(1.dp, colors.border, shape).clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = s.buttonPaddingV)
    ) {
        // The badge is centered on the whole button, independent of where the label sits -- not
        // in the leftover space below it (which used to push it half a label-height too low).
        ButtonLabel(label, s.labelFontSize, 0.7f, modifier = Modifier.align(Alignment.TopCenter))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MicMonBadgeRow(badges, s.micMonBadgeFontSize, s.micMonBadgePaddingH, s.micMonBadgePaddingV, s.micMonBadgeGap)
        }
    }
}

@Composable
private fun RowScope.WideMsgButton(modifier: Modifier, unreadCount: Int, displayValue: String, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    val s = WideSizes
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.background(colors.panelAlt, shape).border(1.dp, colors.border, shape).clickable(onClick = onClick)
            .padding(horizontal = s.buttonPaddingH, vertical = s.buttonPaddingV)
    ) {
        Box(Modifier.fillMaxWidth()) {
            ButtonLabel("MSG", s.labelFontSize, 0.7f, modifier = Modifier.align(Alignment.CenterStart))
            MessageIcon(unreadCount, s.msgIconSize, s.msgCountFontSize, modifier = Modifier.align(Alignment.CenterEnd))
        }
        Text(
            displayValue, fontSize = s.msgNameFontSize, fontWeight = FontWeight.Medium,
            color = colors.text.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

// ============================================================================================
// Band B -- COMPACT (380-499dp): same 4-column shape, tighter type/padding, MSG icon+count only.
// ============================================================================================

// See WideSizes' comment -- same +3sp bump, same COM/XPDR valueFontSize coupling.
private object CompactSizes {
    val fixedMicMonWidth = 46.dp
    val fixedXpdrMsgWidth = 60.dp
    val rowGap = 4.dp
    val colGap = 6.dp
    val buttonPaddingH = 7.dp
    val buttonPaddingV = 6.dp
    val labelFontSize = 10.5.sp
    val valueFontSize = 19.sp
    val stationFontSize = 11.5.sp
    val modeCDotSize = 6.dp
    val micMonBadgeFontSize = 15.sp
    val micMonBadgePaddingH = 5.dp
    val micMonBadgePaddingV = 2.dp
    val micMonBadgeGap = 4.dp
    val msgIconSize = 41.dp
    val msgCountFontSize = 18.sp
}

@Composable
private fun TopBarCompact(
    com1Value: String, com2Value: String, stby1Value: String, stby2Value: String,
    xpdrValue: String, modeCEnabled: Boolean,
    com1Callsign: String?, com2Callsign: String?, com1StandbyCallsign: String?, com2StandbyCallsign: String?,
    micBadges: List<MicMonBadge>, monBadges: List<MicMonBadge>,
    unreadCount: Int,
    onSwapCom1: () -> Unit, onSwapCom2: () -> Unit,
    onOpenCom1Dialog: () -> Unit, onOpenCom2Dialog: () -> Unit, onOpenXpdrDialog: () -> Unit,
    onToggleMic: () -> Unit, onToggleMon: () -> Unit, onToggleChat: () -> Unit
) {
    val s = CompactSizes
    // See TopBarWide's comment -- two independent rows, no cross-row height sync.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(s.rowGap)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(s.colGap)) {
            CompactComButton(Modifier.weight(1f).fillMaxHeight(), "COM1", com1Value, large = true, callsign = com1Callsign, onClick = onSwapCom1)
            CompactMicMonButton(Modifier.width(s.fixedMicMonWidth).fillMaxHeight(), "MIC", micBadges, onToggleMic)
            CompactComButton(Modifier.weight(1f).fillMaxHeight(), "COM2", com2Value, large = true, callsign = com2Callsign, onClick = onSwapCom2)
            CompactXpdrButton(Modifier.width(s.fixedXpdrMsgWidth).fillMaxHeight(), xpdrValue, modeCEnabled, onOpenXpdrDialog)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(s.colGap)) {
            CompactComButton(Modifier.weight(1f).fillMaxHeight(), "STBY", stby1Value, large = false, callsign = com1StandbyCallsign, onClick = onOpenCom1Dialog)
            CompactMicMonButton(Modifier.width(s.fixedMicMonWidth).fillMaxHeight(), "MON", monBadges, onToggleMon)
            CompactComButton(Modifier.weight(1f).fillMaxHeight(), "STBY", stby2Value, large = false, callsign = com2StandbyCallsign, onClick = onOpenCom2Dialog)
            CompactMsgButton(Modifier.width(s.fixedXpdrMsgWidth).fillMaxHeight(), unreadCount, onToggleChat)
        }
    }
}

@Composable
private fun RowScope.CompactComButton(modifier: Modifier, label: String, value: String, large: Boolean, callsign: String?, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    val s = CompactSizes
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.background(colors.panelAlt, shape).border(1.dp, colors.border, shape).clickable(onClick = onClick)
            .padding(horizontal = s.buttonPaddingH, vertical = s.buttonPaddingV)
    ) {
        ButtonLabel(label, s.labelFontSize, if (large) 0.7f else 0.6f)
        FrequencyValueText(
            value = value, fontSize = s.valueFontSize,
            fontWeight = if (large) FontWeight.Bold else FontWeight.Medium,
            color = colors.text.copy(alpha = if (large) 1f else 0.75f),
            availableWidth = 140.dp
        )
        StationLine(callsign ?: "", colors.textMuted, s.stationFontSize)
    }
}

@Composable
private fun RowScope.CompactXpdrButton(modifier: Modifier, value: String, modeCEnabled: Boolean, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    val s = CompactSizes
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.background(colors.panelAlt, shape).border(1.dp, colors.border, shape).clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = s.buttonPaddingV),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ButtonLabel("XPDR", s.labelFontSize, 0.7f)
            ModeCDot(modeCEnabled, s.modeCDotSize)
        }
        FrequencyValueText(value, s.valueFontSize, FontWeight.Bold, colors.text, availableWidth = s.fixedXpdrMsgWidth - 8.dp)
    }
}

@Composable
private fun RowScope.CompactMicMonButton(modifier: Modifier, label: String, badges: List<MicMonBadge>, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    val s = CompactSizes
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.background(colors.panelAlt, shape).border(1.dp, colors.border, shape).clickable(onClick = onClick)
            .padding(horizontal = 1.dp, vertical = s.buttonPaddingV)
    ) {
        // See WideMicMonButton's comment -- centered on the whole button, not the space below the label.
        ButtonLabel(label, s.labelFontSize, 0.7f, modifier = Modifier.align(Alignment.TopCenter))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MicMonBadgeRow(badges, s.micMonBadgeFontSize, s.micMonBadgePaddingH, s.micMonBadgePaddingV, s.micMonBadgeGap)
        }
    }
}

@Composable
private fun RowScope.CompactMsgButton(modifier: Modifier, unreadCount: Int, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    val s = CompactSizes
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.background(colors.panelAlt, shape).border(1.dp, colors.border, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        MessageIcon(unreadCount, s.msgIconSize, s.msgCountFontSize)
    }
}

// ============================================================================================
// Band C -- MINIMUM (266-379dp): a genuinely different shape. COM1/COM2 each become their own
// card with active+standby stacked inside; XPDR/MIC/MON/MSG move to a second row of 4 equal
// icon-first buttons.
// ============================================================================================

// See WideSizes' comment -- same +3sp bump. The design study's own Band C used a smaller
// dedicated xpdrCodeFontSize (12sp) than the COM cards' valueFontSize (15sp) -- dropped in favor
// of reusing valueFontSize directly for XPDR too, so COM and XPDR can't quietly drift out of
// step (feedback: "com and xdpr fonts aren't coupled anymore").
private object MinimumSizes {
    val outerGap = 6.dp
    val cardGap = 4.dp
    val row2Gap = 5.dp
    val activePaddingH = 7.dp
    val activePaddingV = 5.dp
    val standbyPaddingV = 4.dp
    val labelFontSize = 10.sp
    val valueFontSize = 18.sp
    val stationFontSize = 11.sp
    val xpdrLabelFontSize = 9.sp
    val modeCDotSize = 6.dp
    val micMonLabelFontSize = 10.sp
    val micMonBadgeFontSize = 15.sp
    val micMonBadgePaddingH = 5.dp
    val micMonBadgePaddingV = 2.dp
    val micMonBadgeGap = 4.dp
    val msgIconSize = 36.dp
    val msgCountFontSize = 18.sp
}

@Composable
private fun TopBarMinimum(
    com1Value: String, com2Value: String, stby1Value: String, stby2Value: String,
    xpdrValue: String, modeCEnabled: Boolean,
    com1Callsign: String?, com2Callsign: String?, com1StandbyCallsign: String?, com2StandbyCallsign: String?,
    micBadges: List<MicMonBadge>, monBadges: List<MicMonBadge>,
    unreadCount: Int,
    onSwapCom1: () -> Unit, onSwapCom2: () -> Unit,
    onOpenCom1Dialog: () -> Unit, onOpenCom2Dialog: () -> Unit, onOpenXpdrDialog: () -> Unit,
    onToggleMic: () -> Unit, onToggleMon: () -> Unit, onToggleChat: () -> Unit
) {
    val s = MinimumSizes
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(s.outerGap)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(s.cardGap * 2)) {
            MinimumComCard(Modifier.weight(1f), "COM1", com1Value, stby1Value, com1Callsign, com1StandbyCallsign, onSwapCom1, onOpenCom1Dialog)
            MinimumComCard(Modifier.weight(1f), "COM2", com2Value, stby2Value, com2Callsign, com2StandbyCallsign, onSwapCom2, onOpenCom2Dialog)
        }
        // height(IntrinsicSize.Min) here, matching bands A/B's row -- without it, the fillMaxHeight()
        // calls inside MinimumMicMonButton/MinimumMsgButton had no bounded row height to fill
        // against (this Row's own height was otherwise just "wrap to tallest child", an unresolved
        // circular size for a fillMaxHeight() child), so they expanded into whatever unbounded
        // space the parent Column happened to offer -- badges rendered detached, far below their
        // own button's visible border. Found on-device, not in Paparazzi (its BoxWithConstraints
        // harness happens to bound height where the real screen doesn't).
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(s.row2Gap)) {
            MinimumXpdrButton(Modifier.weight(1f).fillMaxHeight(), xpdrValue, modeCEnabled, onOpenXpdrDialog)
            MinimumMicMonButton(Modifier.weight(1f).fillMaxHeight(), "MIC", micBadges, onToggleMic)
            MinimumMicMonButton(Modifier.weight(1f).fillMaxHeight(), "MON", monBadges, onToggleMon)
            MinimumMsgButton(Modifier.weight(1f).fillMaxHeight(), unreadCount, onToggleChat)
        }
    }
}

@Composable
private fun RowScope.MinimumComCard(
    modifier: Modifier, label: String, activeValue: String, standbyValue: String,
    activeCallsign: String?, standbyCallsign: String?, onActiveClick: () -> Unit, onStandbyClick: () -> Unit
) {
    val colors = LocalHandoffColors.current
    val s = MinimumSizes
    val shape = RoundedCornerShape(8.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(s.cardGap)) {
        Column(
            Modifier.fillMaxWidth().background(colors.panelAlt, shape).border(1.dp, colors.border, shape).clickable(onClick = onActiveClick)
                .padding(horizontal = s.activePaddingH, vertical = s.activePaddingV)
        ) {
            ButtonLabel(label, s.labelFontSize, 0.7f)
            FrequencyValueText(activeValue, s.valueFontSize, FontWeight.Bold, colors.text, availableWidth = 120.dp)
            StationLine(activeCallsign ?: "", colors.textMuted, s.stationFontSize)
        }
        Column(
            Modifier.fillMaxWidth().background(colors.panelAlt, shape).border(1.dp, colors.border, shape).clickable(onClick = onStandbyClick)
                .padding(horizontal = s.activePaddingH, vertical = s.standbyPaddingV)
        ) {
            // No "STBY" label here -- the design study omits it for this card (active's own label
            // above already establishes which COM this is; the standby value's dimmer color/weight
            // is what distinguishes it, same as the wider bands).
            FrequencyValueText(standbyValue, s.valueFontSize, FontWeight.Medium, colors.text.copy(alpha = 0.75f), availableWidth = 120.dp)
            StationLine(standbyCallsign ?: "", colors.textMuted, s.stationFontSize)
        }
    }
}

@Composable
private fun RowScope.MinimumXpdrButton(modifier: Modifier, value: String, modeCEnabled: Boolean, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    val s = MinimumSizes
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.background(colors.panelAlt, shape).border(1.dp, colors.border, shape).clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ButtonLabel("XPDR", s.xpdrLabelFontSize, 0.7f)
            ModeCDot(modeCEnabled, s.modeCDotSize)
        }
        FrequencyValueText(value, s.valueFontSize, FontWeight.Bold, colors.text, availableWidth = 70.dp)
    }
}

@Composable
private fun RowScope.MinimumMicMonButton(modifier: Modifier, label: String, badges: List<MicMonBadge>, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    val s = MinimumSizes
    val shape = RoundedCornerShape(8.dp)
    // Unlike Wide/Compact, MINIMUM's button is too short for the badge to center on the whole
    // button without colliding with the label -- back to centering in the space below the label.
    Column(
        modifier.background(colors.panelAlt, shape).border(1.dp, colors.border, shape).clickable(onClick = onClick)
            .padding(horizontal = 1.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ButtonLabel(label, s.micMonLabelFontSize, 0.7f)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            MicMonBadgeRow(badges, s.micMonBadgeFontSize, s.micMonBadgePaddingH, s.micMonBadgePaddingV, s.micMonBadgeGap)
        }
    }
}

@Composable
private fun RowScope.MinimumMsgButton(modifier: Modifier, unreadCount: Int, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    val s = MinimumSizes
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.background(colors.panelAlt, shape).border(1.dp, colors.border, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        MessageIcon(unreadCount, s.msgIconSize, s.msgCountFontSize)
    }
}

