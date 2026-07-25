package at.sushi.handoff.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
 *  icons) that always stays the bottom-most row of this container. Tapping it expands a drawer
 *  that grows *upward* -- the expanded content sits above the face row, inside the same bordered
 *  box, rather than the face row sliding down. Per issue #13 screen 1's footer, the sub-
 *  connection rows and version/latency detail are largely not in the protocol yet -- see
 *  docs/protocol.md -- so most of the expanded content is a visible stub. */
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
            .border(1.dp, colors.border, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
    ) {
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StubStatusRow("Connected to RadioHost")
                StubStatusRow("Connected to Simulator")
                StubStatusRow("Connected to Vatsim Info")
                StubStatusRow("Fetched Simbrief flight plan")
                HorizontalDivider(color = colors.border, modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    address?.let { "$it" } ?: "not connected",
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textMuted
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val dotColor = if (connectionStatus == ConnectionStatus.CONNECTED) colors.ok else colors.attention
            Box(Modifier.size(8.dp).background(dotColor, CircleShape))
            val statusText = when (connectionStatus) {
                ConnectionStatus.CONNECTED -> "Connected to Handoff vPilot plugin, flying from " +
                    "${origin ?: "----"} → ${destination ?: "----"}"
                ConnectionStatus.CONNECTING -> "Connecting to Handoff vPilot plugin…"
                ConnectionStatus.DISCONNECTED -> "Disconnected from Handoff vPilot plugin"
            }
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
    }
}

@Composable
private fun StubStatusRow(label: String) {
    val colors = LocalHandoffColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(colors.border, CircleShape))
        Text(
            "$label (not available yet)",
            fontSize = 11.5.sp,
            color = colors.textMuted
        )
    }
}
