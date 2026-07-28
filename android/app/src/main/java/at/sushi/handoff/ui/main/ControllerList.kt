package at.sushi.handoff.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import at.sushi.handoff.ui.theme.RobotoMono
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.sushi.handoff.protocol.Controller
import at.sushi.handoff.protocol.RadioFrequency
import at.sushi.handoff.ui.theme.ControllerBadge
import at.sushi.handoff.ui.theme.FacilityColors
import at.sushi.handoff.ui.theme.LocalHandoffColors
import at.sushi.handoff.ui.theme.controllerBadges
import at.sushi.handoff.ui.theme.controllerRowColors
import at.sushi.handoff.ui.theme.facilitySuffixName
import at.sushi.handoff.ui.theme.oklch
import kotlinx.coroutines.delay

/** A row with an active, unresolved "contact me" (or the SELCAL badge) alternates between two
 *  colors every 500ms, hard-cut (not eased) -- matches the reference's own
 *  `@keyframes contactFlash{0%,49%{a} 50%,99%{b}}` over a 1s cycle exactly. */
@Composable
private fun rememberFlashPhaseA(isFlashing: Boolean): Boolean {
    var phaseA by remember { mutableStateOf(true) }
    LaunchedEffect(isFlashing) {
        if (!isFlashing) {
            phaseA = true
            return@LaunchedEffect
        }
        while (true) {
            delay(500)
            phaseA = !phaseA
        }
    }
    return phaseA
}

/** The reference's fixed "phase B" text color for a flashing row/badge (`--flash-text-b:#111`),
 *  distinct from the near-black `rgba(0,0,0,.82)` used elsewhere. */
private val flashPhaseBText = Color(0xFF111111)

/** Ratings VATSIM defines, per issue #13's "Color & badge logic" table -- display-only, never
 *  used in ranking. */
private val ratingLabels = mapOf(
    1 to "OBS", 2 to "S1", 3 to "S2", 4 to "S3", 5 to "C1", 6 to "C2", 7 to "C3",
    8 to "I1", 9 to "I2", 10 to "I3", 11 to "SUP", 12 to "ADM"
)

private val badgeLabels = mapOf(
    ControllerBadge.TUNED to "TUNED",
    ControllerBadge.STBY to "STBY",
    ControllerBadge.CONTACT_ME to "CONTACT ME",
    ControllerBadge.NEXT to "NEXT",
    ControllerBadge.NEXT_LIKELY to "NEXT?",
    ControllerBadge.PINNED to "PINNED",
    ControllerBadge.SELCAL to "SELCAL"
)

