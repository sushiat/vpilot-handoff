package at.sushi.handoff.ui.debug

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.PixelCopy
import android.view.WindowManager
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
    // Issue #73a follow-up -- the VirtualDisplay/ImageReader pair is created once, right after
    // consent is granted, and kept alive for as long as the checkbox stays checked, rather than
    // being created/torn down per snapshot. Confirmed on-device: recreating a VirtualDisplay from
    // the same MediaProjection for a second capture silently fails and ends the whole projection
    // session -- most MediaProjection implementations only support a single VirtualDisplay across
    // the token's lifetime. Each capture instead just reads the latest already-mirrored frame off
    // this same persistent surface.
    var captureSurface by remember { mutableStateOf<FullDeviceCaptureSurface?>(null) }
    val mediaProjectionCallback = remember {
        object : MediaProjection.Callback() {
            // The system can end a projection on its own (e.g. the pilot revokes it from the
            // status bar's screen-capture indicator) -- react the same way as an explicit
            // uncheck, rather than holding a stale/dead MediaProjection reference around.
            override fun onStop() {
                captureSurface?.close()
                captureSurface = null
                mediaProjection = null
                fullDeviceCapture = false
            }
        }
    }

    fun stopFullDeviceCapture() {
        captureSurface?.close()
        captureSurface = null
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        fullDeviceCapture = false
    }

    val onFullDeviceCaptureChange: (Boolean) -> Unit = { checked ->
        if (checked) {
            MediaProjectionRequester.requestConsent(context) { resultCode, data ->
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // Must happen before getMediaProjection() below -- see
                    // HandoffConnectionService.promoteToMediaProjectionForeground's own doc
                    // comment for why the order matters (Android 14+ crashes otherwise).
                    HandoffConnectionService.instance?.promoteToMediaProjectionForeground()
                    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = manager.getMediaProjection(resultCode, data)
                    if (projection == null) {
                        fullDeviceCapture = false
                    } else {
                        // Must be registered before any createVirtualDisplay call (Android 14+
                        // throws IllegalStateException otherwise) -- see
                        // createFullDeviceCaptureSurface, called right after.
                        projection.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))
                        val surface = createFullDeviceCaptureSurface(context, projection)
                        if (surface == null) {
                            projection.unregisterCallback(mediaProjectionCallback)
                            projection.stop()
                            fullDeviceCapture = false
                        } else {
                            mediaProjection = projection
                            captureSurface = surface
                            fullDeviceCapture = true
                        }
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
        val surface = captureSurface
        if (fullDeviceCapture && surface != null) {
            captureFullDeviceScreenshot(surface, onCaptureResult)
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

    // Issue #73b -- lets the pilot dismiss the inline name field without typing anything, same
    // "the files are simply left exactly as they already are" outcome as never getting around to
    // naming it at all -- no command sent, nothing for the plugin to do.
    val onSkipName: () -> Unit = { lastSavedSnapshotId = null }

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
                        onSkipName = onSkipName,
                        fullDeviceCapture = fullDeviceCapture,
                        onFullDeviceCaptureChange = onFullDeviceCaptureChange
                    )
                }
            }
        } else {
            overlay.hide()
            // Issue #73b -- otherwise closing the window without naming a just-saved snapshot
            // leaves lastSavedSnapshotId set forever (this composable stays alive across the
            // window's own show/hide, so nothing else would ever clear it), permanently stuck
            // showing the inline name field instead of the save button on next open.
            lastSavedSnapshotId = null
            // Deliberately NOT calling stopFullDeviceCapture() here -- per the pilot's own
            // request, the granted MediaProjection consent should survive the window closing
            // (re-approving the system consent dialog every time is exactly what "prompt once"
            // was meant to avoid), not just survive across snapshots taken while the window
            // stays open. Only an explicit uncheck (onFullDeviceCaptureChange) or the system
            // itself revoking it (mediaProjectionCallback.onStop) ever stops it now.
        }
        onDispose {
            overlay.hide()
            lastSavedSnapshotId = null
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

/** Holds the [ImageReader]/[VirtualDisplay] pair backing an opt-in full-device capture session
 *  (issue #73a) -- created once via [createFullDeviceCaptureSurface] right after the pilot grants
 *  MediaProjection consent, and kept alive for the whole "checked" session rather than recreated
 *  per snapshot (see [DebugOverlayHost]'s own doc comment on why: recreating the VirtualDisplay
 *  per capture silently ends the whole MediaProjection session on most devices). [width]/[height]
 *  are needed alongside the reader/display to correctly interpret each captured frame's row
 *  stride/padding. */
private class FullDeviceCaptureSurface(
    val imageReader: ImageReader,
    val virtualDisplay: VirtualDisplay,
    val width: Int,
    val height: Int,
    val handler: Handler
) {
    fun close() {
        imageReader.setOnImageAvailableListener(null, null)
        virtualDisplay.release()
        imageReader.close()
    }
}

/** Sets up the persistent mirrored surface a full-device capture session reads frames from --
 *  see [FullDeviceCaptureSurface]'s own doc comment for why this is created once and reused
 *  rather than per snapshot. Returns null (caller's responsibility to also stop the
 *  [MediaProjection] it was given) if the display size can't be read or the VirtualDisplay fails
 *  to create. */
private fun createFullDeviceCaptureSurface(context: Context, mediaProjection: MediaProjection): FullDeviceCaptureSurface? {
    val (width, height) = fullDisplaySizePx(context) ?: return null
    val densityDpi = context.resources.displayMetrics.densityDpi

    val handler = Handler(Looper.getMainLooper())
    val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    val virtualDisplay = runCatching {
        mediaProjection.createVirtualDisplay(
            "HandoffDebugFullDeviceCapture", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, handler
        )
    }.getOrNull()

    if (virtualDisplay == null) {
        imageReader.close()
        return null
    }
    return FullDeviceCaptureSurface(imageReader, virtualDisplay, width, height, handler)
}

/** Full physical display size in pixels, independent of this app's own current window/split-screen
 *  bounds -- what [MediaProjection.createVirtualDisplay] should mirror into. `WindowMetrics`
 *  (API 30+) is the non-deprecated way to get this; [Display.getRealMetrics] (this app's minSdk
 *  26 target) is deprecated but still the only option below API 30, so it's confined to that
 *  fallback branch rather than used unconditionally. */
private fun fullDisplaySizePx(context: Context): Pair<Int, Int>? {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val bounds = windowManager.maximumWindowMetrics.bounds
        return if (bounds.width() > 0 && bounds.height() > 0) bounds.width() to bounds.height() else null
    }

    @Suppress("DEPRECATION")
    val metrics = DisplayMetrics().also {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(it)
    }
    return if (metrics.widthPixels > 0 && metrics.heightPixels > 0) metrics.widthPixels to metrics.heightPixels else null
}

