package at.sushi.handoff.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
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
import at.sushi.handoff.HandoffConnectionService
import at.sushi.handoff.HandoffState
import at.sushi.handoff.KeypadBlockMode
import at.sushi.handoff.ThemeMode
import at.sushi.handoff.UpdateInterval
import at.sushi.handoff.network.HandoffDiscoveryClient
import at.sushi.handoff.protocol.SetDebugModeCommand
import at.sushi.handoff.ui.theme.HandoffTextField
import at.sushi.handoff.ui.theme.LocalHandoffColors
import at.sushi.handoff.ui.theme.verticalScrollbar
import kotlinx.coroutines.launch

// Shared across every HandoffTextField in this dialog so they render at an identical height --
// previously each one sized itself from HandoffTextField's own default padding/font, which read
// as inconsistent since this dialog fixes an explicit height rather than letting it fall out of
// content.
private val SettingsFieldHeight = 46.dp
private val SettingsFieldFontSize = 15.sp

private data class CreditRow(val label: String, val pillText: String?, val name: String, val url: String)

private val credits = listOf(
    CreditRow("Airport & FIR data", "CC BY-SA 4.0", "VATSpy", "https://github.com/vatsimnetwork/vatspy-data-project"),
    CreditRow("Sector boundaries", "CC BY-NC-SA 4.0", "VatGlasses", "https://github.com/lennycolton/vatglasses-data"),
    CreditRow("Live network data", null, "VATSIM Data Feed", "https://vatsim.dev"),
    CreditRow("Flight plan data", null, "SimBrief by Navigraph", "https://www.simbrief.com"),
    CreditRow("Pilot client", null, "vPilot", "https://vpilot.rosscarlson.dev"),
    CreditRow("UI font", "OFL 1.1", "Roboto Mono", "https://fonts.google.com/specimen/Roboto+Mono")
)

