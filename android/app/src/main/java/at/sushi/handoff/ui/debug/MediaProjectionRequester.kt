package at.sushi.handoff.ui.debug

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager

/** Bridges [MainActivity]'s [androidx.activity.result.ActivityResultLauncher] (MediaProjection
 *  consent, issue #73a, must be requested via [android.app.Activity.startActivityForResult]-style
 *  APIs) to [DebugOverlayHost], which runs its own composition inside a WindowManager overlay
 *  window rather than an Activity (see [DebugOverlayWindow]'s own doc comment) and so has no
 *  Activity of its own to call `registerForActivityResult` on. [bind] is called once from
 *  MainActivity.onCreate; [requestConsent] is called from DebugOverlayHost whenever the pilot
 *  checks the full-device capture checkbox. */
object MediaProjectionRequester {
    private var launchIntent: ((Intent) -> Unit)? = null
    private var pendingCallback: ((resultCode: Int, data: Intent?) -> Unit)? = null

    fun bind(launch: (Intent) -> Unit) {
        launchIntent = launch
    }

    /** Called by MainActivity's ActivityResultLauncher callback with whatever the system consent
     *  dialog returned. */
    fun onResult(resultCode: Int, data: Intent?) {
        val callback = pendingCallback
        pendingCallback = null
        callback?.invoke(resultCode, data)
    }

    fun requestConsent(context: Context, onResult: (resultCode: Int, data: Intent?) -> Unit) {
        val launch = launchIntent
        if (launch == null) {
            onResult(Activity.RESULT_CANCELED, null)
            return
        }
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        pendingCallback = onResult
        launch(manager.createScreenCaptureIntent())
    }
}
