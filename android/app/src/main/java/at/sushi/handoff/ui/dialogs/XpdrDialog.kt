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
import at.sushi.handoff.ui.theme.RobotoMono
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import at.sushi.handoff.ui.theme.LocalHandoffColors

private val quickSetCodes = listOf(2000, 1000, 7000, 1200)

/** Transponder dialog -- issue #13 screen 3, matching the design reference's JS exactly. Same
 *  construction/theming as the COM dialog. Unlike COM entry, every digit 0-7 is legal at every
 *  position (no prefix validation), so the reference never colors the entry red or mutes
 *  untyped positions -- the whole readout is one uniform color, underscore-padded. Mode C isn't
 *  editable here -- it's sim-driven, read-only (shown only on the main screen's XPDR badge). */
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
        Text(
            typed.padEnd(4, '_'),
            fontSize = 44.sp,
            fontWeight = FontWeight.Light,
            fontFamily = RobotoMono,
            letterSpacing = 0.1f.em,
            color = colors.text
        )

        // Reference order (digitKeys(4, [1,2,3,4,5,6,7,0]) + CLR + backspace, then a hidden
        // spacer + the confirm key): 1 2 3 / 4 5 6 / 7 0 CLR / backspace (spacer) confirm.
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items((1..7).toList()) { digit ->
                KeypadKey(digit.toString(), enabled = typed.length < 4) { typed += digit }
            }
            item {
                KeypadKey("0", enabled = typed.length < 4) { typed += "0" }
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
                KeypadKey("⌫", enabled = typed.isNotEmpty()) { typed = typed.dropLast(1) }
            }
            item {
                // Hidden spacer, matching the reference's invisible placeholder in this slot.
                Box(Modifier)
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
                    Text(
                        "✓",
                        color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
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
                        fontFamily = RobotoMono,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.text
                    )
                }
            }
        }
    }
}
