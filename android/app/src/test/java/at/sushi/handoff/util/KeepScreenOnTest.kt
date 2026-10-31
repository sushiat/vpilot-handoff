package at.sushi.handoff.util

import at.sushi.handoff.KeepScreenOnMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeepScreenOnTest {

    @Test
    fun initialValue_offAndOnIgnoreCharger() {
        assertFalse(KeepScreenOn.initialValue(KeepScreenOnMode.OFF, charging = true))
        assertFalse(KeepScreenOn.initialValue(KeepScreenOnMode.OFF, charging = false))
        assertTrue(KeepScreenOn.initialValue(KeepScreenOnMode.ON, charging = true))
        assertTrue(KeepScreenOn.initialValue(KeepScreenOnMode.ON, charging = false))
    }

    @Test
    fun initialValue_chargingFollowsCharger() {
        assertTrue(KeepScreenOn.initialValue(KeepScreenOnMode.CHARGING, charging = true))
        assertFalse(KeepScreenOn.initialValue(KeepScreenOnMode.CHARGING, charging = false))
    }

    @Test
    fun onPowerConnected_chargingForcesOn() {
        assertTrue(KeepScreenOn.onPowerConnected(KeepScreenOnMode.CHARGING, current = false))
        assertTrue(KeepScreenOn.onPowerConnected(KeepScreenOnMode.CHARGING, current = true))
    }

    @Test
    fun onPowerConnected_offAndOnLeaveCurrentValue() {
        assertFalse(KeepScreenOn.onPowerConnected(KeepScreenOnMode.OFF, current = false))
        assertTrue(KeepScreenOn.onPowerConnected(KeepScreenOnMode.OFF, current = true))
        assertFalse(KeepScreenOn.onPowerConnected(KeepScreenOnMode.ON, current = false))
        assertTrue(KeepScreenOn.onPowerConnected(KeepScreenOnMode.ON, current = true))
    }
}
