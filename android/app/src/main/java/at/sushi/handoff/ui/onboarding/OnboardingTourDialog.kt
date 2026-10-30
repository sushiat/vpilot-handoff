package at.sushi.handoff.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import at.sushi.handoff.R
import at.sushi.handoff.ui.theme.LocalHandoffColors

/** First-launch onboarding tour (issue #205). Deliberately not closable by scrim tap or the back
 *  gesture: the only ways out are Skip (every page but the last) and the last page's Get started,
 *  both of which call [onFinish]. Replaying it from Settings goes through the same dialog. */
@Composable
fun OnboardingTourDialog(onFinish: () -> Unit) {
    val colors = LocalHandoffColors.current
    val pages = TourPage.entries
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[index]
    val isLast = index == pages.lastIndex

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

                // The visual takes whatever height the title/body/footer leave and scrolls when a
                // narrow or short window leaves less than it needs.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 8.dp)
                        .background(colors.bg, RoundedCornerShape(16.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TourVisual(page)
                }

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

                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Same min width on both sides keeps the dots centred while Back is hidden on page 1.
                    Box(Modifier.widthIn(min = 96.dp)) {
                        if (index > 0) {
                            TourButton("Back", filled = false) { index-- }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        pages.indices.forEach { i ->
                            Box(
                                Modifier
                                    .size(width = if (i == index) 24.dp else 8.dp, height = 8.dp)
                                    .background(
                                        if (i == index) colors.accent else colors.border,
                                        if (i == index) RoundedCornerShape(4.dp) else CircleShape
                                    )
                            )
                        }
                    }
                    Box(Modifier.widthIn(min = 96.dp), contentAlignment = Alignment.CenterEnd) {
                        TourButton(if (isLast) "Get started" else "Next", filled = true) {
                            if (isLast) onFinish() else index++
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TourButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .then(if (filled) Modifier.background(colors.accent, shape) else Modifier.border(BorderStroke(1.dp, colors.border), shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (filled) Color.White else colors.text, maxLines = 1, softWrap = false)
    }
}

/** Numbered legend: a 22dp accent circle per line, two columns. */
@Composable
private fun TourLegend(items: List<String>, modifier: Modifier = Modifier) {
    val colors = LocalHandoffColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.withIndex().chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (i, text) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CalloutBadge(i + 1)
                        Text(text, fontSize = 13.sp, color = colors.textMuted)
                    }
                }
                if (row.size == 1 && items.size > 1) Box(Modifier.weight(1f))
            }
        }
    }
}
