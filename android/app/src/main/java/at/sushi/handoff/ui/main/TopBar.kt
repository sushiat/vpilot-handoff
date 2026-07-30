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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import at.sushi.handoff.ui.theme.RobotoMono
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import at.sushi.handoff.R
import at.sushi.handoff.protocol.RadioFrequency
import at.sushi.handoff.protocol.RadioStateMessage
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** The main screen's top bar: the app's own header ("Handoff" wordmark + "by sushi.at" +
 *  version, per issue #13's Assets section) above a 4x2 grid (COM1/MIC/COM2/XPDR active row,
 *  STBY/MON/STBY/MSG standby row, per issue #29). Tapping a COM active button swaps it with
 *  standby immediately (no dialog); tapping a standby button opens that COM's tuning dialog.
 *  MIC/XPDR (top) and MON/MSG (bottom) are fixed-width columns -- their content is short and
 *  roughly constant -- while COM1/COM2/STBY1/STBY2 flex to fill whatever space remains,
 *  shrinking a shared font size down to a floor before ever wrapping past two lines (issue #29:
 *  "I don't ever want the frequencies on more than two lines and below a certain minimum font
 *  size or they become useless"). */
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
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Drives MIC/MON/MSG's own (coarser, 2-step) font sizing -- the shared COM/STBY/XPDR
            // font size below already reacts continuously to available width, so isNarrow no
            // longer needs to gate that.
            val isNarrow = maxWidth < NarrowTopBarThreshold
            // maxWidth here is measured *before* the Column below applies its own 14dp-per-side
            // horizontal padding -- subtract that first, same as before.
            val rowContentWidth = maxWidth - 14.dp * 2
            // 4 columns per row: 2 flexible (COM1/COM2 or STBY1/STBY2) + 2 fixed-width
            // (MIC/XPDR or MON/MSG), 3 gaps of 8dp between them.
            val flexWidth = ((rowContentWidth - 8.dp * 3 - FixedColumnWidth * 2) / 2).coerceAtLeast(0.dp)
            val flexContentWidth = (flexWidth - 20.dp).coerceAtLeast(0.dp) // 10dp padding each side
            // XPDR's text shares its fixed column with the always-visible Mode C badge -- its
            // available text width is narrower than MIC/MON's own by that badge's width.
            val xpdrContentWidth = (FixedColumnWidth - 20.dp - ModeCBadgeSize.width).coerceAtLeast(0.dp)

            val com1Value = radioState.com1Frequency?.let(RadioFrequency::format) ?: "---.---"
            val com2Value = radioState.com2Frequency?.let(RadioFrequency::format) ?: "---.---"
            val stby1Value = radioState.com1StandbyFrequency?.let(RadioFrequency::format) ?: "---.---"
            val stby2Value = radioState.com2StandbyFrequency?.let(RadioFrequency::format) ?: "---.---"
            val xpdrValue = radioState.transponderCode?.toString()?.padStart(4, '0') ?: "----"

            // One shared font size across all 5 value texts (COM1/COM2 active, STBY1/STBY2, XPDR)
            // -- computed once here, not independently per button. Letting each button pick its
            // own size would mean e.g. COM1 shrinking while XPDR stays large, which reads as
            // visually uneven; this way they all step down together.
            val sharedFontSize = rememberSharedFontSize(
                values = listOf(
                    com1Value to FontWeight.Bold,
                    com2Value to FontWeight.Bold,
                    stby1Value to FontWeight.Medium,
                    stby2Value to FontWeight.Medium,
                    xpdrValue to FontWeight.Bold
                ),
                availableWidths = listOf(flexContentWidth, flexContentWidth, flexContentWidth, flexContentWidth, xpdrContentWidth)
            )

            // txCom is null only before the first SimConnect read completes (or the radio host
            // isn't connected) -- neither com1TransmitEnabled nor com2TransmitEnabled true yet.
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
            val micValue = txCom?.toString() ?: "-"
            val monValue = if (monListeningBoth) "1+2" else micValue

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // height(IntrinsicSize.Min) on the Row + fillMaxHeight() on each child is the
                // standard Compose idiom for "stretch every child to the tallest one's height" --
                // a Row doesn't do this by default (unlike CSS flexbox's align-items:stretch), so
                // without it, whichever button wraps its value onto a second line ends up visibly
                // taller than its neighbors instead of the whole row growing together.
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FrequencyButton(
                        Modifier.weight(1f).fillMaxHeight(),
                        label = "COM1",
                        value = com1Value,
                        large = true,
                        valueFontSize = sharedFontSize,
                        callsign = com1Callsign,
                        onClick = onSwapCom1
                    )
                    MicMonButton(
                        Modifier.width(FixedColumnWidth).fillMaxHeight(),
                        label = "MIC",
                        value = micValue,
                        isNarrow = isNarrow,
                        onClick = onToggleMic
                    )
                    FrequencyButton(
                        Modifier.weight(1f).fillMaxHeight(),
                        label = "COM2",
                        value = com2Value,
                        large = true,
                        valueFontSize = sharedFontSize,
                        callsign = com2Callsign,
                        onClick = onSwapCom2
                    )
                    XpdrButton(
                        Modifier.width(FixedColumnWidth).fillMaxHeight(),
                        xpdrValue = xpdrValue,
                        valueFontSize = sharedFontSize,
                        modeCEnabled = radioState.modeCEnabled,
                        onClick = onOpenXpdrDialog
                    )
                }
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FrequencyButton(
                        Modifier.weight(1f).fillMaxHeight(),
                        label = "STBY",
                        value = stby1Value,
                        large = false,
                        valueFontSize = sharedFontSize,
                        callsign = com1StandbyCallsign,
                        onClick = onOpenCom1Dialog
                    )
                    MicMonButton(
                        Modifier.width(FixedColumnWidth).fillMaxHeight(),
                        label = "MON",
                        value = monValue,
                        isNarrow = isNarrow,
                        onClick = onToggleMon
                    )
                    FrequencyButton(
                        Modifier.weight(1f).fillMaxHeight(),
                        label = "STBY",
                        value = stby2Value,
                        large = false,
                        valueFontSize = sharedFontSize,
                        callsign = com2StandbyCallsign,
                        onClick = onOpenCom2Dialog
                    )
                    MsgButton(
                        Modifier.width(FixedColumnWidth).fillMaxHeight(),
                        lastMessageLabel = lastMessageLabel,
                        unreadCount = unreadCount,
                        radioFrequency = radioState.com1Frequency,
                        isNarrow = isNarrow,
                        onClick = onToggleChat
                    )
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(color = colors.border)
    }
}

