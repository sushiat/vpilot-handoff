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
import at.sushi.handoff.VersionMismatch
import at.sushi.handoff.network.VersionSkew
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** One-shot-on-connect notice that the Windows plugin and this app are on different versions (issue
 *  #87). Distinct from [AppUpdateDialog] because the resolution steps differ meaningfully by which
 *  side is behind, and -- for the app-behind case -- by install source (see [VersionMismatch]).
 *  Soft/informational, never a hard gate: per docs/protocol.md the contract is
 *  additive/backward-compatible, so a mismatch is worth flagging but not worth blocking use over.
 *  Driven by HandoffState.versionMismatch, same "app-state, not a user-initiated button" shape as
 *  [AppUpdateDialog]. [onViewRelease] is only invoked from the app-behind + sideloaded branch. */
@Composable
fun VersionMismatchDialog(
    mismatch: VersionMismatch,
    onViewRelease: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalHandoffColors.current

    val title: String
    val body: String
    when (mismatch.skew) {
        VersionSkew.PLUGIN_BEHIND -> {
            title = "Plugin out of date"
            body = "The vPilot plugin (v${mismatch.pluginVersion}) is older than this app " +
                "(v${mismatch.appVersion}). Restart vPilot to apply the downloaded update, or wait " +
                "for the plugin's auto-updater to run."
        }
        VersionSkew.APP_BEHIND -> {
            title = "App out of date"
            body = "This app (v${mismatch.appVersion}) is older than the vPilot plugin " +
                "(v${mismatch.pluginVersion}). " +
                if (mismatch.viaObtainium) {
                    "Update Handoff via Obtainium to catch up."
                } else {
                    "Re-download the latest APK from GitHub and sideload it to catch up."
                }
        }
    }

    SimpleDialogPanel(title = title, width = 320.dp, onDismiss = onDismiss) {
        Text(
            body,
            fontSize = 15.sp,
            color = colors.textMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Only the app-behind + manual-sideload path has an actionable link; the plugin-behind
            // and Obtainium paths resolve outside this app, so they just get an acknowledge button.
            if (mismatch.skew == VersionSkew.APP_BEHIND && !mismatch.viaObtainium) {
                Button(onClick = onViewRelease, modifier = Modifier.fillMaxWidth()) {
                    Text("View release", fontSize = 15.sp)
                }
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Dismiss", fontSize = 15.sp)
            }
        }
    }
}
