package at.sushi.handoff.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import at.sushi.handoff.ChannelSpacing
import at.sushi.handoff.ConnectionStatus
import at.sushi.handoff.KeypadBlockMode
import at.sushi.handoff.ThemeMode
import at.sushi.handoff.network.HandoffDiscoveryClient
import at.sushi.handoff.ui.theme.HandoffTextField
import at.sushi.handoff.ui.theme.LocalHandoffColors
import at.sushi.handoff.ui.theme.verticalScrollbar
import kotlinx.coroutines.launch

private data class CreditRow(val label: String, val pillText: String?, val name: String, val url: String)

private val credits = listOf(
    CreditRow("Airport & FIR data", "CC BY-SA 4.0", "VATSpy", "https://github.com/vatsimnetwork/vatspy-data-project"),
    CreditRow("Sector boundaries", "CC BY-NC-SA 4.0", "VatGlasses", "https://github.com/lennycolton/vatglasses-data"),
    CreditRow("Live network data", null, "VATSIM Data Feed", "https://vatsim.dev"),
    CreditRow("Flight plan data", null, "SimBrief by Navigraph", "https://www.simbrief.com"),
    CreditRow("Pilot client", null, "vPilot", "https://vpilot.rosscarlson.dev")
)

private val contributeRows = listOf(
    CreditRow("GitHub", null, "sushi.at/vpilot-handoff", "https://github.com/sushiat/vpilot-handoff"),
    CreditRow("Flightsim.to", null, "sushiat", "https://flightsim.to/profile/sushiat")
)

/** Settings dialog -- redesigned per the updated `design_handoff_vatsim_companion` reference
 *  bundle's `settingsDialog` object (wide two-column layout, `width:min(640px,90vw)`,
 *  `max-height:88vh` with its own scroll -- the original single-column stack cut off below the
 *  IP field on real tablets). Left column: SimBrief, Appearance, Plugin Connection, Default
 *  Channel Spacing, Frequency Keypad, then a full-width Save button spanning both columns.
 *  Right column: Credits, then a separate Contribute section (GitHub, Flightsim.to rows in the
 *  same label/name style as Credits, just without a license pill).
 *
 *  Uses its own Dialog/BoxWithConstraints chrome rather than [SimpleDialogPanel] (fixed-width) or
 *  [KeypadDialogChrome] -- neither offers the responsive `min(640dp, 90% available width)` sizing
 *  this needs, and this is scoped to Settings only, not shared chrome other dialogs rely on. */
