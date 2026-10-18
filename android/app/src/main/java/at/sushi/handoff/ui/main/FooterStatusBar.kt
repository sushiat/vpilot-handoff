package at.sushi.handoff.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenLockLandscape
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import at.sushi.handoff.ui.theme.RobotoMono
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.ConnectionStatus
import at.sushi.handoff.protocol.SubsystemStatusMessage
import at.sushi.handoff.ui.theme.LocalHandoffColors
import at.sushi.handoff.util.CdmSlotDisplay
import at.sushi.handoff.util.CdmWindowPhase
import at.sushi.handoff.util.chipText
import at.sushi.handoff.util.windowSummary

/** The controller list's footer: a "face" row (connection dot + status line, refresh + settings
 *  icons) that's the first/top child of this container, with the expandable detail content
 *  (subsystem status rows, then the plugin info line) placed *below* it, last. Because this
 *  whole container sits at the bottom of MainScreen's Column (below the weighted controller
 *  list), growing it downward-in-DOM-order is what actually pushes the face row visually
 *  *upward* on screen when expanded -- the container's own bottom edge stays pinned to the
 *  screen's bottom, so adding content below the face row grows the block upward as a whole,
 *  carrying the face row with it, rather than the face row itself relocating. Per issue #13
 *  screen 1's footer, the sub-connection rows / plugin version / address now come from the
 *  plugin's `subsystemStatus` message and the persisted host pref; latency is measured
 *  client-side from the ping/pong exchange (see HandoffConnectionService) -- see docs/protocol.md. */
