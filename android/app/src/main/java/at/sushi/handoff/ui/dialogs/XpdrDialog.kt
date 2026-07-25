package at.sushi.handoff.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import at.sushi.handoff.ui.theme.LocalHandoffColors

private val quickSetCodes = listOf(2000, 1000, 7000, 1200)

/** Transponder dialog -- issue #13 screen 3. Same construction/theming as the COM dialog. Every
 *  digit 0-7 is legal at every position (no prefix validation like COM frequencies need), and
 *  Mode C isn't editable here -- it's sim-driven, read-only (shown only on the main screen's XPDR
 *  button badge). */
@Composable
fun XpdrDialog(
    onDismiss: () -> Unit,
    onSetCode: (Int) -> Unit
) {
    var typed by remember { mutableStateOf("") }
    val colors = LocalHandoffColors.current

    KeypadDialogPanel(title = "TRANSPONDER", onDismiss = onDismiss) {
        Text(
            "ENTRY",
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.14f.em,
            color = colors.textMuted,
            modifier = Modifier.padding(top = 16.dp)
        )
        Row {
            val padded = typed.padEnd(4, '-')
            padded.forEachIndexed { index, char ->
                Text(
                    if (char == '-') "-" else char.toString(),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.1f.em,
                    color = if (index < typed.length) colors.text else colors.textMuted
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
        ) {
            items((1..7).toList()) { digit ->
                KeypadKey(digit.toString(), enabled = typed.length < 4) { typed += digit }
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
                KeypadKey("0", enabled = typed.length < 4) { typed += "0" }
            }
            item {
                KeypadKey("⌫", enabled = typed.isNotEmpty()) { typed = typed.dropLast(1) }
            }
            item {
                val enabled = typed.length == 4
                Box(
                    Modifier
                        .background(
                            if (enabled) androidx.compose.ui.graphics.Color(0xFF3E8E5C) else colors.panelAlt.copy(alpha = 0.45f),
                            RoundedCornerShape(14.dp)
                        )
                        .then(
                            if (enabled) {
                                Modifier.clickable {
                                    onSetCode(typed.toInt())
                                    onDismiss()
                                }
                            } else Modifier
                        )
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickSetCodes.forEach { code ->
                Box(
                    Modifier
                        .weight(1f)
                        .background(colors.panelAlt, RoundedCornerShape(12.dp))
                        .clickable {
                            onSetCode(code)
                            onDismiss()
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        code.toString().padStart(4, '0'),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.text
                    )
                }
            }
        }
    }
}
