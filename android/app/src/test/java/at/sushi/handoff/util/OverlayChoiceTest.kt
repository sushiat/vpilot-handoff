package at.sushi.handoff.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayChoiceTest {
    @Test
    fun floatingChatNeedsFeaturePermissionAndNoDecline() {
        assertTrue(useFloatingChat(featureAvailable = true, hasPermission = true, declined = false))
        assertFalse(useFloatingChat(featureAvailable = false, hasPermission = true, declined = false))
        assertFalse(useFloatingChat(featureAvailable = true, hasPermission = false, declined = false))
        assertFalse(useFloatingChat(featureAvailable = true, hasPermission = true, declined = true))
    }

    @Test
    fun explainerShowsOnlyWhenUndecidedAndUnpermitted() {
        assertTrue(shouldPromptOverlayExplainer(true, hasPermission = false, declined = false, snoozedThisSession = false))
        assertFalse(shouldPromptOverlayExplainer(false, false, false, false))
        assertFalse(shouldPromptOverlayExplainer(true, hasPermission = true, declined = false, snoozedThisSession = false))
        assertFalse(shouldPromptOverlayExplainer(true, false, declined = true, snoozedThisSession = false))
        assertFalse(shouldPromptOverlayExplainer(true, false, declined = false, snoozedThisSession = true))
    }
}
