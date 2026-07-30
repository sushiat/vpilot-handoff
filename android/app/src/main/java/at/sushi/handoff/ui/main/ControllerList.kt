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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
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
import at.sushi.handoff.ui.theme.LocalRowColorPalette
import at.sushi.handoff.ui.theme.controllerBadges
import at.sushi.handoff.ui.theme.controllerRowColors
import at.sushi.handoff.ui.theme.controllerRowGroup
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
    com1Standby: Int?,
    com2Standby: Int?,
    hideTuned: Boolean,
    onToggleHideTuned: () -> Unit,
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
    // Filtered once here so the count text, column-width measurement, scroll-to-top effect, and
    // the LazyColumn itself all agree on exactly the same set of rows -- including the group-gap
    // logic below, which naturally stops inserting a gap for a tuned row that isn't in this list
    // at all anymore rather than needing its own separate hiding rule. Covers both isCurrent and
    // isStandbyTuned. Pinned rows are exempt either way -- pinning is a deliberate manual choice,
    // "hide tuned" shouldn't override it.
    val visibleControllers = if (hideTuned) {
        controllers.filter { !(it.isCurrent || it.isStandbyTuned) || it.isPinned }
    } else {
        controllers
    }

    Column(modifier.fillMaxWidth().background(colors.panel)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "CONTROLLERS · ${visibleControllers.size}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textMuted
            )
            // Once a station is actually tuned, chat with it happens over the radio, not this
            // app's private chat -- easy to bring back by unchecking. onCheckedChange = null on
            // the Checkbox itself since the whole Row is the click target, not just the box.
            Row(
                Modifier.clickable(onClick = onToggleHideTuned),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Hide tuned", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.textMuted)
                Checkbox(
                    checked = hideTuned,
                    onCheckedChange = null,
                    modifier = Modifier.scale(0.75f),
                    colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.textMuted)
                )
            }
        }
        // Shared across every row so the frequency column lines up regardless of individual
        // callsign length -- matches the original JS reference's own formula exactly
        // (`Math.max(90, longestCallsign * 8.5 + 10)`), just carried over from a static
        // `widthIn(min = 90.dp)` that never actually read the list's real callsigns.
        val callsignColWidth = remember(visibleControllers) {
            val longest = visibleControllers.maxOfOrNull { it.callsign.length } ?: 0
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
        // Snap back to the top whenever a row's badge set changes (TUNED/STBY/CONTACT_ME/NEXT/
        // NEXT_LIKELY/PINNED/SELCAL) -- gain *or* loss, not just gain. Whatever's relevant can
        // otherwise land above the viewport (or, on loss, drop below it while the list reorders
        // underneath) if the pilot has scrolled down, and go unnoticed.
        //
        // This intentionally does NOT key off com1Standby/com2Standby (the local radioState
        // values, which update the instant a tuning command round-trips) or a separately-tracked
        // pinned-callsign set, as an earlier version of this fix did. Every badge here (PINNED
        // included) is server-authoritative on [Controller] itself (issue #18), broadcast on the
        // plugin's own ~1/sec cadence -- decoupled from, and slower than, local radioState
        // updates. Scrolling to the top on the early local signal effectively locked LazyColumn's
        // key-based scroll anchor onto whatever row was at index 0 *at that moment* (unchanged,
        // since the real reorder hadn't landed yet) -- then when the plugin's actual reorder
        // arrived a beat later, Compose faithfully kept that same row in view as it dropped down
        // the ranking, hiding everything above it (confirmed on-device: adding a delay() before
        // the scroll call made this race land the same way *every* time instead of
        // intermittently, rather than fixing it -- the real problem was firing on the wrong
        // signal, not bad timing on the right one). Badges are exactly as stale as the visible
        // ordering, since both come from the same broadcast -- keying on them fires this in step
        // with the real reorder instead of ahead of it.
        val listState = rememberLazyListState()
        var previousBadgedCallsigns by remember { mutableStateOf<Set<String>?>(null) }
        var previousHideTuned by remember { mutableStateOf(hideTuned) }
        LaunchedEffect(visibleControllers, com1Active, com2Active, hideTuned) {
            val currentBadged = visibleControllers.filter { controller ->
                controllerBadges(controller, com1Active, com2Active).isNotEmpty()
            }.mapTo(mutableSetOf()) { it.callsign }

            val previous = previousBadgedCallsigns
            // Toggling "hide tuned" is its own explicit trigger -- a direct, immediate pilot
            // action that can leave the badge set completely unchanged (e.g. no tuned rows exist
            // yet), so it wouldn't otherwise be caught by the check above.
            if (previous != null && (currentBadged != previous || hideTuned != previousHideTuned)) {
                listState.scrollToItem(0)
            }
            previousBadgedCallsigns = currentBadged
            previousHideTuned = hideTuned
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
            // An extra 10dp gap is added above the first row of a new group (tuned -> other
            // flagged/highlighted -> plain, see controllerRowGroup) on top of the normal 6dp --
            // feedback: once the tuned border experiment was dropped, there was no visual
            // separation left between "my radio", "worth noticing", and "just ambient traffic" at
            // a glance beyond color alone (5dp was tried first and judged too subtle to notice).
            itemsIndexed(visibleControllers, key = { _, controller -> controller.callsign }) { index, controller ->
                val previousGroup = visibleControllers.getOrNull(index - 1)?.let { controllerRowGroup(it, com1Active, com2Active) }
                val thisGroup = controllerRowGroup(controller, com1Active, com2Active)
                if (previousGroup != null && previousGroup != thisGroup) {
                    Spacer(Modifier.height(10.dp))
                }
                ControllerRow(
                    controller = controller,
                    callsignColWidth = callsignColWidth,
                    frequencyTextWidth = frequencyTextWidth,
                    com1Active = com1Active,
                    com2Active = com2Active,
                    com1Standby = com1Standby,
                    com2Standby = com2Standby,
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
    com1Standby: Int?,
    com2Standby: Int?,
    onTogglePin: () -> Unit,
    onOpenChat: () -> Unit,
    onTuneCom1Active: () -> Unit,
    onTuneCom2Active: () -> Unit,
    onTuneCom1Standby: () -> Unit,
    onTuneCom2Standby: () -> Unit,
    onDismissSelcal: () -> Unit
) {
    val colors = LocalHandoffColors.current
    val palette = LocalRowColorPalette.current
    val rowColors = controllerRowColors(controller, com1Active, com2Active, colors, com1Standby, com2Standby, palette)
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

            // isCurrent/isStandbyTuned no longer get any special border treatment -- issue #21
            // feedback dropped that experiment (ring, gradient dip, max-intensity color all
            // tried and reverted) in favor of a fixed-color TUNED/STBY badge plus extra spacing
            // between row groups (see controllerRowGroup below) instead.
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
                                    when {
                                        badge == ControllerBadge.SELCAL ->
                                            BadgePill(badgeLabels.getValue(badge), flashPhaseBText, selcalBackground)
                                        // TUNED/STBY reuse the row's dedicated COM1/COM2 border
                                        // color on the badge itself (issue #21) -- but only
                                        // outside the yellow flash phase, so it doesn't fight the
                                        // contact-me alert for attention during that brief window.
                                        (badge == ControllerBadge.TUNED || badge == ControllerBadge.STBY) &&
                                            !(rowColors.isFlashing && !rowPhaseA) && rowColors.tunedBadgeBackground != null ->
                                            BadgePill(badgeLabels.getValue(badge), rowColors.tunedBadgeText ?: text, rowColors.tunedBadgeBackground)
                                        else -> BadgePill(badgeLabels.getValue(badge), text, badgeBackground)
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
                            Icon(
                                Icons.Filled.PushPin,
                                contentDescription = if (controller.isPinned) "Unpin controller" else "Pin controller",
                                tint = text,
                                // Tilted (as if actually stuck in at an angle, the classic
                                // Google-Keep-style pinned look) vs. the plain upright glyph --
                                // a state change on the icon itself, not just the row's color,
                                // so a pinned row still reads as pinned even before the color
                                // treatment registers.
                                modifier = Modifier.size(20.dp).rotate(if (controller.isPinned) 45f else 0f)
                            )
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

            // Always shows full detail regardless of what the row itself had to hide for space --
            // nothing is truly lost to the width-based overflow above, just relocated here.
            // Nested inside BoxWithConstraints (not a sibling in the outer Box) purely to read
            // maxWidth -- DropdownMenu is a Popup under the hood, so it's not actually laid out
            // within these bounds, just capped to no wider than the row it opened from.
            ControllerTuneMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                callsign = controller.callsign,
                frequencyLabel = RadioFrequency.format(controller.frequency),
                stationName = suffixName,
                realNameOrCid = realNameOrCid,
                ratingLabel = ratingLabel,
                textAtis = controller.textAtis,
                rowBackground = rowColors.background,
                rowBorder = rowColors.border,
                rowText = rowColors.text,
                rowWidth = maxWidth,
                showDismissSelcal = controller.isSelcalActive,
                onTuneCom1Active = { menuOpen = false; onTuneCom1Active() },
                onTuneCom2Active = { menuOpen = false; onTuneCom2Active() },
                onTuneCom1Standby = { menuOpen = false; onTuneCom1Standby() },
                onTuneCom2Standby = { menuOpen = false; onTuneCom2Standby() },
                onDismissSelcal = { menuOpen = false; onDismissSelcal() }
            )
        }
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

// Overall cap on ControllerTuneMenu's height -- generous enough that header/detail/grid/ATIS
// never hit it on a typical tablet screen, but present so the dialog degrades to an internal
// scroll instead of an unreachable clip if it ever does (see ControllerTuneMenu's own comment).
private val MaxDialogHeight = 480.dp

/** The floating popover opened by tapping anywhere on a row except its icon buttons -- a
 *  COM1/COM2/STBY/STBY tune grid, an ATIS text panel when this controller has one, and a Dismiss
 *  SELCAL button when this row has an active, undismissed alert. Built on material3's
 *  DropdownMenu for free anchoring/outside-tap-dismiss; its content is fully custom-styled rather
 *  than using DropdownMenuItem's default look. */
@Composable
private fun ControllerTuneMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    callsign: String,
    frequencyLabel: String,
    stationName: String?,
    realNameOrCid: String?,
    ratingLabel: String?,
    textAtis: List<String>?,
    // The row's own (non-flashing "phase A") background/border/text -- reusing
    // controllerRowColors' output rather than re-deriving anything here, so this stays correct
    // if/when facility colors become user-themeable (that all flows through HandoffColors/
    // FacilityColors already; this composable never touches a hue/lightness value directly).
    // Deliberately the stable base colors, not whatever the row is live-flashing between --
    // a flashing dialog background would fight the pilot's focus on the buttons/ATIS text they
    // opened this for.
    rowBackground: Color,
    rowBorder: Color,
    rowText: Color,
    // The row card's own measured width (BoxWithConstraints.maxWidth from ControllerRow) -- caps
    // the popover so it never reads as wider than the row it opened from, while still capping at
    // TargetMaxWidth on a wide/fullscreen layout rather than growing unbounded.
    rowWidth: Dp,
    showDismissSelcal: Boolean,
    onTuneCom1Active: () -> Unit,
    onTuneCom2Active: () -> Unit,
    onTuneCom1Standby: () -> Unit,
    onTuneCom2Standby: () -> Unit,
    onDismissSelcal: () -> Unit
) {
    val colors = LocalHandoffColors.current
    // Grid switches from 2x2 (COM1/COM2 then STBY/STBY) to a single 4-column row (COM1, STBY,
    // COM2, STBY) whenever the ATIS text has a long line -- a 2x2 grid stacked above tall ATIS
    // text would push it further down/require more scrolling than trading grid rows for the
    // extra popover width is worth. The design's reference markup fixes the popover at a single
    // width in both cases (not the 220->280dp width toggle its own prose describes) -- the markup
    // is authoritative here, so width stays constant and only the grid's row/column split changes.
    // Widened from the design's 280dp to a 300dp target, and every font size in this dialog
    // bumped +2sp, per the user's real-device readability call -- verified against a live
    // screenshot at the user's actual (narrow) split-screen ratio to confirm 300dp still clears
    // the app window's right edge with margin to spare; a full +30dp (310dp) would have landed
    // flush against it. Capped at the row's own width, not just a flat 300dp -- a popover visibly
    // wider than the row it opened from read as misaligned; this way it only ever matches or
    // narrows from the row's width, never overhangs it.
    val hasAtis = !textAtis.isNullOrEmpty()
    val wideGrid = hasAtis && textAtis!!.any { it.length > 30 }
    val width = minOf(300.dp, rowWidth)
    // A muted variant of the row's own text color for secondary lines (header/detail/ATIS) --
    // same "copy the foreground, don't reach for an unrelated muted token" approach as the row
    // itself uses for its real-name/CID line (ControllerRow: text.copy(alpha = 0.75f)).
    val mutedRowText = rowText.copy(alpha = 0.75f)

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = rowBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, rowBorder)
    ) {
        // The whole dialog scrolls as one unit, capped at MaxDialogHeight -- previously only the
        // ATIS text had its own 120dp scroll area, with no height cap on the dialog as a whole.
        // That worked fine at the design's fixed width, but once the popover started narrowing to
        // match a narrow row (see rowWidth above), each ATIS line wraps across more visual lines,
        // growing the whole assembly's natural height past what the Popup had room for near the
        // screen edge -- and since only the ATIS sub-section was scrollable, the excess (the last
        // line or two) was silently clipped with no way to reach it, not gracefully scrolled.
        Column(
            Modifier
                .width(width)
                .heightIn(max = MaxDialogHeight)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(
                "$callsign · $frequencyLabel",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = mutedRowText
            )
            // Always shown in full here regardless of what the row itself had to hide for width
            // -- the popover isn't space-constrained the way the row is, so there's no need to
            // mirror the row's own breakpoints.
            val detailLine = listOfNotNull(stationName, realNameOrCid, ratingLabel).joinToString(" · ")
            if (detailLine.isNotEmpty()) {
                Text(
                    detailLine,
                    fontSize = 13.sp,
                    color = mutedRowText.copy(alpha = mutedRowText.alpha * 0.8f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (wideGrid) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TuneMenuButton("COM1", Modifier.weight(1f), onTuneCom1Active, verticalPadding = 12.dp)
                    TuneMenuButton("STBY", Modifier.weight(1f), onTuneCom1Standby, verticalPadding = 12.dp)
                    TuneMenuButton("COM2", Modifier.weight(1f), onTuneCom2Active, verticalPadding = 12.dp)
                    TuneMenuButton("STBY", Modifier.weight(1f), onTuneCom2Standby, verticalPadding = 12.dp)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TuneMenuButton("COM1", Modifier.weight(1f), onTuneCom1Active, verticalPadding = 12.dp)
                    TuneMenuButton("COM2", Modifier.weight(1f), onTuneCom2Active, verticalPadding = 12.dp)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TuneMenuButton("STBY", Modifier.weight(1f), onTuneCom1Standby, verticalPadding = 12.dp)
                    TuneMenuButton("STBY", Modifier.weight(1f), onTuneCom2Standby, verticalPadding = 12.dp)
                }
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
            if (hasAtis) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(rowBorder))
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    textAtis!!.forEach { line ->
                        Text(
                            line,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 20.3.sp,
                            color = mutedRowText
                        )
                    }
                }
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
    contentColor: androidx.compose.ui.graphics.Color? = null,
    verticalPadding: Dp = 10.dp
) {
    val colors = LocalHandoffColors.current
    Box(
        modifier
            .background(background ?: colors.panelAlt, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = verticalPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = contentColor ?: colors.text
        )
    }
}
