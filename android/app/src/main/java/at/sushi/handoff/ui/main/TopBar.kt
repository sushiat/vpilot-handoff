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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import at.sushi.handoff.R
import at.sushi.handoff.protocol.RadioFrequency
import at.sushi.handoff.protocol.RadioStateMessage
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** The main screen's top bar: the app's own header ("Handover" wordmark + "by sushi.at" +
 *  version, per issue #13's Assets section) above a 3x2 grid (COM1/COM2/XPDR active row,
 *  COM1/COM2/MSG standby row) per screen 1. Tapping a COM active button swaps it with standby
 *  immediately (no dialog); tapping a standby button opens that COM's tuning dialog. */
@Composable
fun TopBar(
    radioState: RadioStateMessage,
    lastMessageLabel: String?,
    unreadCount: Int,
    onSwapCom1: () -> Unit,
    onSwapCom2: () -> Unit,
    onOpenCom1Dialog: () -> Unit,
    onOpenCom2Dialog: () -> Unit,
    onOpenXpdrDialog: () -> Unit,
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
            // Below this width the layout starts breaking down (Mode C badge crowding the XPDR
            // label into wrapping, COM1/2's 20sp active readout no longer fitting) -- narrow mode
            // drops the badge entirely and shrinks the active-frequency font instead of letting
            // either clip or wrap.
            val isNarrow = maxWidth < NarrowTopBarThreshold
            // Computed here (from this BoxWithConstraints, which sits above any
            // height(IntrinsicSize.Min) row) rather than with a second, nested BoxWithConstraints
            // inside each button -- BoxWithConstraints is built on SubcomposeLayout, and
            // Compose cannot compute intrinsic measurements through a SubcomposeLayout node.
            // Nesting one inside a Row that uses height(IntrinsicSize.Min) (see below) crashed
            // outright with "Asking for intrinsic measurements of SubcomposeLayout layouts is not
            // supported." maxWidth here is measured *before* the Column below applies its own
            // 14dp-per-side horizontal padding -- forgetting to subtract that first overestimated
            // every button's available width, which meant the split-vs-clip check thought there
            // was more room than actually existed and let text clip instead of wrapping. Then:
            // three equal-weight buttons with 2 8dp gaps between them, each with 10dp horizontal
            // padding on both sides.
            val rowContentWidth = maxWidth - 14.dp * 2
            val buttonContentWidth = (rowContentWidth - 8.dp * 2) / 3 - 20.dp
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // height(IntrinsicSize.Min) on the Row + fillMaxHeight() on each child is the
                // standard Compose idiom for "stretch every child to the tallest one's height" --
                // a Row doesn't do this by default (unlike CSS flexbox's align-items:stretch), so
                // without it, whichever button wraps its value onto a second line (XPDR, at the
                // narrowest widths) ends up visibly taller than its neighbors instead of the whole
                // row growing together.
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FrequencyButton(
                        Modifier.weight(1f).fillMaxHeight(),
                        label = "COM1",
                        value = radioState.com1Frequency?.let(RadioFrequency::format) ?: "---.---",
                        large = true,
                        isNarrow = isNarrow,
                        availableWidth = buttonContentWidth,
                        onClick = onSwapCom1
                    )
                    FrequencyButton(
                        Modifier.weight(1f).fillMaxHeight(),
                        label = "COM2",
                        value = radioState.com2Frequency?.let(RadioFrequency::format) ?: "---.---",
                        large = true,
                        isNarrow = isNarrow,
                        availableWidth = buttonContentWidth,
                        onClick = onSwapCom2
                    )
                    XpdrButton(Modifier.weight(1f).fillMaxHeight(), radioState, isNarrow = isNarrow, availableWidth = buttonContentWidth, onClick = onOpenXpdrDialog)
                }
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FrequencyButton(
                        Modifier.weight(1f).fillMaxHeight(),
                        label = "COM1",
                        value = radioState.com1StandbyFrequency?.let(RadioFrequency::format) ?: "---.---",
                        large = false,
                        isNarrow = isNarrow,
                        availableWidth = buttonContentWidth,
                        onClick = onOpenCom1Dialog
                    )
                    FrequencyButton(
                        Modifier.weight(1f).fillMaxHeight(),
                        label = "COM2",
                        value = radioState.com2StandbyFrequency?.let(RadioFrequency::format) ?: "---.---",
                        large = false,
                        isNarrow = isNarrow,
                        availableWidth = buttonContentWidth,
                        onClick = onOpenCom2Dialog
                    )
                    MsgButton(
                        Modifier.weight(1f).fillMaxHeight(),
                        lastMessageLabel = lastMessageLabel,
                        unreadCount = unreadCount,
                        radioFrequency = radioState.com1Frequency,
                        availableWidth = buttonContentWidth,
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
            painterResource(R.drawable.ic_handover_mark),
            contentDescription = null,
            tint = colors.textMuted,
            modifier = Modifier.size(18.dp)
        )
        Text("Handover", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.textMuted)
        Text("by sushi.at", fontSize = 10.sp, color = colors.textMuted.copy(alpha = 0.85f))
        Box(Modifier.weight(1f))
        Text(
            "v$versionName",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = RobotoMono,
            color = colors.textMuted.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun RowScope.FrequencyButton(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    large: Boolean,
    isNarrow: Boolean,
    availableWidth: androidx.compose.ui.unit.Dp,
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
        FrequencyValueText(
            value = value,
            fontSize = if (large) (if (isNarrow) 16.sp else 20.sp) else 15.sp,
            fontWeight = if (large) FontWeight.Bold else FontWeight.Medium,
            color = colors.text.copy(alpha = if (large) 1f else 0.75f),
            availableWidth = availableWidth
        )
    }
}

