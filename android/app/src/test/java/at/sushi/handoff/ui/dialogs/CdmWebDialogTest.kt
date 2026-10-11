package at.sushi.handoff.ui.dialogs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CdmWebDialogTest {

    @Test
    fun allowsTheSiteVatsimLoginAndCloudflare() {
        assertTrue(isAllowedVdgsNavigation("https://vdgs.vatsimspain.es/vdgs/index.php"))
        assertTrue(isAllowedVdgsNavigation("https://auth.vatsim.net/login"))
        assertTrue(isAllowedVdgsNavigation("https://vatsim.net/"))
        assertTrue(isAllowedVdgsNavigation("https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/g/x"))
        assertTrue(isAllowedVdgsNavigation("HTTPS://AUTH.VATSIM.NET/login"))
    }

    @Test
    fun blocksOtherHostsAndLookalikes() {
        assertFalse(isAllowedVdgsNavigation("https://example.com/"))
        assertFalse(isAllowedVdgsNavigation("https://evilvatsim.net/"))
        assertFalse(isAllowedVdgsNavigation("https://vatsim.net.evil.com/"))
        assertFalse(isAllowedVdgsNavigation("https://vatsim.net@evil.com/"))
    }

    @Test
    fun blocksNonHttpsSchemesAndGarbage() {
        assertFalse(isAllowedVdgsNavigation("http://auth.vatsim.net/login"))
        assertFalse(isAllowedVdgsNavigation("content://com.android.contacts/contacts"))
        assertFalse(isAllowedVdgsNavigation("file:///sdcard/secret.html"))
        assertFalse(isAllowedVdgsNavigation("javascript:alert(1)"))
        assertFalse(isAllowedVdgsNavigation("not a url"))
        assertFalse(isAllowedVdgsNavigation(""))
    }
}
