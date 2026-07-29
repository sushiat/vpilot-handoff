package at.sushi.handoff.ui.theme

import androidx.compose.ui.graphics.Color
import at.sushi.handoff.protocol.Controller

/** Resolved background/border/text/badge-background for one controller-list row, per issue
 *  #13's JS reference (`fullColor`/`fadedColor`/`textColorForL`/`chipBg`, transcribed exactly --
 *  not re-derived from the prose spec). [isFlashing] tells the caller (a Composable, since actual
 *  animation needs Compose) whether to alternate this against the hazard-yellow "phase B" state
 *  every 500ms, per the reference's `contactFlash` keyframes (`0%,49% -> phase A; 50%,99% ->
 *  phase B`, over a 1s cycle, hard-cut via `steps(1)`, not eased). Kept as a pure function (no
 *  Compose dependency beyond [Color]) so this -- the easiest thing in this feature to get subtly
 *  wrong -- stays unit-testable in isolation. */
data class RowColors(
    val background: Color,
    val border: Color,
    val text: Color,
    val badgeBackground: Color,
    val isFlashing: Boolean,
    // Non-null only for isCurrent/isStandbyTuned rows -- the TUNED/STBY badge gets its own fixed
    // COM1/COM2-hue color (see controllerRowColors) instead of the row's normal badgeBackground,
    // so it needs its own text/background pair. Every other badge (PINNED, NEXT, SELCAL, ...)
    // keeps the normal pair -- the row's background/border are always just the facility color.
    val tunedBadgeText: Color? = null,
    val tunedBadgeBackground: Color? = null
)

/** The near-black text color the reference uses instead of pure black (`rgba(0,0,0,.82)`). */
val nearBlackText = Color.Black.copy(alpha = 0.82f)

/** Scales this color's RGB channels toward black by [amount] (0 = unchanged, 1 = pure black) --
 *  RowColorPalette.darkModeOffset's implementation. A plain per-channel scale rather than an
 *  OKLCH-based recomputation since it needs to apply uniformly to an already-resolved [Color] from
 *  either fullColor or fadedColor, not just one specific hue/lightness/chroma call site. */
private fun Color.darkenTowardBlack(amount: Float): Color =
    copy(red = red * (1f - amount), green = green * (1f - amount), blue = blue * (1f - amount))

/** Last underscore-delimited token of a callsign (e.g. "LOWW_N_GND" -> "GND"), used as a
 *  callsign-only fallback for facility classification before the VATSIM data feed has enriched
 *  a controller with a real facility number. */
private fun facilitySuffix(callsign: String): String =
    callsign.substringAfterLast('_', missingDelimiterValue = "")

/** VATSIM facility number -> hue (degrees). ATIS is detected via callsign suffix rather than a
 *  facility number. [Controller.facility] is null until the VATSIM data feed enriches a
 *  freshly-added controller (it comes only from IBroker at first) -- falling straight to a
 *  hardcoded "unknown" hue there previously caused newly-added GND/TWR stations to render
 *  indistinguishably from DEL (250 vs DEL's 255, both blue) until the next feed poll. So an
 *  unenriched controller is classified from its callsign suffix instead, matching
 *  [facilitySuffixName]; only a truly unrecognized suffix falls back to hue 250. */
fun facilityHue(controller: Controller, palette: RowColorPalette = DefaultRowColorPalette): Float {
    if (controller.callsign.endsWith("_ATIS")) return palette.atisHue
    return when (controller.facility) {
        2 -> palette.delHue
        3 -> palette.gndHue
        4 -> palette.twrHue
        5 -> palette.appDepHue
        6 -> palette.ctrHue
        else -> when (facilitySuffix(controller.callsign)) {
            "DEL" -> palette.delHue
            "GND" -> palette.gndHue
            "TWR" -> palette.twrHue
            "APP", "DEP" -> palette.appDepHue
            "CTR" -> palette.ctrHue
            else -> 250f
        }
    }
}

/** Whether a controller should get the TWR-specific L48/C0.22 color tweak -- true for a
 *  data-feed-confirmed facility 4, or (before enrichment) a callsign ending in "_TWR", so the
 *  tweak doesn't silently disappear for freshly-added stations the same way the hue used to. */
private fun isTowerFacility(controller: Controller): Boolean =
    controller.facility == 4 || (controller.facility == null && facilitySuffix(controller.callsign) == "TWR")

/** The row's full-saturation color if it were flagged (isCurrent/contact-me/next/likelyNext) --
 *  exposed separately from [controllerRowColors] since the chat panel's SELCAL bubble also needs
 *  a station's "own color" as its non-flashing phase. */
fun facilityColor(controller: Controller, palette: RowColorPalette = DefaultRowColorPalette): Color {
    val hue = facilityHue(controller, palette)
    val isAtis = controller.callsign.endsWith("_ATIS")
    return when {
        isAtis -> FacilityColors.fullColor(hue, 85f, 0.19f).bg
        isTowerFacility(controller) -> FacilityColors.fullColor(hue, 48f, 0.22f).bg
        else -> FacilityColors.fullColor(hue).bg
    }
}