/** Grabs the most recent frame off an already-live [FullDeviceCaptureSurface] -- never creates or
 *  tears down the underlying [ImageReader]/[VirtualDisplay] itself (see that class's doc comment).
 *  The surface should already have a recent frame buffered from ongoing mirroring; falls back to
 *  waiting for the next one via a one-shot listener if [ImageReader.acquireLatestImage] comes back
 *  empty (e.g. this is the very first capture right after the surface was created). */
private fun captureFullDeviceScreenshot(surface: FullDeviceCaptureSurface, onResult: (String?) -> Unit) {
    var resolved = false

    fun finish(result: String?) {
        if (resolved) return
        resolved = true
        surface.imageReader.setOnImageAvailableListener(null, null)
        onResult(result)
    }

    fun processImage(image: android.media.Image) {
        runCatching {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPaddingPx = (rowStride - pixelStride * surface.width) / pixelStride
            val bitmap = Bitmap.createBitmap(surface.width + rowPaddingPx, surface.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (rowPaddingPx == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, surface.width, surface.height)
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
    }

    val immediateImage = runCatching { surface.imageReader.acquireLatestImage() }.getOrNull()
    if (immediateImage != null) {
        processImage(immediateImage)
        return
    }

    surface.imageReader.setOnImageAvailableListener({ reader ->
        val image = reader.acquireLatestImage()
        if (image == null) finish(null) else processImage(image)
    }, surface.handler)

    // Backstop in case no frame ever arrives (e.g. the display genuinely never produces one) --
    // same reasoning as PixelCopy's SUCCESS/failure callback always firing, just without an
    // equivalent guarantee from this API.
    surface.handler.postDelayed({ finish(null) }, 5_000L)
}
