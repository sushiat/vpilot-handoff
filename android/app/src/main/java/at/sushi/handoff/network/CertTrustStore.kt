package at.sushi.handoff.network

import android.content.SharedPreferences
import androidx.core.content.edit

/** SharedPreferences-backed store for the pinned plugin certificate fingerprint (issue #15) --
 *  same shape as RowColorThemeStore: plain functions taking SharedPreferences as a parameter,
 *  under the existing HandoffConnectionService.PrefsName. Only one plugin instance is ever paired
 *  with at a time, so a single flat key is enough -- no need to key by host/port. */
object CertTrustStore {
    private const val KeyPinnedFingerprint = "pinned_cert_fingerprint"

    fun loadPinnedFingerprint(prefs: SharedPreferences): String? =
        prefs.getString(KeyPinnedFingerprint, null)

    fun savePinnedFingerprint(prefs: SharedPreferences, fingerprint: String) {
        prefs.edit { putString(KeyPinnedFingerprint, fingerprint) }
    }

    fun clearPinnedFingerprint(prefs: SharedPreferences) {
        prefs.edit { remove(KeyPinnedFingerprint) }
    }
}
