package at.sushi.handoff.ui.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TourPageTest {

    @Test
    fun everyPage_hasTitleAndBody() {
        TourPage.entries.forEach {
            assertTrue(it.title.isNotBlank(), "${it.name} title")
            // A page may rely on its numbered legend instead of a body (the design's radios page).
            assertTrue(it.body.isNotBlank() || it.legend.isNotEmpty(), "${it.name} body or legend")
        }
    }

    @Test
    fun everyPage_hasBalancedBoldMarkers() {
        TourPage.entries.forEach {
            assertEquals(0, it.body.split("**").size % 2 - 1, "${it.name} has unbalanced ** markers")
        }
    }

    @Test
    fun radiosPage_legendMatchesItsFourCallouts() {
        assertEquals(4, TourPage.RADIOS.legend.size)
    }

    @Test
    fun parseBold_alternatesPlainAndBold() {
        assertEquals(
            listOf("Tap " to false, "Contact Me" to true, " now" to false),
            parseBold("Tap **Contact Me** now")
        )
    }

    @Test
    fun parseBold_noMarkers_isOnePlainRun() {
        assertEquals(listOf("plain" to false), parseBold("plain"))
    }

    @Test
    fun parseBold_leadingBold() {
        assertEquals(listOf("A" to true, " b" to false), parseBold("**A** b"))
    }

    @Test
    fun parseBold_unmatchedMarker_keptLiterally() {
        assertEquals(listOf("a " to false, "**b" to false), parseBold("a **b"))
    }
}
