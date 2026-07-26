package at.sushi.handoff.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import at.sushi.handoff.protocol.NearbyAircraft
import at.sushi.handoff.ui.theme.HandoffTextField
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** "Private chat to callsign" dialog -- issue #13 screen 6, matching the reference's
 *  `nearbyDialog` object (340dp panel, a rounded-*square* confirm button -- not a circle). The
 *  nearby-aircraft list is fed by the plugin's `nearbyAircraft` message (see docs/protocol.md);
 *  it's empty (not a "not available yet" stub) whenever no traffic is within 20nm or ownship's
 *  position isn't known yet.
 *
 *  [NearbyAircraftDialog] (a real system Dialog) is only safe to use when definitely hosted in
 *  the main Activity's own window (fullscreen mode). It's reachable from the chat panel's
 *  airplane icon, which is *also* rendered inside the split-screen chat overlay's own
 *  `TYPE_APPLICATION_OVERLAY` window -- a Dialog opened from there would be confined to this
 *  app's own narrow window slice in multi-window mode and sit behind the overlay in z-order,
 *  effectively unreachable. [NearbyAircraftContent] + [InlineModalScrim] is the version used from
 *  within the chat overlay, rendered as plain content in whichever window already hosts it. */
@Composable
fun NearbyAircraftDialog(
    aircraft: List<NearbyAircraft>,
    onDismiss: () -> Unit,
    onOpenChatWith: (String) -> Unit
) {
    SimpleDialogPanel(title = "Private chat to callsign", width = 340.dp, onDismiss = onDismiss) {
        NearbyAircraftContent(aircraft = aircraft, onOpenChatWith = { onOpenChatWith(it); onDismiss() })
    }
}

@Composable
fun InlineNearbyAircraftDialog(
    aircraft: List<NearbyAircraft>,
    onDismiss: () -> Unit,
    onOpenChatWith: (String) -> Unit
) {
    InlineModalScrim(onDismiss = onDismiss) {
        SimpleDialogPanelChrome(title = "Private chat to callsign", width = 340.dp, onDismiss = onDismiss) {
            NearbyAircraftContent(aircraft = aircraft, onOpenChatWith = { onOpenChatWith(it); onDismiss() })
        }
    }
}

@Composable
private fun ColumnScope.NearbyAircraftContent(aircraft: List<NearbyAircraft>, onOpenChatWith: (String) -> Unit) {
    var callsign by remember { mutableStateOf("") }
    val colors = LocalHandoffColors.current

    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HandoffTextField(
            value = callsign,
            onValueChange = { callsign = it.uppercase() },
            placeholder = "Callsign",
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        val enabled = callsign.isNotBlank()
        Box(
            Modifier
                .size(40.dp)
                .background(
                    if (enabled) Color(0xFF3E8E5C) else colors.panelAlt,
                    RoundedCornerShape(8.dp)
                )
                .then(
                    if (enabled) Modifier.clickable { onOpenChatWith(callsign) } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Check, contentDescription = "Open chat", tint = Color.White)
        }
    }

    Text(
        "AIRCRAFT WITHIN 20NM · CLOSEST FIRST",
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.06f.em,
        color = colors.textMuted,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
    )
    // Fixed-weight columns (not SpaceBetween, which just spaces each Text by its own natural
    // width) shared with the data rows below -- SpaceBetween alone can't align a header to its
    // column's data since "CALLSIGN" and e.g. "EDW89" are different widths, and it can't even
    // keep rows aligned with each other since every callsign/type/distance string differs in
    // width too. Horizontal padding also now matches the data rows' 12dp exactly.
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text("CALLSIGN", fontSize = 11.sp, color = colors.textMuted, modifier = Modifier.weight(1.4f))
        Text("TYPE", fontSize = 11.sp, color = colors.textMuted, modifier = Modifier.weight(1f))
        Text("DIST", fontSize = 11.sp, color = colors.textMuted, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
    if (aircraft.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.panelAlt, RoundedCornerShape(10.dp))
                .padding(vertical = 20.dp)
                .padding(top = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No aircraft within 20nm",
                fontSize = 11.sp,
                color = colors.textMuted
            )
        }
    } else {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .background(colors.panelAlt, RoundedCornerShape(10.dp))
                .padding(vertical = 4.dp)
        ) {
            items(aircraft, key = { it.callsign }) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenChatWith(entry.callsign) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(entry.callsign, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.text, modifier = Modifier.weight(1.4f))
                    Text(entry.aircraftType ?: "--", fontSize = 12.sp, color = colors.textMuted, modifier = Modifier.weight(1f))
                    Text("%.1fnm".format(entry.distanceNm), fontSize = 12.sp, color = colors.textMuted, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
