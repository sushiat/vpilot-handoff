package at.sushi.handoff.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.protocol.RadioFrequency
import at.sushi.handoff.protocol.SelcalAlert
import at.sushi.handoff.ui.theme.FacilityColors
import at.sushi.handoff.ui.theme.LocalHandoffColors
import kotlinx.coroutines.delay

private val selcalDismissedText = Color(0xFF111111)

/** SELCAL alerts aren't a separate banner -- they're merged into the RADIO tab's message list as
 *  centered "system" bubbles, sorted by timestamp with real messages. Transcribed exactly from
 *  issue #13's JS reference (not the prose spec, which describes this a little differently):
 *  while undismissed, flashes hard-cut (not eased) between the theme's `attentionBg`/`attention`
 *  pair and hazard-yellow/`#111`, on a 1.4s cycle (`animation:contactFlash 1.4s steps(1)
 *  infinite`, i.e. 700ms per phase) -- *not* the calling station's own facility color. Once
 *  dismissed it's a static hazard-yellow background with `#111` text. */
@Composable
fun SelcalEntry(alert: SelcalAlert, isActive: Boolean) {
    val colors = LocalHandoffColors.current
    var phaseA by remember { mutableStateOf(true) }

    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        while (true) {
            delay(700)
            phaseA = !phaseA
        }
    }

    val background: Color
    val textColor: Color
    if (!isActive) {
        background = FacilityColors.hazardYellow
        textColor = selcalDismissedText
    } else if (phaseA) {
        background = colors.attentionBg
        textColor = colors.attention
    } else {
        background = FacilityColors.hazardYellow
        textColor = selcalDismissedText
    }

    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .background(background, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(
                "📻 SELCAL — ${alert.from} on ${alert.frequencies.joinToString { RadioFrequency.format(it) }} · ${alert.timestamp}",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
}