@Composable
fun ControllerList(
    controllers: List<Controller>,
    com1Active: Int?,
    com2Active: Int?,
    onTogglePin: (String) -> Unit,
    onOpenChatWith: (String) -> Unit,
    onTuneCom1Active: (Int) -> Unit,
    onTuneCom2Active: (Int) -> Unit,
    onTuneCom1Standby: (Int) -> Unit,
    onTuneCom2Standby: (Int) -> Unit,
    onDismissSelcal: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalHandoffColors.current
    // `modifier` must carry the caller's Modifier.weight(1f) (Column scope) -- otherwise this
    // Column has no bounded height of its own, and the LazyColumn below's weight(1f) measures
    // against the wrong (much larger) constraint, crowding whatever comes after this composable
    // in the parent Column off the bottom of the screen.
    // The reference's frame background (t.bg -- oklch(97% 0.006 250), a pale blue-gray, not
    // pure white) is what this inherited by design, but on the actual tablet display that
    // reads as a visible off-white rather than matching the panel-colored top bar/footer --
    // using colors.panel here instead, per the user's explicit call on the real device.
    Column(modifier.fillMaxWidth().background(colors.panel)) {
        Text(
            "CONTROLLERS · ${controllers.size}",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textMuted,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)
        )
        // Shared across every row so the frequency column lines up regardless of individual
        // callsign length -- matches the original JS reference's own formula exactly
        // (`Math.max(90, longestCallsign * 8.5 + 10)`), just carried over from a static
        // `widthIn(min = 90.dp)` that never actually read the list's real callsigns.
        val callsignColWidth = remember(controllers) {
            val longest = controllers.maxOfOrNull { it.callsign.length } ?: 0
            maxOf(90f, longest * 8.5f + 10f).dp
        }
        // Measured once (the format is always exactly "DDD.DDD", per RadioFrequency.format --
        // fixed shape regardless of which controller) rather than guessed at, so ControllerRow
        // can precisely decide whether frequency actually fits inline instead of relying on a
        // blanket row-width threshold that didn't account for how much the callsign column and
        // icon group already consume -- that mismatch was clipping/hiding frequency entirely at
        // exactly the widths it was supposed to still fit.
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val frequencyTextWidth = remember(textMeasurer) {
            val widthPx = textMeasurer.measure(
                text = "123.725",
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = RobotoMono)
            ).size.width
            with(density) { widthPx.toDp() }
        }
        // A newly badged row (TUNED/STBY/CONTACT_ME/NEXT/NEXT_LIKELY/PINNED/SELCAL) can land above
        // whatever's currently in the viewport if the pilot has scrolled down -- easy to miss
        // entirely, worst of all for CONTACT_ME. Auto-scroll to the top whenever the *set* of
        // badged callsigns gains a new member, not on every recompute (badges flipping off, or a
        // row merely changing position among already-badged ones, shouldn't yank the scroll
        // position around).
        val listState = rememberLazyListState()
        var previousBadgedCallsigns by remember { mutableStateOf<Set<String>?>(null) }
        LaunchedEffect(controllers, com1Active, com2Active) {
            val currentBadged = controllers.filter { controller ->
                controllerBadges(controller, com1Active, com2Active).isNotEmpty()
            }.mapTo(mutableSetOf()) { it.callsign }

            val previous = previousBadgedCallsigns
            if (previous != null && (currentBadged - previous).isNotEmpty()) {
                listState.animateScrollToItem(0)
            }
            previousBadgedCallsigns = currentBadged
        }

        // Reference container is `padding:0 10px 14px;display:flex;flex-direction:column;
        // gap:6px` -- rows are individually rounded cards with their own border/gap between
        // them, not a plain divided list (no HorizontalDivider in the reference at all).
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Rendered in exactly the order the server sent it -- never re-sorted client-side.
            items(controllers, key = { it.callsign }) { controller ->
                ControllerRow(
                    controller = controller,
                    callsignColWidth = callsignColWidth,
                    frequencyTextWidth = frequencyTextWidth,
                    com1Active = com1Active,
                    com2Active = com2Active,
                    onTogglePin = { onTogglePin(controller.callsign) },
                    onOpenChat = { onOpenChatWith(controller.callsign) },
                    onTuneCom1Active = { onTuneCom1Active(controller.frequency) },
                    onTuneCom2Active = { onTuneCom2Active(controller.frequency) },
                    onTuneCom1Standby = { onTuneCom1Standby(controller.frequency) },
                    onTuneCom2Standby = { onTuneCom2Standby(controller.frequency) },
                    onDismissSelcal = { onDismissSelcal(controller.callsign) }
                )
            }
        }
    }
}

// Fixed quantities of the row's non-frequency, non-callsign content, used to precisely compute
// how much space is actually left over for the frequency text -- a blanket row-width threshold
// turned out to badly mispredict this (it doesn't know how much callsignColWidth or the icon
// group actually consume), which clipped/hid the frequency entirely at widths it was supposed to
// still fit at.
private val RowHorizontalPadding = 32.dp // 16dp each side
private val RowItemGap = 10.dp // Arrangement.spacedBy on the outer Row
private val IconsGroupWidth = 62.dp // pin + message, 30dp each + 2dp gap
private val RatingBadgeWidth = 32.dp // ~30dp badge + 2dp gap to the icons group
// Real name/CID + station suffix name have no fixed shape (unlike the frequency), so this is a
// deliberately generous estimate for the purposes of deciding whether they'll fit -- overshooting
// only means hiding them a little earlier than strictly necessary, never clipping the frequency.
private val InfoColumnEstimatedWidth = 90.dp

