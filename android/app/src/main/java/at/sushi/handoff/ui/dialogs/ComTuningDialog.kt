package at.sushi.handoff.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import at.sushi.handoff.ui.theme.RobotoMono
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import at.sushi.handoff.ChannelSpacing
import at.sushi.handoff.KeypadBlockMode
import at.sushi.handoff.R
import at.sushi.handoff.ui.theme.LocalHandoffColors
import at.sushi.handoff.util.ChannelGrid
import kotlin.math.abs

/** COM1/COM2 tuning dialog -- see issue #13 screen 2. Just the ENTRY field (no current-value
 *  rows -- the main screen's own COM buttons stay visible above this dialog, so repeating them
 *  was redundant). Digits live-disable per [ChannelGrid.isValidPrefix] unless the user has
 *  "Allow all" selected in Settings, in which case grid/band validation only gates Set/flip.
 *
 *  The completed-value/red-flagging logic here mirrors the design reference's own
 *  `completeBuffer()` precisely (see issue #13's attached JS source): a live preview only
 *  appears once the whole-MHz digits are typed *and* at least one decimal digit has been entered
 *  (`typed.length >= 4`) -- before that, empty positions show a plain "_", not a snapped guess. */
@Composable
fun ComTuningDialog(
    comNumber: Int,
    defaultSpacing: ChannelSpacing,
    keypadBlockMode: KeypadBlockMode,
    onDismiss: () -> Unit,
    onSetActive: (Double) -> Unit,
    onSetStandby: (Double) -> Unit
) {
    var typed by remember { mutableStateOf("") }
    var spacing by remember { mutableStateOf(defaultSpacing) }

    // The live "what would actually be set" preview -- decimal snapped to the nearest
    // spacing-valid grid point, deliberately *not* clamped into the civil band, so a genuinely
    // out-of-range value shows red rather than being silently coerced.
    val completed: String? = if (typed.length >= 4) {
        val intPart = typed.take(3)
        val typedDec = typed.substring(3)
        val baseline = (typedDec + "000").take(3).toInt()
        val decNum = ChannelGrid.validDecimalValues(spacing).minByOrNull { abs(it - baseline) } ?: baseline
        intPart + decNum.toString().padStart(3, '0')
    } else null

    val completedMhz = completed?.toInt()?.div(1000.0)
    val showAsRed = completed != null && (completedMhz == null || completedMhz < 118.0 || completedMhz > 136.990)
    val canCommit = completed != null && !showAsRed
    val iconTint = if (canCommit) Color.White else Color.White.copy(alpha = 0.35f)

    KeypadDialogPanel(title = "COM$comNumber TUNE", onDismiss = onDismiss) {
        Text(
            "ENTRY",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.14f.em,
            color = LocalHandoffColors.current.textMuted,
            modifier = Modifier.padding(top = 16.dp)
        )
        EntryReadout(typed, completed, showAsRed)
        if (showAsRed) {
            Text(
                "outside 118.000–136.990 civil airband",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = outOfBandRed,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items((1..9).toList()) { digit ->
                DigitKey(digit.toString(), typed, spacing, keypadBlockMode) { typed = typed + digit }
            }
            item {
                KeypadKey(
                    "CLR",
                    background = clrKeyBackground,
                    contentColor = clrKeyText,
                    fontSize = 12.sp
                ) { typed = "" }
            }
            item {
                DigitKey("0", typed, spacing, keypadBlockMode) { typed = typed + "0" }
            }
            item {
                KeypadKey("⌫", enabled = typed.isNotEmpty()) { typed = typed.dropLast(1) }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SpacingToggle(spacing, Modifier.weight(1f)) { spacing = it }
            // Only reachable when canCommit is true (Set is disabled otherwise), so this
            // (defensively) coerced value is never actually used out-of-band.
            val commitValue = completed?.toInt()?.let { ChannelGrid.nearestValid(it, spacing) }
            SetButton(
                Modifier.weight(1f),
                enabled = canCommit,
                background = androidx.compose.ui.graphics.Color(0xFF3E8E5C),
                onClick = { commitValue?.let { onSetActive(ChannelGrid.toMegahertz(it)) }; onDismiss() }
            ) {
                Icon(
                    painterResource(R.drawable.ic_handover_mark),
                    contentDescription = "Set active",
                    tint = iconTint
                )
            }
            SetButton(
                Modifier.weight(1f),
                enabled = canCommit,
                background = androidx.compose.ui.graphics.Color(0xFF3E8E5C),
                onClick = { commitValue?.let { onSetStandby(ChannelGrid.toMegahertz(it)) }; onDismiss() }
            ) {
                Text("✓", color = iconTint, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EntryReadout(typed: String, completed: String?, showAsRed: Boolean) {
    val colors = LocalHandoffColors.current
    // Once `completed` exists, every position shows its (possibly snapped) digit; until then,
    // untyped positions show a plain underscore -- matches the reference's own buf/completed
    // branching exactly.
    val shown = completed ?: typed.padEnd(6, '_')
    Row(verticalAlignment = Alignment.Bottom) {
        shown.forEachIndexed { index, char ->
            if (index == 3) {
                Text(".", fontSize = 44.sp, fontWeight = FontWeight.Light, fontFamily = RobotoMono, color = colors.text)
            }
            val color = when {
                showAsRed -> outOfBandRed
                index < typed.length -> colors.text
                else -> colors.textMuted
            }
            Text(char.toString(), fontSize = 44.sp, fontWeight = FontWeight.Light, fontFamily = RobotoMono, color = color)
        }
    }
}

@Composable
private fun DigitKey(
    digit: String,
    typed: String,
    spacing: ChannelSpacing,
    keypadBlockMode: KeypadBlockMode,
    onClick: () -> Unit
) {
    val enabled = typed.length < 6 && (
        keypadBlockMode == KeypadBlockMode.ALLOW_ALL || ChannelGrid.isValidPrefix(typed + digit, spacing)
    )
    KeypadKey(digit, enabled = enabled, onClick = onClick)
}

@Composable
private fun SpacingToggle(spacing: ChannelSpacing, modifier: Modifier = Modifier, onChange: (ChannelSpacing) -> Unit) {
    val colors = LocalHandoffColors.current
    val other = if (spacing == ChannelSpacing.KHZ_25) ChannelSpacing.KHZ_8_33 else ChannelSpacing.KHZ_25
    Column(
        modifier
            .aspectRatio(1.6f) // matches KeypadKey/SetButton height so the bottom row lines up
            .background(colors.panelAlt, RoundedCornerShape(14.dp))
            .clickable { onChange(other) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(spacingLabel(spacing), fontSize = 15.sp, fontFamily = RobotoMono, color = colors.text)
        Text(
            spacingLabel(other),
            fontSize = 9.sp,
            color = colors.textMuted.copy(alpha = 0.45f)
        )
    }
}

private fun spacingLabel(spacing: ChannelSpacing) = if (spacing == ChannelSpacing.KHZ_25) "25" else "8.33"

@Composable
private fun SetButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    background: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val colors = LocalHandoffColors.current
    Box(
        modifier
            .aspectRatio(1.6f)
            .background(if (enabled) background else colors.panelAlt.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
