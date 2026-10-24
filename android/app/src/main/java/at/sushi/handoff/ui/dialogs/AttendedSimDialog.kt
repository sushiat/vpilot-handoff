package at.sushi.handoff.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import at.sushi.handoff.ui.theme.LocalHandoffColors

const val VatsimCodeOfConductUrl = "https://vatsim.net/docs/policy/code-of-conduct"

/** Issue #188 -- one-time "stay at your sim" notice. VATSIM's Code of Conduct (A9, B3(b)) forbids
 *  unattended connections; Handoff is a LAN-only second screen for vPilot and never connects to
 *  VATSIM itself, but a second device makes it easy to forget that the rules still apply. Not
 *  dismissable except through the acknowledge button (no back/outside/close affordance), so it
 *  can't be waved away by accident; [onAcknowledge] persists the answer.
 *
 *  The penalty line is VATSIM's published summary-judgement scale for A9/B3 (7/14/30/60 days).
 *  VATSIM marks that scale as optional and has changed enforcement procedures since, so the
 *  wording stays "published scale", and the numbers need re-checking against the Code of
 *  Regulations before each release that touches this text. */
@Composable
fun AttendedSimDialog(onAcknowledge: () -> Unit) {
    val colors = LocalHandoffColors.current
    val uriHandler = LocalUriHandler.current
    // Readable red in both themes -- the palette has no red of its own (only the orange "attention").
    val penaltyRed = if (colors.isDark) Color(0xFFFF7B72) else Color(0xFFC62828)

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Column(
            Modifier
                .width(340.dp)
                .heightIn(max = 560.dp)
                .background(colors.panel, RoundedCornerShape(16.dp))
                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text("Unattended connections", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.text)
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 10.dp)
            ) {
                Text(
                    "Handoff lets you chat and tune the radio from a second device. VATSIM's Code of " +
                        "Conduct still applies in full: you must not leave your connection unattended " +
                        "(A9), and a pilot connection may never be unattended for more than 30 minutes " +
                        "(B3(b)).",
                    fontSize = 15.sp,
                    color = colors.textMuted
                )
                Text(
                    "Handoff only works on your local network with vPilot running on your sim PC. " +
                        "It never connects to VATSIM itself and does not change these rules.",
                    fontSize = 15.sp,
                    color = colors.textMuted,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    "VATSIM's published penalty scale for this rule: 7-day suspension for a first " +
                        "offence, 14 days for a second, 30 days for a third, 60 days for a fourth.",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = penaltyRed,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    "Read the Code of Conduct",
                    fontSize = 15.sp,
                    color = colors.accent,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clickable { uriHandler.openUri(VatsimCodeOfConductUrl) }
                )
            }
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onAcknowledge, modifier = Modifier.fillMaxWidth()) {
                    Text("I have read and understood the VATSIM Code of Conduct", fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
