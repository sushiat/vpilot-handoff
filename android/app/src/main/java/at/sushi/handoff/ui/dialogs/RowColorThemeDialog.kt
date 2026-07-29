package at.sushi.handoff.ui.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import at.sushi.handoff.protocol.Controller
import at.sushi.handoff.protocol.RadioFrequency
import at.sushi.handoff.ui.theme.DefaultRowColorPalette
import at.sushi.handoff.ui.theme.DeuteranopiaSafeRowColorPalette
import at.sushi.handoff.ui.theme.FacilityColors
import at.sushi.handoff.ui.theme.HandoffTextField
import at.sushi.handoff.ui.theme.LocalHandoffColors
import at.sushi.handoff.ui.theme.ProtanopiaSafeRowColorPalette
import at.sushi.handoff.ui.theme.RobotoMono
import at.sushi.handoff.ui.theme.RowColorPalette
import at.sushi.handoff.ui.theme.SavedRowColorTheme
import at.sushi.handoff.ui.theme.controllerRowColors
import at.sushi.handoff.ui.theme.nearBlackText
import at.sushi.handoff.ui.theme.perceptualLightness
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** One of the editable hue tokens, paired with a synthetic [Controller] (+ radio state) that
 *  renders it through the *real* [controllerRowColors]/[controllerBadges] logic -- issue #21
 *  feedback asked the preview to "use or derive from the actual controller rows" rather than
 *  flat swatches, so tapping a card shows exactly what that token looks like in the real list,
 *  badge/chat-button included, not an approximation. */
private data class PreviewToken(
    val label: String,
    val get: (RowColorPalette) -> Float,
    val with: (RowColorPalette, Float) -> RowColorPalette,
    val controller: Controller,
    val com1Active: Int? = null,
    val com2Active: Int? = null,
    val com1Standby: Int? = null,
    val com2Standby: Int? = null
)

/** A token shown at both its full-saturation/flagged appearance and its plain everyday one,
 *  stacked directly on top of each other -- feedback: most tokens only ever showed one state
 *  (flat/unflagged), leaving no way to see what a "highlighted" row of that hue looks like without
 *  actually waiting for a real one to light up. Both halves of a group edit the *same* hue (same
 *  [PreviewToken.get]/[with]) -- they're two views of one value, not two separate tokens. */
private data class PreviewGroup(val highlighted: PreviewToken, val other: PreviewToken)

private fun facilityGroup(
    label: String,
    get: (RowColorPalette) -> Float,
    with: (RowColorPalette, Float) -> RowColorPalette,
    callsign: String,
    frequency: Int,
    facility: Int?
): PreviewGroup {
    val base = Controller(callsign = callsign, frequency = frequency, latitude = 0.0, longitude = 0.0, facility = facility, rating = 5)
    // isHighlighted earns a facility row its full-saturation treatment (see RowColors.kt's
    // controllerRowColors) without adding any badge (unlike isPinned/isNext/etc, which all also
    // add their own badge) -- this card is meant to demo the plain highlighted look, not imply
    // "pinned" specifically.
    return PreviewGroup(
        highlighted = PreviewToken("$label (active)", get, with, base.copy(isHighlighted = true)),
        other = PreviewToken("$label (default)", get, with, base)
    )
}

// Frequencies are arbitrary but distinct per controller -- only used so each preview card's
// com1Active/com2Active/com1Standby/com2Standby argument can point back at its own controller
// without cross-matching another card's. 6 groups (facility hues only), each a highlighted/
// default pair -- 12 individual preview cards. COM1/COM2 tuned are deliberately absent: issue #21
// feedback dropped them from being user-editable at all (fixed colors now, same as contact-me/
// SELCAL -- see RowColorPalette.kt), so there's nothing left here to tap-and-edit for them.
private val previewGroups = listOf(
    // LOWW/LOVV, real-world frequencies (VATSIM uses the same ones) -- sushi.at, not sushi.de.
    facilityGroup("Delivery", { it.delHue }, { p, h -> p.copy(delHue = h) }, "LOWW_DEL", 22125, 2),
    facilityGroup("Ground", { it.gndHue }, { p, h -> p.copy(gndHue = h) }, "LOWW_GND", 21600, 3),
    facilityGroup("Tower", { it.twrHue }, { p, h -> p.copy(twrHue = h) }, "LOWW_TWR", 19400, 4),
    facilityGroup("Approach / Departure", { it.appDepHue }, { p, h -> p.copy(appDepHue = h) }, "LOWW_APP", 34675, 5),
    facilityGroup("Center", { it.ctrHue }, { p, h -> p.copy(ctrHue = h) }, "LOVV_CTR", 32600, 6),
    facilityGroup("ATIS", { it.atisHue }, { p, h -> p.copy(atisHue = h) }, "LOWW_D_ATIS", 21730, null)
)

