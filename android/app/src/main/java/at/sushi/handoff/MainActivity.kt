package at.sushi.handoff

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import at.sushi.handoff.ui.chat.ChatOverlayWindow
import at.sushi.handoff.ui.main.MainScreen

class MainActivity : ComponentActivity() {
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startConnectionService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startConnectionService()
        }

        // Needed for the chat panel's split-screen overlay (ChatOverlayWindow) -- requested here
        // rather than lazily when chat is first opened, so it's available by the time the user
        // reaches for it instead of silently no-oping.
        if (!ChatOverlayWindow.hasOverlayPermission(this)) {
            startActivity(ChatOverlayWindow.overlayPermissionIntent(this))
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
