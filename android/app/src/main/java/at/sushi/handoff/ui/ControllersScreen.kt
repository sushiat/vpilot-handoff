package at.sushi.handoff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import at.sushi.handoff.HandoffState
import at.sushi.handoff.protocol.Controller
import at.sushi.handoff.protocol.RadioFrequency

@Composable
fun ControllersScreen() {
    val controllers by HandoffState.controllers.collectAsState()

    LazyColumn(Modifier.fillMaxWidth()) {
        items(controllers.controllers, key = { it.callsign }) { controller ->
            ControllerRow(controller)
            HorizontalDivider()
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