// Mirrors ControllerList.kt's private ratingLabels -- every preview controller above is given
// rating = 5 ("C1"), just needs a label to render.
private val previewRatingLabels = mapOf(5 to "C1")

/** Below this angular distance from [FacilityColors.HAZARD_YELLOW_HUE], the contact-me flash
 *  (which alternates a row between its own facility color and the fixed alert yellow) reads as
 *  too low-contrast to notice -- an unscientific but reasonable "close enough to worry about"
 *  band, not derived from a contrast formula. */
private const val HazardYellowWarningThresholdDegrees = 20f

private fun hueDistance(a: Float, b: Float): Float {
    val diff = kotlin.math.abs(a - b) % 360f
    return if (diff > 180f) 360f - diff else diff
}

private const val DeuteranopiaChipId = "__deuteranopia__"
private const val ProtanopiaChipId = "__protanopia__"

/** Below this panel width even a 1-column grid of preview cards can't render usably -- shows a
 *  fallback message instead of a squeezed layout (see SettingsDialog's own 420dp/
 *  ToggleShortLabelThreshold floors for the same convention in this app). */
private val RowColorEditorMinUsableWidth = 260.dp

/** How long the Delete button stays armed after its first tap before reverting to "Delete" --
 *  a deliberate two-tap confirm (not a separate confirm dialog) since this is a low-stakes,
 *  easily-recreated action, but still needs *some* friction against a stray misclick. */
private const val DeleteConfirmWindowMillis = 5000L

