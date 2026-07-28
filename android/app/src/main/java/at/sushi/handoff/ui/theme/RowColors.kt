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
fun facilityHue(controller: Controller): Float {
    if (controller.callsign.endsWith("_ATIS")) return FacilityColors.ATIS_HUE
    return when (controller.facility) {
        2 -> FacilityColors.DEL_HUE
        3 -> FacilityColors.GND_HUE
        4 -> FacilityColors.TWR_HUE
        5 -> FacilityColors.APP_DEP_HUE
        6 -> FacilityColors.CTR_HUE
        else -> when (facilitySuffix(controller.callsign)) {
            "DEL" -> FacilityColors.DEL_HUE
            "GND" -> FacilityColors.GND_HUE
            "TWR" -> FacilityColors.TWR_HUE
            "APP", "DEP" -> FacilityColors.APP_DEP_HUE
            "CTR" -> FacilityColors.CTR_HUE
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
fun facilityColor(controller: Controller): Color {
    val hue = facilityHue(controller)
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
 *  to COM1's color rather than picking arbitrarily. */
private fun tunedHueFor(frequency: Int, com1Frequency: Int?, com2Frequency: Int?): Float = when {
    frequency == com1Frequency -> FacilityColors.TUNED_HUE
    frequency == com2Frequency -> FacilityColors.COM2_TUNED_HUE
    else -> FacilityColors.TUNED_HUE
}

/** Row background/border/text per the reference's `fullColor`/`fadedColor`/`textColorForL`:
 *  - isCurrent always wins: solid COM1/COM2 tuned color (see [tunedHueFor]), regardless of
 *    anything else.
 *  - else an unresolved contact-me / likely-next / approaching flag: solid facility color (TWR
 *    gets L48/C0.22, a flagged ATIS gets L85/C0.19, everything else the L58/C0.16 default) --
 *    and if it's specifically an unresolved contact-me row, [RowColors.isFlashing] is true so the
 *    caller alternates this against hazard-yellow.
 *  - else if isStandbyTuned: a darker, less saturated shade of whichever COM's tuned color it'll
 *    become active on (L38/C0.12) -- distinguishable from both the bright tuned color and the
 *    plain desaturated facility background below.
 *  - else: the same facility hue, desaturated per-theme (still faintly visible), no border.
 *  Text is white below L62, else a near-black `rgba(0,0,0,.82)` -- never chosen per-color by hand.
 *  Badge chip background is white-22%-alpha on dark rows, black-10%-alpha on light rows. */
fun controllerRowColors(
    controller: Controller,
    com1Active: Int?,
    com2Active: Int?,
    colors: HandoffColors,
    com1Standby: Int? = null,
    com2Standby: Int? = null
): RowColors {
    val hue = facilityHue(controller)
    val isAtis = controller.callsign.endsWith("_ATIS")
    val contactMeActive = isContactMeActive(controller, com1Active, com2Active)

    val col = when {
        controller.isCurrent -> FacilityColors.fullColor(tunedHueFor(controller.frequency, com1Active, com2Active))
        // isHighlighted is a no-badge signal -- it earns the same full-saturation treatment as a
        // badged row (contact-me/next/approaching) but never adds anything to controllerBadges,
        // and (unlike an unresolved contact-me row) never flashes -- see docs/protocol.md.
        contactMeActive || controller.isNext || controller.isLikelyNext || controller.isHighlighted -> when {
            isAtis -> FacilityColors.fullColor(hue, 85f, 0.19f)
            isTowerFacility(controller) -> FacilityColors.fullColor(hue, 48f, 0.22f)
            else -> FacilityColors.fullColor(hue)
        }
        // Just a shade or two darker than the full L58/C0.16 tuned color -- not the big drop down
        // to fadedColor's near-gray L92/L26. A standby-prepared station should read as an almost-
        // as-vivid variant of its eventual tuned color, not lumped in visually with the plain
        // desaturated "nothing going on" rows.
        controller.isStandbyTuned -> FacilityColors.fullColor(
            tunedHueFor(controller.frequency, com1Standby, com2Standby),
            lightnessPercent = 50f,
            chroma = 0.15f
        )
        else -> FacilityColors.fadedColor(hue, colors.isDark)
    }

    val isWhiteText = col.lightnessPercent < 62f
    val text = if (isWhiteText) Color.White else nearBlackText
    val badgeBackground = if (isWhiteText) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.1f)
    return RowColors(col.bg, col.border, text, badgeBackground, contactMeActive)
}
