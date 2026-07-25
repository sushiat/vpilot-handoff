package at.sushi.handoff.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import at.sushi.handoff.ChannelSpacing
import at.sushi.handoff.ConnectionStatus
import at.sushi.handoff.KeypadBlockMode
import at.sushi.handoff.ThemeMode
import at.sushi.handoff.network.HandoffDiscoveryClient
import at.sushi.handoff.ui.theme.LocalHandoffColors
import kotlinx.coroutines.launch

/** Settings dialog -- issue #13 screen 4. Same 336dp panel construction as the COM/XPDR dialogs,
 *  but scrollable since it holds a lot more content. Save persists everything and silently
 *  triggers a SimBrief refresh, with no confirmation toast, per the doc. */
@Composable
fun SettingsDialog(
    connectionStatus: ConnectionStatus,
    initialHost: String,
    initialSimbriefUserId: String,
    initialSimbriefUsername: String,
    initialTheme: ThemeMode,
    initialChannelSpacing: ChannelSpacing,
    initialKeypadBlockMode: KeypadBlockMode,
    onDismiss: () -> Unit,
    onSave: (
        host: String?,
        simbriefUserId: String?,
        simbriefUsername: String?,
        theme: ThemeMode,
        channelSpacing: ChannelSpacing,
        keypadBlockMode: KeypadBlockMode
    ) -> Unit
) {
    val colors = LocalHandoffColors.current
    var host by remember { mutableStateOf(initialHost) }
    var simbriefUserId by remember { mutableStateOf(initialSimbriefUserId) }
    var simbriefUsername by remember { mutableStateOf(initialSimbriefUsername) }
    var theme by remember { mutableStateOf(initialTheme) }
    var channelSpacing by remember { mutableStateOf(initialChannelSpacing) }
    var keypadBlockMode by remember { mutableStateOf(initialKeypadBlockMode) }
    var discoveryStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    KeypadDialogPanel(title = "SETTINGS", onDismiss = onDismiss) {
        Column(
            Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SectionHeader("SIMBRIEF")
            OutlinedTextField(
                value = simbriefUserId,
                onValueChange = { simbriefUserId = it },
                label = { Text("SimBrief user ID") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
            OutlinedTextField(
                value = simbriefUsername,
                onValueChange = { simbriefUsername = it },
                label = { Text("SimBrief username (fallback)") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )

            SectionHeader("APPEARANCE")
            ThreeWayToggle(
                options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"),
                selected = theme,
                onSelect = { theme = it }
            )

            SectionHeader("DEFAULT CHANNEL SPACING")
            ThreeWayToggle(
                options = listOf(ChannelSpacing.KHZ_25 to "25 kHz", ChannelSpacing.KHZ_8_33 to "8.33 kHz"),
                selected = channelSpacing,
                onSelect = { channelSpacing = it }
            )

            SectionHeader("FREQUENCY KEYPAD")
            ThreeWayToggle(
                options = listOf(
                    KeypadBlockMode.BLOCK_INVALID to "Block invalid",
                    KeypadBlockMode.ALLOW_ALL to "Allow all"
                ),
                selected = keypadBlockMode,
                onSelect = { keypadBlockMode = it }
            )

            SectionHeader("PLUGIN CONNECTION")
            Text("Status: $connectionStatus", fontSize = 12.sp, color = colors.textMuted)
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Plugin PC IP") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
            Button(onClick = {
                discoveryStatus = "Searching…"
                scope.launch {
                    val found = HandoffDiscoveryClient().discoverHost()
                    if (found != null) {
                        host = found
                        discoveryStatus = "Found $found"
                    } else {
                        discoveryStatus = "Not found -- enter IP manually"
                    }
                }
            }) {
                Text("Auto-detect")
            }
            if (discoveryStatus.isNotBlank()) {
                Text(discoveryStatus, fontSize = 11.sp, color = colors.textMuted)
            }

            HorizontalDivider(color = colors.border, modifier = Modifier.padding(vertical = 12.dp))
            SectionHeader("CREDITS")
            Text(
                "VATSpy · VatGlasses · VATSIM data feed · SimBrief · vPilot",
                fontSize = 11.sp,
                color = colors.textMuted
            )
            Text(
                "Contribute — sushi.at/vpilot-handoff",
                fontSize = 11.sp,
                color = colors.textMuted,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally)
            )
        }

        Button(
            onClick = {
                onSave(
                    host.ifBlank { null },
                    simbriefUserId.ifBlank { null },
                    simbriefUsername.ifBlank { null },
                    theme,
                    channelSpacing,
                    keypadBlockMode
                )
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Text("Save")
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    val colors = LocalHandoffColors.current
    Text(
        label,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.08f.em,
        color = colors.textMuted,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun <T> ThreeWayToggle(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val colors = LocalHandoffColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (isSelected) colors.accentBg else colors.panelAlt, RoundedCornerShape(10.dp))
                    .clickable { onSelect(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) colors.accent else colors.text
                )
            }
        }
    }
}
