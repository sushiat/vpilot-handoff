package at.sushi.handoff.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import at.sushi.handoff.SplitSide

/** Backs the [Lifecycle]/[ViewModelStore]/[SavedStateRegistry] a [ComposeView] needs, which it
 *  would otherwise get for free from a hosting Activity -- this view isn't attached through an
 *  Activity's hierarchy at all, so it needs its own minimal owner. */
private class OverlayLifecycleOwner : SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    fun start() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}

/** Hosts the chat panel as a real floating window (via [WindowManager], `TYPE_APPLICATION_OVERLAY`)
 *  rather than ordinary in-Activity Compose content -- the only way for it to genuinely extend
 *  over a split-screen neighbor's half of the display, since a normal window is clipped to its
 *  own bounds. Reuses the same draw-over-other-apps permission CLAUDE.md already commits to for
 *  the chat-heads/interruption model, rather than building a second overlay mechanism.
 *
 *  Only used while [at.sushi.handoff.LayoutMode] is SPLIT; in fullscreen the chat panel is
 *  ordinary in-Activity Compose content next to the controller list (see MainScreen.kt) and this
 *  class is never involved. */
class ChatOverlayWindow(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    val isShowing: Boolean get() = composeView != null

    fun show(panelWidthPx: Int, splitSide: SplitSide, content: @Composable () -> Unit) {
        if (composeView != null || !hasOverlayPermission(context)) return

        val owner = OverlayLifecycleOwner().also { it.start() }
        lifecycleOwner = owner

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent(content)
        }

        val params = WindowManager.LayoutParams(
            panelWidthPx,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Grows from the controller-list column's edge outward, into the side of the screen
            // this app *doesn't* otherwise occupy -- i.e. genuinely over the split-screen
            // neighbor, matching the design's visual intent (see issue #13 screen 5).
            gravity = Gravity.TOP or if (splitSide == SplitSide.LEFT) Gravity.END else Gravity.START
        }
        windowManager.addView(view, params)
        composeView = view
    }

    fun hide() {
        composeView?.let { runCatching { windowManager.removeView(it) } }
        composeView = null
        lifecycleOwner?.destroy()
        lifecycleOwner = null
    }

    @SuppressLint("InlinedApi")
    private fun overlayWindowType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    companion object {
        fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun overlayPermissionIntent(context: Context) = android.content.Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }
}