/** The facility-suffix word for the row's station-name line (e.g. "Tower", "Ground"). The
 *  friendly airport/city name (e.g. "Heathrow Tower") is a future VatSpy-backed protocol field
 *  the plugin doesn't send yet -- see docs/protocol.md -- so this client only ever shows the
 *  suffix word, never a resolved airport name. */
fun facilitySuffixName(callsign: String): String? {
    if (callsign.endsWith("_ATIS")) return "ATIS"
    return when (callsign.substringAfterLast('_', missingDelimiterValue = "")) {
        "DEL" -> "Delivery"
        "GND" -> "Ground"
        "TWR" -> "Tower"
        "APP" -> "Approach"
        "DEP" -> "Departure"
        "CTR" -> "Control"
        else -> null
    }
}

/** A "contact me" request is resolved (stops flashing/badging) once the requesting station's
 *  frequency is loaded into COM1 or COM2 active -- computed live from current radio state, not a
 *  one-time dismiss, so flying away from the frequency resumes the flashing. */
fun isContactMeResolved(controller: Controller, com1Active: Int?, com2Active: Int?): Boolean =
    controller.isContactMe && (controller.frequency == com1Active || controller.frequency == com2Active)

private fun isContactMeActive(controller: Controller, com1Active: Int?, com2Active: Int?): Boolean =
    controller.isContactMe && controller.frequency != com1Active && controller.frequency != com2Active

// NEXT_LIKELY (rendered "NEXT?") replaces the old APPROACHING badge -- issue #18's isLikelyNext
// is a confidence-capped variant of isNext (a genuine tie, or unconfirmed route-relevance), not
// an unrelated concept, so it gets the same badge slot with a softer label instead of its own.
enum class ControllerBadge { TUNED, STBY, CONTACT_ME, NEXT, NEXT_LIKELY, PINNED, SELCAL }

/** Badges in the doc's fixed display priority order (TUNED, STBY, CONTACT ME, NEXT/NEXT?,
 *  PINNED, SELCAL). Every flag here except the contact-me-resolved check is read straight off
 *  [Controller] -- server-authoritative, no client-side re-derivation (issue #18: this was the
 *  actual bug behind isPinned/isStandbyTuned being computed locally for a while). */
fun controllerBadges(
    controller: Controller,
    com1Active: Int?,
    com2Active: Int?
): List<ControllerBadge> = buildList {
    if (controller.isCurrent) add(ControllerBadge.TUNED)
    if (controller.isStandbyTuned) add(ControllerBadge.STBY)
    if (isContactMeActive(controller, com1Active, com2Active)) add(ControllerBadge.CONTACT_ME)
    if (controller.isNext) add(ControllerBadge.NEXT)
    if (controller.isLikelyNext) add(ControllerBadge.NEXT_LIKELY)
    if (controller.isPinned) add(ControllerBadge.PINNED)
    if (controller.isSelcalActive) add(ControllerBadge.SELCAL)
}

/** COM1 gets [FacilityColors.TUNED_HUE] (teal), COM2 gets [FacilityColors.COM2_TUNED_HUE]
 *  (rose) -- a row tuned/standby-loaded on both at once (same frequency on both radios) defaults
 *  to COM1's color rather than picking arbitrarily. Fixed constants, not palette-driven (issue
 *  #21: after trying a dedicated hue/border for these, they're hardcoded now, same as the
 *  contact-me alert yellow and SELCAL red). */
private fun tunedHueFor(frequency: Int, com1Frequency: Int?, com2Frequency: Int?): Float = when {
    frequency == com1Frequency -> FacilityColors.TUNED_HUE
    frequency == com2Frequency -> FacilityColors.COM2_TUNED_HUE
    else -> FacilityColors.TUNED_HUE
}

/** Row background/border/text per the reference's `fullColor`/`fadedColor`/`textColorForL`:
 *  - Background is the facility color (full-saturation flagged treatment for isCurrent/
 *    isStandbyTuned/contact-me/next/likelyNext/highlighted/pinned; TWR gets L48/C0.22, a flagged
 *    ATIS gets L85/C0.19, everything else L58/C0.16) or the desaturated faded background
 *    (see [FacilityColors.fadedColor]) for a plain, unflagged row.
 *  - Border always matches the background's own facility color -- issue #21 tried a dedicated
 *    COM1/COM2-hue border (and a gradient fill) to make isCurrent/isStandbyTuned "pop" more, but
 *    it complicated the row's otherwise clean/consistent look for a state that's already
 *    unambiguous from the tuned-frequency readout, the row's position (bucket 1/2, see
 *    docs/controller-ranking.md), and the TUNED/STBY badge -- which now carries the distinct
 *    fixed teal/rose color instead ([RowColors.tunedBadgeBackground]/[tunedBadgeText]).
 *  - An unresolved contact-me row still flashes ([RowColors.isFlashing]) against hazard-yellow.
 *  Text color is picked from [perceptualLightness] of the *actual rendered* background (real sRGB
 *  relative luminance), not the nominal OKLCH lightness value fed into fullColor/fadedColor -- a
 *  flat threshold on that nominal input ignores how much hue/chroma shift a color's real
 *  perceived brightness. Threshold defaults to 54 (user-adjustable, see
 *  RowColorPalette.textLightnessThreshold) -- see RowColorsTest. Badge chip background is
 *  white-22%-alpha on dark rows, black-10%-alpha on light rows. */