@Composable
private fun ControllerRow(
    controller: Controller,
    callsignColWidth: Dp,
    frequencyTextWidth: Dp,
    com1Active: Int?,
    com2Active: Int?,
    onTogglePin: () -> Unit,
    onOpenChat: () -> Unit,
    onTuneCom1Active: () -> Unit,
    onTuneCom2Active: () -> Unit,
    onTuneCom1Standby: () -> Unit,
    onTuneCom2Standby: () -> Unit,
    onDismissSelcal: () -> Unit
) {
    val colors = LocalHandoffColors.current
    val rowColors = controllerRowColors(controller, com1Active, com2Active, colors)
    val badges = controllerBadges(controller, com1Active, com2Active)
    var menuOpen by remember { mutableStateOf(false) }

    val rowPhaseA = rememberFlashPhaseA(rowColors.isFlashing)
    val background = if (rowColors.isFlashing && !rowPhaseA) FacilityColors.hazardYellow else rowColors.background
    val text = if (rowColors.isFlashing && !rowPhaseA) flashPhaseBText else rowColors.text
    val badgeBackground = if (rowColors.isFlashing && !rowPhaseA) Color.Black.copy(alpha = 0.1f) else rowColors.badgeBackground

    // SELCAL flashes independently of the row (hazard-yellow <-> a fixed dark red, always #111
    // text) -- it only ever appears on the isCurrent row, which never has isFlashing itself, so
    // there's no conflict between the two animations.
    val selcalPhaseA = rememberFlashPhaseA(ControllerBadge.SELCAL in badges)
    val selcalBackground = if (selcalPhaseA) FacilityColors.hazardYellow else oklch(0.58f, 0.16f, 10f)

    // Reference rowStyle is `border-radius:10px;background:...;border:1.5px solid ...` -- rows
    // are individually rounded cards, not a plain rectangular strip.
    val rowShape = RoundedCornerShape(10.dp)
    val suffixName = controller.stationName ?: facilitySuffixName(controller.callsign)
    val realNameOrCid = controller.name ?: controller.cid?.toString()
    val ratingLabel = controller.rating?.let { ratingLabels[it] }
    Box {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Step 1: with the info column dropped, does callsign + frequency + icons still fit
            // inline? If not, frequency has to move to its own line regardless -- nothing else
            // left to drop that would free up enough room.
            val reservedMinimal = RowHorizontalPadding + callsignColWidth + IconsGroupWidth + RowItemGap * 2
            val stackFrequency = (maxWidth - reservedMinimal) < frequencyTextWidth

            // Step 2: given that placement, is there *also* room for the real name/CID + station
            // name + rating info column? Checked precisely against actual remaining space rather
            // than a blanket row-width guess, in both the inline and stacked cases.
            val showInfo = if (stackFrequency) {
                val reservedLine1WithInfo = RowHorizontalPadding + callsignColWidth + InfoColumnEstimatedWidth +
                    IconsGroupWidth + RatingBadgeWidth + RowItemGap * 2
                maxWidth >= reservedLine1WithInfo
            } else {
                val reservedInlineWithInfo = RowHorizontalPadding + callsignColWidth + InfoColumnEstimatedWidth +
                    IconsGroupWidth + RatingBadgeWidth + RowItemGap * 3
                (maxWidth - reservedInlineWithInfo) >= frequencyTextWidth
            }

            @Composable
            fun FrequencyText(modifier: Modifier) {
                // maxLines = 1 is load-bearing: without it, Text wraps character-by-character
                // once its available width drops below the string's natural width instead of
                // clipping -- a squawk/frequency value must never break across lines. Combined
                // with stackFrequency below (a full line to itself once the row's too narrow to
                // fit it inline at all), it should also never need to actually clip in practice.
                Text(
                    RadioFrequency.format(controller.frequency),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = RobotoMono,
                    color = text.copy(alpha = 0.9f),
                    maxLines = 1,
                    softWrap = false,
                    modifier = modifier
                )
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(background, rowShape)
                    .border(1.5.dp, rowColors.border, rowShape)
                    .clickable { menuOpen = true }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(Modifier.width(callsignColWidth)) {
                        Text(
                            controller.callsign,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = text,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                        if (badges.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                badges.forEach { badge ->
                                    if (badge == ControllerBadge.SELCAL) {
                                        BadgePill(badgeLabels.getValue(badge), flashPhaseBText, selcalBackground)
                                    } else {
                                        BadgePill(badgeLabels.getValue(badge), text, badgeBackground)
                                    }
                                }
                            }
                        }
                    }

                    // Centering (a spacer on both sides of frequency) is only safe when the info
                    // column is hidden -- that column's own width varies per row (different real
                    // names/CIDs), so once it's in the mix, splitting leftover space into two
                    // equal spacers makes frequency's position depend on *this row's own*
                    // info-text width, breaking alignment across rows. With info hidden there's
                    // nothing row-dependent left (callsignColWidth/frequencyTextWidth are shared
                    // across every row), so centering is safe there. With info shown, frequency
                    // instead sits glued left right after callsign, and the single spacer below
                    // (moved to sit *before* the info column rather than after it) pushes
                    // info+rating+icons together as one clustered group on the right -- matching
                    // how this looked before centering was added, rather than a lone floating gap
                    // between frequency and a left-glued info column.
                    if (!stackFrequency) {
                        if (!showInfo) {
                            Spacer(Modifier.weight(1f))
                        }
                        FrequencyText(Modifier)
                    }

                    // Always present regardless of which of the above are shown -- without a
                    // dedicated flexible element, the info/rating/icons group just packs left
                    // after whatever precedes it once frequency disappears/moves to its own line,
                    // instead of staying anchored to the row's right edge.
                    Spacer(Modifier.weight(1f))

                    // Real name/CID, station suffix name, and rating badge all disappear together
                    // as one unit below HideInfoThreshold -- staggering them at separate
                    // thresholds read as broken (an orphaned line, or the rating surviving until
                    // the row was already unusably cramped) rather than intentional.
                    if (showInfo) {
                        // Reference gap between these two lines is 1px -- Compose Text's default
                        // line height reserves extra ascent/descent padding beyond the glyphs
                        // themselves (legacy Android "font padding"), which reads as a much bigger
                        // gap than 1dp of Arrangement spacing alone would suggest; disabling it via
                        // PlatformTextStyle is what actually closes the gap up to match.
                        if (suffixName != null || realNameOrCid != null) {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                if (suffixName != null) {
                                    Text(
                                        suffixName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = text,
                                        style = androidx.compose.ui.text.TextStyle(
                                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                                        )
                                    )
                                }
                                if (realNameOrCid != null) {
                                    Text(
                                        realNameOrCid,
                                        fontSize = 10.sp,
                                        color = text.copy(alpha = 0.75f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = androidx.compose.ui.text.TextStyle(
                                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // The rating badge sits with the same tight 2px gap as the icon buttons
                    // themselves, not the row's own 10dp inter-section spacing -- grouped into one
                    // Row so Arrangement.spacedBy(10dp) on the outer Row only applies *before* this
                    // group, not between the badge and the icons.
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (showInfo) {
                            ratingLabel?.let { label -> RatingBadge(label, text, badgeBackground) }
                        }

                        // Reference uses tight 30x30 buttons with a 2px gap between them
                        // (`display:flex;gap:2px` around pinBtnStyle/msgBtnStyle, both `width:30px;
                        // height:30px;padding:0`) -- Material3's IconButton (48dp min touch target,
                        // plus the row's own 10dp gap on top) was eating enough space that the
                        // frequency didn't fit on one line.
                        Box(
                            Modifier.size(30.dp).clickable(onClick = onTogglePin),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.PushPin, contentDescription = "Pin controller", tint = text, modifier = Modifier.size(20.dp))
                        }
                        Box(
                            Modifier.size(30.dp).clickable(onClick = onOpenChat),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Message, contentDescription = "Private chat", tint = text, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                if (stackFrequency) {
                    // The outer Row is always at least as tall as its tallest child (the 30dp
                    // pin/message icon boxes), regardless of alignment -- when there's no badge
                    // row, the callsign's own single line of text is shorter than that, so
                    // whatever fixed top padding is added here stacks on top of that Row-height
                    // slack too, reading as a gap roughly the size of a badge pill even though
                    // there isn't one. Only add the padding when a badge row is actually present
                    // (making the callsign column's own height closer to the row's height, so the
                    // slack is much smaller to begin with).
                    FrequencyText(Modifier.padding(top = if (badges.isEmpty()) 0.dp else 6.dp))
                }
            }
        }

        // Always shows full detail regardless of what the row itself had to hide for space --
        // nothing is truly lost to the width-based overflow above, just relocated here.
        ControllerTuneMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            callsign = controller.callsign,
            frequencyLabel = RadioFrequency.format(controller.frequency),
            stationName = suffixName,
            realNameOrCid = realNameOrCid,
            ratingLabel = ratingLabel,
            showDismissSelcal = controller.isSelcalActive,
            onTuneCom1Active = { menuOpen = false; onTuneCom1Active() },
            onTuneCom2Active = { menuOpen = false; onTuneCom2Active() },
            onTuneCom1Standby = { menuOpen = false; onTuneCom1Standby() },
            onTuneCom2Standby = { menuOpen = false; onTuneCom2Standby() },
            onDismissSelcal = { menuOpen = false; onDismissSelcal() }
        )
    }
}

@Composable
private fun BadgePill(label: String, contentColor: Color, background: Color) {
    // Reference is `font:700 9px/1 Roboto;padding:3px 7px` -- line-height 1 (i.e. no extra
    // leading beyond the glyphs). Compose Text's default line height reserves legacy Android
    // font-padding above/below the glyphs on top of whatever box padding is applied, which read
    // as a noticeably taller pill than the reference's tight 3px vertical padding suggests;
    // disabling it via PlatformTextStyle is what actually closes the gap.
    Box(
        Modifier
            .background(background, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            style = androidx.compose.ui.text.TextStyle(
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
            )
        )
    }
}

@Composable
private fun RatingBadge(label: String, contentColor: Color, background: Color) {
    Box(
        Modifier
            .widthIn(min = 30.dp)
            .background(background, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = contentColor)
    }
}

/** The floating popover opened by tapping anywhere on a row except its icon buttons -- a 2x2
 *  COM1/COM2/STBY/STBY tune grid, plus a Dismiss SELCAL button when this row has an active,
 *  undismissed alert. Built on material3's DropdownMenu for free anchoring/outside-tap-dismiss;
 *  its content is fully custom-styled rather than using DropdownMenuItem's default look. */
@Composable
private fun ControllerTuneMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    callsign: String,
    frequencyLabel: String,
    stationName: String?,
    realNameOrCid: String?,
    ratingLabel: String?,
    showDismissSelcal: Boolean,
    onTuneCom1Active: () -> Unit,
    onTuneCom2Active: () -> Unit,
    onTuneCom1Standby: () -> Unit,
    onTuneCom2Standby: () -> Unit,
    onDismissSelcal: () -> Unit
) {
    val colors = LocalHandoffColors.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(min = 220.dp).padding(12.dp)) {
            Text(
                "$callsign · $frequencyLabel",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textMuted
            )
            // Always shown in full here regardless of what the row itself had to hide for width
            // -- the popover isn't space-constrained the way the row is, so there's no need to
            // mirror the row's own breakpoints.
            val detailLine = listOfNotNull(stationName, realNameOrCid, ratingLabel).joinToString(" · ")
            if (detailLine.isNotEmpty()) {
                Text(
                    detailLine,
                    fontSize = 11.sp,
                    color = colors.textMuted.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TuneMenuButton("COM1", Modifier.weight(1f), onTuneCom1Active)
                TuneMenuButton("COM2", Modifier.weight(1f), onTuneCom2Active)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TuneMenuButton("STBY", Modifier.weight(1f), onTuneCom1Standby)
                TuneMenuButton("STBY", Modifier.weight(1f), onTuneCom2Standby)
            }
            if (showDismissSelcal) {
                TuneMenuButton(
                    "Dismiss SELCAL",
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    onDismissSelcal,
                    background = colors.attentionBg,
                    contentColor = colors.attention
                )
            }
        }
    }
}

@Composable
private fun TuneMenuButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    background: androidx.compose.ui.graphics.Color? = null,
    contentColor: androidx.compose.ui.graphics.Color? = null
) {
    val colors = LocalHandoffColors.current
    Box(
        modifier
            .background(background ?: colors.panelAlt, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = contentColor ?: colors.text
        )
    }
}