/** Issue #21's row-color theme editor -- combines live preview and editing (feedback: cards
 *  above should render through the real row-color/badge logic, not separate flat swatches, and
 *  tapping one should open a proper color picker rather than a plain linear slider). Does NOT
 *  autosave a draft on close -- only [onActivate] (fired the instant a chip is tapped) or
 *  [onSaveTheme]/[onDeleteTheme] (fired by the explicit buttons) persist/apply anything. Editing
 *  a pre-shipped palette (Default or a colorblind preset) without saving prompts on close instead
 *  of silently discarding, since those changes have nowhere else to live. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowColorThemeDialog(
    initialPalette: RowColorPalette,
    savedThemes: List<SavedRowColorTheme>,
    activeThemeId: String?,
    onDismiss: () -> Unit,
    onActivate: (id: String?, palette: RowColorPalette) -> Unit,
    onSaveTheme: (name: String, palette: RowColorPalette) -> Unit,
    onDeleteTheme: (id: String) -> Unit
) {
    val colors = LocalHandoffColors.current
    var draft by remember { mutableStateOf(initialPalette) }
    var themeName by remember { mutableStateOf("") }
    var hueWheelToken by remember { mutableStateOf<PreviewToken?>(null) }
    var deleteConfirming by remember { mutableStateOf(false) }
    var showUnsavedCloseConfirm by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    data class ChipEntry(val id: String?, val name: String, val palette: RowColorPalette)
    val chips = remember(savedThemes) {
        listOf(ChipEntry(null, "Default", DefaultRowColorPalette)) +
            listOf(
                ChipEntry(DeuteranopiaChipId, "Deuteranopia-safe", DeuteranopiaSafeRowColorPalette),
                ChipEntry(ProtanopiaChipId, "Protanopia-safe", ProtanopiaSafeRowColorPalette)
            ) +
            savedThemes.map { ChipEntry(it.id, it.name, it.palette) }
    }
    val activeChip = chips.find { it.id == activeThemeId } ?: chips.first()
    val isEditingSavedTheme = savedThemes.any { it.id == activeThemeId }
    val hasUnsavedChanges = draft != activeChip.palette

    val requestClose = {
        if (!isEditingSavedTheme && hasUnsavedChanges) {
            showUnsavedCloseConfirm = true
        } else {
            onDismiss()
        }
    }

    Dialog(onDismissRequest = requestClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val panelWidth = minOf(maxWidth * 0.9f, 760.dp)
            val panelMaxHeight = maxHeight * 0.85f
            // 2 columns of group-pairs (not 1 per token) -- each individual preview row needs
            // enough width for callsign + frequency + chat button to sit on one line (the badge,
            // when present, is the one thing allowed to wrap onto its own line below, matching how
            // the real ControllerRow behaves), which a narrower 3-4 column grid didn't leave room
            // for.
            val groupColumns = if (panelWidth >= 520.dp) 2 else 1

            Column(
                Modifier
                    .width(panelWidth)
                    .heightIn(max = panelMaxHeight)
                    .background(colors.panel, RoundedCornerShape(16.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Controller row colors", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.text)
                    Text(
                        "✕",
                        fontSize = 16.sp,
                        color = colors.text.copy(alpha = 0.5f),
                        modifier = Modifier.clickable(onClick = requestClose)
                    )
                }

                if (panelWidth < RowColorEditorMinUsableWidth) {
                    Text(
                        "Not enough room here — try full screen.",
                        fontSize = 14.sp,
                        color = colors.textMuted,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    return@Column
                }

                // A dedicated scrollbar column (below) instead of RowColors.kt's usual draw-over
                // verticalScrollbar -- that approach kept reading as overlapping the preview
                // cards' right edge regardless of end-padding tuning (confirmed on-device,
                // glaringly obvious against the bright ATIS yellow card), so this reserves real
                // layout space instead of drawing on top of content.
                Row(Modifier.padding(top = 12.dp)) {
                Column(Modifier.weight(1f).verticalScroll(scrollState)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(chips) { chip ->
                            ThemeChip(chip.name, isActive = chip.id == activeThemeId) {
                                draft = chip.palette
                                onActivate(chip.id, chip.palette)
                            }
                        }
                    }

                    SectionLabel("TAP A ROW TO CHANGE ITS COLOR")
                    previewGroups.chunked(groupColumns).forEach { rowGroups ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowGroups.forEach { group ->
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PreviewCard(group.highlighted, draft) { hueWheelToken = group.highlighted }
                                    PreviewCard(group.other, draft) { hueWheelToken = group.other }
                                }
                            }
                            repeat(groupColumns - rowGroups.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }

                    // 2x2 grid: contact-me demo / non-highlight brightness on top, text contrast /
                    // dark-mode offset below -- neither the contact-me demo nor any one slider
                    // needs the full dialog width, so pairing them up saves considerable vertical
                    // space over stacking all 4 full-width.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f)) {
                            SectionLabel("CONTACT-ME ALERT")
                            Text(
                                "An unresolved contact-me request flashes a station's row between its own color and this fixed alert color twice a second.",
                                fontSize = 13.sp,
                                color = colors.textMuted,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            ContactMeFlashDemo(draft)
                        }
                        Column(Modifier.weight(1f)) {
                            SectionLabel("NON-HIGHLIGHT BRIGHTNESS")
                            Text(
                                "Where default (non-highlighted) rows sit between black and white, relative to their highlighted color.",
                                fontSize = 13.sp,
                                color = colors.textMuted,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            CompactSlider(draft.fadedBrightnessOffset, { draft = draft.copy(fadedBrightnessOffset = it) }, -1f..1f, colors.accent)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Black", fontSize = 11.sp, color = colors.textMuted)
                                Text("Highlight", fontSize = 11.sp, color = colors.textMuted)
                                Text("White", fontSize = 11.sp, color = colors.textMuted)
                            }
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f)) {
                            SectionLabel("TEXT CONTRAST THRESHOLD")
                            Text(
                                "Controls when row text switches from dark to white as a color darkens.",
                                fontSize = 13.sp,
                                color = colors.textMuted,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            CompactSlider(draft.textLightnessThreshold, { draft = draft.copy(textLightnessThreshold = it) }, 0f..100f, colors.accent)
                        }
                        Column(Modifier.weight(1f)) {
                            SectionLabel("DARK MODE OFFSET")
                            Text(
                                "Extra darkening applied on top of every row -- highlighted included -- only in dark theme.",
                                fontSize = 13.sp,
                                color = colors.textMuted,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            CompactSlider(draft.darkModeOffset, { draft = draft.copy(darkModeOffset = it) }, 0f..1f, colors.accent)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Off", fontSize = 11.sp, color = colors.textMuted)
                                Text("Black", fontSize = 11.sp, color = colors.textMuted)
                            }
                        }
                    }

                    if (isEditingSavedTheme) {
                        SectionLabel("THEME")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("Save", colors.accent, Color.White, modifier = Modifier.weight(1f)) {
                                onSaveTheme(activeChip.name, draft)
                            }
                            PillButton(
                                if (deleteConfirming) "Confirm?" else "Delete",
                                if (deleteConfirming) outOfBandRed else colors.panelAlt,
                                if (deleteConfirming) Color.White else colors.text,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (deleteConfirming) {
                                    activeThemeId?.let(onDeleteTheme)
                                    deleteConfirming = false
                                } else {
                                    deleteConfirming = true
                                }
                            }
                        }
                        if (deleteConfirming) {
                            LaunchedDeleteConfirmTimeout { deleteConfirming = false }
                        }
                    } else {
                        SectionLabel("SAVE AS")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            HandoffTextField(
                                themeName,
                                { themeName = it },
                                placeholder = "Theme name",
                                modifier = Modifier.weight(1f).height(40.dp)
                            )
                            PillButton("Save", colors.accent, Color.White, enabled = themeName.isNotBlank()) {
                                onSaveTheme(themeName.trim(), draft)
                                themeName = ""
                            }
                        }
                    }
                }
                ScrollbarTrack(
                    scrollState,
                    colors.border,
                    modifier = Modifier.width(4.dp).fillMaxHeight().padding(start = 6.dp)
                )
                }
            }
        }
    }

    hueWheelToken?.let { token ->
        HueWheelDialog(
            label = token.label,
            hue = token.get(draft),
            onHueChange = { draft = token.with(draft, it) },
            onDismiss = { hueWheelToken = null }
        )
    }

    if (showUnsavedCloseConfirm) {
        Dialog(onDismissRequest = { showUnsavedCloseConfirm = false }) {
            Column(
                Modifier
                    .background(colors.panel, RoundedCornerShape(16.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Text("Save your changes as a custom theme before closing?", fontSize = 13.sp, color = colors.text)
                Row(Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton("No", colors.panelAlt, colors.text) {
                        showUnsavedCloseConfirm = false
                        onDismiss()
                    }
                    Spacer(Modifier.width(8.dp))
                    PillButton("Yes", colors.accent, Color.White) {
                        showUnsavedCloseConfirm = false
                        coroutineScope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchedDeleteConfirmTimeout(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(DeleteConfirmWindowMillis)
        onTimeout()
    }
}

@Composable
private fun SectionLabel(label: String) {
    val colors = LocalHandoffColors.current
    Column(Modifier.padding(top = 14.dp)) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textMuted,
            modifier = Modifier.padding(bottom = 3.dp)
        )
        HorizontalDivider(color = colors.border, modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun ThemeChip(name: String, isActive: Boolean, onClick: () -> Unit) {
    val colors = LocalHandoffColors.current
    Box(
        Modifier
            .background(if (isActive) colors.accent else colors.panelAlt, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            name,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            color = if (isActive) Color.White else colors.text
        )
    }
}

/** A [Slider] with a half-height thumb (feedback: the default thumb reads as "massive" next to
 *  this dialog's small labels/swatches) -- shared by both sliders in the row-appearance section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, accent: Color) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = remember { MutableInteractionSource() },
                colors = SliderDefaults.colors(thumbColor = accent),
                thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 22.dp)
            )
        }
    )
}

/** A thumb drawn in its own reserved-width column, not overlapping scrollable content the way
 *  RowColors.kt's usual `Modifier.verticalScrollbar` draw-over approach does -- see the comment at
 *  this dialog's scrollable Row for why. */
