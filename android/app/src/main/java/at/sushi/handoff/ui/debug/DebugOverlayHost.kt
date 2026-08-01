package at.sushi.handoff.ui.debug

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
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
import at.sushi.handoff.protocol.NameDebugSnapshotCommand
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
    val debugSnapshotNamed by HandoffState.debugSnapshotNamed.collectAsState()

    val overlay = remember { DebugOverlayWindow(context) }
    val currentThemeMode = rememberUpdatedState(themeMode)
    val currentControllers = rememberUpdatedState(controllers)
    val currentSubsystemStatus = rememberUpdatedState(subsystemStatus)

    var pendingSnapshotId by remember { mutableStateOf<String?>(null) }
    var snapshotStatus by remember { mutableStateOf<String?>(null) }
    // Issue #73b -- survives pendingSnapshotId being cleared at the end of the save round trip,
    // so the inline naming field (DebugOverlayContent) still knows which snapshot to name.
    // Cleared once a name is actually submitted (see onNameSnapshot) or a new save starts.
    var lastSavedSnapshotId by remember { mutableStateOf<String?>(null) }

    // Issue #73a -- fullDeviceCapture reflects actual granted MediaProjection consent, not just
    // the checkbox tap: it only flips true once consent comes back RESULT_OK (see
    // onFullDeviceCaptureChange), and flips back false on decline/unchecking/window close.
    var fullDeviceCapture by remember { mutableStateOf(false) }
    var mediaProjection by remember { mutableStateOf<MediaProjection?>(null) }
    val mediaProjectionCallback = remember {
        object : MediaProjection.Callback() {
            // The system can end a projection on its own (e.g. the pilot revokes it from the
            // status bar's screen-capture indicator) -- react the same way as an explicit
            // uncheck, rather than holding a stale/dead MediaProjection reference around.
            override fun onStop() {
                mediaProjection = null
                fullDeviceCapture = false
            }
        }
    }

    fun stopFullDeviceCapture() {
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        fullDeviceCapture = false
    }

    val onFullDeviceCaptureChange: (Boolean) -> Unit = { checked ->
        if (checked) {
            MediaProjectionRequester.requestConsent(context) { resultCode, data ->
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = manager.getMediaProjection(resultCode, data)
                    if (projection == null) {
                        fullDeviceCapture = false
                    } else {
                        // Must be registered before any createVirtualDisplay call (Android 14+
                        // throws IllegalStateException otherwise) -- see this file's capture
                        // function.
                        projection.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))
                        mediaProjection = projection
                        fullDeviceCapture = true
                    }
                } else {
                    fullDeviceCapture = false
                }
            }
        } else {
            stopFullDeviceCapture()
        }
    }

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
        lastSavedSnapshotId = null
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
            lastSavedSnapshotId = expectedId
            return@LaunchedEffect
        }

        val onCaptureResult: (String?) -> Unit = { base64Png ->
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
            lastSavedSnapshotId = expectedId
        }

        // Issue #73a -- the pilot's opt-in choice, made once when the checkbox was checked; the
        // plugin can't tell which kind it received either way, both arrive on the same field.
        val projection = mediaProjection
        if (fullDeviceCapture && projection != null) {
            captureFullDeviceScreenshot(context, projection, onCaptureResult)
        } else {
            captureWindowScreenshot(activity, onCaptureResult)
        }
    }

    // Issue #73b -- once the pilot submits a name for lastSavedSnapshotId, this is the cue to
    // report success/failure and clear the inline naming field, same one-shot-consumption
    // pattern as the debugSnapshotSaved effect above.
    val onNameSnapshot: (String) -> Unit = { name ->
        val snapshotId = lastSavedSnapshotId
        if (snapshotId != null) {
            snapshotStatus = "Naming snapshot..."
            HandoffConnectionService.instance?.sendCommand(NameDebugSnapshotCommand(snapshotId = snapshotId, name = name))
        }
    }

    LaunchedEffect(debugSnapshotNamed) {
        val message = debugSnapshotNamed ?: return@LaunchedEffect
        snapshotStatus = if (message.success) "Snapshot named." else "Failed to name snapshot: ${message.error ?: "unknown error"}"
        lastSavedSnapshotId = null
        HandoffState.clearDebugSnapshotNamed()
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
                        snapshotStatus = snapshotStatus,
                        awaitingName = lastSavedSnapshotId != null,
                        onNameSnapshot = onNameSnapshot,
                        fullDeviceCapture = fullDeviceCapture,
                        onFullDeviceCaptureChange = onFullDeviceCaptureChange
                    )
                }
            }
        } else {
            overlay.hide()
            stopFullDeviceCapture()
        }
        onDispose {
            overlay.hide()
            stopFullDeviceCapture()
        }
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

/** Opt-in full-device capture (issue #73a) via [MediaProjection], for cases where seeing the
 *  neighboring split-screen EFB app alongside Handoff's own state actually helps diagnosis --
 *  the pilot must explicitly check the title bar's checkbox first (see [DebugOverlayHost]'s
 *  onFullDeviceCaptureChange), unlike [captureWindowScreenshot]'s always-on app-only default.
 *  One-shot: grabs a single frame via [ImageReader] + [MediaProjection.createVirtualDisplay],
 *  then tears both down immediately -- this isn't a continuous screen recording. */
private fun captureFullDeviceScreenshot(context: Context, mediaProjection: MediaProjection, onResult: (String?) -> Unit) {
    @Suppress("DEPRECATION")
    val metrics = DisplayMetrics().also {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(it)
    }
    val width = metrics.widthPixels
    val height = metrics.heightPixels
    if (width <= 0 || height <= 0) {
        onResult(null)
        return
    }

    val handler = Handler(Looper.getMainLooper())
    val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    var resolved = false

    fun finish(result: String?) {
        if (resolved) return
        resolved = true
        onResult(result)
        virtualDisplay?.release()
        imageReader.close()
    }

    imageReader.setOnImageAvailableListener({ reader ->
        val image = reader.acquireLatestImage()
        if (image == null) {
            finish(null)
            return@setOnImageAvailableListener
        }
        runCatching {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPaddingPx = (rowStride - pixelStride * width) / pixelStride
            val bitmap = Bitmap.createBitmap(width + rowPaddingPx, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (rowPaddingPx == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, width, height)
            val bytes = ByteArrayOutputStream().use { stream ->
                cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }.onSuccess { base64Png ->
            image.close()
            finish(base64Png)
        }.onFailure {
            image.close()
            finish(null)
        }
    }, handler)

    runCatching {
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "HandoffDebugFullDeviceCapture", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, handler
        )
    }.onFailure { finish(null) }

    // Backstop in case no frame ever arrives (e.g. the display genuinely never produces one) --
    // same reasoning as PixelCopy's SUCCESS/failure callback always firing, just without an
    // equivalent guarantee from this API.
    handler.postDelayed({ finish(null) }, 5_000L)
}