@Composable
fun FooterStatusBar(
    connectionStatus: ConnectionStatus,
    // Issue #169 -- the "marked as demo" spot. Overrides the status dot/label below regardless of
    // connectionStatus, which stays truthfully DISCONNECTED while demo mode is on (there is no
    // real plugin link) -- this is purely a display override, not a claim that a connection
    // exists.
    demoModeEnabled: Boolean,
    origin: String?,
    destination: String?,
    // True when the SimBrief and VATSIM-filed plans disagree, or the pilot is connected with
    // nothing filed at all (docs/protocol.md's flightPlan message) -- see MainScreen.kt for the
    // detection logic. Drives the red exclamation icon next to the route.
    flightPlanWarning: Boolean,
    // Narrower than flightPlanWarning above -- true only for an actual SimBrief/VATSIM
    // disagreement (not "nothing filed yet"), since that's the only flight-plan condition this
    // dot's summary treats as "not good" (see this function's overallStatus comment).
    flightPlanMismatch: Boolean,
    // Issue #68 -- the plugin's own on-ground sanity gate: true when ownship's position doesn't
    // match the filed origin's coordinates, even if SimBrief and VATSIM fully agree with each
    // other. Drives its own "WRONG ORIGIN" row in the expanded drawer below, distinct from the
    // SimBrief/VATSIM MISSING/mismatch rows.
    originMismatch: Boolean,
    // True when the VATSIM feed entry matched to our own callsign carries a different cid than
    // our own connection -- a callsign lookup alone can't rule out the feed briefly returning a
    // stale/wrong entry for that callsign string. Drives its own drawer row, distinct from the
    // other flight-plan warnings above -- informational only, doesn't change which plan is used.
    vatsimCidMismatch: Boolean,
    // The live vPilot connection callsign, and both independent flight-plan views -- shown as
    // their own always-visible rows in the expanded drawer regardless of whether they agree, so
    // the pilot can see exactly what each source says instead of just being told "mismatch".
    activeCallsign: String?,
    simbriefOrigin: String?,
    simbriefDestination: String?,
    // True once a SimBrief fetch has had a real chance to succeed (WebSocket connected) but still
    // hasn't -- see MainScreen.kt's rememberSustained. Only then does the row read "MISSING"
    // instead of the placeholder "---- -> ----", which would otherwise flash on every launch.
    simbriefMissing: Boolean,
    vatsimOrigin: String?,
    vatsimDestination: String?,
    vatsimMissing: Boolean,
    // Issue #143 -- read-only VDGS/TOBT status for the current departure, polled directly from
    // api.viffsys.com (a separate concern from everything else this footer shows, none of which
    // comes from the plugin). Null whenever there's nothing to show -- see HandoffState.cdmSlot's
    // doc for exactly when that is. Drives the small collapsed-row chip and its own expanded-drawer
    // detail row, same "only shown when it fires" treatment as originMismatch/vatsimCidMismatch.
    cdmSlot: CdmSlotDisplay?,
    // The host actually used for the current/last connection attempt (HandoffState.resolvedHost)
    // -- not the raw manual-IP preference, which stays null forever for anyone relying on UDP
    // discovery instead of typing an IP in Settings, even while genuinely connected.
    address: String?,
    subsystemStatus: SubsystemStatusMessage,
    // The one combined icon the collapsed row has room for, reduced from every currently-visible
    // operation (docs/protocol.md's operationProgress, several can be active/lingering at once --
    // see MainScreen.kt's combineOperationIndicator) -- null when nothing's visible at all. Shown
    // in the same slot flightPlanWarning's triangle uses, hidden while expanded (the drawer shows
    // each operation's own row instead, see visibleOperations below).
    operationIndicator: at.sushi.handoff.OperationIndicator?,
    // Every currently-visible operation (MainScreen.kt's rememberVisibleOperations already
    // dropped anything past its display window) -- one drawer row each, independent of the single
    // combined icon above.
    visibleOperations: List<at.sushi.handoff.OperationProgressState>,
    latencyMs: Long?,
    expanded: Boolean,
    keepScreenAwake: Boolean,
    onToggleExpanded: () -> Unit,
    // Issue #171 follow-up -- the manual refreshes live on their drawer rows (SimBrief, VATSIM feed,
    // start-up window); the collapsed row's old refresh icon is now the connect/disconnect shortcut.
    onRefresh: () -> Unit,
    onRefreshVatsimFeed: () -> Unit,
    onRefreshCdm: () -> Unit,
    onOpenSettings: () -> Unit,
    // Issue #143 -- opens the embedded VDGS site (see CdmWebDialog) to set/edit the TOBT.
    onOpenCdm: () -> Unit,
    onToggleKeepScreenAwake: () -> Unit,
    // Issue #171 -- hidden entirely (false) on a plugin that predates connectNetwork/
    // disconnectNetwork, and in demo mode where there's no plugin to ask.
    networkControlAvailable: Boolean,
    onConnectNetwork: () -> Unit,
    onDisconnectNetwork: () -> Unit
) {
    val colors = LocalHandoffColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.panel)
    ) {
        HorizontalDivider(color = colors.border) // the reference's border-top only, not a full box
        // Pinned to the same 64dp height as the expanded drawer's detail-line row and the chat
        // compose bar -- this row's height used to fall out implicitly from IconButton's 48dp
        // touch target + this padding (48+2*8=64dp); switching to tight 30dp icon boxes dropped
        // that to 46dp and threw the drawer/compose-bar border alignment off again.
        // contentAlignment is required here -- unlike the plain Box used elsewhere in this file,
        // BoxWithConstraints doesn't center its content by default, so without this the Row (only
        // as tall as its own icon boxes, shorter than the fixed 64dp) sat pinned to the top of
        // this box instead of vertically centered in it.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clickable(onClick = onToggleExpanded),
            contentAlignment = Alignment.Center
        ) {
        // Below this width the "Connected"/"Disconnected" label drops, leaving just the route --
        // if that still doesn't fully fit, it's fine for it to ellipsize (the route itself is
        // still legible), it just must never wrap onto a second line. Reuses TopBar's own narrow
        // threshold rather than a separately-tuned value, so both bars agree on what "narrow"
        // means instead of drifting out of sync with each other.
        val showStatusLabel = maxWidth >= NarrowTopBarThreshold
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Summarizes every sub-element into one dot rather than just this WebSocket's own
            // state: red means the plugin connection itself is down (the one prerequisite for
            // everything else); amber ("attention") means that connection is up but something
            // underneath isn't -- RadioHost/Simulator/VatsimInfo unreachable, or the SimBrief and
            // VATSIM-filed plans actually disagree; green means the plugin's up and everything
            // else checks out. SimBrief being absent entirely is *not* degraded (it's optional --
            // pre-connection, or the pilot doesn't use it), only a SimBrief plan that's present but
            // wrong is -- that's exactly flightPlanMismatch, not the broader flightPlanWarning
            // (which also covers "nothing filed on VATSIM yet", a separate, narrower warning
            // surfaced via the exclamation icon instead).
            val degraded = !subsystemStatus.radioHostConnected ||
                !subsystemStatus.simulatorConnected ||
                !subsystemStatus.vatsimDataFeedConnected ||
                flightPlanMismatch
            val dotColor = when {
                demoModeEnabled -> colors.attention
                connectionStatus != ConnectionStatus.CONNECTED -> at.sushi.handoff.ui.dialogs.outOfBandRed
                degraded -> colors.attention
                else -> colors.ok
            }
            Box(Modifier.size(10.dp).background(dotColor, CircleShape))
            // The doc's full sentence ("Connected to Handoff vPilot plugin, flying from...")
            // reads fine in the design mock's wide preview but wraps awkwardly mid-word next to
            // the refresh/settings icons on a real tablet -- shortened to status + route. Once
            // there's an actual callsign, "Connected" itself is redundant with it (a callsign only
            // exists once truly connected) -- dropped so the line doesn't grow every time it's the
            // one piece of the layout most likely to need retuning across widths.
            val statusLabel = when {
                demoModeEnabled -> "DEMO"
                connectionStatus == ConnectionStatus.CONNECTED -> activeCallsign ?: "Connected"
                connectionStatus == ConnectionStatus.CONNECTING -> "Connecting"
                else -> "Disconnected"
            }
            // Unlike every other placeholder in this footer/drawer, "---- -> ----" reads badly --
            // there's no real airport code sitting next to it to make the dashes look like part
            // of a pattern, it's just two dash-pairs and an arrow floating on their own. Dropped
            // entirely (falling back to just the connection/callsign label) when neither side is
            // known yet; kept once at least one side is, same as before.
            val hasRoute = origin != null || destination != null
            val route = "${origin ?: "----"} → ${destination ?: "----"}"
            val statusText = when {
                !hasRoute -> statusLabel
                showStatusLabel -> "$statusLabel · $route"
                else -> route
            }
            // The Text itself gets weight(1f, fill = false) rather than the outer Row -- that lets
            // it size down to its own (possibly short) natural width within the space available,
            // so the warning icon sits immediately after the visible text instead of being pushed
            // all the way to this row's far edge by the Text's own weight.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    statusText,
                    fontSize = 13.sp,
                    color = colors.text,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (flightPlanWarning) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = "Flight plan mismatch or not filed",
                        tint = colors.attention,
                        modifier = Modifier.padding(start = 4.dp).size(14.dp)
                    )
                } else if (operationIndicator != null && !expanded) {
                    // Same slot as the warning triangle above -- the two are mutually exclusive
                    // attention icons; if a mismatch were ever flagged mid-sync, the triangle
                    // wins since it's the more actionable one. Hidden while expanded: the
                    // drawer's own rows (below) show each operation's icon there instead, so
                    // nothing's shown in both places at once.
                    OperationStatusIcon(
                        indicator = operationIndicator,
                        size = 14.dp,
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            // Issue #143 -- on the narrowest layout (where the status label and callsign already
            // dropped) the chip shrinks to just the dot and countdown instead of disappearing.
            if (cdmSlot != null) {
                CdmChip(cdmSlot, compact = !showStatusLabel, onClick = onOpenCdm)
            }
            // Tight boxes with a 2px gap, same pattern as ControllerList's pin/message icons --
            // Material3's IconButton reserves a 48dp touch target plus its own internal padding,
            // which pushed these icons much further apart than intended. Sized up while there's
            // room for the full "Connected · route" label (showStatusLabel, same narrow-width
            // threshold as everywhere else in this bar); once that drops, these shrink back to
            // their original 30dp/20dp so they don't crowd the now-tighter row.
            val iconBoxSize = if (showStatusLabel) 36.dp else 30.dp
            val iconSize = if (showStatusLabel) 24.dp else 20.dp
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                // Toggle, not a dialog-opener -- tint reflects on/off state the same way the top
                // bar's Mode C badge does, rather than opening anything. Default on: the primary
                // use case is a tablet docked and wired into power in the cockpit for the whole
                // flight, where Android's screen timeout is actively unwanted.
                Box(
                    Modifier.size(iconBoxSize).clickable(onClick = onToggleKeepScreenAwake),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.ScreenLockLandscape,
                        contentDescription = if (keepScreenAwake) "Keep screen awake: on" else "Keep screen awake: off",
                        tint = if (keepScreenAwake) colors.accent else colors.textMuted,
                        modifier = Modifier.size(iconSize)
                    )
                }
                // Issue #171 follow-up -- the same plug icon as the drawer's Network row, so the two read
                // as one feature: it shows the VATSIM state (plugged in = on the network) and taps to
                // connect or disconnect (Disconnect still asks first, see MainScreen). Hidden when the
                // plugin can't do it (too old, link down, demo mode).
                if (networkControlAvailable) {
                    val onNetwork = activeCallsign != null
                    Box(
                        Modifier.size(iconBoxSize).clickable(onClick = if (onNetwork) onDisconnectNetwork else onConnectNetwork),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            networkIcon(onNetwork),
                            contentDescription = if (onNetwork) "Disconnect from VATSIM" else "Connect to VATSIM",
                            // Colours the action, like its icon: green to plug in, red to unplug -- a
                            // green disconnect button read as "all good" on a button that leaves the network.
                            tint = if (onNetwork) at.sushi.handoff.ui.dialogs.outOfBandRed else colors.ok,
                            modifier = Modifier.size(iconSize)
                        )
                    }
                }
                Box(
                    Modifier.size(iconBoxSize).clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = colors.textMuted, modifier = Modifier.size(iconSize))
                }
            }
        }
        }

        if (expanded) {
            HorizontalDivider(color = colors.border)
            // The drawer's width, measured here rather than at the top of the footer so it is the actual
            // width the rows get -- below the same narrow threshold the collapsed row uses, the action
            // buttons drop their text labels and keep only the icon.
            val density = androidx.compose.ui.platform.LocalDensity.current
            var compactActions by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { compactActions = with(density) { it.width.toDp() } < NarrowTopBarThreshold }
                    .clickable(onClick = onToggleExpanded)
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                visibleOperations.forEach { state -> OperationProgressRow(state) }
                SubsystemStatusRow(
                    if (demoModeEnabled) "Demo mode -- no plugin" else "Connected to Handoff vPilot plugin",
                    demoModeEnabled || connectionStatus == ConnectionStatus.CONNECTED
                )
                // Issue #169 -- the only way out of demo mode (Settings has no toggle for it);
                // only present while demo mode is on.
                if (demoModeEnabled) {
                    androidx.compose.material3.Text(
                        "Exit demo mode",
                        color = colors.accent,
                        fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier
                            .clickable { at.sushi.handoff.HandoffConnectionService.instance?.disableDemoMode() }
                            .padding(vertical = 4.dp)
                    )
                }
                SubsystemStatusRow("Connected to RadioHost", subsystemStatus.radioHostConnected)
                SubsystemStatusRow("Connected to Simulator", subsystemStatus.simulatorConnected)
                SubsystemStatusRow("Connected to Vatsim Info", subsystemStatus.vatsimDataFeedConnected)
                SubsystemStatusRow("Fetched Simbrief flight plan", subsystemStatus.simbriefFetched)
                // Always shown (not just on mismatch) -- both flight-plan sources plus the actual
                // connection callsign, so the pilot can see exactly what each source says rather
                // than just being told "mismatch" with no detail. Highlighted in colors.attention
                // when they disagree (or nothing's filed at all), same signal as the collapsed
                // row's exclamation icon.
                // Issue #171 -- labelled "Network", not "Connected": the footer's other
                // "Connected ..." rows describe the plugin link, this one is the VATSIM session.
                // Lives in the drawer (behind the expand tap) so a mid-flight mis-tap can't
                // disconnect, and the action itself still asks for confirmation.
                if (networkControlAvailable) {
                    NetworkControlRow(
                        callsign = activeCallsign,
                        compact = compactActions,
                        onConnect = onConnectNetwork,
                        onDisconnect = onDisconnectNetwork
                    )
                }
                FlightPlanDetailRow("Active callsign", activeCallsign ?: "----", flightPlanWarning)
                FlightPlanDetailRow(
                    "SimBrief",
                    if (simbriefMissing) "MISSING" else "${simbriefOrigin ?: "----"} → ${simbriefDestination ?: "----"}",
                    flightPlanWarning,
                    refreshDescription = "Refresh SimBrief flight plan",
                    compactActions = compactActions,
                    onRefresh = if (demoModeEnabled) null else onRefresh
                )
                FlightPlanDetailRow(
                    "VATSIM",
                    if (vatsimMissing) "MISSING" else "${vatsimOrigin ?: "----"} → ${vatsimDestination ?: "----"}",
                    flightPlanWarning,
                    refreshDescription = "Refresh VATSIM data feed",
                    compactActions = compactActions,
                    // Only while on the network: the plugin's feed poll runs only then, so there is
                    // nothing to refresh before that (same idea as the start-up window's refresh, which
                    // only exists while there is a slot).
                    onRefresh = if (networkControlAvailable && activeCallsign != null) onRefreshVatsimFeed else null
                )
                // Issue #143 -- only shown while there's a CDM slot to report at all (see
                // HandoffState.cdmSlot's doc for when that is). Unlike the plugin-sourced rows
                // above, this comes straight from api.viffsys.com.
                cdmSlot?.let { CdmDrawerRow(it, onClick = onOpenCdm, compactActions = compactActions, onRefresh = if (demoModeEnabled) null else onRefreshCdm) }
                // Issue #68 -- only shown when the plugin's on-ground sanity gate actually fires;
                // unlike the two rows above (always visible), this is purely a "something's wrong"
                // flag with no not-wrong state worth displaying.
                if (originMismatch) {
                    FlightPlanDetailRow("Route tracking", "WRONG ORIGIN", warning = true)
                }
                // Same "only shown when it fires" treatment as the row above -- the VATSIM feed
                // entry matched to our own callsign belongs to a different cid than ours.
                if (vatsimCidMismatch) {
                    FlightPlanDetailRow("VATSIM lookup", "CID MISMATCH", warning = true)
                }
            }
            // Pulled out of the padded Column above and placed as a direct child here instead --
            // it was nested inside that Column's own 14dp horizontal padding, so it rendered
            // visibly inset compared to the face row's and chat compose bar's dividers, which are
            // both full-width/edge-to-edge (placed before any padding is applied). This matches
            // that same edge-to-edge treatment.
            HorizontalDivider(color = colors.border, modifier = Modifier.padding(top = 8.dp))
            // This row is pinned to the same 64dp height as the face row ("Connected · LOWW ->
            // LOWI") -- it was previously sized from its own padding+text-line-height alone
            // (~35-40dp), noticeably shorter, which is what left its border out of alignment.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val versionLabel = subsystemStatus.pluginVersion?.let { "v$it" } ?: "v?"
                val latencyLabel = latencyMs?.let { "${it}ms" } ?: "--ms"
                Column {
                    // Split across two lines -- at the larger font size below, "vPilot plugin
                    // vX.X.X · ws://host:port · Xms" all on one line ran past the row's right edge.
                    Text(
                        "vPilot plugin $versionLabel",
                        fontSize = 14.5.sp,
                        fontFamily = RobotoMono,
                        color = colors.textMuted
                    )
                    Text(
                        "${address?.let { "ws://$it:48765" } ?: "not connected"} · $latencyLabel",
                        fontSize = 14.5.sp,
                        fontFamily = RobotoMono,
                        color = colors.textMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkControlRow(callsign: String?, compact: Boolean, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val colors = LocalHandoffColors.current
    val connected = callsign != null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Network",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textMuted,
            modifier = Modifier.widthIn(min = 90.dp)
        )
        // One line always -- on the narrow layout the value used to wrap a character or two at a time
        // ("Not / conn / ecte / d") once the action button took its share of the row. The callsign
        // alone is enough there (the drawer's "Active callsign" row names it too).
        Text(
            when {
                !connected -> "Not connected"
                compact -> callsign!!
                else -> "Connected as $callsign"
            },
            fontSize = 11.5.sp,
            fontFamily = RobotoMono,
            color = colors.textMuted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // Same look as the rows' refresh buttons, and the plug icon the collapsed row's shortcut uses,
        // so the pilot sees the shortcut and this as one feature.
        DrawerActionButton(
            icon = networkIcon(connected),
            label = if (connected) "Disconnect" else "Connect...",
            description = if (connected) "Disconnect from VATSIM" else "Connect to VATSIM",
            compact = compact,
            onClick = if (connected) onDisconnect else onConnect
        )
    }
}
// The one place the connect/disconnect icons are chosen (collapsed shortcut and drawer row both use
// it): a plug in / plug out pair, easy to swap for another Material icon pair. The icon shows the
// action the button performs -- plug in to connect, plug out to disconnect -- so it always matches
// its label; the collapsed shortcut's colour is what shows the current state.
private fun networkIcon(onNetwork: Boolean) = if (onNetwork) Icons.Filled.PowerOff else Icons.Filled.Power

@Composable
private fun FlightPlanDetailRow(
    label: String,
    value: String,
    warning: Boolean,
    // Issue #171 follow-up -- an optional refresh icon right after the value (null = none), so the
    // pilot can re-poll that one source without leaving the drawer.
    refreshDescription: String = "Refresh",
    compactActions: Boolean = false,
    onRefresh: (() -> Unit)? = null
) {
    val colors = LocalHandoffColors.current
    // fillMaxWidth + the value taking the free space (weight below) pushes an action button to the
    // row's right edge, so every row's button lands in one right-aligned column -- under the header's
    // own icon cluster -- whatever the value's length.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (warning) colors.attention else colors.textMuted,
            modifier = Modifier.widthIn(min = 90.dp)
        )
        Text(
            value,
            fontSize = 11.5.sp,
            fontFamily = RobotoMono,
            color = if (warning) colors.attention else colors.textMuted,
            modifier = Modifier.weight(1f)
        )
        if (onRefresh != null) {
            DrawerActionButton(Icons.Filled.Refresh, "Refresh", refreshDescription, compactActions, onRefresh)
        }
    }
}

