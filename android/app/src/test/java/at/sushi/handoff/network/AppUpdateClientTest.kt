package at.sushi.handoff.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateClientTest {

    @Test
    fun isNewer_higherPatch_isNewer() {
        assertTrue(AppUpdateClient.isNewer("0.1.1", "0.1.0"))
    }

    @Test
    fun isNewer_higherMinor_isNewer() {
        assertTrue(AppUpdateClient.isNewer("0.2.0", "0.1.9"))
    }

    @Test
    fun isNewer_sameVersion_isNotNewer() {
        assertFalse(AppUpdateClient.isNewer("0.1.0", "0.1.0"))
    }

    @Test
    fun isNewer_lowerVersion_isNotNewer() {
        assertFalse(AppUpdateClient.isNewer("0.1.0", "0.2.0"))
    }

    @Test
    fun isNewer_differentSegmentCounts_comparesMissingAsZero() {
        assertTrue(AppUpdateClient.isNewer("0.1.0.1", "0.1.0"))
        assertFalse(AppUpdateClient.isNewer("0.1", "0.1.0"))
    }
}
