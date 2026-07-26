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
    val isFlashing: Boolean
)

/** The near-black text color the reference uses instead of pure black (`rgba(0,0,0,.82)`). */
val nearBlackText = Color.Black.copy(alpha = 0.82f)

/** VATSIM facility number -> hue (degrees). ATIS is detected via callsign suffix rather than a
 *  facility number; an unrecognized/missing facility falls back to hue 250, matching the
 *  reference's `FACILITY[c.facility] || 250` (there's no "no hue" case there). */
fun facilityHue(controller: Controller): Float {
    if (controller.callsign.endsWith("_ATIS")) return FacilityColors.ATIS_HUE
    return when (controller.facility) {
        2 -> FacilityColors.DEL_HUE
        3 -> FacilityColors.GND_HUE
        4 -> FacilityColors.TWR_HUE
        5 -> FacilityColors.APP_DEP_HUE
        6 -> FacilityColors.CTR_HUE
        else -> 250f
    }
}

/** The row's full-saturation color if it were flagged (isCurrent/contact-me/next/approaching) --
 *  exposed separately from [controllerRowColors] since the chat panel's SELCAL bubble also needs
 *  a station's "own color" as its non-flashing phase. */
fun facilityColor(controller: Controller): Color {
    val hue = facilityHue(controller)
    val isAtis = controller.callsign.endsWith("_ATIS")
    return when {
        isAtis -> FacilityColors.fullColor(hue, 85f, 0.19f).bg
        controller.facility == 4 -> FacilityColors.fullColor(hue, 48f, 0.22f).bg
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
    if (isContactMeActive(controller, com1Active, com2Active)) add(ControllerBadge.CONTACT_ME)
    if (controller.isLikelyNextCandidate) add(ControllerBadge.NEXT)
    if (controller.isApproaching) add(ControllerBadge.APPROACHING)
    if (isPinned) add(ControllerBadge.PINNED)
    if (selcalActive) add(ControllerBadge.SELCAL)
}

/** Row background/border/text per the reference's `fullColor`/`fadedColor`/`textColorForL`:
 *  - isCurrent always wins: solid teal (hue 195, default L58/C0.16), regardless of anything else.
 *  - else an unresolved contact-me / likely-next / approaching flag: solid facility color (TWR
 *    gets L48/C0.22, a flagged ATIS gets L85/C0.19, everything else the L58/C0.16 default) --
 *    and if it's specifically an unresolved contact-me row, [RowColors.isFlashing] is true so the
 *    caller alternates this against hazard-yellow.
 *  - else: the same facility hue, desaturated per-theme (still faintly visible), no border.
 *  Text is white below L62, else a near-black `rgba(0,0,0,.82)` -- never chosen per-color by hand.
 *  Badge chip background is white-22%-alpha on dark rows, black-10%-alpha on light rows. */
fun controllerRowColors(
    controller: Controller,
    com1Active: Int?,
    com2Active: Int?,
    colors: HandoffColors
): RowColors {
    val hue = facilityHue(controller)
    val isAtis = controller.callsign.endsWith("_ATIS")
    val contactMeActive = isContactMeActive(controller, com1Active, com2Active)

    val col = when {
        controller.isCurrent -> FacilityColors.fullColor(FacilityColors.TUNED_HUE)
        contactMeActive || controller.isLikelyNextCandidate || controller.isApproaching -> when {
            isAtis -> FacilityColors.fullColor(hue, 85f, 0.19f)
            controller.facility == 4 -> FacilityColors.fullColor(hue, 48f, 0.22f)
            else -> FacilityColors.fullColor(hue)
        }
        else -> FacilityColors.fadedColor(hue, colors.isDark)
    }

    val isWhiteText = col.lightnessPercent < 62f
    val text = if (isWhiteText) Color.White else nearBlackText
    val badgeBackground = if (isWhiteText) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.1f)
    return RowColors(col.bg, col.border, text, badgeBackground, contactMeActive)
}
