package at.sushi.handoff.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.protocol.Controller
import at.sushi.handoff.protocol.ControllersMessage
import at.sushi.handoff.protocol.SubsystemStatusMessage
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** The debug overlay window's actual content -- see [DebugOverlayWindow]'s own doc comment for
 *  why this is a real floating window rather than an in-app dialog. Two sections (issue #65):
 *  Ranking (plugin-wide context + per-controller explain, the main reason this exists) and a
 *  collapsed-by-default Systems section (lean per-subsystem health lines) -- see
 *  docs/protocol.md's `systemsDebug` doc comment for why that split exists. */
@Composable
fun DebugOverlayContent(
    controllers: ControllersMessage,
    subsystemStatus: SubsystemStatusMessage,
    onDragTitleBar: (dxPx: Float, dyPx: Float) -> Unit,
    onClose: () -> Unit,
    onSaveSnapshot: () -> Unit,
    snapshotStatus: String?
) {
    val colors = LocalHandoffColors.current
    var systemsExpanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.panel, RoundedCornerShape(12.dp))
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Debug", fontSize = 13.sp, color = colors.text)
            Text("✕", fontSize = 14.sp, color = colors.text.copy(alpha = 0.6f), modifier = Modifier.clickable(onClick = onClose))
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(10.dp)) {
            val debug = controllers.debug
            if (debug == null) {
                Text("Waiting for debug data...", fontSize = 12.sp, color = colors.textMuted)
            } else {
                Text("Phase: ${debug.phaseOfFlight}", fontSize = 11.sp, color = colors.textMuted)
                Text(
                    "Route: ${debug.lastPassedWaypoint ?: "-"} -> ${debug.activeRouteWaypoint ?: "-"}",
                    fontSize = 11.sp, color = colors.textMuted
                )
                Text("ETA: ${debug.etaCalculationDetail ?: "-"}", fontSize = 11.sp, color = colors.textMuted)
            }

            Text(
                "Ranking (${controllers.controllers.size})",
                fontSize = 12.sp, color = colors.text,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
            )
            controllers.controllers.forEach { controller -> DebugControllerRow(controller) }

            Text(
                "Systems" + if (systemsExpanded) " ▴" else " ▾",
                fontSize = 12.sp, color = colors.text,
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .clickable { systemsExpanded = !systemsExpanded }
            )
            if (systemsExpanded) {
                val systems = subsystemStatus.systemsDebug
                if (systems == null) {
                    Text("No systems data yet.", fontSize = 11.sp, color = colors.textMuted)
                } else {
                    Text("Radio host: ${systems.radioHostConnected}, simulator: ${systems.simulatorConnected}", fontSize = 11.sp, color = colors.textMuted)
                    Text("VATSIM feed: ${systems.vatsimFeedConnected} (last poll ${systems.vatsimFeedLastPollAt ?: "-"})", fontSize = 11.sp, color = colors.textMuted)
                    Text(
                        "SimBrief: ${systems.simbriefFetchedSuccessfully}" + (systems.simbriefLastError?.let { " ($it)" } ?: ""),
                        fontSize = 11.sp, color = colors.textMuted
                    )
                    Text("VATGlasses regions: ${systems.vatGlassesLoadedRegionCount}, vatspy boundaries: ${systems.vatSpyBoundaryCount}", fontSize = 11.sp, color = colors.textMuted)
                    Text("Paired devices: ${systems.pairedClientCount}, connected sockets: ${systems.authenticatedSocketCount}", fontSize = 11.sp, color = colors.textMuted)
                    Text("Active operations: ${systems.activeOperationCount}", fontSize = 11.sp, color = colors.textMuted)
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            if (snapshotStatus != null) {
                Text(snapshotStatus, fontSize = 11.sp, color = colors.textMuted, modifier = Modifier.padding(bottom = 6.dp))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.panelAlt, RoundedCornerShape(8.dp))
                    .clickable(onClick = onSaveSnapshot)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Save debug snapshot", fontSize = 12.sp, color = colors.text)
            }
        }
    }
}

@Composable
private fun DebugControllerRow(controller: Controller) {
    val colors = LocalHandoffColors.current
    val debug = controller.debug ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("${controller.callsign} -- bucket ${debug.bucket} (${debug.bucketName})", fontSize = 11.sp, color = colors.text)
        Text(debug.reason, fontSize = 10.sp, color = colors.textMuted)
    }
}
