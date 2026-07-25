package at.sushi.handoff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import at.sushi.handoff.protocol.ChatEntry
import at.sushi.handoff.protocol.SendPrivateMessageCommand
import at.sushi.handoff.protocol.SendRadioMessageCommand

@Composable
fun ChatScreen() {
    val chat by HandoffState.chat.collectAsState()
    var recipient by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
            items(chat.messages) { entry -> ChatEntryRow(entry) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            OutlinedTextField(
                value = recipient,
                onValueChange = { recipient = it },
                label = { Text("To (blank = radio)") },
                modifier = Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Message") },
                modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                if (message.isNotBlank()) {
                    val command = if (recipient.isBlank()) {
                        SendRadioMessageCommand(message = message)
                    } else {
                        SendPrivateMessageCommand(to = recipient, message = message)
                    }
                    HandoffConnectionService.instance?.sendCommand(command)
                    message = ""
                }
            }) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun ChatEntryRow(entry: ChatEntry) {
    val label = when {
        entry.channel == "private" -> "${entry.direction} <-> ${entry.peer}"
        entry.channel == "radio" -> "${entry.direction} radio"
        else -> "${entry.direction} ${entry.channel}"
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label)
        Text(entry.text)
    }
}