private val contributeRows = listOf(
    CreditRow("GitHub", null, "sushi.at/vpilot-handoff", "https://github.com/sushiat/vpilot-handoff"),
    CreditRow("Flightsim.to", null, "sushiat", "https://flightsim.to/profile/sushiat"),
    CreditRow("iOS client", null, "MANFahrer-GF/vpilot-handoff-ios", "https://github.com/MANFahrer-GF/vpilot-handoff-ios")
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
    initialUpdateInterval: UpdateInterval,
    initialIgnoredDeviceCount: Int,
    onClearIgnoredDevices: () -> Unit,
    onDismiss: () -> Unit,
    onOpenRowColorEditor: () -> Unit,
    onSave: (
        host: String?,
        simbriefUserId: String?,
        simbriefUsername: String?,
        theme: ThemeMode,
        channelSpacing: ChannelSpacing,
        keypadBlockMode: KeypadBlockMode,
        updateInterval: UpdateInterval
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
    var updateInterval by remember { mutableStateOf(initialUpdateInterval) }
    var discoveryStatus by remember { mutableStateOf("") }
    var ignoredDeviceCount by remember { mutableStateOf(initialIgnoredDeviceCount) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Issue #65 -- hidden debug-mode toggle, same 7-tap pattern as Android's own build-number
    // developer options. Deliberately not discoverable: no visual affordance hints this is
    // tappable, and there's no separate "enabled" toast/dialog -- the title's own " - debug
    // active" suffix (below) is the only in-the-moment confirmation, and only for the rest of
    // this dialog session. Resets the tap count on any gap over 2s so idle taps elsewhere in the
    // dialog session can't slowly accumulate toward it.
    var debugTapCount by remember { mutableStateOf(0) }
    var lastDebugTapAt by remember { mutableStateOf(0L) }
    var debugActivatedThisDialogSession by remember { mutableStateOf(false) }
    val onSettingsTitleTap = {
        val now = System.currentTimeMillis()
        if (now - lastDebugTapAt > 2000L) debugTapCount = 0
        lastDebugTapAt = now
        debugTapCount++
        if (debugTapCount >= 7) {
            debugTapCount = 0
            if (!HandoffState.debugModeEnabled.value) {
                HandoffState.setDebugModeEnabled(true)
                HandoffConnectionService.instance?.sendCommand(SetDebugModeCommand(enabled = true))
            }
            debugActivatedThisDialogSession = true
        }
    }

    // No explicit Save button -- every close path (✕, back gesture, tap-outside, which
    // onDismissRequest already covers uniformly) saves once on the way out instead. No
    // debouncing needed since this only ever fires a single time, right at close.
    val saveAndDismiss = {
        onSave(
            host.ifBlank { null },
            simbriefUserId.ifBlank { null },
            simbriefUsername.ifBlank { null },
            theme,
            channelSpacing,
            keypadBlockMode,
            updateInterval
        )
        onDismiss()
    }

    Dialog(onDismissRequest = saveAndDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Reference: width:min(640px,90vw) -- clamps to whichever available window is
            // narrower, so this still degrades sensibly in split-screen's narrower app pane.
            val panelWidth = minOf(maxWidth * 0.9f, 640.dp)
            val panelMaxHeight = maxHeight * 0.88f
            // Below this the two-column layout has no room left to be worth it -- the right
            // column (Credits/Contribute) is the least essential content here, so it's simplest
            // to just drop it entirely rather than trying to squeeze both columns narrower. The
            // Save button isn't part of this Row at all (see below), so it already stays
            // full-width/single-column regardless of this split. Tuned on-device: 415.8dp (a
            // 462dp-wide split-screen pane) was called out as the narrowest comfortable
            // two-column width, so the cutoff sits just above that.
            val singleColumn = panelWidth < 420.dp

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
                    Text(
                        "Settings" + if (debugActivatedThisDialogSession) " - debug active" else "",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.text,
                        // No ripple/indication -- any visible feedback before the 7th tap would
                        // give away that this is tappable at all, defeating the point of a
                        // not-randomly-discoverable toggle (issue #65).
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onSettingsTitleTap
                        )
                    )
                    Text(
                        "✕",
                        fontSize = 16.sp,
                        color = colors.text.copy(alpha = 0.5f),
                        modifier = Modifier.clickable(onClick = saveAndDismiss)
                    )
                }

                Column(
                    Modifier
                        .padding(top = 14.dp)
                        .verticalScroll(scrollState)
                        .verticalScrollbar(scrollState, colors.border)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Column(if (singleColumn) Modifier.fillMaxWidth() else Modifier.weight(1f)) {
                            SectionLabel("SIMBRIEF", topPadding = 0.dp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    FieldLabel("User ID")
                                    HandoffTextField(simbriefUserId, { simbriefUserId = it }, placeholder = "e.g. 123456", fontSize = SettingsFieldFontSize, modifier = Modifier.fillMaxWidth().height(SettingsFieldHeight))
                                }
                                Column(Modifier.weight(1f)) {
                                    FieldLabel("Username (fallback)")
                                    HandoffTextField(simbriefUsername, { simbriefUsername = it }, placeholder = "optional", fontSize = SettingsFieldFontSize, modifier = Modifier.fillMaxWidth().height(SettingsFieldHeight))
                                }
                            }

                            SectionLabel("APPEARANCE")
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f)) {
                                    ToggleRow(
                                        listOf(
                                            ToggleOption(ThemeMode.SYSTEM, "System"),
                                            ToggleOption(ThemeMode.LIGHT, "Light"),
                                            ToggleOption(ThemeMode.DARK, "Dark")
                                        ),
                                        theme
                                    ) {
                                        // Applied live (not just carried into the onSave payload
                                        // at close) -- feedback: adjusting row colors right after
                                        // switching theme required closing Settings first just to
                                        // see the new theme take effect.
                                        theme = it
                                        HandoffState.setTheme(it)
                                    }
                                }
                                // Issue #21 -- opens the row-color theme editor. A small icon
                                // button tucked next to the System/Light/Dark toggle rather than
                                // a new full-width row, per the issue's own entry-point proposal.
                                Box(
                                    Modifier
                                        .background(colors.panelAlt, RoundedCornerShape(8.dp))
                                        .clickable(onClick = onOpenRowColorEditor)
                                        .padding(horizontal = 10.dp, vertical = 9.dp)
                                ) {
                                    Text("🎨", fontSize = 14.sp)
                                }
                            }

                            SectionLabel("PLUGIN CONNECTION")
                            FieldLabel("Manual IP (if discovery fails)")
                            HandoffTextField(host, { host = it }, placeholder = "192.168.1.42[:port]", fontSize = SettingsFieldFontSize, modifier = Modifier.fillMaxWidth().height(SettingsFieldHeight))
                            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Status: $connectionStatus",
                                    fontSize = 12.sp,
                                    // CONNECTING isn't named in either color -- it's neither a
                                    // success nor a failure state, just leave it muted.
                                    color = when (connectionStatus) {
                                        ConnectionStatus.CONNECTED -> colors.ok
                                        ConnectionStatus.DISCONNECTED -> outOfBandRed
                                        ConnectionStatus.CONNECTING -> colors.textMuted
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                // Not in the static design mock (it has no live network to
                                // discover on), but a real, working feature this app already has
                                // (HandoffDiscoveryClient) that the protocol doc calls for as the
                                // IP field's fallback partner -- kept, not dropped.
                                Text(
                                    "Auto-detect",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.accent,
                                    modifier = Modifier.clickable {
                                        discoveryStatus = "Searching…"
                                        scope.launch {
                                            val found = HandoffDiscoveryClient().discover()?.host
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
                            // Issue #15 -- no per-device management UI yet, just a way back from
                            // "Ignore this machine" (PairingCodeDialog's cancel flow) without
                            // that being a one-way door. Only shown once there's actually
                            // something to clear.
                            if (ignoredDeviceCount > 0) {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Ignored devices ($ignoredDeviceCount)",
                                        fontSize = 12.sp,
                                        color = colors.textMuted
                                    )
                                    Text(
                                        "Clear",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.accent,
                                        modifier = Modifier.clickable {
                                            onClearIgnoredDevices()
                                            ignoredDeviceCount = 0
                                        }
                                    )
                                }
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

                            // Issue #88 -- how often the plugin polls the sim and broadcasts to us.
                            // The plugin owns the actual cadences each tier maps to and is the
                            // authoritative source of the current value (echoed via
                            // subsystemStatus); this just picks the tier and sends it down.
                            SectionLabel("UPDATE INTERVAL")
                            ToggleRow(
                                listOf(
                                    ToggleOption(UpdateInterval.FAST, "Fast"),
                                    ToggleOption(UpdateInterval.NORMAL, "Normal"),
                                    ToggleOption(UpdateInterval.SLOW, "Slow")
                                ),
                                updateInterval
                            ) { updateInterval = it }
                        }

                        if (!singleColumn) {
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
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(label: String, topPadding: androidx.compose.ui.unit.Dp = 14.dp) {
    val colors = LocalHandoffColors.current
    Column(Modifier.padding(top = topPadding)) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.06f.em,
            color = colors.textMuted,
            // Tight -- the divider right below now marks the section's start, so this no longer
            // needs to carry the whole gap to the label text above it on its own.
            modifier = Modifier.padding(bottom = 3.dp)
        )
        HorizontalDivider(color = colors.border, modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun FieldLabel(label: String) {
    val colors = LocalHandoffColors.current
    Text(
        label,
        fontSize = 12.sp,
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
                        // 12->14sp grew the label's own line height by ~2dp, so padding alone had
                        // to drop by ~4dp (not 2dp) for the outer box to actually end up 2dp
                        // shorter overall, not just unchanged.
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        color = if (isSelected) androidx.compose.ui.graphics.Color.White else colors.text
                    )
                }
            }
        }
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
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.textMuted)
        Row(
            // Tighter gap between the label ("Flightsim.to") and its name/link ("sushiat") below.
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.text)
            if (pillText != null) {
                Box(
                    Modifier
                        .background(colors.border, RoundedCornerShape(6.dp))
                        // 9->10sp grew the pill text's own line height by ~1dp, so padding needed
                        // to drop by ~3dp (not 2dp) for the pill to actually end up shorter overall.
                        .padding(horizontal = 9.dp, vertical = 2.5.dp)
                ) {
                    Text(
                        pillText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.03f.em,
                        color = colors.textMuted
                    )
                }
            }
        }
    }
}
