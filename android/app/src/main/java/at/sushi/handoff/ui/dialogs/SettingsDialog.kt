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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
import at.sushi.handoff.ui.theme.HandoffTextField
import at.sushi.handoff.ui.theme.LocalHandoffColors
import at.sushi.handoff.ui.theme.verticalScrollbar
import kotlinx.coroutines.launch

private data class CreditRow(val label: String, val pillText: String, val url: String)

private val credits = listOf(
    CreditRow("Airport & FIR reference data", "VATSpy · CC BY-SA 4.0", "https://github.com/vatsimnetwork/vatspy-data-project"),
    CreditRow("Sector boundary data", "VatGlasses · CC BY-NC-SA 4.0", "https://github.com/lennycolton/vatglasses-data"),
    CreditRow("Live network data", "VATSIM Data Feed", "https://vatsim.dev"),
    CreditRow("Flight plan data", "SimBrief by Navigraph", "https://www.simbrief.com"),
    CreditRow("Pilot client", "vPilot", "https://vpilot.rosscarlson.dev")
)

/** Settings dialog -- issue #13 screen 4, matching the reference's `settingsDialog` object
 *  exactly: 320dp panel via [SimpleDialogPanel] (not the COM/XPDR dialogs' chrome -- that was a
 *  real mismatch, see SimpleDialogPanel's own doc comment), SimBrief fields, three toggle
 *  sections, a plugin-connection IP field, a full-width Save button, and a five-row Credits list
 *  with linked attribution pills plus a centered "Contribute" link -- none of which existed here
 *  before. Save persists everything and silently triggers a SimBrief refresh, with no
 *  confirmation toast, per the doc. */
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
    val uriHandler = LocalUriHandler.current
    var host by remember { mutableStateOf(initialHost) }
    var simbriefUserId by remember { mutableStateOf(initialSimbriefUserId) }
    var simbriefUsername by remember { mutableStateOf(initialSimbriefUsername) }
    var theme by remember { mutableStateOf(initialTheme) }
    var channelSpacing by remember { mutableStateOf(initialChannelSpacing) }
    var keypadBlockMode by remember { mutableStateOf(initialKeypadBlockMode) }
    var discoveryStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    SimpleDialogPanel(title = "Settings", width = 320.dp, onDismiss = onDismiss) {
        Column(
            Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(scrollState)
                .verticalScrollbar(scrollState, colors.border)
                .padding(end = 8.dp) // room for the scrollbar thumb so it doesn't sit on top of text
        ) {
            SectionLabel("SIMBRIEF")
            FieldLabel("SimBrief user ID")
            HandoffTextField(simbriefUserId, { simbriefUserId = it }, placeholder = "e.g. 123456", modifier = Modifier.fillMaxWidth())
            Box(Modifier.padding(top = 10.dp)) {
                FieldLabel("SimBrief username (fallback)")
            }
            HandoffTextField(simbriefUsername, { simbriefUsername = it }, placeholder = "optional", modifier = Modifier.fillMaxWidth())

            SectionLabel("APPEARANCE")
            ToggleRow(
                listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "☀ Light", ThemeMode.DARK to "☾ Dark"),
                theme
            ) { theme = it }

            SectionLabel("DEFAULT CHANNEL SPACING")
            ToggleRow(
                listOf(ChannelSpacing.KHZ_25 to "25 kHz", ChannelSpacing.KHZ_8_33 to "8.33 kHz"),
                channelSpacing
            ) { channelSpacing = it }

            SectionLabel("FREQUENCY KEYPAD")
            ToggleRow(
                listOf(KeypadBlockMode.BLOCK_INVALID to "Block invalid", KeypadBlockMode.ALLOW_ALL to "Allow all"),
                keypadBlockMode
            ) { keypadBlockMode = it }

            SectionLabel("PLUGIN CONNECTION")
            FieldLabel("Manual IP (if discovery fails)")
            HandoffTextField(host, { host = it }, placeholder = "192.168.1.42", modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Status: $connectionStatus",
                    fontSize = 10.sp,
                    color = colors.textMuted,
                    modifier = Modifier.weight(1f)
                )
                // Not in the static design mock (it has no live network to discover on), but a
                // real, working feature this app already has (HandoffDiscoveryClient) that the
                // protocol doc calls for as the IP field's fallback partner -- kept, not dropped.
                Text(
                    "Auto-detect",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent,
                    modifier = Modifier.clickable {
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
                    }
                )
            }
            if (discoveryStatus.isNotBlank()) {
                Text(discoveryStatus, fontSize = 10.sp, color = colors.textMuted, modifier = Modifier.padding(top = 2.dp))
            }

            Box(Modifier.padding(top = 14.dp)) {
                SaveButton {
                    onSave(
                        host.ifBlank { null },
                        simbriefUserId.ifBlank { null },
                        simbriefUsername.ifBlank { null },
                        theme,
                        channelSpacing,
                        keypadBlockMode
                    )
                    onDismiss()
                }
            }

            Box(Modifier.padding(top = 20.dp)) {
                SectionLabel("CREDITS")
            }
            credits.forEach { credit ->
                AboutRow(credit.label, credit.pillText) { uriHandler.openUri(credit.url) }
            }
            Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Contribute — sushi.at/vpilot-handoff",
                    fontSize = 11.sp,
                    color = colors.textMuted,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { uriHandler.openUri("https://github.com/sushiat/vpilot-handoff") }
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(label: String) {
    val colors = LocalHandoffColors.current
    Text(
        label,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.06f.em,
        color = colors.textMuted,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
    )
}

@Composable
private fun FieldLabel(label: String) {
    val colors = LocalHandoffColors.current
    Text(
        label,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.04f.em,
        color = colors.textMuted,
        modifier = Modifier.padding(bottom = 5.dp)
    )
}

@Composable
private fun <T> ToggleRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val colors = LocalHandoffColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (isSelected) colors.accent else colors.panelAlt, RoundedCornerShape(8.dp))
                    .clickable { onSelect(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) androidx.compose.ui.graphics.Color.White else colors.text
                )
            }
        }
    }
}

@Composable
private fun SaveButton(onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.accent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White)
    }
}

@Composable
private fun AboutRow(label: String, pillText: String, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, color = colors.textMuted, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .background(colors.panelAlt, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            Text(
                pillText,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.03f.em,
                color = colors.textMuted
            )
        }
    }
}
