package at.sushi.handoff.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.OperationProgressState
import at.sushi.handoff.protocol.ConnectNetworkCommand
import at.sushi.handoff.protocol.DisconnectNetworkCommand
import at.sushi.handoff.ui.theme.HandoffTextField
import at.sushi.handoff.ui.theme.LocalHandoffColors
import at.sushi.handoff.util.NetworkConnect
import at.sushi.handoff.util.SelcalVisualTransformation
import java.util.UUID
import kotlinx.coroutines.delay

/** docs/protocol.md's client-side backstop for an operation that never reports `finished`. */
private const val NetworkOperationTimeoutMs = 60_000L

/** Result of waiting on one network operation, driven by the `operationProgress` the plugin
 *  broadcasts under the id this client minted. */
private sealed interface NetworkWait {
    data object Idle : NetworkWait
    data class Waiting(val operationId: String, val status: String) : NetworkWait
}

/** Tracks one in-flight network operation: [start] arms it, and the matching progress entry closes
 *  it through [onSuccess] / [onFailure]; no progress at all for 60s counts as a timeout. */
@Composable
private fun rememberNetworkWait(
    operations: Map<String, OperationProgressState>,
    onSuccess: () -> Unit,
    onFailure: (String) -> Unit
): Pair<NetworkWait, (String) -> Unit> {
    var pendingId by remember { mutableStateOf<String?>(null) }
    val progress = pendingId?.let { operations[it]?.message }

    LaunchedEffect(pendingId, progress) {
        if (progress != null && progress.finished) {
            pendingId = null
            if (progress.success) onSuccess() else onFailure(progress.status)
        }
    }
    LaunchedEffect(pendingId) {
        val id = pendingId ?: return@LaunchedEffect
        delay(NetworkOperationTimeoutMs)
        if (pendingId == id) {
            pendingId = null
            onFailure("Timed out waiting for vPilot. Check the state in vPilot and try again.")
        }
    }

    val wait = pendingId?.let { NetworkWait.Waiting(it, progress?.status ?: "Waiting for vPilot...") } ?: NetworkWait.Idle
    return wait to { id: String -> pendingId = id }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = LocalHandoffColors.current.textMuted,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun WaitingRow(status: String) {
    val colors = LocalHandoffColors.current
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
        Text(status, fontSize = 13.sp, color = colors.textMuted)
    }
}

/** Issue #171 -- "Connect to VATSIM" form. Callsign and aircraft type are prefilled from the latest
 *  SimBrief plan but stay editable, and Connect is disabled with the reason spelled out until the
 *  form can actually be sent. Stays open with a spinner while waiting for the plugin to report
 *  the outcome, closes itself on success and shows a failure inline. */