/** Renders a "DDD.DDD"-shaped frequency value, splitting it into two explicit lines at the
 *  decimal point *only if it's actually measured not to fit* on one line at this font size and
 *  this button's real available width -- not a blanket narrow-mode flag. An earlier version
 *  split whenever the whole top bar crossed its narrow-mode width threshold, which also governs
 *  unrelated things (font size, the Mode C badge) and doesn't track each button's own available
 *  space -- it force-split frequencies onto two lines even in the part of that range where the
 *  (already-shrunk) font still fit on one line just fine. Plain soft-wrap was tried before that
 *  and also reverted: Compose breaks wherever the current width forces it to, which landed on the
 *  decimal point at one specific width but, at others, just as often orphaned a single trailing
 *  digit onto its own line ("122.80" / "0"). Measuring directly avoids both failure modes.
 *
 *  [availableWidth] is passed in (computed once, from TopBar's own top-level BoxWithConstraints)
 *  rather than measured here with a nested BoxWithConstraints -- BoxWithConstraints is built on
 *  SubcomposeLayout, and Compose cannot compute intrinsic measurements through one. This
 *  composable sits inside a Row that uses height(IntrinsicSize.Min) (see TopBar) to make all
 *  three buttons in a row match whichever one is tallest; nesting a BoxWithConstraints anywhere
 *  in that Row's subtree crashes outright with "Asking for intrinsic measurements of
 *  SubcomposeLayout layouts is not supported." */
