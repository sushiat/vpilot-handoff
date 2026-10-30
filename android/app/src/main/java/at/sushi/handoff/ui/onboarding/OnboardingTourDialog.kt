package at.sushi.handoff.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import at.sushi.handoff.ui.main.isPhoneClassDevice
import at.sushi.handoff.R
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** First-launch onboarding tour (issue #205). Deliberately not closable by scrim tap or the back
 *  gesture: the only ways out are Skip (every page but the last) and the last page's Get started (Go on a phone),
 *  both of which call [onFinish]. Replaying it from Settings goes through the same dialog. */
@Composable
fun OnboardingTourDialog(onFinish: () -> Unit) {
    val colors = LocalHandoffColors.current
    val pages = TourPage.entries
    // A pager, so the neighbouring page follows the finger while you swipe the text area and snaps back or
    // on when you let go. Its own touch scrolling is off: only the text area may start a page swipe (the visual
    // above scrolls vertically and must not turn pages).
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val index = pagerState.currentPage
    val isLast = index == pages.lastIndex
    // "Get started" is too long for the footer on a phone, which says "Go" (see isPhoneClassDevice for the test).
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val phone = remember(configuration) { isPhoneClassDevice(context, configuration) }
    fun goTo(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page, animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)) }
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val panelWidth = min(600.dp, maxWidth - 24.dp)
            // Fixed, not just capped: every page gets the same panel, so Back/Next and the dots never
            // move between pages (the visual flexes to the height the text above the footer leaves).
            val panelHeight = min(700.dp, maxHeight - 24.dp)
            Column(
                Modifier
                    .width(panelWidth)
                    .height(panelHeight)
                    .background(colors.panel, RoundedCornerShape(24.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(24.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Same logo and wordmark as the main screen's header, in the same muted colour.
                    Icon(
                        painterResource(R.drawable.ic_handoff_mark),
                        contentDescription = null,
                        tint = colors.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "Handoff",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textMuted,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                    Text(
                        "${index + 1} OF ${pages.size}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        color = colors.textMuted.copy(alpha = 0.7f),
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(end = 4.dp).weight(1f)
                    )
                    if (!isLast) {
                        Box(
                            Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(onClick = onFinish).padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Skip", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textMuted)
                        }
                    }
                }

                // The page (visual plus text) moves with the swipe; the header and the footer stay put. Each page
                // keeps its own scroll position, and a page fades a little as it leaves.
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                    userScrollEnabled = false
                ) { pageIndex ->
                    val page = pages[pageIndex]
                    val scrollState = rememberScrollState()
                    Column(
                        Modifier.fillMaxSize().graphicsLayer {
                            val away = abs((pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction)
                            alpha = 1f - 0.5f * away.coerceIn(0f, 1f)
                        }
                    ) {
                        // The visual takes whatever height the title/body/footer leave and scrolls when a
                        // narrow or short window leaves less than it needs. A fade with a chevron at the bottom
                        // edge says so for as long as there is more below.
                        BoxWithConstraints(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(vertical = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.bg)
                                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(scrollState)
                                    .padding(14.dp)
                                    // At least the visible height, so a short visual is centred in the panel (and only
                                    // taller ones scroll).
                                    .heightIn(min = maxHeight - 28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                TourVisual(page)
                            }
                            // A real amount left to scroll, not just canScrollForward: a visual sized to exactly the panel
                            // can overflow by a pixel from dp rounding, which would otherwise show the hint on a page that
                            // has nothing more to show.
                            val moreBelow = with(LocalDensity.current) { (scrollState.maxValue - scrollState.value) > 12.dp.toPx() }
                            if (moreBelow) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .background(Brush.verticalGradient(listOf(Color.Transparent, colors.bg))),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowDown,
                                        contentDescription = "Scroll for more",
                                        tint = colors.textMuted,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }

                        // Swiping the text area drags the pager with the finger. On release it moves to the next (left
                        // swipe) or previous (right swipe) page only if dragged far enough, otherwise it snaps back to
                        // this one. The pager cannot be dragged past the first or last page.
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .pointerInput(pageIndex) {
                                    var dragged = 0f
                                    var startPage = 0
                                    val threshold = minOf(size.width * 0.25f, 72.dp.toPx())
                                    val settle = {
                                        val target = when {
                                            dragged <= -threshold -> startPage + 1
                                            dragged >= threshold -> startPage - 1
                                            else -> startPage
                                        }.coerceIn(0, pages.lastIndex)
                                        goTo(target)
                                    }
                                    detectHorizontalDragGestures(
                                        onDragStart = {
                                            dragged = 0f
                                            startPage = pagerState.currentPage
                                        },
                                        onDragEnd = { settle() },
                                        onDragCancel = { settle() },
                                        onHorizontalDrag = { change, amount ->
                                            change.consume()
                                            dragged += amount
                                            pagerState.dispatchRawDelta(-amount)
                                        }
                                    )
                                }
                        ) {
                            Text(page.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = colors.text, modifier = Modifier.padding(top = 8.dp))
                            if (page.body.isNotBlank()) {
                                Text(
                                    buildAnnotatedString {
                                        parseBold(page.body).forEach { (text, bold) ->
                                            if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.text)) { append(text) } else append(text)
                                        }
                                    },
                                    fontSize = 16.sp,
                                    color = colors.textMuted,
                                    // Reserves the longest bodies' height so the visual above doesn't jump by a line between pages.
                                    minLines = 3,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            if (page.legend.isNotEmpty()) {
                                TourLegend(page.legend, Modifier.padding(top = 8.dp))
                            }

                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Back and Next/Go share TourButtonMinWidth, so the strip between them is centred while Back is
                    // hidden on page 1.
                    Box(Modifier.widthIn(min = TourButtonMinWidth)) {
                        if (index > 0) {
                            TourButton("Back", filled = false) { goTo(index - 1) }
                        }
                    }
                    // The dots take the space between the buttons and shrink to fit it (with a margin each side),
                    // instead of staying a fixed size and touching the buttons on a narrow screen.
                    BoxWithConstraints(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        val gap = 5.dp
                        val count = pages.size
                        // The active pill is three dots wide: (count - 1) dots + one pill + (count - 1) gaps.
                        val fitting = (maxWidth - 16.dp - gap * (count - 1)) / (count + 2)
                        val dot = min(8.dp, fitting)
                        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                            pages.indices.forEach { i ->
                                val dotWidth by animateDpAsState(if (i == index) dot * 3 else dot, label = "dotWidth")
                                val dotColor by animateColorAsState(if (i == index) colors.accent else colors.border, label = "dotColor")
                                Box(Modifier.size(width = dotWidth, height = dot).background(dotColor, RoundedCornerShape(dot / 2)))
                            }
                        }
                    }
                    Box(Modifier.widthIn(min = TourButtonMinWidth), contentAlignment = Alignment.CenterEnd) {
                        TourButton(if (isLast) (if (phone) "Go" else "Get started") else "Next", filled = true) {
                            if (isLast) onFinish() else goTo(index + 1)
                        }
                    }
                }
            }
        }
    }
}

// Back, Next and Go share this width. 84dp (not wider) so the three fit with the page dots on a phone.
private val TourButtonMinWidth = 84.dp

@Composable
private fun TourButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            // One minimum width for Back, Next and Go, so the last page's shorter "Go" is as wide as "Next".
            .widthIn(min = TourButtonMinWidth)
            .heightIn(min = 48.dp)
            .then(if (filled) Modifier.background(colors.accent, shape) else Modifier.border(BorderStroke(1.dp, colors.border), shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (filled) Color.White else colors.text, maxLines = 1, softWrap = false)
    }
}

/** Numbered legend: a 22dp accent circle per line. Two columns when the dialog is wide, one on a phone, where
 *  two columns would wrap every line into a few words; the circle stays level with the first line of text. */
@Composable
private fun TourLegend(items: List<String>, modifier: Modifier = Modifier) {
    val colors = LocalHandoffColors.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 480.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.withIndex().chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (i, text) ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CalloutBadge(i + 1)
                            Text(text, fontSize = 13.sp, color = colors.textMuted, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    if (row.size < columns) Box(Modifier.weight((columns - row.size).toFloat()))
                }
            }
        }
    }
}
