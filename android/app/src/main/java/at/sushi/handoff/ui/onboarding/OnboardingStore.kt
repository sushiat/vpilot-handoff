package at.sushi.handoff.ui.onboarding

import android.content.SharedPreferences
import androidx.core.content.edit

/** Persisted "the first-launch tour has been shown" flag (issue #205). Separate from the one-time
 *  attended-sim notice's own acknowledgement flag: that one is a safety notice, this one is just a
 *  tour, and replaying it from the top bar never touches either. */
object OnboardingStore {
    private const val KeyTourSeen = "onboarding_tour_seen"

    fun isSeen(prefs: SharedPreferences): Boolean = prefs.getBoolean(KeyTourSeen, false)

    fun markSeen(prefs: SharedPreferences) {
        prefs.edit { putBoolean(KeyTourSeen, true) }
    }
}
