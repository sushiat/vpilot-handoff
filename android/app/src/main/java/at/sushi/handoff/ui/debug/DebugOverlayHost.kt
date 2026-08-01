package at.sushi.handoff.ui.debug

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import at.sushi.handoff.HandoffConnectionService
import at.sushi.handoff.HandoffState
import at.sushi.handoff.ThemeMode
import at.sushi.handoff.protocol.AttachDebugSnapshotScreenshotCommand
import at.sushi.handoff.protocol.SaveDebugSnapshotCommand
import at.sushi.handoff.ui.theme.HandoffTheme
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.util.UUID

private const val SnapshotStatusLingerMillis = 10_000L

private val DefaultDebugWindowWidth = 680.dp
private val DefaultDebugWindowHeight = 560.dp

/** Shows/hides [DebugOverlayWindow] as a side effect of [HandoffState.debugWindowOpen], and owns
 *  the whole snapshot button flow (issue #65 sections 3-5): generate a snapshotId, send
 *  [SaveDebugSnapshotCommand], wait for the matching [at.sushi.handoff.protocol.DebugSnapshotSavedMessage],
 *  then capture and send a view-scoped screenshot. Mirrors
 *  [at.sushi.handoff.ui.main.MainScreen]'s ChatOverlayHost -- a real WindowManager window managed
 *  imperatively, not part of this composable's own declarative layout. */
@Composable
fun DebugOverlayHost(themeMode: ThemeMode) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val debugModeEnabled by HandoffState.debugModeEnabled.collectAsState()
    val visible by HandoffState.debugWindowOpen.collectAsState()
    val controllers by HandoffState.controllers.collectAsState()
    val subsystemStatus by HandoffState.subsystemStatus.collectAsState()
    val debugSnapshotSaved by HandoffState.debugSnapshotSaved.collectAsState()

    val overlay = remember { DebugOverlayWindow(context) }
    val currentThemeMode = rememberUpdatedState(themeMode)
    val currentControllers = rememberUpdatedState(controllers)
    val currentSubsystemStatus = rememberUpdatedState(subsystemStatus)

    var pendingSnapshotId by remember { mutableStateOf<String?>(null) }
    var snapshotStatus by remember { mutableStateOf<String?>(null) }

    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    // Clears itself ~20s after the most recent status text -- re-keyed on snapshotStatus itself,
    // so each new status (Saving... -> Snapshot saved... -> Snapshot + screenshot saved.)
    // restarts the timer and only the final one actually lingers for the full window. Without
    // this the message just sits there forever, taking up space and leaving no way to tell
    // whether a *second* snapshot attempt actually saved or silently reused the old text.
    LaunchedEffect(snapshotStatus) {
        if (snapshotStatus != null) {
            delay(SnapshotStatusLingerMillis)
            snapshotStatus = null
        }
    }

    val onSaveSnapshot: () -> Unit = onSaveSnapshot@{
        val snapshotId = UUID.randomUUID().toString()
        pendingSnapshotId = snapshotId
        snapshotStatus = "Saving snapshot..."
        HandoffConnectionService.instance?.sendCommand(SaveDebugSnapshotCommand(snapshotId = snapshotId, appVersion = appVersion))
    }

    // The plugin's debugSnapshotSaved reply is the cue to *now* capture the screenshot (issue #65
    // section 4/5) -- capturing eagerly the instant the button was tapped would race the plugin's
    // own snapshot-gathering, and this ordering is exactly what the round trip is designed for.
    LaunchedEffect(debugSnapshotSaved, pendingSnapshotId) {
        val message = debugSnapshotSaved ?: return@LaunchedEffect
        val expectedId = pendingSnapshotId ?: return@LaunchedEffect
        if (message.snapshotId != expectedId) return@LaunchedEffect

        snapshotStatus = "Snapshot saved. Capturing screenshot..."
        val activity = context as? Activity
        if (activity == null) {
            snapshotStatus = "Snapshot saved (no screenshot -- host isn't an Activity)."
            HandoffState.clearDebugSnapshotSaved()
            pendingSnapshotId = null
            return@LaunchedEffect
        }

        captureWindowScreenshot(activity) { base64Png ->
            if (base64Png != null) {
                HandoffConnectionService.instance?.sendCommand(
                    AttachDebugSnapshotScreenshotCommand(snapshotId = expectedId, screenshotPngBase64 = base64Png)
                )
                snapshotStatus = "Snapshot + screenshot saved."
            } else {
                snapshotStatus = "Snapshot saved (screenshot capture failed)."
            }
            HandoffState.clearDebugSnapshotSaved()
            pendingSnapshotId = null
        }
    }

    DisposableEffect(visible, debugModeEnabled) {
        if (visible && debugModeEnabled) {
            val widthPx = DebugOverlayWindowState.widthPx ?: with(density) { DefaultDebugWindowWidth.roundToPx() }
            val heightPx = DebugOverlayWindowState.heightPx ?: with(density) { DefaultDebugWindowHeight.roundToPx() }
            val xPx = DebugOverlayWindowState.xPx ?: with(density) { 24.dp.roundToPx() }
            val yPx = DebugOverlayWindowState.yPx ?: with(density) { 120.dp.roundToPx() }
            DebugOverlayWindowState.widthPx = widthPx
            DebugOverlayWindowState.heightPx = heightPx

            overlay.show(widthPx, heightPx, xPx, yPx) {
                // Own composition root -- doesn't inherit CompositionLocals from MainScreen's
                // composition, same reasoning as ChatOverlayWindow's content lambda.
                HandoffTheme(currentThemeMode.value) {
                    DebugOverlayContent(
                        controllers = currentControllers.value,
                        subsystemStatus = currentSubsystemStatus.value,
                        onDragTitleBar = { dxPx, dyPx ->
                            val newX = (DebugOverlayWindowState.xPx ?: xPx) + dxPx.toInt()
                            val newY = (DebugOverlayWindowState.yPx ?: yPx) + dyPx.toInt()
                            overlay.updatePosition(newX, newY)
                        },
                        onClose = { HandoffState.setDebugWindowOpen(false) },
                        onSaveSnapshot = onSaveSnapshot,
                        snapshotStatus = snapshotStatus
                    )
                }
            }
        } else {
            overlay.hide()
        }
        onDispose { overlay.hide() }
    }
}

/** View-scoped capture of this app's own [Activity.getWindow] via [PixelCopy] -- never a
 *  full-display capture, per issue #65 section 5's split-screen privacy requirement: the tablet
 *  normally runs Handoff alongside another EFB app, and a full-display capture would pull in
 *  whatever's on the other side of the split. [PixelCopy] against this Activity's own window can
 *  only ever produce pixels belonging to this app by construction. */
private fun captureWindowScreenshot(activity: Activity, onResult: (String?) -> Unit) {
    val window = activity.window
    val view = window.decorView
    if (view.width <= 0 || view.height <= 0) {
        onResult(null)
        return
    }

    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    runCatching {
        PixelCopy.request(window, bitmap, { result ->
            if (result != PixelCopy.SUCCESS) {
                onResult(null)
                return@request
            }
            val bytes = ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
            onResult(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        }, Handler(Looper.getMainLooper()))
    }.onFailure { onResult(null) }
}