// internal, not private: FooterStatusBar reuses this same width to decide when to drop its own
// "Connected"/"Disconnected" label, so both bars agree on what counts as "narrow" instead of
// each carrying its own independently-tuned (and therefore possibly inconsistent) threshold.
internal val NarrowTopBarThreshold = 375.dp

// Shared fixed width for MIC/XPDR (top row) and MON/MSG (bottom row) -- their content is short
// and roughly constant, unlike COM1/COM2/STBY1/STBY2 which flex to fill the rest of the row.
private val FixedColumnWidth = 84.dp

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
        Text("Handoff", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.textMuted)
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

// Shared by FrequencyButton's callsign line and XpdrButton's matching blank line, so the two
// stay pixel-identical and the row's three "main line" frequency values (122.800 / ---- / 2000)
// line up regardless of which buttons have real third-line content.
private val CallsignLineFontSize = 11.sp

@Composable
private fun RowScope.FrequencyButton(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    large: Boolean,
    valueFontSize: androidx.compose.ui.unit.TextUnit,
    // Station tuned on this COM at this frequency (active or standby), Garmin-style third line
    // below the frequency -- passed for all 4 COM buttons (active + standby), never XPDR/MSG.
    // Rendered even when null (as an empty line) so every button's height -- and therefore the
    // whole row's frequency-value alignment -- doesn't depend on whether a station happens to be
    // tuned there (feedback: without this, STBY buttons came out visibly shorter than the active
    // row above them whenever nothing was on standby).
    callsign: String? = null,
    onClick: () -> Unit
) {
    val colors = LocalHandoffColors.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .background(colors.panelAlt, shape)
            .border(1.dp, colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.06f.em,
            color = colors.textMuted.copy(alpha = if (large) 0.7f else 0.6f),
            maxLines = 1,
            softWrap = false
        )
        // Centered horizontally within the button (issue #29) -- the label above stays top-left.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            FrequencyValueText(
                value = value,
                fontSize = valueFontSize,
                // STBY's font size now matches active's (issue #29) -- only the color alpha still
                // distinguishes them ("dimming"), same as fontWeight (Bold for active, Medium for
                // standby).
                fontWeight = if (large) FontWeight.Bold else FontWeight.Medium,
                color = colors.text.copy(alpha = if (large) 1f else 0.75f)
            )
            CallsignLine(callsign ?: "", colors.textMuted)
        }
    }
}

/** Shared by [FrequencyButton]'s real callsign line and [XpdrButton]'s blank matching one --
 *  disables legacy Android font-padding (the same fix already used elsewhere in this app, e.g.
 *  BadgePill/RatingBadge) since the default per-line leading was reading as a distractingly wide
 *  gap directly under the frequency value (feedback: "much tighter"). */
