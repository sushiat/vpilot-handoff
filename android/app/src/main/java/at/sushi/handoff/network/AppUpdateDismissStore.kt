package at.sushi.handoff.network

import android.content.SharedPreferences
import androidx.core.content.edit

/** Persisted "don't nag me about this version again" (issue #34), set when the pilot dismisses
 *  the update-available notice (see AppUpdateDialog). Keyed by version string rather than a plain
 *  boolean so a further release still prompts even if an earlier one was dismissed -- same
 *  reasoning as IgnoredDeviceStore's per-fingerprint keying. */
object AppUpdateDismissStore {
    private const val KeyDismissedVersion = "app_update_dismissed_version"

    fun loadDismissedVersion(prefs: SharedPreferences): String? =
        prefs.getString(KeyDismissedVersion, null)

    fun saveDismissedVersion(prefs: SharedPreferences, version: String) {
        prefs.edit { putString(KeyDismissedVersion, version) }
    }
}
