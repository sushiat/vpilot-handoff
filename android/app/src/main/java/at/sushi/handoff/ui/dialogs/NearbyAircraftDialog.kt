package at.sushi.handoff.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** "Private chat to callsign" dialog -- issue #13 screen 6. The nearby-aircraft list below the
 *  callsign field has no data source yet: the protocol has no message for it (needs a new
 *  server->client message with callsign/type/distance, likely VATSIM-data-feed + own-position
 *  derived -- see docs/protocol.md). The callsign field + confirm button work today; the list is
 *  a visible "not available yet" stub. */
@Composable
fun NearbyAircraftDialog(
    onDismiss: () -> Unit,
    onOpenChatWith: (String) -> Unit
) {
    var callsign by remember { mutableStateOf("") }
    val colors = LocalHandoffColors.current

    KeypadDialogPanel(title = "PRIVATE CHAT TO CALLSIGN", onDismiss = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = callsign,
                onValueChange = { callsign = it.uppercase() },
                label = { Text("Callsign") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            val enabled = callsign.isNotBlank()
            Box(
                Modifier
                    .size(40.dp)
                    .background(
                        if (enabled) androidx.compose.ui.graphics.Color(0xFF3E8E5C) else colors.panelAlt.copy(alpha = 0.45f),
                        CircleShape
                    )
                    .then(
                        if (enabled) Modifier.clickable { onOpenChatWith(callsign); onDismiss() } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Check, contentDescription = "Open chat", tint = androidx.compose.ui.graphics.Color.White)
            }
        }

        Text(
            "AIRCRAFT WITHIN 20NM · CLOSEST FIRST",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.08f.em,
            color = colors.textMuted,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("CALLSIGN", fontSize = 9.sp, color = colors.textMuted)
            Text("TYPE", fontSize = 9.sp, color = colors.textMuted)
            Text("DIST", fontSize = 9.sp, color = colors.textMuted)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.panelAlt, RoundedCornerShape(10.dp))
                .padding(vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Not available yet -- needs a new protocol message from the plugin",
                fontSize = 11.sp,
                color = colors.textMuted
            )
        }
    }
}