@Composable
fun ConnectNetworkDialog(
    initialCallsign: String,
    initialAircraftType: String,
    initialSelcal: String,
    operations: Map<String, OperationProgressState>,
    onSend: (ConnectNetworkCommand) -> Unit,
    onDismiss: () -> Unit,
    // Issue #205 -- the onboarding tour shows this form as plain inline content (no Dialog window,
    // see SimpleDialogPanelChrome) whose buttons do nothing.
    preview: Boolean = false
) {
    val colors = LocalHandoffColors.current
    var callsign by remember { mutableStateOf(initialCallsign) }
    var aircraftType by remember { mutableStateOf(initialAircraftType) }
    var selcal by remember { mutableStateOf(NetworkConnect.prefillSelcal(initialSelcal)) }
    var observer by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val (wait, arm) = rememberNetworkWait(operations, onSuccess = onDismiss, onFailure = { error = it })
    val waiting = wait is NetworkWait.Waiting
    val blockedReason = NetworkConnect.connectBlockedReason(callsign, aircraftType, observer)
    val selcalWarning = if (observer) null else NetworkConnect.selcalWarning(selcal)

    val content: @Composable ColumnScope.() -> Unit = {
        FieldLabel("Callsign")
        HandoffTextField(
            callsign,
            { callsign = it.uppercase(); error = null },
            placeholder = "e.g. EIDGX",
            fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth()
        )
        if (!observer) {
            FieldLabel("Aircraft type")
            HandoffTextField(
                aircraftType,
                { aircraftType = it.uppercase(); error = null },
                placeholder = "e.g. A320",
                fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth()
            )
            FieldLabel("SELCAL (optional)")
            HandoffTextField(
                selcal,
                { selcal = NetworkConnect.cleanSelcalInput(it); error = null },
                placeholder = "e.g. AB-CD",
                fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = SelcalVisualTransformation
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clickable(enabled = !waiting) { observer = !observer },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = observer, onCheckedChange = null, enabled = !waiting)
            Text("Connect as observer", fontSize = 14.sp, color = colors.text, modifier = Modifier.padding(start = 8.dp))
        }

        when {
            wait is NetworkWait.Waiting -> WaitingRow(wait.status)
            error != null -> Text(error!!, fontSize = 13.sp, color = outOfBandRed, modifier = Modifier.padding(top = 12.dp))
            blockedReason != null -> Text(blockedReason, fontSize = 13.sp, color = colors.attention, modifier = Modifier.padding(top = 12.dp))
            // Advice only -- Connect stays enabled, vPilot decides whether it takes the code.
            selcalWarning != null -> Text(selcalWarning, fontSize = 13.sp, color = colors.attention, modifier = Modifier.padding(top = 12.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel", fontSize = 15.sp) }
            Button(
                enabled = blockedReason == null && !waiting,
                onClick = {
                    if (preview) return@Button
                    val operationId = UUID.randomUUID().toString()
                    val cleanSelcal = NetworkConnect.formatSelcal(selcal)
                    error = null
                    arm(operationId)
                    onSend(
                        ConnectNetworkCommand(
                            operationId = operationId,
                            callsign = NetworkConnect.normalize(callsign),
                            aircraftType = if (observer) null else NetworkConnect.normalize(aircraftType),
                            selcalCode = if (observer || cleanSelcal.isEmpty()) null else cleanSelcal,
                            observer = observer
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            ) { Text("Connect", fontSize = 15.sp) }
        }
    }
    if (preview) {
        SimpleDialogPanelChrome(title = "Connect to VATSIM", width = 320.dp, onDismiss = onDismiss, content = content)
    } else {
        SimpleDialogPanel(title = "Connect to VATSIM", width = 320.dp, onDismiss = onDismiss, content = content)
    }
}

/** Issue #171 -- confirmation before leaving the network. Always asks (a mis-tap mid-flight is
 *  costly) and names the consequence; spinner/inline failure behave like [ConnectNetworkDialog]. */
@Composable
fun DisconnectNetworkDialog(
    callsign: String?,
    operations: Map<String, OperationProgressState>,
    onSend: (DisconnectNetworkCommand) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalHandoffColors.current
    var error by remember { mutableStateOf<String?>(null) }
    val (wait, arm) = rememberNetworkWait(operations, onSuccess = onDismiss, onFailure = { error = it })
    val waiting = wait is NetworkWait.Waiting

    SimpleDialogPanel(title = "Disconnect from VATSIM?", width = 320.dp, onDismiss = onDismiss) {
        Text(
            "You'll leave the network now" + (callsign?.let { " as $it" } ?: "") + ". vPilot keeps running.",
            fontSize = 15.sp,
            color = colors.textMuted,
            modifier = Modifier.padding(top = 8.dp)
        )
        when {
            wait is NetworkWait.Waiting -> WaitingRow(wait.status)
            error != null -> Text(error!!, fontSize = 13.sp, color = outOfBandRed, modifier = Modifier.padding(top = 12.dp))
        }
        // Stacked, full-width, like DiversionConfirmDialog: side by side, each button only got ~90dp of
        // text on a narrow phone (or a large font setting) and "Disconnect" wrapped to "Disconne / ct".
        Column(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                enabled = !waiting,
                onClick = {
                    val operationId = UUID.randomUUID().toString()
                    error = null
                    arm(operationId)
                    onSend(DisconnectNetworkCommand(operationId = operationId))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Disconnect", fontSize = 15.sp, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel", fontSize = 15.sp) }
        }
    }
}
