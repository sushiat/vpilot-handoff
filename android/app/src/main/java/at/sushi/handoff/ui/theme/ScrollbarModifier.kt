package at.sushi.handoff.ui.theme

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A thin, always-drawn (while scrollable) thumb for a plain `Modifier.verticalScroll` Column --
 *  unlike a LazyColumn there's no built-in scrollbar for this, and a long dialog that's cut off
 *  exactly at the viewport edge gives no visual hint that there's more content below. Draws
 *  directly over the content rather than adding a separate layout box, so it needs no extra
 *  width reserved in the scrolling content itself. */
fun Modifier.verticalScrollbar(
    state: ScrollState,
    color: Color,
    width: Dp = 4.dp,
    endPadding: Dp = 2.dp
): Modifier = drawWithContent {
    drawContent()
    val viewportHeight = size.height
    val contentHeight = viewportHeight + state.maxValue
    if (state.maxValue <= 0 || contentHeight <= 0f) return@drawWithContent

    val thumbHeight = (viewportHeight * (viewportHeight / contentHeight)).coerceAtLeast(24.dp.toPx())
    val scrollableTrack = viewportHeight - thumbHeight
    val thumbY = if (state.maxValue == 0) 0f else scrollableTrack * (state.value.toFloat() / state.maxValue)
    val thumbWidthPx = width.toPx()

    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - thumbWidthPx - endPadding.toPx(), thumbY),
        size = Size(thumbWidthPx, thumbHeight),
        cornerRadius = CornerRadius(thumbWidthPx / 2)
    )
}