@Composable
fun SettingsDialog(
    connectionStatus: ConnectionStatus,
    initialHost: String,
    initialSimbriefUserId: String,
    initialSimbriefUsername: String,
    initialTheme: ThemeMode,
    initialChannelSpacing: ChannelSpacing,
    initialKeypadBlockMode: KeypadBlockMode,
    initialKeepScreenAwake: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        host: String?,
        simbriefUserId: String?,
        simbriefUsername: String?,
        theme: ThemeMode,
        channelSpacing: ChannelSpacing,
        keypadBlockMode: KeypadBlockMode,
        keepScreenAwake: Boolean
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
    var keepScreenAwake by remember { mutableStateOf(initialKeepScreenAwake) }
    var discoveryStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Reference: width:min(640px,90vw) -- clamps to whichever available window is
            // narrower, so this still degrades sensibly in split-screen's narrower app pane.
            val panelWidth = minOf(maxWidth * 0.9f, 640.dp)
            val panelMaxHeight = maxHeight * 0.88f

            Column(
                Modifier
                    .width(panelWidth)
                    .heightIn(max = panelMaxHeight)
                    .background(colors.panel, RoundedCornerShape(16.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(horizontal = 22.dp, vertical = 20.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Settings", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.text)
                    Text(
                        "✕",
                        fontSize = 16.sp,
                        color = colors.text.copy(alpha = 0.5f),
                        modifier = Modifier.clickable(onClick = onDismiss)
                    )
                }

                Column(
                    Modifier
                        .padding(top = 14.dp)
                        .verticalScroll(scrollState)
                        .verticalScrollbar(scrollState, colors.border)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Column(Modifier.weight(1f)) {
                            SectionLabel("SIMBRIEF", topPadding = 0.dp)
                            FieldLabel("SimBrief user ID")
                            HandoffTextField(simbriefUserId, { simbriefUserId = it }, placeholder = "e.g. 123456", modifier = Modifier.fillMaxWidth())
                            Box(Modifier.padding(top = 10.dp)) {
                                FieldLabel("SimBrief username (fallback)")
                            }
                            HandoffTextField(simbriefUsername, { simbriefUsername = it }, placeholder = "optional", modifier = Modifier.fillMaxWidth())

                            SectionLabel("APPEARANCE")
                            ToggleRow(
                                listOf(
                                    ToggleOption(ThemeMode.SYSTEM, "System"),
                                    ToggleOption(ThemeMode.LIGHT, "Light"),
                                    ToggleOption(ThemeMode.DARK, "Dark")
                                ),
                                theme
                            ) { theme = it }

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
                                // Not in the static design mock (it has no live network to
                                // discover on), but a real, working feature this app already has
                                // (HandoffDiscoveryClient) that the protocol doc calls for as the
                                // IP field's fallback partner -- kept, not dropped.
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

                            SectionLabel("DEFAULT CHANNEL SPACING")
                            ToggleRow(
                                listOf(
                                    ToggleOption(ChannelSpacing.KHZ_25, "25 kHz"),
                                    ToggleOption(ChannelSpacing.KHZ_8_33, "8.33 kHz")
                                ),
                                channelSpacing
                            ) { channelSpacing = it }

                            SectionLabel("FREQUENCY KEYPAD")
                            ToggleRow(
                                listOf(
                                    ToggleOption(KeypadBlockMode.BLOCK_INVALID, "Block invalid", "Block inval"),
                                    ToggleOption(KeypadBlockMode.ALLOW_ALL, "Allow all")
                                ),
                                keypadBlockMode
                            ) { keypadBlockMode = it }

                            // Default on -- the primary use case is a tablet docked and wired
                            // into power in the cockpit for the whole flight, where Android's
                            // screen timeout is actively unwanted.
                            SectionLabel("KEEP SCREEN AWAKE")
                            ToggleRow(
                                listOf(
                                    ToggleOption(true, "Keep awake"),
                                    ToggleOption(false, "Allow sleep")
                                ),
                                keepScreenAwake
                            ) { keepScreenAwake = it }
                        }

                        Column(Modifier.weight(1f)) {
                            SectionLabel("CREDITS", topPadding = 0.dp)
                            credits.forEach { credit ->
                                AboutRow(credit.label, credit.name, credit.pillText) { uriHandler.openUri(credit.url) }
                            }
                            SectionLabel("CONTRIBUTE")
                            contributeRows.forEach { row ->
                                AboutRow(row.label, row.name, row.pillText) { uriHandler.openUri(row.url) }
                            }
                        }
                    }

                    Box(Modifier.padding(top = 18.dp)) {
                        SaveButton {
                            onSave(
                                host.ifBlank { null },
                                simbriefUserId.ifBlank { null },
                                simbriefUsername.ifBlank { null },
                                theme,
                                channelSpacing,
                                keypadBlockMode,
                                keepScreenAwake
                            )
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(label: String, topPadding: androidx.compose.ui.unit.Dp = 14.dp) {
    val colors = LocalHandoffColors.current
    Text(
        label,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.06f.em,
        color = colors.textMuted,
        modifier = Modifier.padding(top = topPadding, bottom = 8.dp)
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

private data class ToggleOption<T>(val value: T, val label: String, val shortLabel: String? = null)

/** Below this per-button width, buttons switch to their [ToggleOption.shortLabel] (if any) -- the
 *  split-screen app pane can shrink down to ~20% of the tablet's width, squeezing this dialog's
 *  two-column layout tight enough that "Block invalid" doesn't fit its button comfortably. */
private val ToggleShortLabelThreshold = 90.dp

@Composable
private fun <T> ToggleRow(options: List<ToggleOption<T>>, selected: T, onSelect: (T) -> Unit) {
    val colors = LocalHandoffColors.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gapCount = options.size - 1
        val perButtonWidth = (maxWidth - 8.dp * gapCount) / options.size
        val useShortLabels = perButtonWidth < ToggleShortLabelThreshold

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val isSelected = option.value == selected
                val label = if (useShortLabels) option.shortLabel ?: option.label else option.label
                Box(
                    Modifier
                        .weight(1f)
                        .background(if (isSelected) colors.accent else colors.panelAlt, RoundedCornerShape(8.dp))
                        .clickable { onSelect(option.value) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        color = if (isSelected) androidx.compose.ui.graphics.Color.White else colors.text
                    )
                }
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

/** Matches the reference's `aboutRowStyle` (label above, muted, 11sp) + `attributionRowStyle`
 *  (name + optional license pill in a row below, clickable as a whole -- the link's `<a>`). The
 *  pill uses the reference's flat `attributionPillStyle` (solid `t.border` fill, 6px radius --
 *  matches the app's other badges, not a rounded-pill shape), only shown when a license applies. */
@Composable
private fun AboutRow(label: String, name: String, pillText: String?, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clickable(onClick = onClick)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.textMuted)
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.text)
            if (pillText != null) {
                Box(
                    Modifier
                        .background(colors.border, RoundedCornerShape(6.dp))
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
    }
}
