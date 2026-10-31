package at.sushi.handoff.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimbriefCredentialsTest {

    @Test
    fun unchanged_isNotAChange() {
        assertFalse(SimbriefCredentials.changed("123456", "pilot", "123456", "pilot"))
    }

    @Test
    fun nullAndEmptyAndSpaces_areTheSame() {
        assertFalse(SimbriefCredentials.changed(null, null, "", ""))
        assertFalse(SimbriefCredentials.changed("", null, null, ""))
        assertFalse(SimbriefCredentials.changed("123456", "pilot", " 123456 ", "pilot "))
    }

    @Test
    fun changedUserId_isAChange() {
        assertTrue(SimbriefCredentials.changed("123456", "pilot", "654321", "pilot"))
    }

    @Test
    fun changedUsername_isAChange() {
        assertTrue(SimbriefCredentials.changed("", "pilot", "", "other"))
    }

    @Test
    fun enteringOrClearingCredentials_isAChange() {
        assertTrue(SimbriefCredentials.changed(null, null, "123456", ""))
        assertTrue(SimbriefCredentials.changed("123456", "", "", ""))
    }
}
