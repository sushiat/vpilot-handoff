package at.sushi.handoff.ui.theme

import at.sushi.handoff.protocol.Controller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RowColorsTest {

    private fun controller(
        callsign: String = "EGLL_TWR",
        frequency: Int = 23725,
        facility: Int? = 4,
        isCurrent: Boolean = false,
        isContactMe: Boolean = false,
        isHighlighted: Boolean = false,
        isNext: Boolean = false,
        isLikelyNext: Boolean = false,
        isPinned: Boolean = false,
        isStandbyTuned: Boolean = false,
        isSelcalActive: Boolean = false
    ) = Controller(
        callsign = callsign,
        frequency = frequency,
        latitude = 0.0,
        longitude = 0.0,
        facility = facility,
        isCurrent = isCurrent,
        isContactMe = isContactMe,
        isHighlighted = isHighlighted,
        isNext = isNext,
        isLikelyNext = isLikelyNext,
        isPinned = isPinned,
        isStandbyTuned = isStandbyTuned,
        isSelcalActive = isSelcalActive
    )

    @Test
    fun isContactMeResolved_falseUntilTunedToTheRequestingFrequency() {
        val c = controller(frequency = 23725, isContactMe = true)
        assertFalse(isContactMeResolved(c, com1Active = 21000, com2Active = null))
        assertTrue(isContactMeResolved(c, com1Active = 23725, com2Active = null))
        assertTrue(isContactMeResolved(c, com1Active = null, com2Active = 23725))
    }

    @Test
    fun isContactMeResolved_falseWhenNotRequestingContactMeAtAll() {
        val c = controller(frequency = 23725, isContactMe = false)
        assertFalse(isContactMeResolved(c, com1Active = 23725, com2Active = null))
    }

    @Test
    fun controllerRowColors_isCurrentUsesFacilityColorForBothBackgroundAndBorder() {
        // issue #21: isCurrent no longer overrides the row's background OR border with a
        // dedicated COM1/COM2 hue -- after trying that (and a gradient dip), both were dropped in
        // favor of a fixed-color TUNED/STBY badge instead (see tunedBadgeBackground/Text below).
        // The row itself always just uses its own facility color, tuned or not.
        val c = controller(isCurrent = true, isContactMe = true, isLikelyNext = true, facility = 4)
        val result = controllerRowColors(c, com1Active = null, com2Active = null, colors = LightHandoffColors)
        assertEquals(FacilityColors.fullColor(FacilityColors.TWR_HUE, 48f, 0.22f).bg, result.background)
        assertEquals(FacilityColors.fullColor(FacilityColors.TWR_HUE, 48f, 0.22f).border, result.border)
    }

    @Test
    fun controllerRowColors_unresolvedContactMeUsesFullSaturationFacilityColorAndFlashes() {
        val c = controller(facility = 3, isContactMe = true) // GND
        val result = controllerRowColors(c, com1Active = null, com2Active = null, colors = LightHandoffColors)
        assertEquals(FacilityColors.fullColor(FacilityColors.GND_HUE).bg, result.background)
        assertTrue(result.isFlashing)
    }

    @Test
    fun controllerRowColors_resolvedContactMeFallsBackToDesaturatedAndStopsFlashing() {
        val c = controller(facility = 3, frequency = 23725, isContactMe = true)
        val tunedAway = controllerRowColors(c, com1Active = 23725, com2Active = null, colors = LightHandoffColors)
        val unresolved = controllerRowColors(c, com1Active = null, com2Active = null, colors = LightHandoffColors)
        assertEquals(FacilityColors.fullColor(FacilityColors.GND_HUE).bg, unresolved.background)
        assertTrue(tunedAway.background != FacilityColors.fullColor(FacilityColors.GND_HUE).bg)
        assertFalse(tunedAway.isFlashing)
    }

    @Test
    fun controllerRowColors_unrelatedRowIsDesaturatedNotFullSaturationAndHasNoBorder() {
        val c = controller(facility = 4) // plain TWR, no flags set
        val result = controllerRowColors(c, com1Active = null, com2Active = null, colors = LightHandoffColors)
        assertTrue(result.background != FacilityColors.fullColor(FacilityColors.TWR_HUE, 48f, 0.22f).bg)
        assertEquals(androidx.compose.ui.graphics.Color.Transparent, result.border)
    }

    @Test
    fun controllerRowColors_flaggedRowHasAVisibleBorder() {
        val c = controller(facility = 3, isContactMe = true)
        val result = controllerRowColors(c, com1Active = null, com2Active = null, colors = LightHandoffColors)
        assertTrue(result.border != androidx.compose.ui.graphics.Color.Transparent)
    }

    @Test
    fun controllerRowColors_com1TunedBadgeAlwaysGetsWhiteText() {
        // Not the usual perceptualLightness-threshold decision -- feedback: COM1's teal read fine
        // with near-black text in isolation, but inconsistent next to COM2's white-on-rose badge,
        // and read "darker" than the formula's nominal input suggested on the real device. Both
        // COM1/COM2 badges are meant to look like one consistent chip style.
        val current = controller(isCurrent = true)
        val result = controllerRowColors(current, com1Active = 23725, com2Active = null, colors = LightHandoffColors)
        assertEquals(androidx.compose.ui.graphics.Color.White, result.tunedBadgeText)
    }

    @Test
    fun controllerRowColors_com2TunedBadgeAlsoAlwaysGetsWhiteText() {
        val com2 = controller(frequency = 18000, isCurrent = true)
        val result = controllerRowColors(com2, com1Active = null, com2Active = 18000, colors = LightHandoffColors)
        assertEquals(androidx.compose.ui.graphics.Color.White, result.tunedBadgeText)
    }

    @Test
    fun controllerRowColors_highlightedFacilityRowsGetWhiteTextFromTheFormula() {
        // GND/TWR/CTR all compute a real perceptualLightness well below the 54 threshold despite
        // TWR's nominal input (48) being *lower* than GND/CTR's (58) -- confirmed on-device that
        // forcing any of these to black (an earlier, since-reverted attempt) made them harder to
        // read, not easier.
        val highlightedGnd = controller(facility = 3, isHighlighted = true)
        val highlightedTwr = controller(facility = 4, isHighlighted = true)
        val highlightedCtr = controller(facility = 6, isHighlighted = true)
        for (c in listOf(highlightedGnd, highlightedTwr, highlightedCtr)) {
            val result = controllerRowColors(c, com1Active = null, com2Active = null, colors = LightHandoffColors)
            assertEquals(androidx.compose.ui.graphics.Color.White, result.text)
        }
    }

    @Test
    fun controllerRowColors_standbyTunedBadgeUsesSameTunedHueTextFormulaAsCurrent() {
        // isCurrent and isStandbyTuned share the identical border/badge treatment (issue #21).
        val standby = controller(isStandbyTuned = true)
        val result = controllerRowColors(standby, com1Active = null, com2Active = null, colors = LightHandoffColors, com1Standby = standby.frequency)
        assertEquals(androidx.compose.ui.graphics.Color.White, result.tunedBadgeText)
    }

    @Test
    fun controllerRowColors_fadedRowAtDefaultOffsetGetsWhiteText() {
        // issue #21: fadedBrightnessOffset is theme-independent (governs light and dark alike),
        // so this no longer depends on colors.isDark at all. Default (-0.5) on a TWR row computes
        // L24 (halfway from TWR's own L48 highlight base toward black) -- well below the
        // threshold -> white text.
        val plain = controller(facility = 4)
        val result = controllerRowColors(plain, com1Active = null, com2Active = null, colors = LightHandoffColors)
        assertEquals(androidx.compose.ui.graphics.Color.White, result.text)
    }

    @Test
    fun controllerRowColors_fadedAtOffsetZeroExactlyMatchesTheHighlightedColorIncludingTowerOverride() {
        // The whole point of re-anchoring on the highlight color (issue #21): offset=0 must
        // reproduce the row's own highlighted appearance exactly, including per-facility
        // overrides like TWR's L48/C0.22 -- not a fixed generic L58/C0.16 that could never
        // actually reach TWR/ATIS's real highlighted color even at its brightest setting.
        val plainTwr = controller(facility = 4)
        val highlightedTwr = controller(facility = 4, isHighlighted = true)
        val plainResult = controllerRowColors(
            plainTwr, com1Active = null, com2Active = null, colors = LightHandoffColors,
            palette = DefaultRowColorPalette.copy(fadedBrightnessOffset = 0f)
        )
        val highlightedResult = controllerRowColors(highlightedTwr, com1Active = null, com2Active = null, colors = LightHandoffColors)
        assertEquals(highlightedResult.background, plainResult.background)
    }

    @Test
    fun controllerRowColors_fadedAtFullWhiteGetsNearBlackText() {
        val c = controller(facility = 4)
        val result = controllerRowColors(
            c, com1Active = null, com2Active = null, colors = LightHandoffColors,
            palette = DefaultRowColorPalette.copy(fadedBrightnessOffset = 1f)
        )
        assertEquals(nearBlackText, result.text)
    }

    @Test
    fun controllerRowColors_fadedAtFullBlackGetsWhiteText() {
        val c = controller(facility = 4)
        val result = controllerRowColors(
            c, com1Active = null, com2Active = null, colors = LightHandoffColors,
            palette = DefaultRowColorPalette.copy(fadedBrightnessOffset = -1f)
        )
        assertEquals(androidx.compose.ui.graphics.Color.White, result.text)
    }

    @Test
    fun facilityHue_fallsBackToCallsignSuffixWhenFacilityNotYetEnrichedByDataFeed() {
        // Regression: before the VATSIM data feed enriches a freshly-added controller,
        // `facility` is null. GND and TWR must not both collapse into DEL's near-identical hue.
        val gnd = controller(callsign = "LZIB_GND", facility = null)
        val twr = controller(callsign = "LZIB_TWR", facility = null)
        val del = controller(callsign = "LZIB_DEL", facility = null)
        assertEquals(FacilityColors.GND_HUE, facilityHue(gnd))
        assertEquals(FacilityColors.TWR_HUE, facilityHue(twr))
        assertEquals(FacilityColors.DEL_HUE, facilityHue(del))
        assertTrue(facilityHue(gnd) != facilityHue(del))
        assertTrue(facilityHue(twr) != facilityHue(del))
    }

    @Test
    fun facilityHue_unrecognizedSuffixWithNoFacilityFallsBackToNeutralHue() {
        val c = controller(callsign = "LZIB_APP2", facility = null)
        assertEquals(250f, facilityHue(c))
    }

    @Test
    fun facilitySuffixName_mapsKnownSuffixesAndAtis() {
        assertEquals("Tower", facilitySuffixName("EGLL_TWR"))
        assertEquals("Ground", facilitySuffixName("EGLL_GND"))
        assertEquals("Delivery", facilitySuffixName("EGLL_DEL"))
        assertEquals("Approach", facilitySuffixName("EGLL_APP"))
        assertEquals("Departure", facilitySuffixName("EGLL_DEP"))
        assertEquals("Control", facilitySuffixName("LON_CTR"))
        assertEquals("ATIS", facilitySuffixName("EGLL_ATIS"))
        assertEquals(null, facilitySuffixName("EGLL"))
    }

    @Test
    fun controllerBadges_orderIsFixedAndOnlyAppliesFlaggedBadges() {
        val c = controller(
            isCurrent = true,
            isNext = true,
            isLikelyNext = true,
            isPinned = true,
            isSelcalActive = true
        )
        val badges = controllerBadges(c, com1Active = null, com2Active = null)
        assertEquals(
            listOf(
                ControllerBadge.TUNED,
                ControllerBadge.NEXT,
                ControllerBadge.NEXT_LIKELY,
                ControllerBadge.PINNED,
                ControllerBadge.SELCAL
            ),
            badges
        )
    }

    @Test
    fun controllerBadges_standbyTunedAddsStbyBadgeRightAfterTuned() {
        val c = controller(isLikelyNext = true, isStandbyTuned = true)
        val badges = controllerBadges(c, com1Active = null, com2Active = null)
        assertEquals(listOf(ControllerBadge.STBY, ControllerBadge.NEXT_LIKELY), badges)
    }

    @Test
    fun controllerRowColors_com1CurrentUsesTunedHueForBadgeComp2CurrentUsesDistinctHue() {
        // Background/border are always just the row's own facility color regardless of which COM
        // it's tuned on (issue #21) -- only the TUNED/STBY badge carries the distinct COM1/COM2
        // hue now.
        val com1Current = controller(frequency = 23725, isCurrent = true)
        val com2Current = controller(frequency = 18000, isCurrent = true)
        val com1Result = controllerRowColors(com1Current, com1Active = 23725, com2Active = null, colors = LightHandoffColors)
        val com2Result = controllerRowColors(com2Current, com1Active = null, com2Active = 18000, colors = LightHandoffColors)
        assertEquals(FacilityColors.fullColor(FacilityColors.TUNED_HUE).bg, com1Result.tunedBadgeBackground)
        assertEquals(FacilityColors.fullColor(FacilityColors.COM2_TUNED_HUE).bg, com2Result.tunedBadgeBackground)
        assertTrue(com1Result.tunedBadgeBackground != com2Result.tunedBadgeBackground)
        assertEquals(com1Result.background, com2Result.background)
        assertEquals(com1Result.border, com2Result.border)
    }

    @Test
    fun controllerRowColors_standbyTunedGetsBadgeOfWhicheverComItWillBecomeActiveOn() {
        val com1Standby = controller(frequency = 21000, isStandbyTuned = true)
        val com2Standby = controller(frequency = 19000, isStandbyTuned = true)
        val com1Result = controllerRowColors(com1Standby, com1Active = null, com2Active = null, colors = LightHandoffColors, com1Standby = 21000, com2Standby = null)
        val com2Result = controllerRowColors(com2Standby, com1Active = null, com2Active = null, colors = LightHandoffColors, com1Standby = null, com2Standby = 19000)
        assertEquals(FacilityColors.fullColor(FacilityColors.TUNED_HUE).bg, com1Result.tunedBadgeBackground)
        assertEquals(FacilityColors.fullColor(FacilityColors.COM2_TUNED_HUE).bg, com2Result.tunedBadgeBackground)
        assertTrue(com1Result.tunedBadgeBackground != com2Result.tunedBadgeBackground)
        assertEquals(com1Result.background, com2Result.background)
    }

    @Test
    fun controllerRowGroup_classifiesTunedFlaggedAndPlainIntoDistinctGroups() {
        val tuned = controller(isCurrent = true)
        val standby = controller(isStandbyTuned = true)
        val pinned = controller(isPinned = true)
        val plain = controller()
        assertEquals(1, controllerRowGroup(tuned, com1Active = null, com2Active = null))
        assertEquals(1, controllerRowGroup(standby, com1Active = null, com2Active = null))
        assertEquals(2, controllerRowGroup(pinned, com1Active = null, com2Active = null))
        assertEquals(3, controllerRowGroup(plain, com1Active = null, com2Active = null))
    }

    @Test
    fun controllerRowColors_isHighlightedGetsFullSaturationLikeABadgedRowButDoesNotFlash() {
        val c = controller(callsign = "LON_CTR", facility = 6, isHighlighted = true)
        val result = controllerRowColors(c, com1Active = null, com2Active = null, colors = LightHandoffColors)
        assertEquals(FacilityColors.fullColor(FacilityColors.CTR_HUE).bg, result.background)
        assertFalse(result.isFlashing)
    }

    @Test
    fun controllerRowColors_pinnedAloneGetsFullSaturationNotTheDesaturatedFallback() {
        // Regression: isPinned wasn't wired into controllerRowColors at all -- a plain pinned
        // row with no other flag fell all the way through to the same desaturated "unrelated
        // station" look as a row nobody had touched, which read as if pinning had done nothing.
        val c = controller(callsign = "LON_CTR", facility = 6, isPinned = true)
        val result = controllerRowColors(c, com1Active = null, com2Active = null, colors = LightHandoffColors)
        assertEquals(FacilityColors.fullColor(FacilityColors.CTR_HUE).bg, result.background)
        assertFalse(result.isFlashing)
    }

    @Test
    fun controllerBadges_isHighlightedNeverAddsABadge() {
        val badges = controllerBadges(controller(isHighlighted = true), com1Active = null, com2Active = null)
        assertTrue(badges.isEmpty())
    }

    @Test
    fun controllerBadges_emptyWhenNoFlagsSet() {
        val badges = controllerBadges(controller(), com1Active = null, com2Active = null)
        assertTrue(badges.isEmpty())
    }
}
