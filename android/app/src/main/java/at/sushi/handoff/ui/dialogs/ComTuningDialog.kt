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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import at.sushi.handoff.ChannelSpacing
import at.sushi.handoff.KeypadBlockMode
import at.sushi.handoff.R
import at.sushi.handoff.ui.theme.LocalHandoffColors
import at.sushi.handoff.util.ChannelGrid

private val outOfBandRed = androidx.compose.ui.graphics.Color(0xFFCC4A33) // oklch(65% 0.18 25) approx

/** COM1/COM2 tuning dialog -- see issue #13 screen 2. Just the ENTRY field (no current-value
 *  rows -- the main screen's own COM buttons stay visible above this dialog, so repeating them
 *  was redundant). Digits live-disable per [ChannelGrid.isValidPrefix] unless the user has
 *  "Allow all" selected in Settings, in which case grid/band validation only gates Set/flip. */
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
    val colors = LocalHandoffColors.current

    val padded = paddedValue(typed)
    val inBand = ChannelGrid.isInBand(padded)

    KeypadDialogPanel(title = "COM$comNumber TUNE", onDismiss = onDismiss) {
        Text(
            "ENTRY",
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.14f.em,
            color = colors.textMuted,
            modifier = Modifier.padding(top = 16.dp)
        )
        EntryReadout(typed, inBand)
        if (!inBand) {
            Text(
                "outside 118.000–136.990 civil airband",
                fontSize = 10.sp,
                color = outOfBandRed,
                modifier = Modifier.padding(top = 4.dp)
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
                    background = colors.attentionBg,
                    contentColor = colors.attention,
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
            val commitValue = ChannelGrid.nearestValid(padded, spacing)
            SetButton(
                Modifier.weight(1f),
                enabled = inBand,
                background = androidx.compose.ui.graphics.Color(0xFF3E8E5C),
                onClick = { onSetActive(ChannelGrid.toMegahertz(commitValue)); onDismiss() }
            ) {
                Icon(
                    painterResource(R.drawable.ic_handover_mark),
                    contentDescription = "Set active",
                    tint = Color.White
                )
            }
            SetButton(
                Modifier.weight(1f),
                enabled = inBand,
                background = androidx.compose.ui.graphics.Color(0xFF3E8E5C),
                onClick = { onSetStandby(ChannelGrid.toMegahertz(commitValue)); onDismiss() }
            ) {
                Text("✓", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** [typed] zero-padded to 3 whole-MHz + 3 decimal digits, parsed as thousandths of a MHz --
 *  the value that's actually validated/snapped/sent, per the doc's "zero-padded on unset
 *  trailing digits" commit rule. */
private fun paddedValue(typed: String): Int {
    val whole = typed.take(3).padEnd(3, '0')
    val decimal = if (typed.length > 3) typed.substring(3).padEnd(3, '0') else "000"
    return whole.toInt() * 1000 + decimal.toInt()
}

@Composable
private fun EntryReadout(typed: String, inBand: Boolean) {
    val colors = LocalHandoffColors.current
    val padded = paddedValue(typed).toString().padStart(6, '0')
    Row(verticalAlignment = Alignment.Bottom) {
        padded.forEachIndexed { index, char ->
            if (index == 3) {
                Text(".", fontSize = 44.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace, color = colors.text)
            }
            val isTyped = index < typed.length
            val color = when {
                !inBand -> outOfBandRed
                isTyped -> colors.text
                else -> colors.textMuted
            }
            Text(
                char.toString(),
                fontSize = 44.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace,
                color = color
            )
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
            .background(colors.panelAlt, RoundedCornerShape(14.dp))
            .clickable { onChange(other) }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(spacingLabel(spacing), fontSize = 15.sp, fontFamily = FontFamily.Monospace, color = colors.text)
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