/** The one style for every action in the drawer (the rows' refresh buttons and the Network row's
 *  connect/disconnect): accent-coloured icon plus a short label, or just the icon when [compact]
 *  (the narrow layout, where the label would squeeze the row's own text). */
@Composable
private fun DrawerActionButton(icon: ImageVector, label: String, description: String, compact: Boolean, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    Row(
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // The text label already names the action; the description is only needed for the icon alone.
        Icon(icon, contentDescription = if (compact) description else null, tint = colors.accent, modifier = Modifier.size(16.dp))
        if (!compact) {
            Text(label, color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
    }
}

/** Re-derives [slot]'s time-dependent fields every second (the poll that produced it only runs
 *  once a minute, but the window is only a few minutes wide). */
@Composable
private fun rememberTickingCdmSlot(slot: CdmSlotDisplay): CdmSlotDisplay {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    return slot.at(nowMillis)
}

/** The on-tablet start-up-window indicator (issues #143, #165): a colored dot plus a very short label
 *  (see [chipText]) -- yellow counting down to the window opening, green counting down to it
 *  closing, red once missed, grey while nothing anchors a window. */
@Composable
private fun CdmChip(initial: CdmSlotDisplay, compact: Boolean, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    val slot = rememberTickingCdmSlot(initial)
    val dotColor = when (slot.phase) {
        CdmWindowPhase.BEFORE -> colors.attention
        CdmWindowPhase.OPEN -> colors.ok
        CdmWindowPhase.MISSED -> at.sushi.handoff.ui.dialogs.outOfBandRed
        CdmWindowPhase.UNKNOWN -> colors.textMuted
    }
    val label = slot.chipText(compact)
    Row(
        Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(Modifier.size(10.dp).background(dotColor, CircleShape))
        Text(label, fontSize = 13.sp, color = colors.text, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun CdmDrawerRow(initial: CdmSlotDisplay, onClick: () -> Unit, compactActions: Boolean, onRefresh: (() -> Unit)?) {
    val slot = rememberTickingCdmSlot(initial)
    Column(Modifier.clickable(onClick = onClick)) {
    slot.sid?.let { sid ->
        FlightPlanDetailRow("Departure", listOfNotNull(slot.depRunway?.let { "RWY $it" }, "SID $sid").joinToString(" · "), warning = false)
    }
    FlightPlanDetailRow(
        "Start-up window",
        slot.windowSummary(),
        warning = slot.phase == CdmWindowPhase.MISSED,
        refreshDescription = "Refresh start-up window",
        compactActions = compactActions,
        onRefresh = onRefresh
    )
    slot.eobt?.let { FlightPlanDetailRow("EOBT", it, warning = false) }
    // A request the backend hasn't accepted yet is shown next to (or instead of) the accepted T/OBT.
    val tobtValue = listOfNotNull(slot.tobt, slot.requestedTobt?.let { "pending $it" }).joinToString(" · ")
    if (tobtValue.isNotEmpty()) FlightPlanDetailRow("T/OBT", tobtValue, warning = false)
    slot.tsat?.let { FlightPlanDetailRow("TSAT", it, warning = false) }
    slot.exot?.let { FlightPlanDetailRow("EXOT", "${it}m", warning = false) }
    slot.ctot?.let { FlightPlanDetailRow("CTOT", it, warning = false) }
    slot.reason?.let { FlightPlanDetailRow("Reason", it, warning = true) }
    slot.atfcmStatus?.let { FlightPlanDetailRow("ATFCM", it, warning = false) }
    }
}

/** Mirrors SubsystemStatusRow's layout, but with OperationStatusIcon instead of a static dot --
 *  this is the "same indicator, now sitting next to the status line" effect from the collapsed
 *  row's version (see the face row's operationIndicator branch above), both ultimately driven
 *  off the same underlying per-operation data. One row per still-visible operation (see
 *  visibleOperations above) -- unlike the collapsed row's single combined icon, each row here
 *  only ever reflects its own operation, never combined with any other. */
@Composable
private fun OperationProgressRow(state: at.sushi.handoff.OperationProgressState) {
    val colors = LocalHandoffColors.current
    val indicator = when {
        !state.message.finished -> at.sushi.handoff.OperationIndicator.RUNNING_NEUTRAL
        state.message.success -> at.sushi.handoff.OperationIndicator.SUCCESS
        else -> at.sushi.handoff.OperationIndicator.FAILURE
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OperationStatusIcon(indicator = indicator, size = 10.dp, strokeWidth = 1.5.dp)
        Text(state.message.status, fontSize = 13.5.sp, color = colors.textMuted)
    }
}

// Material's CheckCircle/Cancel glyphs carry noticeably more built-in padding within their
// vector viewport than CircularProgressIndicator's ring (which draws essentially edge-to-edge
// within its bounds) -- at an identical size() the two read as visibly different weights, the
// icons looking smaller even though the layout box is the same. Scaled up to compensate so the
// spinner-to-icon swap doesn't look like a size change.
private const val StatusIconSizeCorrection = 1.35f

/** Renders one [at.sushi.handoff.OperationIndicator] -- a spinner for the three RUNNING_* values
 *  (tinted neutral/green/red depending on what else is known about the operations combined into
 *  it, see MainScreen.kt's combineOperationIndicator), or a green check / red X once nothing
 *  combined into it is running anymore. Shared by both places this footer shows operation status
 *  (the collapsed row's single combined icon and each of the drawer's per-operation rows). */
@Composable
private fun OperationStatusIcon(indicator: at.sushi.handoff.OperationIndicator, size: Dp, strokeWidth: Dp, modifier: Modifier = Modifier) {
    val colors = LocalHandoffColors.current
    when (indicator) {
        at.sushi.handoff.OperationIndicator.RUNNING_NEUTRAL ->
            CircularProgressIndicator(color = colors.textMuted, strokeWidth = strokeWidth, modifier = modifier.size(size))
        at.sushi.handoff.OperationIndicator.RUNNING_GOOD ->
            CircularProgressIndicator(color = colors.ok, strokeWidth = strokeWidth, modifier = modifier.size(size))
        at.sushi.handoff.OperationIndicator.RUNNING_BAD ->
            CircularProgressIndicator(color = at.sushi.handoff.ui.dialogs.outOfBandRed, strokeWidth = strokeWidth, modifier = modifier.size(size))
        at.sushi.handoff.OperationIndicator.SUCCESS ->
            Icon(Icons.Filled.CheckCircle, contentDescription = "Succeeded", tint = colors.ok, modifier = modifier.size(size * StatusIconSizeCorrection))
        at.sushi.handoff.OperationIndicator.FAILURE ->
            Icon(Icons.Filled.Cancel, contentDescription = "Failed", tint = at.sushi.handoff.ui.dialogs.outOfBandRed, modifier = modifier.size(size * StatusIconSizeCorrection))
    }
}

@Composable
private fun SubsystemStatusRow(label: String, connected: Boolean) {
    val colors = LocalHandoffColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(if (connected) colors.ok else colors.border, CircleShape))
        Text(
            label,
            fontSize = 13.5.sp,
            color = colors.textMuted
        )
    }
}
