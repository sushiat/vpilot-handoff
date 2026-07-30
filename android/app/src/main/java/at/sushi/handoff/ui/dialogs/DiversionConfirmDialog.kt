package at.sushi.handoff.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** Prompted whenever the plugin notices the VATSIM feed's filed destination change mid-session
 *  (docs/protocol.md's diversionPending message) -- a feed hiccup or ATC typo shouldn't silently
 *  drop the filed route from the plugin's own approach/convergence prediction without the pilot
 *  getting a say. Driven by HandoffState.diversionPending rather than a locally-owned open/closed
 *  flag, same reasoning as PairingCodeDialog -- this is triggered by the plugin, not a
 *  user-initiated button. Tapping outside/back dismisses as a "not a real diversion" answer,
 *  same as tapping "Not a diversion" -- there's no third "ask me again later" state, since the
 *  plugin won't re-prompt for this same destination again either way. */
@Composable
fun DiversionConfirmDialog(
    destination: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalHandoffColors.current

    SimpleDialogPanel(title = "Confirm diversion?", width = 320.dp, onDismiss = onDismiss) {
        Text(
            "The network now shows your destination as $destination. If this is a real diversion, " +
                "the plugin will stop using your filed route for approach/handoff predictions.",
            fontSize = 15.sp,
            color = colors.textMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Text("Confirm diversion to $destination", fontSize = 15.sp)
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Not a diversion", fontSize = 15.sp)
            }
        }
    }
}
