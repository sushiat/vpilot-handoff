package at.sushi.handoff.ui.debug

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/** Issue #73a follow-up -- MediaProjection/VirtualDisplay/ImageReader state, held here as a
 *  process-wide singleton (same pattern as [at.sushi.handoff.HandoffState]/
 *  [at.sushi.handoff.HandoffConnectionService.instance]) instead of local Compose `remember`
 *  state inside [DebugOverlayHost]. Confirmed on-device: `remember` state is scoped to
 *  MainActivity's own composition, and Android recreates that Activity on a fullscreen<->
 *  split-screen transition -- a routine Configuration change for this app, which explicitly
 *  supports running split-screen alongside another EFB app (see CLAUDE.md), not an edge case.
 *  That recreation silently reset the "Full-device screenshot" checkbox back to unchecked even
 *  though the real system MediaProjection session (visible via the status bar's screen-share
 *  icon) was still alive underneath -- only this app's own reference to it was lost. */
object FullDeviceCaptureSession {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var surface: FullDeviceCaptureSurface? = null

    internal val currentSurface: FullDeviceCaptureSurface? get() = surface

    /** Consumes the granted consent Intent, registers the required [MediaProjection.Callback]
     *  before creating the VirtualDisplay (Android 14+ throws IllegalStateException otherwise --
     *  see [createFullDeviceCaptureSurface]), and holds everything alive until [stop] or the
     *  system revoking it. Any previous session is torn down first. */
    fun start(context: Context, resultCode: Int, data: Intent) {
        stop()
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data) ?: return

        val callback = object : MediaProjection.Callback() {
            // The system can end a projection on its own (e.g. the pilot revokes it from the
            // status bar's screen-capture indicator) -- react the same way as an explicit
            // uncheck, rather than holding a stale/dead MediaProjection reference around.
            override fun onStop() = stop()
        }
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))

        val newSurface = createFullDeviceCaptureSurface(context, projection)
        if (newSurface == null) {
            projection.unregisterCallback(callback)
            projection.stop()
            return
        }

        mediaProjection = projection
        mediaProjectionCallback = callback
        surface = newSurface
        _active.value = true
    }

    fun stop() {
        surface?.close()
        surface = null
        mediaProjectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        mediaProjectionCallback = null
        mediaProjection?.stop()
        mediaProjection = null
        _active.value = false
    }
}

/** Holds the [ImageReader]/[VirtualDisplay] pair backing an opt-in full-device capture session
 *  (issue #73a) -- created once via [createFullDeviceCaptureSurface] right after the pilot grants
 *  MediaProjection consent, and kept alive for the whole "checked" session rather than recreated
 *  per snapshot (see [FullDeviceCaptureSession]'s own doc comment on why: recreating the
 *  VirtualDisplay per capture silently ends the whole MediaProjection session on most devices).
 *  [width]/[height] are needed alongside the reader/display to correctly interpret each captured
 *  frame's row stride/padding. */
internal class FullDeviceCaptureSurface(
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
internal fun captureFullDeviceScreenshot(surface: FullDeviceCaptureSurface, onResult: (String?) -> Unit) {
    var resolved = false

    fun finish(result: String?) {
        if (resolved) return
        resolved = true
        surface.imageReader.setOnImageAvailableListener(null, null)
        onResult(result)
    }

    fun processImage(image: Image) {
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
