package at.sushi.handoff

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import at.sushi.handoff.ui.debug.MediaProjectionRequester
import at.sushi.handoff.ui.main.MainScreen

class MainActivity : ComponentActivity() {
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startConnectionService() }

    // Issue #73a -- MediaProjection consent must be requested via an Activity; DebugOverlayHost's
    // overlay window isn't one, so MediaProjectionRequester bridges the two.
    private val requestMediaProjection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            MediaProjectionRequester.onResult(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MediaProjectionRequester.bind { intent -> requestMediaProjection.launch(intent) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startConnectionService()
        }

        // Issue #123 -- a phone-class screen doesn't have room for the tablet-focused two-pane
        // layout in landscape (TopBar+Footer alone eat most of a phone's short landscape height,
        // squeezing the controller list to near-nothing); rather than build a second, shrunken
        // layout for a state that'd still be too small to be useful, phones are locked to
        // portrait outright. Tablets (this app's primary target) keep free rotation, matching
        // today's behavior exactly -- no android:screenOrientation is set in the manifest, so
        // UNSPECIFIED here is a no-op restoring the previous default for them.
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp < PhoneClassMaxSmallestWidthDp) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        setContent {
            MainScreen()
        }
    }

    private fun startConnectionService() {
        // onCreate re-runs on every activity relaunch (rotation, split-screen resize -- the
        // exact scenario this app is built for), but the service instance survives that. Calling
        // startForegroundService() again on an already-running service reaches onStartCommand
        // without a matching startForeground() call (it already dropped out of foreground state
        // via appVisibilityObserver once the app became visible), which trips Android's 5s
        // "didn't call startForeground in time" watchdog and kills the process.
        if (HandoffConnectionService.instance != null) return
        ContextCompat.startForegroundService(this, Intent(this, HandoffConnectionService::class.java))
    }
}
