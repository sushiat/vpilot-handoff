package at.sushi.handoff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import at.sushi.handoff.HandoffConnectionService
import at.sushi.handoff.HandoffState
import at.sushi.handoff.protocol.RadioFrequency
import at.sushi.handoff.protocol.SetCom1FrequencyCommand
import at.sushi.handoff.protocol.SetCom2FrequencyCommand

@Composable
fun RadioScreen() {
    val radioState by HandoffState.radioState.collectAsState()

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("COM1: " + (radioState.com1Frequency?.let(RadioFrequency::format) ?: "--"))
        FrequencyEntryRow(onSet = { HandoffConnectionService.instance?.sendCommand(SetCom1FrequencyCommand(megahertz = it)) })

        Text("COM2: " + (radioState.com2Frequency?.let(RadioFrequency::format) ?: "--"))
        FrequencyEntryRow(onSet = { HandoffConnectionService.instance?.sendCommand(SetCom2FrequencyCommand(megahertz = it)) })

        Text("Mode C: " + if (radioState.modeCEnabled) "on" else "off")
    }
}

@Composable
private fun FrequencyEntryRow(onSet: (Double) -> Unit) {
    var value by remember { mutableStateOf("") }

    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("MHz") }
        )
        Button(onClick = {
            value.toDoubleOrNull()?.let(onSet)
        }) {
            Text("Set")
        }
    }
}
