package at.sushi.handoff.ui.dialogs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** Issue #225 -- shown instead of the tour when the "?" is tapped in a window too narrow for the tour's
 *  pages to be readable (see NarrowWindowMaxWidth). */
@Composable
fun TourNeedsWiderWindowDialog(onDismiss: () -> Unit) {
    val colors = LocalHandoffColors.current
    SimpleDialogPanel(title = "Tour unavailable", width = 260.dp, onDismiss = onDismiss) {
        Text(
            "The tour needs a wider window. Make Handoff wider, or return it to fullscreen, and try again.",
            fontSize = 15.sp,
            color = colors.textMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("OK", fontSize = 15.sp)
        }
    }
}
