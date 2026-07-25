package at.sushi.handoff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import at.sushi.handoff.HandoffConnectionService
import at.sushi.handoff.HandoffState
import at.sushi.handoff.network.HandoffDiscoveryClient
import at.sushi.handoff.protocol.RefreshFlightPlanCommand
import at.sushi.handoff.protocol.SetSimbriefCredentialsCommand
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(HandoffConnectionService.PrefsName, android.content.Context.MODE_PRIVATE) }
    var host by remember { mutableStateOf(prefs.getString(HandoffConnectionService.PrefKeyHost, "") ?: "") }
    var simbriefUserId by remember { mutableStateOf(prefs.getString(HandoffConnectionService.PrefKeySimbriefUserId, "") ?: "") }
    var simbriefUsername by remember { mutableStateOf(prefs.getString(HandoffConnectionService.PrefKeySimbriefUsername, "") ?: "") }
    var status by remember { mutableStateOf("") }
    val connectionStatus by HandoffState.connectionStatus.collectAsState()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Connection: $connectionStatus")

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Plugin PC IP") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                prefs.edit { putString(HandoffConnectionService.PrefKeyHost, host.ifBlank { null }) }
                HandoffConnectionService.instance?.reconnectNow()
            }) {
                Text("Save & reconnect")
            }

            Button(onClick = {
                status = "Searching…"
                scope.launch {
                    val found = HandoffDiscoveryClient().discoverHost()
                    if (found != null) {
                        host = found
                        status = "Found $found"
                    } else {
                        status = "Not found -- enter IP manually"
                    }
                }
            }) {
                Text("Auto-detect")
            }
        }

        if (status.isNotBlank()) {
            Text(status, modifier = Modifier.padding(top = 8.dp))
        }

        Text("SimBrief", modifier = Modifier.padding(top = 24.dp))
        Text("User ID takes priority; username is only used as a fallback.")

        OutlinedTextField(
            value = simbriefUserId,
            onValueChange = { simbriefUserId = it },
            label = { Text("SimBrief user ID") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )

        OutlinedTextField(
            value = simbriefUsername,
            onValueChange = { simbriefUsername = it },
            label = { Text("SimBrief username") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )

        Button(onClick = {
            prefs.edit {
                putString(HandoffConnectionService.PrefKeySimbriefUserId, simbriefUserId.ifBlank { null })
                putString(HandoffConnectionService.PrefKeySimbriefUsername, simbriefUsername.ifBlank { null })
            }
            HandoffConnectionService.instance?.sendCommand(
                SetSimbriefCredentialsCommand(
                    simbriefUserId = simbriefUserId.ifBlank { null },
                    simbriefUsername = simbriefUsername.ifBlank { null }
                )
            )
            HandoffConnectionService.instance?.sendCommand(RefreshFlightPlanCommand())
        }) {
            Text("Save & refresh flight plan")
        }
    }
}