@Composable
private fun CallsignLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        fontSize = CallsignLineFontSize,
        fontWeight = FontWeight.Medium,
        fontFamily = RobotoMono,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = androidx.compose.ui.text.TextStyle(
            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
        )
    )
}

// Zero-width space -- inserted right after a frequency's decimal point so a normal, naturally
// wrapping Text only ever breaks there (issue #29: "two words... overflow the second word into a
// newline when needed, but without a visual space between them when not"). Compose never shows a
// visible gap for a zero-width character, and unlike plain soft-wrap (tried and reverted before --
// see git history) it can't orphan a single trailing digit onto its own line, since there's only
// ever this one break point to choose.
private const val Zwsp = "​"

/** Renders a "DDD.DDD"-shaped frequency value (or a plain code like an XPDR squawk, which has no
 *  decimal point and therefore no split point at all) at [fontSize], wrapping naturally at the
 *  hidden zero-width space after the decimal point if it doesn't fit on one line -- never more
 *  than 2 lines. [fontSize] itself is chosen by the caller (see [rememberSharedFontSize]); this
 *  composable only lays out, it doesn't measure. */
@Composable
private fun FrequencyValueText(
    value: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    color: androidx.compose.ui.graphics.Color
) {
    val dotIndex = value.indexOf('.')
    val display = if (dotIndex >= 0) value.substring(0, dotIndex + 1) + Zwsp + value.substring(dotIndex + 1) else value
    Text(
        display,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontFamily = RobotoMono,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center
    )
}

// Ordered largest-first; 13sp is the hard floor (issue #29: "I don't ever want the frequencies
// ... below a certain minimum font size or they become useless") -- below that, the value is left
// to wrap to its second line via FrequencyValueText's hidden zero-width space instead of shrinking
// further.
private val ComFontSizeCandidates = listOf(20.sp, 18.sp, 16.sp, 14.sp, 13.sp)

/** Picks the single largest font size (from [ComFontSizeCandidates]) at which every one of
 *  [values] fits on one line within its own corresponding entry in [availableWidths] -- shared
 *  across all 5 COM/STBY/XPDR value texts so they step down together rather than independently
 *  (issue #29 feedback: one shrinking while another stays large "would look very uneven"). Falls
 *  back to the smallest candidate (the floor) if even that doesn't fit everything; whichever
 *  value(s) still don't fit at the floor size wrap to a second line instead (see
 *  [FrequencyValueText]). */
@Composable
private fun rememberSharedFontSize(
    values: List<Pair<String, FontWeight>>,
    availableWidths: List<androidx.compose.ui.unit.Dp>
): androidx.compose.ui.unit.TextUnit {
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(values, availableWidths, density) {
        val availablePx = availableWidths.map { with(density) { it.roundToPx() } }
        ComFontSizeCandidates.firstOrNull { size ->
            values.indices.all { i ->
                val (value, weight) = values[i]
                val style = androidx.compose.ui.text.TextStyle(fontSize = size, fontWeight = weight, fontFamily = RobotoMono)
                textMeasurer.measure(value, style).size.width <= availablePx[i]
            }
        } ?: ComFontSizeCandidates.last()
    }
}

// Matches ModeCBadge's own fixed size (26dp x 24dp) -- used to work out XPDR's remaining text
// width alongside the badge in its fixed-width column.
private data class BadgeSize(val width: androidx.compose.ui.unit.Dp, val height: androidx.compose.ui.unit.Dp)
private val ModeCBadgeSize = BadgeSize(26.dp, 24.dp)

@Composable
private fun RowScope.XpdrButton(
    modifier: Modifier = Modifier,
    xpdrValue: String,
    valueFontSize: androidx.compose.ui.unit.TextUnit,
    modeCEnabled: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalHandoffColors.current
    val shape = RoundedCornerShape(10.dp)
    // Top-aligned throughout, same as MsgButton's unread badge -- both badges stay top-right
    // rather than centered, since centering against a height that varies for reasons unrelated to
    // the badge (a wrapped two-line value) would make the two badges disagree with each other.
    Row(
        modifier
            .background(colors.panelAlt, shape)
            .border(1.dp, colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "XPDR",
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.06f.em,
                color = colors.textMuted.copy(alpha = 0.7f),
                maxLines = 1,
                softWrap = false
            )
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                FrequencyValueText(
                    value = xpdrValue,
                    fontSize = valueFontSize,
                    fontWeight = FontWeight.Bold,
                    color = colors.text
                )
                // Blank line matching FrequencyButton's callsign line exactly -- XPDR has no
                // equivalent content, but needs the same reserved height so the row's "main line"
                // frequency values stay aligned across all three flex/fixed-width buttons.
                CallsignLine("", colors.text)
            }
        }
        // Now always shown -- XPDR is its own fixed-width column (issue #29) that no longer
        // competes with COM1/COM2 for space, so the badge no longer needs to react to their wrap
        // state the way it used to.
        ModeCBadge(modeCEnabled)
    }
}

