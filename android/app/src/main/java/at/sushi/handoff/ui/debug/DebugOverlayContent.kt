package at.sushi.handoff.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.protocol.Controller
import at.sushi.handoff.protocol.ControllersMessage
import at.sushi.handoff.protocol.SubsystemStatusMessage
import kotlin.math.roundToInt
import at.sushi.handoff.ui.theme.HandoffTextField
import at.sushi.handoff.ui.theme.LocalHandoffColors

private val DebugWindowShape = RoundedCornerShape(12.dp)
private val SystemsColumnWidth = 260.dp

/** The debug overlay window's actual content -- see [DebugOverlayWindow]'s own doc comment for
 *  why this is a real floating window rather than an in-app dialog. Two sections side by side
 *  (issue #65, plenty of horizontal room in the default window size to show both at once rather
 *  than behind a collapse toggle): Ranking (plugin-wide context + per-controller explain, the
 *  main reason this exists) on the left, Systems (lean per-subsystem health lines) always visible
 *  in a fixed-width right column -- see docs/protocol.md's `systemsDebug` doc comment for why
 *  that data stays this lean rather than the exhaustive per-subsystem snapshot detail. */
@Composable
fun DebugOverlayContent(
    controllers: ControllersMessage,
    subsystemStatus: SubsystemStatusMessage,
    onDragTitleBar: (dxPx: Float, dyPx: Float) -> Unit,
    onClose: () -> Unit,
    onSaveSnapshot: () -> Unit,
    snapshotStatus: String?,
    // Issue #73b -- true once a save round trip just completed, swapping the save button for an
    // inline name field (in the same spot, not a new dialog) until a name is submitted or a new
    // save starts.
    awaitingName: Boolean,
    onNameSnapshot: (String) -> Unit,
    // Issue #73a -- opt-in full-device snapshot screenshot (MediaProjection), off by default; the
    // consent prompt this triggers happens once per check, not per snapshot (DebugOverlayHost).
    fullDeviceCapture: Boolean,
    onFullDeviceCaptureChange: (Boolean) -> Unit
) {
    val colors = LocalHandoffColors.current

    Column(
        Modifier
            .fillMaxSize()
            // A shadow plus an explicit border, not just a background fill -- the panel color is
            // near-white in light mode (same as the app's own bg), so a plain fill alone blended
            // invisibly into whatever's behind the overlay (confirmed against a real on-device
            // screenshot: the window was genuinely unfindable once opened).
            .shadow(12.dp, DebugWindowShape)
            .background(colors.panel, DebugWindowShape)
            .border(1.5.dp, colors.border, DebugWindowShape)
    ) {
        // Title bar -- the drag handle. detectDragGestures reports per-frame deltas, which this
        // just forwards straight to the caller (DebugOverlayHost), which owns the actual
        // WindowManager position and DebugOverlayWindowState persistence.
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.panelAlt, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDragTitleBar(dragAmount.x, dragAmount.y)
                    }
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Debug", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Issue #73a -- placed here, left of the close button, per the issue's own
                // "saves vertical space for the controller list" reasoning. onCheckedChange = null
                // on the Checkbox itself since the whole Row is the click target, same pattern as
                // ControllerList's "Hide tuned" checkbox.
                Row(
                    Modifier.clickable { onFullDeviceCaptureChange(!fullDeviceCapture) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Full-device", fontSize = 12.sp, color = colors.textMuted)
                    Checkbox(
                        checked = fullDeviceCapture,
                        onCheckedChange = null,
                        modifier = Modifier.scale(0.75f),
                        colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.textMuted)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text("✕", fontSize = 18.sp, color = colors.text.copy(alpha = 0.6f), modifier = Modifier.clickable(onClick = onClose))
            }
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(14.dp)) {
                val debug = controllers.debug
                if (debug == null) {
                    Text("Waiting for debug data...", fontSize = 15.sp, color = colors.textMuted)
                } else {
                    Text("Phase: ${debug.phaseOfFlight}", fontSize = 15.sp, color = colors.textMuted)
                    Text(
                        "Route: " +
                            formatWaypoint(debug.lastPassedWaypoint, debug.lastPassedWaypointBearingTrue, debug.lastPassedWaypointDistanceNm) +
                            " --> " +
                            formatWaypoint(debug.activeRouteWaypoint, debug.activeRouteWaypointBearingTrue, debug.activeRouteWaypointDistanceNm),
                        fontSize = 15.sp, color = colors.textMuted
                    )
                    Text("ETA: ${debug.etaCalculationDetail ?: "-"}", fontSize = 15.sp, color = colors.textMuted)
                    Text(
                        "Last advance: ${debug.lastWaypointAdvanceMechanism ?: "-"}" +
                            (debug.lastWaypointAdvanceAt?.let { " (${formatRelativeTime(it)})" } ?: ""),
                        fontSize = 15.sp, color = colors.textMuted
                    )
                }

                Text(
                    "Ranking (${controllers.controllers.size})",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.text,
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
                )
                controllers.controllers.forEach { controller -> DebugControllerRow(controller) }
            }

            Column(
                Modifier
                    .width(SystemsColumnWidth)
                    .fillMaxHeight()
                    .background(colors.panelAlt)
                    .border(1.dp, colors.border)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                Text("Systems", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.text, modifier = Modifier.padding(bottom = 8.dp))
                val systems = subsystemStatus.systemsDebug
                if (systems == null) {
                    Text("No systems data yet.", fontSize = 14.sp, color = colors.textMuted)
                } else {
                    Text("Radio host: ${systems.radioHostConnected}", fontSize = 14.sp, color = colors.textMuted)
                    Text("Simulator: ${systems.simulatorConnected}", fontSize = 14.sp, color = colors.textMuted)
                    Text(
                        "VATSIM feed: ${systems.vatsimFeedConnected}\n(last poll ${formatRelativeTime(systems.vatsimFeedLastPollAt)})",
                        fontSize = 14.sp, color = colors.textMuted, modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        "SimBrief: ${systems.simbriefFetchedSuccessfully}" + (systems.simbriefLastError?.let { " ($it)" } ?: ""),
                        fontSize = 14.sp, color = colors.textMuted, modifier = Modifier.padding(top = 6.dp)
                    )
                    Text("VATGlasses regions: ${systems.vatGlassesLoadedRegionCount}", fontSize = 14.sp, color = colors.textMuted, modifier = Modifier.padding(top = 6.dp))
                    Text("vatspy boundaries: ${systems.vatSpyBoundaryCount}", fontSize = 14.sp, color = colors.textMuted)
                    Text("Paired devices: ${systems.pairedClientCount}", fontSize = 14.sp, color = colors.textMuted, modifier = Modifier.padding(top = 6.dp))
                    Text("Connected sockets: ${systems.authenticatedSocketCount}", fontSize = 14.sp, color = colors.textMuted)
                    Text("Active operations: ${systems.activeOperationCount}", fontSize = 14.sp, color = colors.textMuted, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            if (snapshotStatus != null) {
                Text(snapshotStatus, fontSize = 14.sp, color = colors.textMuted, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (awaitingName) {
                var name by remember(awaitingName) { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HandoffTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = "Name this snapshot (optional)",
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .background(colors.panelAlt, RoundedCornerShape(8.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                            .clickable(enabled = name.isNotBlank()) { onNameSnapshot(name) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Save name", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.text)
                    }
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.panelAlt, RoundedCornerShape(8.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                        .clickable(onClick = onSaveSnapshot)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Save debug snapshot", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.text)
                }
            }
        }
    }
}

@Composable
private fun DebugControllerRow(controller: Controller) {
    val colors = LocalHandoffColors.current
    val debug = controller.debug ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val bucketLabel = debug.subBucket ?: debug.bucket.toString()
        Text("${controller.callsign} -- bucket $bucketLabel (${debug.bucketName})", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.text)
        Text(debug.reason, fontSize = 13.sp, color = colors.textMuted)
    }
}

/** "POINTA (178° 12.3nm)" -- bearing/distance are from ownship's current position to this
 *  waypoint (not the leg course between waypoints), so a stale/wrong one reads at a glance (e.g.
 *  a "last passed" waypoint that's actually still far ahead of you, not behind). "-" when the
 *  waypoint itself is unknown; bearing/distance are simply omitted (not "-") when only ownship's
 *  position isn't known yet, since the waypoint name alone is still useful. */
private fun formatWaypoint(ident: String?, bearingTrue: Double?, distanceNm: Double?): String {
    if (ident == null) return "-"
    if (bearingTrue == null || distanceNm == null) return ident
    return "$ident (${bearingTrue.roundToInt()}° ${"%.1f".format(distanceNm)}nm)"
}

/** "3s ago"/"4m ago" instead of a full ISO-8601 timestamp with fractional seconds and an offset --
 *  this is a live-updating debug readout, not a record someone needs the exact wall-clock moment
 *  for, so relative age is both more legible at a glance and more useful (docs/protocol.md's
 *  `systemsDebug` timestamps are plain ISO-8601 strings on the wire; this is purely a display
 *  concern, not a protocol change). Falls back to the raw string if it doesn't parse as an
 *  ISO-8601 offset timestamp -- better a slightly ugly string than a silently blank field. */
private fun formatRelativeTime(iso: String?): String {
    if (iso == null) return "-"
    val instant = runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrNull() ?: return iso
    val seconds = java.time.Duration.between(instant, java.time.Instant.now()).seconds
    return when {
        seconds < 0 -> "0s ago"
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        else -> "${seconds / 3600}h ago"
    }
}
