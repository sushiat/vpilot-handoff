package at.sushi.handoff.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun versionSkew_pluginOlderThanApp_isPluginBehind() {
        assertEquals(VersionSkew.PLUGIN_BEHIND, versionSkew(pluginVersion = "0.2.0", appVersion = "0.3.0"))
    }

    @Test
    fun versionSkew_appOlderThanPlugin_isAppBehind() {
        assertEquals(VersionSkew.APP_BEHIND, versionSkew(pluginVersion = "0.3.0", appVersion = "0.2.0"))
    }

    @Test
    fun versionSkew_sameVersion_isNull() {
        assertNull(versionSkew(pluginVersion = "0.2.0", appVersion = "0.2.0"))
    }

    @Test
    fun versionSkew_unknownPluginVersion_isNull() {
        assertNull(versionSkew(pluginVersion = null, appVersion = "0.2.0"))
    }

    @Test
    fun versionSkew_differingSegmentCounts_stillCompares() {
        assertEquals(VersionSkew.APP_BEHIND, versionSkew(pluginVersion = "0.2.1", appVersion = "0.2"))
        assertEquals(VersionSkew.PLUGIN_BEHIND, versionSkew(pluginVersion = "0.2", appVersion = "0.2.1"))
    }
}
