package at.sushi.handoff.ui.theme

import androidx.compose.ui.graphics.Color
import at.sushi.handoff.protocol.Controller

/** Resolved background/text color for one controller-list row, per the issue's "Color & badge
 *  logic" table. Kept as a pure function (no Compose dependency beyond [Color]) so the easiest
 *  thing in this feature to get subtly wrong is unit-testable in isolation. */
data class RowColors(val background: Color, val text: Color)

/** VATSIM facility number -> hue (degrees), per the design doc's table. ATIS is detected via
 *  callsign suffix rather than a facility number. Returns null for an unrecognized/missing
 *  facility (falls back to a neutral background in [controllerRowColors]). Kept separate from
 *  [facilityColor] so the desaturated variant can reuse the same hue at a different L/C rather
 *  than reverse-engineering hue out of an already-built RGB color. */
fun facilityHue(controller: Controller): Float? {
    if (controller.callsign.endsWith("_ATIS")) return 95f
    return when (controller.facility) {
        2 -> 255f
        3 -> 140f
        4 -> 25f
        5 -> 60f
        6 -> 300f
        else -> null
    }
}

fun facilityColor(controller: Controller): Color? = when {
    controller.callsign.endsWith("_ATIS") -> FacilityColors.atis
    controller.facility == 2 -> FacilityColors.del
    controller.facility == 3 -> FacilityColors.gnd
    controller.facility == 4 -> FacilityColors.twr
    controller.facility == 5 -> FacilityColors.appDep
    controller.facility == 6 -> FacilityColors.ctr
    else -> null
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

enum class ControllerBadge { TUNED, CONTACT_ME, NEXT, APPROACHING, PINNED, SELCAL }

/** Badges in the doc's fixed display priority order (TUNED, CONTACT ME, NEXT, APPROACHING,
 *  PINNED, SELCAL). [selcalActive] should only ever be true for the currently-tuned row -- SELCAL
 *  always targets whatever frequency is tuned -- but that constraint is the caller's to enforce
 *  (it depends on chat/SELCAL state this function doesn't see). */
fun controllerBadges(
    controller: Controller,
    com1Active: Int?,
    com2Active: Int?,
    isPinned: Boolean,
    selcalActive: Boolean
): List<ControllerBadge> = buildList {
    if (controller.isCurrent) add(ControllerBadge.TUNED)
    if (!isContactMeResolved(controller, com1Active, com2Active) && controller.isContactMe) {
        add(ControllerBadge.CONTACT_ME)
    }
    if (controller.isLikelyNextCandidate) add(ControllerBadge.NEXT)
    if (controller.isApproaching) add(ControllerBadge.APPROACHING)
    if (isPinned) add(ControllerBadge.PINNED)
    if (selcalActive) add(ControllerBadge.SELCAL)
}

/** Row background/text per the doc:
 *  - isCurrent always wins: solid teal ("current"), regardless of anything else.
 *  - else an unresolved contact-me / likely-next / approaching flag: solid facility color.
 *  - else: the same facility hue, desaturated per-theme (still faintly visible).
 *  Text color is picked black/white from the background's perceptual lightness (~62 threshold),
 *  never chosen per-color by hand. */
fun controllerRowColors(
    controller: Controller,
    com1Active: Int?,
    com2Active: Int?,
    colors: HandoffColors
): RowColors {
    val facility = facilityColor(controller)
    val hue = facilityHue(controller)
    val background = when {
        controller.isCurrent -> FacilityColors.current
        !isContactMeResolved(controller, com1Active, com2Active) &&
            (controller.isContactMe || controller.isLikelyNextCandidate || controller.isApproaching) ->
            facility ?: FacilityColors.current
        hue != null -> desaturate(hue, colors.isDark)
        else -> colors.panelAlt
    }
    val text = if (perceptualLightness(background) >= 62f) Color.Black else Color.White
    return RowColors(background, text)
}

/** Same hue as [facilityHue], desaturated to the doc's L92/C0.025 (light theme) or L26/C0.02
 *  (dark theme) so an "unrelated" row still faintly signals facility identity. */
private fun desaturate(hue: Float, isDark: Boolean): Color =
    if (isDark) oklch(0.26f, 0.02f, hue) else oklch(0.92f, 0.025f, hue)
