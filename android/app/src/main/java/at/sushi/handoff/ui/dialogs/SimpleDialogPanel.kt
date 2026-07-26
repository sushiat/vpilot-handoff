package at.sushi.handoff.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** Settings/Nearby-aircraft dialog chrome -- distinct from [KeypadDialogPanel]: the reference's
 *  `settingsDialog.panelStyle`/`nearbyDialog.panelStyle` use a plain 14px bold title and a
 *  borderless 50%-opacity "✕" (not the COM/XPDR dialogs' muted/uppercase/letter-spaced title +
 *  boxed close button), 16px corner radius (not 24px), and 18px padding (not 20px). Sharing
 *  [KeypadDialogPanel] across all dialogs was a real mismatch -- these two header styles are
 *  genuinely different in the design reference, not just a missed detail. */
@Composable
fun SimpleDialogPanel(
    title: String,
    width: Dp,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        SimpleDialogPanelChrome(title, width, onDismiss, content)
    }
}

/** The panel's visual chrome without a hosting [Dialog] window -- for callers that need to
 *  render it as plain inline content instead (see [InlineModalScrim]). A Compose `Dialog` always
 *  creates a window attached to the *Activity's* window/task, which in multi-window mode is
 *  bounded to that Activity's own on-screen rectangle and, for this app's split-screen chat
 *  overlay specifically, sits *below* the overlay's `TYPE_APPLICATION_OVERLAY` window in z-order
 *  regardless of which composition triggered it -- so a dialog opened from within the chat
 *  overlay would render squeezed into this app's own narrow window slice, not anywhere near
 *  where the user is actually looking, and could end up effectively unreachable. */
@Composable
fun SimpleDialogPanelChrome(
    title: String,
    width: Dp,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalHandoffColors.current
    Column(
        Modifier
            .width(width)
            .background(colors.panel, RoundedCornerShape(16.dp))
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(18.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.text)
            Text(
                "✕",
                fontSize = 16.sp,
                color = colors.text.copy(alpha = 0.5f),
                modifier = Modifier.clickable(onClick = onDismiss)
            )
        }
        content()
    }
}

/** A full-bleed scrim + centered panel, drawn as plain Compose content within whatever window is
 *  already hosting the caller -- used instead of a system [Dialog] when that window might be the
 *  split-screen chat overlay's own `TYPE_APPLICATION_OVERLAY` window (see
 *  [SimpleDialogPanelChrome]'s doc comment for why a real Dialog doesn't work there). Tapping the
 *  scrim dismisses, matching a normal Dialog's outside-tap-to-dismiss behavior. */
@Composable
fun InlineModalScrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {} // absorb taps on the panel itself so they don't fall through to the scrim's dismiss handler
            )
        ) {
            content()
        }
    }
}
