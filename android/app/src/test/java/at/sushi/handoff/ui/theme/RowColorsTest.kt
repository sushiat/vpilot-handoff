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
        isLikelyNextCandidate: Boolean = false,
        isApproaching: Boolean = false,
        isHighlighted: Boolean = false
    ) = Controller(
        callsign = callsign,
        frequency = frequency,
        latitude = 0.0,
        longitude = 0.0,
        facility = facility,
        isCurrent = isCurrent,
        isContactMe = isContactMe,
        isLikelyNextCandidate = isLikelyNextCandidate,
        isApproaching = isApproaching,
        isHighlighted = isHighlighted
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
        val c = controller(isCurrent = true, isContactMe = true, isApproaching = true, facility = 4)
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
    fun controllerRowColors_textFlipsToWhiteOnDarkBackground() {
        val current = controller(isCurrent = true)
        val result = controllerRowColors(current, com1Active = null, com2Active = null, colors = LightHandoffColors)
        // Tuned/current uses the default L58% -- below the 62 threshold -> white text.
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
            isLikelyNextCandidate = true,
            isApproaching = true
        )
        val badges = controllerBadges(
            c,
            com1Active = null,
            com2Active = null,
            com1Standby = null,
            com2Standby = null,
            isPinned = true,
            selcalActive = true
        )
        assertEquals(
            listOf(
                ControllerBadge.TUNED,
                ControllerBadge.NEXT,
                ControllerBadge.APPROACHING,
                ControllerBadge.PINNED,
                ControllerBadge.SELCAL
            ),
            badges
        )
    }

    @Test
    fun controllerBadges_standbyTunedAddsStbyBadgeRightAfterTuned() {
        val c = controller(isLikelyNextCandidate = true)
        val badges = controllerBadges(
            c,
            com1Active = null,
            com2Active = null,
            com1Standby = c.frequency,
            com2Standby = null,
            isPinned = false,
            selcalActive = false
        )
        assertEquals(listOf(ControllerBadge.STBY, ControllerBadge.NEXT), badges)
    }

    @Test
    fun controllerBadges_standbyTunedNeverAppliesToTheCurrentRow() {
        // A row that's already TUNED (current) shouldn't also read as "prepared in standby" --
        // it's already active, not waiting to become active.
        val c = controller(isCurrent = true)
        val badges = controllerBadges(
            c,
            com1Active = null,
            com2Active = null,
            com1Standby = c.frequency,
            com2Standby = null,
            isPinned = false,
            selcalActive = false
        )
        assertEquals(listOf(ControllerBadge.TUNED), badges)
    }

    @Test
    fun controllerRowColors_isHighlightedGetsFullSaturationLikeABadgedRowButDoesNotFlash() {
        val c = controller(callsign = "LON_CTR", facility = 6, isHighlighted = true)
        val result = controllerRowColors(c, com1Active = null, com2Active = null, colors = LightHandoffColors)
        assertEquals(FacilityColors.fullColor(FacilityColors.CTR_HUE).bg, result.background)
        assertFalse(result.isFlashing)
    }

    @Test
    fun controllerBadges_isHighlightedNeverAddsABadge() {
        val badges = controllerBadges(
            controller(isHighlighted = true),
            com1Active = null,
            com2Active = null,
            com1Standby = null,
            com2Standby = null,
            isPinned = false,
            selcalActive = false
        )
        assertTrue(badges.isEmpty())
    }

    @Test
    fun controllerBadges_emptyWhenNoFlagsSet() {
        val badges = controllerBadges(
            controller(),
            com1Active = null,
            com2Active = null,
            com1Standby = null,
            com2Standby = null,
            isPinned = false,
            selcalActive = false
        )
        assertTrue(badges.isEmpty())
    }
}