@Composable
private fun ScrollbarTrack(state: androidx.compose.foundation.ScrollState, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (state.maxValue <= 0) return@Canvas
        val viewportHeight = size.height
        val contentHeight = viewportHeight + state.maxValue
        val thumbHeight = (viewportHeight * (viewportHeight / contentHeight)).coerceAtLeast(24.dp.toPx())
        val scrollableTrack = viewportHeight - thumbHeight
        val thumbY = scrollableTrack * (state.value.toFloat() / state.maxValue)
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, thumbY),
            size = androidx.compose.ui.geometry.Size(size.width, thumbHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2)
        )
    }
}

@Composable
private fun PillButton(
    label: String,
    background: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier
            .background(if (enabled) background else background.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) contentColor else contentColor.copy(alpha = 0.6f))
    }
}

/** A card rendered through the real [controllerRowColors]/[controllerBadges] functions against
 *  [token]'s synthetic controller -- what this token actually looks like in the live list, badge
 *  and chat button included, not a flat color swatch. Layout mirrors the real ControllerRow: the
 *  badge sits *under* the callsign (not crammed into the main line), so callsign + frequency +
 *  chat button always fit on one row regardless of whether a badge is present. The chat button
 *  itself is a plain icon with no background box, matching the real row's pin/chat buttons -- an
 *  earlier version wrapped it in a colored badge-style box, which doesn't exist on the real row.
 *  Tapping the card opens the hue wheel for [token]. */
