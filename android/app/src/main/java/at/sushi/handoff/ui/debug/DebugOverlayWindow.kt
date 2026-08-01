package at.sushi.handoff.ui.debug

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
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

/** Same minimal [Lifecycle]/[ViewModelStore]/[SavedStateRegistry] owner as
 *  [at.sushi.handoff.ui.chat.ChatOverlayWindow]'s own -- a WindowManager-attached [ComposeView]
 *  isn't hosted through an Activity, so it needs one of its own. Duplicated rather than shared
 *  since neither overlay depends on the other and there's nothing else in common worth coupling
 *  them over. */
private class DebugOverlayLifecycleOwner : SavedStateRegistryOwner, ViewModelStoreOwner {
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

/** Remembers the debug window's last position/size for the rest of this process's lifetime only
 *  (issue #65: matches debug mode's own session-only lifetime, not persisted across an app
 *  restart) -- so closing and reopening the window via the version-string toggle doesn't reset it
 *  back to a default spot every time. */
object DebugOverlayWindowState {
    var xPx: Int? = null
    var yPx: Int? = null
    var widthPx: Int? = null
    var heightPx: Int? = null
}

/** Hosts the debug window as a real floating [WindowManager] window (`TYPE_APPLICATION_OVERLAY`),
 *  same reasoning and mechanism as [at.sushi.handoff.ui.chat.ChatOverlayWindow] -- the pilot needs
 *  to keep using the controller list/chat while this is open (comparing what it says against
 *  what's happening live), and needs to be able to drag it anywhere useful, including *across* the
 *  split-screen boundary into the neighboring EFB app's half of the screen, which only an overlay
 *  window (not an in-app dialog) can do. Draggable by its own title bar -- see [show]'s
 *  `onDrag` callback, which the caller wires to a title-bar `pointerInput` drag gesture that calls
 *  back into [updatePosition]. */
class DebugOverlayWindow(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var lifecycleOwner: DebugOverlayLifecycleOwner? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = composeView != null

    fun show(widthPx: Int, heightPx: Int, xPx: Int, yPx: Int, content: @Composable () -> Unit) {
        if (composeView != null || !hasOverlayPermission(context)) return

        val owner = DebugOverlayLifecycleOwner().also { it.start() }
        lifecycleOwner = owner

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent(content)
        }

        val layoutParams = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            overlayWindowType(),
            // FLAG_NOT_TOUCH_MODAL -- see ChatOverlayWindow's own doc comment for why this is the
            // one flag that actually matters: without it, this window would swallow every touch
            // on the whole screen, not just its own bounds.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = xPx
            y = yPx
        }
        windowManager.addView(view, layoutParams)
        composeView = view
        params = layoutParams
    }

    /** Called from the title-bar drag gesture -- updates this window's on-screen position live as
     *  the pilot drags, and remembers it in [DebugOverlayWindowState] for the rest of the
     *  session. */
    fun updatePosition(xPx: Int, yPx: Int) {
        val view = composeView ?: return
        val layoutParams = params ?: return
        layoutParams.x = xPx
        layoutParams.y = yPx
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
        DebugOverlayWindowState.xPx = xPx
        DebugOverlayWindowState.yPx = yPx
    }

    fun hide() {
        composeView?.let { runCatching { windowManager.removeView(it) } }
        composeView = null
        params = null
        lifecycleOwner?.destroy()
        lifecycleOwner = null
    }

    @SuppressLint("InlinedApi")
    private fun overlayWindowType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    companion object {
        fun hasOverlayPermission(context: Context): Boolean = android.provider.Settings.canDrawOverlays(context)
    }
}
