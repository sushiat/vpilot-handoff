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
        assertNull(NetworkConnect.connectBlockedReason("eidgx", "a320", observer = false, simulatorConnected = true))
    }

    @Test
    fun connectBlockedReason_simulatorNotConnected_winsOverEverythingElse() {
        assertEquals(
            "vPilot isn't connected to the simulator yet.",
            NetworkConnect.connectBlockedReason("", "", observer = false, simulatorConnected = false)
        )
    }

    @Test
    fun connectBlockedReason_emptyCallsign() {
        assertEquals("Enter a callsign.", NetworkConnect.connectBlockedReason("  ", "A320", observer = false, simulatorConnected = true))
    }

    @Test
    fun connectBlockedReason_badCallsignCharacters() {
        assertEquals(
            "Callsign can only use letters, digits, - and _ (max 10).",
            NetworkConnect.connectBlockedReason("EI DGX", "A320", observer = false, simulatorConnected = true)
        )
        assertEquals(
            "Callsign can only use letters, digits, - and _ (max 10).",
            NetworkConnect.connectBlockedReason("ABCDEFGHIJK", "A320", observer = false, simulatorConnected = true)
        )
    }

    @Test
    fun connectBlockedReason_missingOrBadType() {
        assertEquals("Enter the aircraft type.", NetworkConnect.connectBlockedReason("EIDGX", "", observer = false, simulatorConnected = true))
        assertEquals(
            "Aircraft type is the 2-4 character ICAO code, e.g. A320.",
            NetworkConnect.connectBlockedReason("EIDGX", "AIRBUS", observer = false, simulatorConnected = true)
        )
    }

    @Test
    fun connectBlockedReason_observer_needsNoType() {
        assertNull(NetworkConnect.connectBlockedReason("EIDGX_OBS", "", observer = true, simulatorConnected = true))
    }
}
