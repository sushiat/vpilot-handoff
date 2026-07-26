package at.sushi.handoff.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenLockLandscape
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import at.sushi.handoff.ui.theme.RobotoMono
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.ConnectionStatus
import at.sushi.handoff.protocol.SubsystemStatusMessage
import at.sushi.handoff.ui.theme.LocalHandoffColors

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
    origin: String?,
    destination: String?,
    address: String?,
    subsystemStatus: SubsystemStatusMessage,
    latencyMs: Long?,
    expanded: Boolean,
    keepScreenAwake: Boolean,
    onToggleExpanded: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleKeepScreenAwake: () -> Unit
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
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val dotColor = if (connectionStatus == ConnectionStatus.CONNECTED) colors.ok else colors.attention
            Box(Modifier.size(8.dp).background(dotColor, CircleShape))
            // The doc's full sentence ("Connected to Handoff vPilot plugin, flying from...")
            // reads fine in the design mock's wide preview but wraps awkwardly mid-word next to
            // the refresh/settings icons on a real tablet -- shortened to status + route.
            val statusLabel = when (connectionStatus) {
                ConnectionStatus.CONNECTED -> "Connected"
                ConnectionStatus.CONNECTING -> "Connecting"
                ConnectionStatus.DISCONNECTED -> "Disconnected"
            }
            val statusText = "$statusLabel · ${origin ?: "----"} → ${destination ?: "----"}"
            Text(
                statusText,
                fontSize = 12.sp,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // Tight 30x30 boxes with a 2px gap, same pattern as ControllerList's pin/message
            // icons -- Material3's IconButton reserves a 48dp touch target plus its own internal
            // padding, which pushed these icons much further apart than intended.
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                // Toggle, not a dialog-opener -- tint reflects on/off state the same way the top
                // bar's Mode C badge does, rather than opening anything. Default on: the primary
                // use case is a tablet docked and wired into power in the cockpit for the whole
                // flight, where Android's screen timeout is actively unwanted.
                Box(
                    Modifier.size(30.dp).clickable(onClick = onToggleKeepScreenAwake),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.ScreenLockLandscape,
                        contentDescription = if (keepScreenAwake) "Keep screen awake: on" else "Keep screen awake: off",
                        tint = if (keepScreenAwake) colors.accent else colors.textMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    Modifier.size(30.dp).clickable(onClick = onRefresh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh flight plan", tint = colors.textMuted, modifier = Modifier.size(20.dp))
                }
                Box(
                    Modifier.size(30.dp).clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = colors.textMuted, modifier = Modifier.size(20.dp))
                }
            }
        }

        if (expanded) {
            HorizontalDivider(color = colors.border)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SubsystemStatusRow("Connected to RadioHost", subsystemStatus.radioHostConnected)
                SubsystemStatusRow("Connected to Simulator", subsystemStatus.simulatorConnected)
                SubsystemStatusRow("Connected to Vatsim Info", subsystemStatus.vatsimDataFeedConnected)
                SubsystemStatusRow("Fetched Simbrief flight plan", subsystemStatus.simbriefFetched)
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
                Text(
                    // Matches the doc's "vPilot plugin v1.4.2 · ws://host:port · 38ms" line
                    // shape -- all three fields are now real data (subsystemStatus message +
                    // persisted host pref + client-measured ping/pong RTT).
                    "vPilot plugin $versionLabel · ${address?.let { "ws://$it:48765" } ?: "not connected"} · $latencyLabel",
                    fontSize = 10.5.sp,
                    fontFamily = RobotoMono,
                    color = colors.textMuted
                )
            }
        }
    }
}

@Composable
private fun SubsystemStatusRow(label: String, connected: Boolean) {
    val colors = LocalHandoffColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(7.dp).background(if (connected) colors.ok else colors.border, CircleShape))
        Text(
            label,
            fontSize = 11.5.sp,
            color = colors.textMuted
        )
    }
}
