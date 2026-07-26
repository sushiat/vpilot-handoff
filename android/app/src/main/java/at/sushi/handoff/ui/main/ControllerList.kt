package at.sushi.handoff.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    ControllerBadge.CONTACT_ME to "CONTACT ME",
    ControllerBadge.NEXT to "NEXT",
    ControllerBadge.APPROACHING to "APPROACHING",
    ControllerBadge.PINNED to "PINNED",
    ControllerBadge.SELCAL to "SELCAL"
)

@Composable
fun ControllerList(
    controllers: List<Controller>,
    com1Active: Int?,
    com2Active: Int?,
    pinnedCallsign: String?,
    selcalActiveCallsigns: Set<String>,
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
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textMuted,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)
        )
        // Reference container is `padding:0 10px 14px;display:flex;flex-direction:column;
        // gap:6px` -- rows are individually rounded cards with their own border/gap between
        // them, not a plain divided list (no HorizontalDivider in the reference at all).
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Rendered in exactly the order the server sent it -- never re-sorted client-side.
            items(controllers, key = { it.callsign }) { controller ->
                ControllerRow(
                    controller = controller,
                    com1Active = com1Active,
                    com2Active = com2Active,
                    isPinned = controller.callsign == pinnedCallsign,
                    selcalActive = controller.callsign in selcalActiveCallsigns,
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

@Composable
private fun ControllerRow(
    controller: Controller,
    com1Active: Int?,
    com2Active: Int?,
    isPinned: Boolean,
    selcalActive: Boolean,
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
    val badges = controllerBadges(controller, com1Active, com2Active, isPinned, selcalActive)
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
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .background(background, rowShape)
                .border(1.5.dp, rowColors.border, rowShape)
                .clickable { menuOpen = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.widthIn(min = 90.dp)) {
                Text(
                    controller.callsign,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = text
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

            Text(
                RadioFrequency.format(controller.frequency),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = text.copy(alpha = 0.9f),
                modifier = Modifier.weight(1f)
            )

            // Reference gap between these two lines is 1px -- Compose Text's default line
            // height reserves extra ascent/descent padding beyond the glyphs themselves (legacy
            // Android "font padding"), which reads as a much bigger gap than 1dp of Arrangement
            // spacing alone would suggest; disabling it via PlatformTextStyle is what actually
            // closes the gap up to match.
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                val suffixName = controller.stationName ?: facilitySuffixName(controller.callsign)
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
                Text(
                    controller.name ?: controller.cid?.toString() ?: "",
                    fontSize = 10.sp,
                    color = text.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.ui.text.TextStyle(
                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                    )
                )
            }

            // The rating badge sits with the same tight 2px gap as the icon buttons themselves,
            // not the row's own 10dp inter-section spacing -- grouped into one Row so
            // Arrangement.spacedBy(10dp) on the outer Row only applies *before* this group, not
            // between the badge and the icons.
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                controller.rating?.let { rating ->
                    ratingLabels[rating]?.let { label -> RatingBadge(label, text, badgeBackground) }
                }

                // Reference uses tight 30x30 buttons with a 2px gap between them
                // (`display:flex;gap:2px` around pinBtnStyle/msgBtnStyle, both `width:30px;
                // height:30px;padding:0`) -- Material3's IconButton (48dp min touch target, plus
                // the row's own 10dp gap on top) was eating enough space that the frequency
                // didn't fit on one line.
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

        ControllerTuneMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            callsign = controller.callsign,
            frequencyLabel = RadioFrequency.format(controller.frequency),
            showDismissSelcal = selcalActive,
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
                color = colors.textMuted,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
