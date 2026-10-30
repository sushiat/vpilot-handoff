package at.sushi.handoff.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceIdleStopTest {

    @Test
    fun appVisible_neverStopsOrWarns_inAnyConnectionState() {
        for (pluginConnected in listOf(false, true)) {
            for (onVatsim in listOf(false, true)) {
                val policy = ServiceIdleStop.policy(appVisible = true, pluginConnected = pluginConnected, onVatsim = onVatsim)
                assertNull(policy.stopAfterMillis)
                assertNull(policy.warnAfterMillis)
            }
        }
    }

    @Test
    fun backgrounded_pluginDown_stopsAfterFiveMinutes() {
        val policy = ServiceIdleStop.policy(appVisible = false, pluginConnected = false, onVatsim = false)
        assertEquals(5 * 60_000L, policy.stopAfterMillis)
        assertNull(policy.warnAfterMillis)
    }

    @Test
    fun backgrounded_pluginDown_butVatsimCallsignStillSet_isStillTheFiveMinuteTier() {
        // Demo mode's shape: DemoData fills in a vatsimCallsign while the plugin connection is
        // kept DISCONNECTED -- the plugin check has to win.
        val policy = ServiceIdleStop.policy(appVisible = false, pluginConnected = false, onVatsim = true)
        assertEquals(ServiceIdleStop.NoPluginStopMillis, policy.stopAfterMillis)
        assertNull(policy.warnAfterMillis)
    }

    @Test
    fun backgrounded_pluginUp_notOnVatsim_stopsAfterFifteenMinutes() {
        val policy = ServiceIdleStop.policy(appVisible = false, pluginConnected = true, onVatsim = false)
        assertEquals(15 * 60_000L, policy.stopAfterMillis)
        assertNull(policy.warnAfterMillis)
    }

    @Test
    fun backgrounded_connectedToBoth_neverStops_butWarnsBeforeAndroidsSixHourLimit() {
        val policy = ServiceIdleStop.policy(appVisible = false, pluginConnected = true, onVatsim = true)
        assertNull(policy.stopAfterMillis)
        assertEquals(5 * 3_600_000L + 30 * 60_000L, policy.warnAfterMillis)
        assertTrue(policy.warnAfterMillis!! < 6 * 3_600_000L)
    }
}