/** Matches the reference's `modeCBadgeStyle` exactly: min-width 26dp, height 24dp, radius 6dp,
 *  solid fill (accent when on, otherwise a solid `t.border` fill -- not an outline), white text
 *  always, 14sp/700. */
@Composable
private fun ModeCBadge(modeCEnabled: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalHandoffColors.current
    Box(
        modifier
            .widthIn(min = ModeCBadgeSize.width)
            .size(width = ModeCBadgeSize.width, height = ModeCBadgeSize.height)
            .background(if (modeCEnabled) colors.accent else colors.border, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "C",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = androidx.compose.ui.graphics.Color.White
        )
    }
}

/** Shared by MIC and MON (issue #29's new buttons, inserted between COM1/COM2 and STBY1/STBY2) --
 *  a small top label and a large centered value, matching the reference mockup's `micMonBase`
 *  shape/chrome but using this app's own color/shape/padding conventions rather than its raw CSS.
 *  Font size uses the same coarse 2-step [isNarrow] threshold as MSG rather than the COM/STBY/
 *  XPDR shared-measurement logic -- MIC/MON's content ("1", "2", "1+2") is short and constant
 *  enough that it doesn't need per-width measurement. */
@Composable
private fun RowScope.MicMonButton(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    isNarrow: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalHandoffColors.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .background(colors.panelAlt, shape)
            .border(1.dp, colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            label,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.04f.em,
            color = colors.textMuted.copy(alpha = 0.7f),
            maxLines = 1,
            softWrap = false
        )
        Text(
            value,
            fontSize = if (isNarrow) 14.sp else 17.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = RobotoMono,
            color = colors.text,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun RowScope.MsgButton(
    modifier: Modifier = Modifier,
    lastMessageLabel: String?,
    unreadCount: Int,
    radioFrequency: Int?,
    isNarrow: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalHandoffColors.current
    val shape = RoundedCornerShape(10.dp)
    // "RADIO" is MainScreen's own sentinel for the radio tab (see its lastMessageLabel
    // computation) -- when that's what's showing, the tuned frequency is far more useful here
    // than the literal word "RADIO".
    val isRadioTab = lastMessageLabel == "RADIO"
    val displayValue = if (isRadioTab) {
        radioFrequency?.let(RadioFrequency::format) ?: "---.---"
    } else {
        lastMessageLabel ?: ""
    }
    // Top-aligned for the same reason as XpdrButton -- matches FrequencyButton's neighbors
    // instead of drifting to the row's vertical center once this button stretches taller than
    // its own content.
    Row(
        modifier
            .background(colors.panelAlt, shape)
            .border(1.dp, colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "MSG",
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.06f.em,
                color = colors.textMuted.copy(alpha = 0.7f),
                maxLines = 1,
                softWrap = false
            )
            if (isRadioTab) {
                // Same hidden-zero-width-space wrap as the COM/STBY buttons -- this fixed, narrow
                // column can't grow to fit a frequency on one line the way the old equal-width
                // layout could.
                FrequencyValueText(
                    value = displayValue,
                    fontSize = if (isNarrow) 13.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.text.copy(alpha = 0.75f)
                )
            } else {
                // An arbitrary callsign doesn't have a natural break point like a frequency's
                // decimal point, so it keeps a single-line ellipsis instead of wrapping mid-word.
                Text(
                    displayValue,
                    fontSize = if (isNarrow) 13.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.text.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Blank line matching the COM STBY buttons' callsign line -- keeps this row's
            // buttons the same height regardless of whether either STBY has a station tuned.
            CallsignLine("", colors.textMuted)
        }
        // Always visible (not just when unread > 0) -- greyed out and showing "0" when there's
        // nothing new, same always-there treatment as the Mode C badge, rather than the button
        // looking sparse/asymmetric next to its neighbors when empty.
        Box(
            Modifier
                .widthIn(min = ModeCBadgeSize.width)
                .size(width = ModeCBadgeSize.width, height = ModeCBadgeSize.height)
                .background(if (unreadCount > 0) colors.attention else colors.border, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                unreadCount.toString(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (unreadCount > 0) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f)
            )
        }
    }
}
