package at.sushi.handoff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import at.sushi.handoff.HandoffConnectionService
import at.sushi.handoff.HandoffState
import at.sushi.handoff.protocol.Controller
import at.sushi.handoff.protocol.RadioFrequency
import at.sushi.handoff.protocol.RefreshFlightPlanCommand

@Composable
fun ControllersScreen() {
    val controllers by HandoffState.controllers.collectAsState()
    val flightPlan by HandoffState.flightPlan.collectAsState()

    Column(Modifier.fillMaxSize()) {
        // Basic display only -- full UI overhaul is a separate future task. Alternate is
        // fetched/stored by the plugin but intentionally not shown here yet.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                (flightPlan.callsign ?: "--") + "   " + (flightPlan.origin ?: "----") + " -> " + (flightPlan.destination ?: "----")
            )
            IconButton(onClick = { HandoffConnectionService.instance?.sendCommand(RefreshFlightPlanCommand()) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh flight plan")
            }
        }
        HorizontalDivider()

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(controllers.controllers, key = { it.callsign }) { controller ->
                ControllerRow(controller)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ControllerRow(controller: Controller) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(controller.callsign)
        Text(RadioFrequency.format(controller.frequency))
    }
}