@Composable
private fun PreviewCard(token: PreviewToken, palette: RowColorPalette, onClick: () -> Unit) {
    val handoffColors = LocalHandoffColors.current
    val rowColors = controllerRowColors(
        token.controller, token.com1Active, token.com2Active, handoffColors, token.com1Standby, token.com2Standby, palette
    )
    val rowShape = RoundedCornerShape(10.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .background(rowColors.background, rowShape)
            .border(1.5.dp, rowColors.border, rowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(token.controller.callsign, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = rowColors.text, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(
                RadioFrequency.format(token.controller.frequency),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = RobotoMono,
                color = rowColors.text.copy(alpha = 0.9f),
                maxLines = 1
            )
            token.controller.rating?.let { rating ->
                Box(
                    Modifier
                        .widthIn(min = 30.dp)
                        .background(rowColors.badgeBackground, RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(previewRatingLabels[rating] ?: rating.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = rowColors.text)
                }
            }
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null, tint = rowColors.text, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Facility tokens a contact-me request can realistically land on -- excludes ATIS (an ATIS
 *  station never requests contact-me), used both for the flash demo below and its warning list. */
private val contactMeCapableFacilities = listOf(
    "Delivery" to RowColorPalette::delHue,
    "Ground" to RowColorPalette::gndHue,
    "Tower" to RowColorPalette::twrHue,
    "Approach / Departure" to RowColorPalette::appDepHue,
    "Center" to RowColorPalette::ctrHue
)

/** Shows the contact-me flash live against Center's hue -- CTR is the most common facility a
 *  contact-me request actually comes from, unlike ATIS (which the demo used before and can never
 *  trigger one). Alternates against the fixed alert yellow every 500ms exactly like the real row,
 *  plus a warning line if any contact-me-capable facility hue sits within
 *  [HazardYellowWarningThresholdDegrees] of it. */
@Composable
private fun ContactMeFlashDemo(palette: RowColorPalette) {
    val colors = LocalHandoffColors.current
    var phaseA by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            phaseA = !phaseA
        }
    }

    val atRisk = contactMeCapableFacilities.filter { (_, get) -> hueDistance(get(palette), FacilityColors.HAZARD_YELLOW_HUE) < HazardYellowWarningThresholdDegrees }

    val demoColor = if (phaseA) FacilityColors.fullColor(palette.ctrHue).bg else FacilityColors.hazardYellow
    val demoText = if (perceptualLightness(demoColor) < palette.textLightnessThreshold) Color.White else nearBlackText
    // Same badge-background formula controllerRowColors uses -- keeps the "CONTACT ME" pill
    // exactly like a real badge, not an approximation, including as it flips look on the yellow
    // phase.
    val demoBadgeBg = if (perceptualLightness(demoColor) < palette.textLightnessThreshold) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.1f)

    Box(
        Modifier
            .fillMaxWidth()
            .background(demoColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Callsign and badge stacked (not inline) -- matches how the real row puts the badge
        // *under* the callsign (see PreviewCard/ControllerRow), and avoids the badge's own Box
        // getting squeezed narrow enough at split-screen widths to wrap "CONTACT ME" one letter
        // per line instead of just not fitting inline.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("LOVV_CTR", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = demoText)
            Box(
                Modifier
                    .background(demoBadgeBg, RoundedCornerShape(5.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text("CONTACT ME", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = demoText, maxLines = 1, softWrap = false)
            }
        }
    }

    if (atRisk.isNotEmpty()) {
        val names = atRisk.joinToString(", ") { it.first }
        val verb = if (atRisk.size == 1) "is" else "are"
        Text(
            "⚠ $names $verb close to the contact-me alert color — the flash may be hard to notice.",
            fontSize = 13.sp,
            color = outOfBandRed,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** A dedicated dialog around [HueWheel] -- issue #21 feedback asked for a "traditional" color
 *  selector (rather than a plain 0-360 linear slider) that shows every hue at once, so e.g. red
 *  can be spotted at a glance instead of hunting for it by dragging. Stays hue-only (no
 *  saturation/lightness control), matching this palette's data model -- see RowColorPalette.kt. */
@Composable
private fun HueWheelDialog(label: String, hue: Float, onHueChange: (Float) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalHandoffColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(colors.panel, RoundedCornerShape(16.dp))
                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.text)
            Spacer(Modifier.height(16.dp))
            HueWheel(hue = hue, onHueChange = onHueChange, modifier = Modifier.size(240.dp))
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(28.dp).background(FacilityColors.fullColor(hue).bg, RoundedCornerShape(6.dp)))
                Text("${hue.roundToInt()}°", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
            }
            Spacer(Modifier.height(16.dp))
            PillButton("Done", colors.accent, Color.White, onClick = onDismiss)
        }
    }
}

/** A ring showing every hue at once (a sampled sweep gradient at the same L/C
 *  [FacilityColors.fullColor] uses by default) with a draggable handle -- lets a hue be picked by
 *  its visual position (e.g. "red is over there") instead of hunting along a linear 0-360 slider.
 *  Driven purely by drag, not a separate tap handler -- Compose's detectDragGestures already fires
 *  on the initial touch-down once past its slop threshold, which covers this control's normal
 *  press-and-drag-around-the-ring usage. */
@Composable
private fun HueWheel(hue: Float, onHueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val sweepColors = remember { (0..24).map { i -> FacilityColors.fullColor(i * 15f).bg } }
    val brush = remember(sweepColors) { Brush.sweepGradient(sweepColors) }

    Canvas(
        modifier.pointerInput(Unit) {
            fun angleFor(offset: Offset): Float {
                val center = Offset(size.width / 2f, size.height / 2f)
                val degrees = Math.toDegrees(atan2((offset.y - center.y).toDouble(), (offset.x - center.x).toDouble())).toFloat()
                return (degrees + 360f) % 360f
            }
            detectDragGestures(
                onDragStart = { onHueChange(angleFor(it)) },
                onDrag = { change, _ -> onHueChange(angleFor(change.position)) }
            )
        }
    ) {
        val strokeWidth = size.minDimension * 0.18f
        val radius = (size.minDimension - strokeWidth) / 2f
        drawCircle(brush = brush, radius = radius, style = Stroke(strokeWidth))

        val angleRad = Math.toRadians(hue.toDouble())
        val handleCenter = Offset(
            center.x + radius * cos(angleRad).toFloat(),
            center.y + radius * sin(angleRad).toFloat()
        )
        drawCircle(color = Color.White, radius = strokeWidth / 2f + 4.dp.toPx(), center = handleCenter, style = Stroke(3.dp.toPx()))
        drawCircle(color = FacilityColors.fullColor(hue).bg, radius = strokeWidth / 2f - 4.dp.toPx(), center = handleCenter)
    }
}
