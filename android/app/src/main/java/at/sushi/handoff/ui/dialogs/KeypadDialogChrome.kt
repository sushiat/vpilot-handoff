package at.sushi.handoff.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import at.sushi.handoff.ui.theme.LocalHandoffColors
import at.sushi.handoff.ui.theme.oklch

/** Shared "destructive"/out-of-range reds across the keypad dialogs, matching the design
 *  reference's JS source exactly (`oklch(65% 0.18 25)` for out-of-band entry text; CLR uses a
 *  distinct pair, `oklch(58% 0.19 25 / .14)` background + `oklch(52% 0.2 25)` text) -- genuine
 *  reds, distinct from the theme's orange-ish `attention` token. */
val outOfBandRed = oklch(0.65f, 0.18f, 25f)
val clrKeyBackground = oklch(0.58f, 0.19f, 25f, alpha = 0.14f)
val clrKeyText = oklch(0.52f, 0.20f, 25f)

/** Shared chrome for the COM/XPDR/Settings dialogs: 336dp panel, app light/dark theme surfaces,
 *  24px radius, 20dp padding, 1px border -- per issue #13's COM tuning dialog spec, reused as-is
 *  for XPDR/Settings ("identical construction and theming"). An earlier design revision styled
 *  the COM dialog like a cockpit radio bezel; that was dropped in favor of this shared look. */
@Composable
fun KeypadDialogPanel(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalHandoffColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(336.dp)
                .background(colors.panel, RoundedCornerShape(24.dp))
                .border(1.dp, colors.border, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.14f.em,
                    color = colors.textMuted
                )
                Box(
                    Modifier
                        .size(26.dp)
                        .background(colors.panelAlt, RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = colors.textMuted, modifier = Modifier.size(16.dp))
                }
            }
            content()
        }
    }
}

/** One numeric-keypad key. Disabled keys are 25% opacity and non-clickable, per the doc's
 *  live validation rule ("digits disable themselves in real time"). */
@Composable
fun KeypadKey(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    background: Color? = null,
    contentColor: Color? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 21.sp,
    onClick: () -> Unit
) {
    val colors = LocalHandoffColors.current
    // Multiplying (rather than overwriting) the base color's own alpha when disabled preserves
    // an already-translucent custom `background` (e.g. CLR's tinted red) instead of forcing it
    // fully opaque -- overwriting to a flat 1f/0.25f here was a real bug: it silently discarded
    // CLR's intended translucency and made its text unreadable against its own background.
    val baseBackground = background ?: colors.panelAlt
    val resolvedBackground = if (enabled) baseBackground else baseBackground.copy(alpha = baseBackground.alpha * 0.25f)
    Box(
        modifier
            .aspectRatio(1.6f)
            .background(resolvedBackground, RoundedCornerShape(14.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = (contentColor ?: colors.text).copy(alpha = if (enabled) 1f else 0.3f)
        )
    }
}