fun controllerRowColors(
    controller: Controller,
    com1Active: Int?,
    com2Active: Int?,
    colors: HandoffColors,
    com1Standby: Int? = null,
    com2Standby: Int? = null,
    palette: RowColorPalette = DefaultRowColorPalette
): RowColors {
    val hue = facilityHue(controller, palette)
    val isAtis = controller.callsign.endsWith("_ATIS")
    val contactMeActive = isContactMeActive(controller, com1Active, com2Active)

    val col = when {
        // isHighlighted/isPinned are no-badge-color signals -- pinned in particular is a manual
        // bookmark the pilot deliberately set, so it should never read as visually less relevant
        // than an untouched, plain station -- both earn the same full-saturation treatment as a
        // badged row (contact-me/next/likelyNext/isCurrent/isStandbyTuned) but don't add anything
        // themselves to controllerBadges beyond their own PINNED tag, and (unlike an unresolved
        // contact-me row) never flash -- see docs/protocol.md.
        controller.isCurrent || controller.isStandbyTuned || contactMeActive || controller.isNext ||
            controller.isLikelyNext || controller.isHighlighted || controller.isPinned -> when {
            isAtis -> FacilityColors.fullColor(hue, 85f, 0.19f)
            isTowerFacility(controller) -> FacilityColors.fullColor(hue, 48f, 0.22f)
            else -> FacilityColors.fullColor(hue)
        }
        else -> FacilityColors.fadedColor(hue, isAtis, isTowerFacility(controller), palette.fadedBrightnessOffset)
    }

    // Extra uniform darkening, dark theme only, applied on top of everything above -- including
    // highlighted/tuned rows. Applied before the text-contrast decision below so black-vs-white
    // still tracks the *actually rendered* (dimmed) background, not the pre-dim value.
    val darkModeDim = if (colors.isDark) palette.darkModeOffset else 0f
    val backgroundColor = col.bg.darkenTowardBlack(darkModeDim)
    val borderColor = col.border.darkenTowardBlack(darkModeDim)

    val isWhiteText = perceptualLightness(backgroundColor) < palette.textLightnessThreshold
    val text = if (isWhiteText) Color.White else nearBlackText
    val badgeBackground = if (isWhiteText) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.1f)

    val tunedHue = when {
        controller.isCurrent -> tunedHueFor(controller.frequency, com1Active, com2Active)
        controller.isStandbyTuned -> tunedHueFor(controller.frequency, com1Standby, com2Standby)
        else -> null
    }
    val tunedBadgeBackground = tunedHue?.let { FacilityColors.fullColor(it).bg }
    // Always white, not the usual perceptualLightness-threshold decision -- feedback: COM1's teal
    // read fine with the computed near-black text in isolation, but next to COM2's white-on-rose
    // the two badges looked inconsistent, and COM1 read as "darker" than the formula's nominal
    // input suggested on the real device. Both COM1/COM2 badges are meant to look like one
    // consistent chip style, not independently optimized per hue.
    val tunedBadgeText = tunedBadgeBackground?.let { Color.White }

    return RowColors(backgroundColor, borderColor, text, badgeBackground, contactMeActive, tunedBadgeText, tunedBadgeBackground)
}

/** Which of the 3 visually-distinct groups a row falls into, in the same order the server already
 *  sorts the list (see docs/controller-ranking.md's buckets 1-9) -- tuned (buckets 1-2), other
 *  flagged/highlighted (3-8), or plain (bucket 9). Mirrors [controllerRowColors]' own branching
 *  exactly (not the doc's bucket numbers directly) so the boundary this drives -- extra spacing
 *  between groups in the list, see ControllerList.kt -- always lines up with an actual visible
 *  background-color transition, never a spacing change with no color change beside it. */
fun controllerRowGroup(controller: Controller, com1Active: Int?, com2Active: Int?): Int = when {
    controller.isCurrent || controller.isStandbyTuned -> 1
    isContactMeActive(controller, com1Active, com2Active) || controller.isNext ||
        controller.isLikelyNext || controller.isHighlighted || controller.isPinned -> 2
    else -> 3
}
