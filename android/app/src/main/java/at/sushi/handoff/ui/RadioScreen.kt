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
import at.sushi.handoff.protocol.SetCom1StandbyFrequencyCommand
import at.sushi.handoff.protocol.SetCom2FrequencyCommand
import at.sushi.handoff.protocol.SetCom2StandbyFrequencyCommand
import at.sushi.handoff.protocol.SetTransponderCodeCommand

@Composable
fun RadioScreen() {
    val radioState by HandoffState.radioState.collectAsState()

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("COM1 active: " + (radioState.com1Frequency?.let(RadioFrequency::format) ?: "--"))
        FrequencyEntryRow(onSet = { HandoffConnectionService.instance?.sendCommand(SetCom1FrequencyCommand(megahertz = it)) })

        Text("COM1 standby: " + (radioState.com1StandbyFrequency?.let(RadioFrequency::format) ?: "--"))
        FrequencyEntryRow(onSet = { HandoffConnectionService.instance?.sendCommand(SetCom1StandbyFrequencyCommand(megahertz = it)) })

        Text("COM2 active: " + (radioState.com2Frequency?.let(RadioFrequency::format) ?: "--"))
        FrequencyEntryRow(onSet = { HandoffConnectionService.instance?.sendCommand(SetCom2FrequencyCommand(megahertz = it)) })

        Text("COM2 standby: " + (radioState.com2StandbyFrequency?.let(RadioFrequency::format) ?: "--"))
        FrequencyEntryRow(onSet = { HandoffConnectionService.instance?.sendCommand(SetCom2StandbyFrequencyCommand(megahertz = it)) })

        Text("Mode C: " + if (radioState.modeCEnabled) "on" else "off")

        Text("Transponder: " + (radioState.transponderCode?.toString()?.padStart(4, '0') ?: "----"))
        TransponderEntryRow(onSet = { HandoffConnectionService.instance?.sendCommand(SetTransponderCodeCommand(transponderCode = it)) })
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
            val parsed = value.toDoubleOrNull()
            android.util.Log.d("HandoffWS", "FrequencyEntryRow Set tapped, value='$value', parsed=$parsed")
            parsed?.let(onSet)
        }) {
            Text("Set")
        }
    }
}

@Composable
private fun TransponderEntryRow(onSet: (Int) -> Unit) {
    var value by remember { mutableStateOf("") }

    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("Squawk") }
        )
        Button(onClick = {
            val squawk = value.toIntOrNull()
            val validRange = squawk != null && squawk in 0..7777
            val validDigits = squawk?.toString()?.all { it in '0'..'7' } ?: false
            android.util.Log.d("HandoffWS", "TransponderEntryRow Set tapped, value='$value', squawk=$squawk, validRange=$validRange, validDigits=$validDigits")
            if (squawk != null && validRange && validDigits) {
                onSet(squawk)
            }
        }) {
            Text("Set")
        }
    }
}
