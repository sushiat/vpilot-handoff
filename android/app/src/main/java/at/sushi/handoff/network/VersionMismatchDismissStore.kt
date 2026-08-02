package at.sushi.handoff.network

import android.content.SharedPreferences
import androidx.core.content.edit

/** Persisted "don't nag me about this version skew again" (issue #87), set when the pilot dismisses
 *  the plugin/app version-mismatch notice (see VersionMismatchDialog). Keyed by the composite
 *  `"<pluginVersion>|<appVersion>"` pair rather than a single version so that a dismissed skew stays
 *  quiet, yet any change on either side -- the plugin auto-updating, or the app being updated --
 *  re-prompts. Same per-key reasoning as [AppUpdateDismissStore], just over the pair instead of one
 *  release. */
object VersionMismatchDismissStore {
    private const val KeyDismissedSkew = "version_mismatch_dismissed_skew"

    private fun skewKey(pluginVersion: String, appVersion: String) = "$pluginVersion|$appVersion"

    fun isDismissed(prefs: SharedPreferences, pluginVersion: String, appVersion: String): Boolean =
        prefs.getString(KeyDismissedSkew, null) == skewKey(pluginVersion, appVersion)

    fun saveDismissed(prefs: SharedPreferences, pluginVersion: String, appVersion: String) {
        prefs.edit { putString(KeyDismissedSkew, skewKey(pluginVersion, appVersion)) }
    }
}
