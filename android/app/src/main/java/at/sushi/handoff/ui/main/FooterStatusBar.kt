package at.sushi.handoff.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.ConnectionStatus
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** The controller list's footer: a "face" row (connection dot + status line, refresh + settings
 *  icons) that's the first/top child of this container, with the expandable detail content
 *  (subsystem status rows, then the plugin info line) placed *below* it, last. Because this
 *  whole container sits at the bottom of MainScreen's Column (below the weighted controller
 *  list), growing it downward-in-DOM-order is what actually pushes the face row visually
 *  *upward* on screen when expanded -- the container's own bottom edge stays pinned to the
 *  screen's bottom, so adding content below the face row grows the block upward as a whole,
 *  carrying the face row with it, rather than the face row itself relocating. Per issue #13
 *  screen 1's footer, the sub-connection rows and version/latency detail are largely not in the
 *  protocol yet -- see docs/protocol.md -- so most of the expanded content is a visible stub. */
@Composable
fun FooterStatusBar(
    connectionStatus: ConnectionStatus,
    origin: String?,
    destination: String?,
    address: String?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = LocalHandoffColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.panel)
    ) {
        HorizontalDivider(color = colors.border) // the reference's border-top only, not a full box
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 14.dp, vertical = 8.dp),
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
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh flight plan", tint = colors.textMuted)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = colors.textMuted)
            }
        }

        if (expanded) {
            HorizontalDivider(color = colors.border)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StubStatusRow("Connected to RadioHost")
                StubStatusRow("Connected to Simulator")
                StubStatusRow("Connected to Vatsim Info")
                StubStatusRow("Fetched Simbrief flight plan")
                HorizontalDivider(color = colors.border, modifier = Modifier.padding(vertical = 2.dp))
                Text(
                    // Matches the doc's "vPilot plugin v1.4.2 · ws://host:port · 38ms" line
                    // shape, but only the address is real data -- the plugin doesn't report its
                    // own version yet, and latency needs an app-level ping/pong the protocol
                    // doesn't have either (see docs/protocol.md), so both stay static
                    // placeholders until those exist.
                    "vPilot plugin vX.X.X · ${address?.let { "ws://$it:48765" } ?: "not connected"} · --ms",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textMuted
                )
            }
        }
    }
}

@Composable
private fun StubStatusRow(label: String) {
    val colors = LocalHandoffColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(7.dp).background(colors.border, CircleShape))
        Text(
            "$label (not available yet)",
            fontSize = 11.5.sp,
            color = colors.textMuted
        )
    }
}
