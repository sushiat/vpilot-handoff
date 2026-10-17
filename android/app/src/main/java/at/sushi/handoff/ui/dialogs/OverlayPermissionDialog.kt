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

/** Issue #177 -- shown before Android's raw "Display over other apps" settings page so the pilot
 *  knows why Handoff wants it. [onContinue] opens the system screen, [onNotNow] snoozes the ask for
 *  this app session, [onDecline] persists "never ask, use the in-window chat in split-screen". */
@Composable
fun OverlayPermissionDialog(
    onContinue: () -> Unit,
    onNotNow: () -> Unit,
    onDecline: () -> Unit
) {
    val colors = LocalHandoffColors.current
    SimpleDialogPanel(title = "Floating chat panel", width = 320.dp, onDismiss = onNotNow) {
        Text(
            "Handoff can show the chat temporarily over your other app while it's open, so " +
                "Handoff itself can stay small. This needs Android's \"display over other apps\" " +
                "permission, used only for the chat panel. Without it, the chat opens in place " +
                "of the controller list instead.",
            fontSize = 15.sp,
            color = colors.textMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Continue", fontSize = 15.sp)
            }
            OutlinedButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth()) {
                Text("Not now", fontSize = 15.sp)
            }
            OutlinedButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) {
                Text("I don't want that", fontSize = 15.sp)
            }
        }
    }
}

/** Issue #177 -- the debug window is a real overlay window and silently can't open without the
 *  permission; this explains why instead of leaving the toggle looking dead. */
@Composable
fun DebugOverlayPermissionDialog(
    isSplitScreen: Boolean,
    onGrant: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalHandoffColors.current
    val hint = if (isSplitScreen) {
        "Return Handoff to fullscreen to use it, or grant the permission."
    } else {
        "Grant the permission to use it."
    }
    SimpleDialogPanel(title = "Debug window unavailable", width = 320.dp, onDismiss = onDismiss) {
        Text(
            "The debug window needs Android's \"display over other apps\" permission, which isn't granted. $hint",
            fontSize = 15.sp,
            color = colors.textMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                Text("Grant permission", fontSize = 15.sp)
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("OK", fontSize = 15.sp)
            }
        }
    }
}
