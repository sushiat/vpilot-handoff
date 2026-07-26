package at.sushi.handoff.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A text field styled entirely from [HandoffColors] -- used everywhere in place of Material3's
 *  `OutlinedTextField`, whose default outline/cursor color comes from the ambient MaterialTheme
 *  color scheme (an unrelated, un-themed purple/blue in this app) rather than this app's own
 *  design tokens. Matches the reference's `inputStyle`/`composeInputStyle`: `t.panelAlt`
 *  background, `1px solid t.border`, 8px radius. */
@Composable
fun HandoffTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    fontSize: TextUnit = 13.sp,
    horizontalPadding: androidx.compose.ui.unit.Dp = 11.dp,
    verticalPadding: androidx.compose.ui.unit.Dp = 9.dp
) {
    val colors = LocalHandoffColors.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .background(colors.panelAlt, shape)
            .border(1.dp, colors.border, shape)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    ) {
        if (value.isEmpty() && placeholder != null) {
            Text(placeholder, fontSize = fontSize, color = colors.textMuted)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(fontSize = fontSize, color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
