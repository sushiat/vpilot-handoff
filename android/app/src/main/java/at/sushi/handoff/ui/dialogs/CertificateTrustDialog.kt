package at.sushi.handoff.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.PendingCertTrust
import at.sushi.handoff.ui.theme.LocalHandoffColors
import kotlin.math.ceil

/** Trust-on-first-use prompt for the plugin's self-signed TLS certificate (issue #15) -- the
 *  first explicit two-button Trust/Cancel dialog in this codebase (existing dialogs save on
 *  dismiss rather than confirm/cancel). Shows all 4 values needed for an informed trust decision
 *  (host, port, hostname from the cert's CN, fingerprint) rather than just the hash.
 *
 *  [pending].isChanged switches between a neutral first-connection prompt and a visibly alarming
 *  "this cert changed" warning -- a changed fingerprint could mean a legitimate cert rotation
 *  (reinstalled plugin) or a genuine MITM/spoof, so it must not look like an ordinary first-trust
 *  prompt a pilot might tap through on autopilot. */
@Composable
fun CertificateTrustDialog(pending: PendingCertTrust, onTrust: () -> Unit, onCancel: () -> Unit) {
    val colors = LocalHandoffColors.current

    SimpleDialogPanel(
        title = if (pending.isChanged) "Certificate changed!" else "New plugin connection",
        width = 340.dp,
        onDismiss = onCancel
    ) {
        if (pending.isChanged) {
            Text(
                "This plugin's certificate changed since you last connected. This can happen " +
                    "after reinstalling the plugin -- but it can also mean someone else is " +
                    "impersonating it. Only trust this if you just reinstalled or reset the plugin.",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = colors.attention,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.attentionBg, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            )
        } else {
            Text(
                "Connect to this plugin? Verify the details below match the PC you're pairing with.",
                fontSize = 15.sp,
                color = colors.textMuted,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
            CertificateDetailRow("Host", "${pending.host}:${pending.port}", colors.text)
            CertificateDetailRow("Hostname", pending.commonName ?: "unknown", colors.text)
            CertificateDetailRow(
                "Fingerprint",
                formatFingerprintMultiline(pending.fingerprint),
                colors.text,
                valueOnNewLine = true,
                valueFontFamily = FontFamily.Monospace
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().weight(1f)) {
                Text("Cancel", fontSize = 15.sp)
            }
            Button(
                onClick = onTrust,
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = if (pending.isChanged) {
                    ButtonDefaults.buttonColors(containerColor = colors.attention)
                } else {
                    ButtonDefaults.buttonColors(containerColor = colors.accent)
                }
            ) {
                Text("Trust", fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun CertificateDetailRow(
    label: String,
    value: String,
    textColor: androidx.compose.ui.graphics.Color,
    valueOnNewLine: Boolean = false,
    valueFontFamily: FontFamily = FontFamily.Default
) {
    val colors = LocalHandoffColors.current
    if (valueOnNewLine) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(label, fontSize = 14.sp, color = colors.textMuted)
            Text(
                value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = valueFontFamily,
                color = textColor,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 14.sp, color = colors.textMuted)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = valueFontFamily, color = textColor)
        }
    }
}

/** Splits a colon-separated hex fingerprint (32 byte-pairs for SHA-256) evenly across 3 lines --
 *  wrapping mid-pair inside a non-monospace `Text` was hard to read; pre-splitting on ":"
 *  boundaries and forcing a monospace font (see the Fingerprint row above) keeps every pair
 *  intact and each line the same width. */
private fun formatFingerprintMultiline(fingerprint: String): String {
    val groups = fingerprint.split(":")
    val perLine = ceil(groups.size / 3.0).toInt()
    return groups.chunked(perLine).joinToString("\n") { it.joinToString(":") }
}
