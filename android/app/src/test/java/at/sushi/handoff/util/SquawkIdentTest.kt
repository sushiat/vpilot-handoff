package at.sushi.handoff.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SquawkIdentTest {

    @Test
    fun isSupported_atOrAboveMinimum() {
        assertTrue(SquawkIdent.isSupported("0.10.0"))
        assertTrue(SquawkIdent.isSupported("0.10.1"))
        assertTrue(SquawkIdent.isSupported("1.0.0"))
    }

    @Test
    fun isSupported_belowMinimumOrUnknown_isHidden() {
        assertFalse(SquawkIdent.isSupported("0.9.0"))
        assertFalse(SquawkIdent.isSupported("0.9.9"))
        assertFalse(SquawkIdent.isSupported(null))
    }

    @Test
    fun isOffered_hidesOnlyForAConnectedPluginThatIsTooOld() {
        assertFalse(SquawkIdent.isOffered(demoMode = false, pluginConnected = true, pluginVersion = "0.9.0"))
        assertFalse(SquawkIdent.isOffered(demoMode = false, pluginConnected = true, pluginVersion = null))
        assertTrue(SquawkIdent.isOffered(demoMode = false, pluginConnected = true, pluginVersion = "0.10.0"))
    }

    @Test
    fun isOffered_showsWhenNothingIsConnected() {
        assertTrue(SquawkIdent.isOffered(demoMode = false, pluginConnected = false, pluginVersion = null))
        assertTrue(SquawkIdent.isOffered(demoMode = false, pluginConnected = false, pluginVersion = "0.9.0"))
    }

    @Test
    fun isOffered_alwaysShowsInDemoMode() {
        assertTrue(SquawkIdent.isOffered(demoMode = true, pluginConnected = false, pluginVersion = null))
        assertTrue(SquawkIdent.isOffered(demoMode = true, pluginConnected = true, pluginVersion = "0.9.0"))
    }
}