@Composable
private fun FrequencyValueText(
    value: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    color: androidx.compose.ui.graphics.Color,
    availableWidth: androidx.compose.ui.unit.Dp
) {
    val dotIndex = value.indexOf('.')
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val textStyle = androidx.compose.ui.text.TextStyle(fontSize = fontSize, fontWeight = fontWeight, fontFamily = RobotoMono)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val naturalWidth = remember(value, fontSize, fontWeight) {
        textMeasurer.measure(value, textStyle).size.width
    }
    val availableWidthPx = remember(availableWidth, density) { with(density) { availableWidth.roundToPx() } }
    val needsSplit = dotIndex >= 0 && naturalWidth > availableWidthPx
    if (needsSplit) {
        Column {
            Text(value.substring(0, dotIndex + 1), fontSize = fontSize, fontWeight = fontWeight, fontFamily = RobotoMono, color = color, maxLines = 1, softWrap = false)
            Text(value.substring(dotIndex + 1), fontSize = fontSize, fontWeight = fontWeight, fontFamily = RobotoMono, color = color, maxLines = 1, softWrap = false)
        }
    } else {
        Text(value, fontSize = fontSize, fontWeight = fontWeight, fontFamily = RobotoMono, color = color, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun RowScope.XpdrButton(
    modifier: Modifier = Modifier,
    radioState: RadioStateMessage,
    isNarrow: Boolean,
    availableWidth: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val colors = LocalHandoffColors.current
    val shape = RoundedCornerShape(10.dp)
    // Reference structure is a single outer row (originally vertically centered across the whole
    // button height, to fix an even older bug where the badge was only centered against the
    // value line) -- now top-aligned instead, to match FrequencyButton's plain (implicitly
    // top-aligned) Column. Once buttons in a row can stretch to match whichever sibling wrapped
    // onto a second line (see the height(IntrinsicSize.Min)/fillMaxHeight() pairing in TopBar),
    // center-aligning here alone made this button's label/value drift down to the row's vertical
    // center while its un-stretched neighbors stayed pinned to the top.
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
            FrequencyValueText(
                value = radioState.transponderCode?.toString()?.padStart(4, '0') ?: "----",
                fontSize = if (isNarrow) 16.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.text,
                availableWidth = availableWidth
            )
        }
        // Dropped entirely below the narrow threshold rather than shrunk further -- it was
        // crowding the "XPDR" label into wrapping onto its own line even before the label text
        // itself ran out of room.
        if (!isNarrow) {
            ModeCBadge(radioState.modeCEnabled)
        }
    }
}

/** Matches the reference's `modeCBadgeStyle` exactly: min-width 26dp, height 24dp, radius 6dp,
 *  solid fill (accent when on, otherwise a solid `t.border` fill -- not an outline), white text
 *  always, 14sp/700. */
@Composable
private fun ModeCBadge(modeCEnabled: Boolean) {
    val colors = LocalHandoffColors.current
    Box(
        Modifier
            .widthIn(min = 26.dp)
            .size(width = 26.dp, height = 24.dp)
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

@Composable
private fun RowScope.MsgButton(
    modifier: Modifier = Modifier,
    lastMessageLabel: String?,
    unreadCount: Int,
    radioFrequency: Int?,
    availableWidth: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val colors = LocalHandoffColors.current
    val shape = RoundedCornerShape(10.dp)
    // "RADIO" is MainScreen's own sentinel for the radio tab (see its lastMessageLabel
    // computation) -- when that's what's showing, the tuned frequency is far more useful here
    // than the literal word "RADIO", and happens to be exactly what fills this button out to
    // match the COM standby buttons' size/weight instead of looking sparse next to them.
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
            .padding(horizontal = 10.dp, vertical = 8.dp),
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
                // Matches the COM standby buttons' value style exactly (15sp/Medium) instead of a
                // smaller 12sp -- otherwise this button needs to read as the same weight/size as
                // its neighbors, not visually sparse next to them. Same deterministic
                // decimal-point split as FrequencyButton -- see its doc comment.
                FrequencyValueText(
                    value = displayValue,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.text.copy(alpha = 0.75f),
                    availableWidth = availableWidth
                )
            } else {
                // An arbitrary callsign doesn't have a natural break point like a frequency's
                // decimal point, so it keeps a single-line ellipsis instead of wrapping mid-word.
                Text(
                    displayValue,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.text.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (unreadCount > 0) {
            Box(
                Modifier
                    .widthIn(min = 26.dp)
                    .size(width = 26.dp, height = 24.dp)
                    .background(colors.attention, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    unreadCount.toString(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White
                )
            }
        }
    }
}
