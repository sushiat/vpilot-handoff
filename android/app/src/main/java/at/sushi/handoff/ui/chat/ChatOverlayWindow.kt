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

    /** [xOffsetPx] is an absolute screen X coordinate (this window's left edge), not a
     *  gravity-relative offset -- `Gravity.END`/`START` anchor to the *display's* edge, which
     *  places the panel flush against the far screen edge, past the split-screen neighbor app,
     *  rather than immediately next to this app's own window. The caller computes this from this
     *  app's actual on-screen window bounds (see MainScreen.kt's ChatOverlayHost), so the panel
     *  always sits directly adjacent to this app regardless of what fraction of the display it
     *  occupies or where exactly it's positioned. */
    fun show(panelWidthPx: Int, xOffsetPx: Int, content: @Composable () -> Unit) {
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
            // FLAG_NOT_TOUCH_MODAL is the critical one: without it, a focused overlay window
            // swallows *every* touch on the entire screen and routes it to itself (the standard
            // behavior a modal dialog relies on for tap-outside-to-dismiss) -- not just touches
            // within its own bounds. That's what made the whole main Activity window completely
            // unresponsive while this was showing, not just the area it visually covers.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = xOffsetPx
            y = 0
            // Without this, the overlay window defaults to ADJUST_PAN (just shifts the whole
            // window up by the keyboard's height) instead of properly resizing/reporting IME
            // insets -- RESIZE is what lets Compose's own Modifier.imePadding() (see
            // ChatPanelContent.kt) precisely track the live keyboard height, including any
            // IME-specific accessory bars (suggestion strips, the cut/copy/paste toolbar), rather
            // than the compose bar ending up shifted by an approximate/stale amount.
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
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
