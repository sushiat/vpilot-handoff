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
    fun controllerRowColors_isCurrentAlwaysWinsOverEverythingElseForColor() {
        // isCurrent and an unresolved contact-me can't really co-occur in real server data
        // (a truly-tuned frequency is by definition already resolved), but the reference
        // computes contactMeActive/its flash independently of isCurrent, so this (admittedly
        // synthetic) combination still flashes -- isCurrent only wins the base color.
        val c = controller(isCurrent = true, isContactMe = true, isLikelyNext = true, facility = 4)
        val result = controllerRowColors(c, com1Active = null, com2Active = null, colors = LightHandoffColors)
        assertEquals(FacilityColors.fullColor(FacilityColors.TUNED_HUE).bg, result.background)
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
    fun controllerRowColors_com1TunedGetsNearBlackTextFromTheRealPerceptualLightness() {
        // COM1-tuned's teal (real perceptualLightness ~56) reads noticeably better in black than
        // a threshold on the *nominal* OKLCH input would give it (nominal 58% alone reads as
        // "should be white") -- confirmed on-device. This is why controllerRowColors decides text
        // color from perceptualLightness(col.bg), the real rendered color, not the nominal input.
        val current = controller(isCurrent = true)
        val result = controllerRowColors(current, com1Active = 23725, com2Active = null, colors = LightHandoffColors)
        assertEquals(nearBlackText, result.text)
    }

    @Test
    fun controllerRowColors_com2TunedGetsWhiteTextUnderTheSameFormula() {
        // COM2-tuned's rose computes a *lower* real perceptualLightness (~49) than COM1's teal
        // (~56) despite sharing the same nominal L58 input -- the formula genuinely can't put
        // both COM1 and COM2 on the same side of one threshold as GND (~53, confirmed wants
        // white) without contradicting one of them, so this is the accepted trade-off: COM2
        // renders white here, unlike the earlier hardcoded-black attempt.
        val com2 = controller(frequency = 18000, isCurrent = true)
        val result = controllerRowColors(com2, com1Active = null, com2Active = 18000, colors = LightHandoffColors)
        assertEquals(androidx.compose.ui.graphics.Color.White, result.text)
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
    fun controllerRowColors_standbyTunedStaysWhiteTextUnderTheFormula() {
        val standby = controller(isStandbyTuned = true)
        val result = controllerRowColors(standby, com1Active = null, com2Active = null, colors = LightHandoffColors, com1Standby = standby.frequency)
        assertEquals(androidx.compose.ui.graphics.Color.White, result.text)
    }

    @Test
    fun controllerRowColors_textFlipsToNearBlackOnLightDesaturatedBackground() {
        val plain = controller(facility = 4)
        val result = controllerRowColors(plain, com1Active = null, com2Active = null, colors = LightHandoffColors)
        // Light theme's desaturated background (L92) is well above the threshold -> near-black text.
        assertEquals(nearBlackText, result.text)
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
    fun controllerRowColors_com1CurrentUsesTunedHueComp2CurrentUsesDistinctHue() {
        val com1Current = controller(frequency = 23725, isCurrent = true)
        val com2Current = controller(frequency = 18000, isCurrent = true)
        val com1Result = controllerRowColors(com1Current, com1Active = 23725, com2Active = null, colors = LightHandoffColors)
        val com2Result = controllerRowColors(com2Current, com1Active = null, com2Active = 18000, colors = LightHandoffColors)
        assertEquals(FacilityColors.fullColor(FacilityColors.TUNED_HUE).bg, com1Result.background)
        assertEquals(FacilityColors.fullColor(FacilityColors.COM2_TUNED_HUE).bg, com2Result.background)
        assertTrue(com1Result.background != com2Result.background)
    }

    @Test
    fun controllerRowColors_standbyTunedGetsADarkerShadeOfWhicheverComItWillBecomeActiveOn() {
        val com1Standby = controller(frequency = 21000, isStandbyTuned = true)
        val com2Standby = controller(frequency = 19000, isStandbyTuned = true)
        val com1Result = controllerRowColors(com1Standby, com1Active = null, com2Active = null, colors = LightHandoffColors, com1Standby = 21000, com2Standby = null)
        val com2Result = controllerRowColors(com2Standby, com1Active = null, com2Active = null, colors = LightHandoffColors, com1Standby = null, com2Standby = 19000)
        val expectedCom1 = FacilityColors.fullColor(FacilityColors.TUNED_HUE, lightnessPercent = 50f, chroma = 0.15f).bg
        val expectedCom2 = FacilityColors.fullColor(FacilityColors.COM2_TUNED_HUE, lightnessPercent = 50f, chroma = 0.15f).bg
        assertEquals(expectedCom1, com1Result.background)
        assertEquals(expectedCom2, com2Result.background)
        assertTrue(com1Result.background != com2Result.background)
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
