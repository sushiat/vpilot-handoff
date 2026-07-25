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
        isApproaching: Boolean = false
    ) = Controller(
        callsign = callsign,
        frequency = frequency,
        latitude = 0.0,
        longitude = 0.0,
        facility = facility,
        isCurrent = isCurrent,
        isContactMe = isContactMe,
        isLikelyNextCandidate = isLikelyNextCandidate,
        isApproaching = isApproaching
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
    fun controllerRowColors_isCurrentAlwaysWinsOverEverythingElse() {
        val c = controller(isCurrent = true, isContactMe = true, isApproaching = true, facility = 4)
        val result = controllerRowColors(c, com1Active = null, com2Active = null, colors = LightHandoffColors)
        assertEquals(FacilityColors.current, result.background)
    }

    @Test
    fun controllerRowColors_unresolvedContactMeUsesFullSaturationFacilityColor() {
        val c = controller(facility = 3, isContactMe = true) // GND
        val result = controllerRowColors(c, com1Active = null, com2Active = null, colors = LightHandoffColors)
        assertEquals(FacilityColors.gnd, result.background)
    }

    @Test
    fun controllerRowColors_resolvedContactMeFallsBackToDesaturated() {
        val c = controller(facility = 3, frequency = 23725, isContactMe = true)
        val tunedAway = controllerRowColors(c, com1Active = 23725, com2Active = null, colors = LightHandoffColors)
        val unresolved = controllerRowColors(c, com1Active = null, com2Active = null, colors = LightHandoffColors)
        assertEquals(FacilityColors.gnd, unresolved.background)
        assertTrue(tunedAway.background != FacilityColors.gnd)
    }

    @Test
    fun controllerRowColors_unrelatedRowIsDesaturatedNotFullSaturation() {
        val c = controller(facility = 4) // plain TWR, no flags set
        val result = controllerRowColors(c, com1Active = null, com2Active = null, colors = LightHandoffColors)
        assertTrue(result.background != FacilityColors.twr)
    }

    @Test
    fun controllerRowColors_textFlipsToWhiteOnDarkBackground() {
        val current = controller(isCurrent = true)
        val result = controllerRowColors(current, com1Active = null, com2Active = null, colors = LightHandoffColors)
        // FacilityColors.current is oklch(0.55, ...), below the ~62 threshold -> white text.
        assertEquals(androidx.compose.ui.graphics.Color.White, result.text)
    }

    @Test
    fun controllerRowColors_textFlipsToBlackOnLightDesaturatedBackground() {
        val plain = controller(facility = 4)
        val result = controllerRowColors(plain, com1Active = null, com2Active = null, colors = LightHandoffColors)
        // Light theme's desaturated background (L92) is well above the threshold -> black text.
        assertEquals(androidx.compose.ui.graphics.Color.Black, result.text)
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
    fun controllerBadges_emptyWhenNoFlagsSet() {
        val badges = controllerBadges(
            controller(),
            com1Active = null,
            com2Active = null,
            isPinned = false,
            selcalActive = false
        )
        assertTrue(badges.isEmpty())
    }
}
