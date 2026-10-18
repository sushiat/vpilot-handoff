package at.sushi.handoff.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkConnectTest {

    @Test
    fun isSupported_atOrAboveMinimum() {
        assertTrue(NetworkConnect.isSupported("0.8.0"))
        assertTrue(NetworkConnect.isSupported("0.8.1"))
        assertTrue(NetworkConnect.isSupported("1.0.0"))
    }

    @Test
    fun isSupported_belowMinimumOrUnknown_isHidden() {
        assertFalse(NetworkConnect.isSupported("0.7.0"))
        assertFalse(NetworkConnect.isSupported("0.7.9"))
        assertFalse(NetworkConnect.isSupported(null))
    }

    @Test
    fun connectBlockedReason_validForm_isNull() {
        assertNull(NetworkConnect.connectBlockedReason("eidgx", "a320", observer = false))
    }

    @Test
    fun connectBlockedReason_emptyCallsign() {
        assertEquals("Enter a callsign.", NetworkConnect.connectBlockedReason("  ", "A320", observer = false))
    }

    @Test
    fun connectBlockedReason_badCallsignCharacters() {
        assertEquals(
            "Callsign can only use letters, digits, - and _ (max 10).",
            NetworkConnect.connectBlockedReason("EI DGX", "A320", observer = false)
        )
        assertEquals(
            "Callsign can only use letters, digits, - and _ (max 10).",
            NetworkConnect.connectBlockedReason("ABCDEFGHIJK", "A320", observer = false)
        )
    }

    @Test
    fun connectBlockedReason_missingOrBadType() {
        assertEquals("Enter the aircraft type.", NetworkConnect.connectBlockedReason("EIDGX", "", observer = false))
        assertEquals(
            "Aircraft type is the 2-4 character ICAO code, e.g. A320.",
            NetworkConnect.connectBlockedReason("EIDGX", "AIRBUS", observer = false)
        )
    }

    @Test
    fun connectBlockedReason_observer_needsNoType() {
        assertNull(NetworkConnect.connectBlockedReason("EIDGX_OBS", "", observer = true))
    }

    @Test
    fun normalizeSelcal_dropsDashesSpacesAndUppercases() {
        assertEquals("ABCD", NetworkConnect.normalizeSelcal("ab-cd"))
        assertEquals("ABCD", NetworkConnect.normalizeSelcal(" a b c d "))
    }

    @Test
    fun formatSelcal_insertsDashAfterSecondLetter() {
        assertEquals("", NetworkConnect.formatSelcal(""))
        assertEquals("AB", NetworkConnect.formatSelcal("ab"))
        assertEquals("AB-C", NetworkConnect.formatSelcal("abc"))
        assertEquals("AB-CD", NetworkConnect.formatSelcal("ABCD"))
        assertEquals("AB-CD", NetworkConnect.formatSelcal("AB-CD"))
        assertEquals("AB-CD", NetworkConnect.formatSelcal("ABCDE"))
        assertEquals("AB", NetworkConnect.formatSelcal("AB-"))
    }

    @Test
    fun selcalVisualTransformation_showsDashButKeepsCursorAfterTheLastLetter() {
        // The field stores plain letters; typing "ABCD" must leave the cursor at the end each step,
        // which is what the dash-rewriting version got wrong (it produced "AB-DC").
        val expectedShown = mapOf("A" to "A", "AB" to "AB", "ABC" to "AB-C", "ABCD" to "AB-CD")
        for ((typed, shown) in expectedShown) {
            val result = SelcalVisualTransformation.filter(androidx.compose.ui.text.AnnotatedString(typed))
            assertEquals(shown, result.text.text)
            assertEquals(shown.length, result.offsetMapping.originalToTransformed(typed.length))
            assertEquals(typed.length, result.offsetMapping.transformedToOriginal(shown.length))
        }
    }

    @Test
    fun prefillSelcal_passesThePlanCodeThroughEvenWhenInvalid() {
        assertEquals("ABCD", NetworkConnect.prefillSelcal("ABCD"))
        assertEquals("ABCD", NetworkConnect.prefillSelcal("ab-cd"))
        assertEquals("IAZ9", NetworkConnect.prefillSelcal("IAZ9")) // kept so it can be corrected in place
        assertEquals("ABC", NetworkConnect.prefillSelcal("ABC"))
        assertEquals("", NetworkConnect.prefillSelcal(null))
    }

    @Test
    fun selcalWarning_flagsNonStandardCodesButNeverBlocks() {
        assertNull(NetworkConnect.selcalWarning(""))
        assertEquals("SELCAL is normally 4 characters, e.g. AB-CD.", NetworkConnect.selcalWarning("AB-C"))
        assertEquals("vPilot currently doesn't support SELCAL with digits (SELCAL32).", NetworkConnect.selcalWarning("IAZ9"))
        assertNull(NetworkConnect.selcalWarning("AB-CD"))
        assertNull(NetworkConnect.selcalWarning("GH-JK")) // J is allowed, I is not
        assertEquals("Unusual SELCAL: standard codes use the letters A-S without I, N and O.", NetworkConnect.selcalWarning("AB-XZ"))
        assertEquals("Unusual SELCAL: standard codes use the letters A-S without I, N and O.", NetworkConnect.selcalWarning("AB-CI"))
        assertEquals("Unusual SELCAL: standard codes don't repeat a letter.", NetworkConnect.selcalWarning("AB-AC"))
        assertEquals("Unusual SELCAL: standard codes have each pair in alphabetical order, e.g. AB-CD.", NetworkConnect.selcalWarning("BA-CD"))
        assertEquals("Unusual SELCAL: standard codes have each pair in alphabetical order, e.g. AB-CD.", NetworkConnect.selcalWarning("AB-DC"))
    }

    @Test
    fun selcalProblems_neverBlockConnect() {
        // vPilot currently refuses digits ("IA-Z9") and accepts any letters ("IA-ZZ"), but the form only
        // warns -- vPilot has the final say, and its rules may change.
        for (code in listOf("", "AB", "ABCD", "IAZ9", "IAZZ", "AB-XZ", "AB-PI", "BA-CD", "AA-BB")) {
            assertNull(NetworkConnect.connectBlockedReason("EIDGX", "A320", observer = false), code)
        }
    }
}
