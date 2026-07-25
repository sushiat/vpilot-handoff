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
import at.sushi.handoff.ui.theme.perceptualLightness
import kotlinx.coroutines.delay

/** SELCAL alerts aren't a separate banner -- they're merged into the RADIO tab's message list as
 *  centered "system" bubbles, sorted by timestamp with real messages. While undismissed they
 *  flash hard-cut (not eased, like a real audio alert light) between the calling station's own
 *  facility color and hazard yellow every ~0.7s; once dismissed they stay on plain static yellow.
 *  See issue #13 screen 5. */
@Composable
fun SelcalEntry(alert: SelcalAlert, ownColor: Color, isActive: Boolean) {
    var flashOn by remember { mutableStateOf(true) }

    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        while (true) {
            delay(700)
            flashOn = !flashOn
        }
    }

    val background = when {
        !isActive -> FacilityColors.hazardYellow
        flashOn -> ownColor
        else -> FacilityColors.hazardYellow
    }
    val textColor = if (perceptualLightness(background) >= 62f) Color.Black else Color.White

    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .background(background, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                "📻 SELCAL — ${alert.from} on ${alert.frequencies.joinToString { RadioFrequency.format(it) }} · ${alert.timestamp}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
}
