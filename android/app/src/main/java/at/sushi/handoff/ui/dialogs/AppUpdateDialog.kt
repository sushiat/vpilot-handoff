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

/** One-shot startup notice for sideloaded installs that a newer Handoff release exists (issue
 *  #34) -- suppressed entirely for Obtainium-managed installs (see
 *  AppUpdateClient.isInstalledViaObtainium), which already handle this on their own. Same
 *  driven-by-app-state shape as DiversionConfirmDialog/PairingCodeDialog -- this is triggered by
 *  the startup check, not a user-initiated button. */
@Composable
fun AppUpdateDialog(
    version: String,
    onOpenRelease: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalHandoffColors.current

    SimpleDialogPanel(title = "Update available", width = 320.dp, onDismiss = onDismiss) {
        Text(
            "Handoff v$version is available on GitHub.",
            fontSize = 15.sp,
            color = colors.textMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onOpenRelease, modifier = Modifier.fillMaxWidth()) {
                Text("View release", fontSize = 15.sp)
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Not now", fontSize = 15.sp)
            }
        }
    }
}
