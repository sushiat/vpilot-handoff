package at.sushi.handoff.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A thin scrollbar thumb for a plain `Modifier.verticalScroll` Column, drawn in its own column
 *  next to the scrolling content rather than on top of it -- unlike a LazyColumn there's no
 *  built-in scrollbar for this, and a long dialog cut off exactly at the viewport edge gives no
 *  visual hint that there's more below. Place it as a sibling of the scrolling Column inside a Row;
 *  it sizes itself (full height, a 6dp gap, then a 4dp thumb column), so callers pass no sizing.
 *  The order matters: padding is applied *before* width, because `width(4.dp).padding(start = 6.dp)`
 *  leaves the padding no room and the thumb gets 0dp to draw in. An earlier draw-over
 *  `Modifier.verticalScrollbar` painted the thumb across the content's right edge (issue #174,
 *  and the same overlap that RowColorThemeDialog had already hit), so this reserves real layout
 *  space instead. Draws nothing while the content fits. */
@Composable
fun ScrollbarTrack(
    state: ScrollState,
    color: Color,
    modifier: Modifier = Modifier,
    /** Shifts the drawn thumb right of its layout slot without taking layout space, for dialogs
     *  whose own end padding leaves a wide empty strip to the right of the Row (the thumb would
     *  otherwise sit tight against the content with a big gap behind it). */
    thumbOffset: Dp = 0.dp
) {
    Canvas(modifier.fillMaxHeight().padding(start = 6.dp).width(4.dp).offset(x = thumbOffset)) {
        if (state.maxValue <= 0) return@Canvas
        val viewportHeight = size.height
        val contentHeight = viewportHeight + state.maxValue
        val thumbHeight = (viewportHeight * (viewportHeight / contentHeight)).coerceAtLeast(24.dp.toPx())
        val scrollableTrack = viewportHeight - thumbHeight
        val thumbY = scrollableTrack * (state.value.toFloat() / state.maxValue)
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, thumbY),
            size = Size(size.width, thumbHeight),
            cornerRadius = CornerRadius(size.width / 2)
        )
    }
}
