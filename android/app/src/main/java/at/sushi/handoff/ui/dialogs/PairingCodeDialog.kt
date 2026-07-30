package at.sushi.handoff.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.PendingPairing
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** Device-pairing prompt (issue #15) -- the pilot's actual trust gate is a code read off the
 *  plugin's own on-screen pairing window, not a certificate fingerprint (see CertTrustStore's
 *  doc comment for why that changed: a hash means nothing to most pilots, a code shown on the
 *  correct PC is something they can actually verify by being in front of it). Two states, both
 *  hosted in the same SimpleDialogPanel: code entry, and a follow-up "stop asking?" confirm once
 *  Cancel is tapped -- just closing and reappearing on the next reconnect a few seconds later
 *  wasn't respecting a real cancel. */
@Composable
fun PairingCodeDialog(
    pending: PendingPairing,
    onSubmitCode: (String) -> Unit,
    onCancel: (permanent: Boolean) -> Unit
) {
    val colors = LocalHandoffColors.current
    var code by remember { mutableStateOf("") }
    var confirmingCancel by remember { mutableStateOf(false) }

    SimpleDialogPanel(
        title = if (confirmingCancel) "Stop pairing?" else "Pair with this PC",
        width = 340.dp,
        // First dismiss (back press / tap outside / the "x") asks for confirmation rather than
        // silently going away; a second one while already confirming just takes the safe
        // "just this once" default instead of forcing a button tap.
        onDismiss = { if (confirmingCancel) onCancel(false) else confirmingCancel = true }
    ) {
        if (confirmingCancel) {
            Text(
                "Stop trying to pair with this device? You can always reconnect later.",
                fontSize = 15.sp,
                color = colors.textMuted,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onCancel(false) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Just this once", fontSize = 15.sp)
                }
                Button(onClick = { onCancel(true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Ignore this machine", fontSize = 15.sp)
                }
            }
        } else {
            Text(
                "Enter the code shown on the PC" +
                    (pending.commonName?.let { " ($it)" } ?: "") +
                    " at ${pending.host}:${pending.port} to pair this tablet with it.",
                fontSize = 15.sp,
                color = colors.textMuted,
                modifier = Modifier.padding(top = 8.dp)
            )

            OutlinedTextField(
                value = code,
                onValueChange = { new -> code = new.filter(Char::isDigit).take(6) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                // At least 30pt per the pilot's ask, same as the plugin's own pairing window --
                // this needs to be readable and easy to double-check digit-by-digit.
                textStyle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                placeholder = {
                    Text("000000", fontSize = 34.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                },
                singleLine = true
            )

            pending.errorMessage?.let { message ->
                Text(message, fontSize = 14.sp, color = colors.attention, modifier = Modifier.padding(top = 8.dp))
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = { confirmingCancel = true }, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Text("Cancel", fontSize = 15.sp)
                }
                Button(
                    onClick = { onSubmitCode(code) },
                    enabled = code.length == 6,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    Text("Pair", fontSize = 15.sp)
                }
            }
        }
    }
}
