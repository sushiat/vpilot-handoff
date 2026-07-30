package at.sushi.handoff.network

import android.content.SharedPreferences
import androidx.core.content.edit

/** SharedPreferences-backed store for the silently-pinned plugin certificate fingerprint (issue
 *  #15) -- same shape as RowColorThemeStore: plain functions taking SharedPreferences as a
 *  parameter, under the existing HandoffConnectionService.PrefsName.
 *
 *  This is never shown to the pilot or asked about directly -- a raw hash means nothing to most
 *  people installing this app, so the actual human-verifiable trust gate is the pairing code
 *  (PairingTokenStore, HandoffPairingSession-equivalent client flow), read off the plugin's own
 *  screen. This store's fingerprint still matters for security, just silently: a mismatch here
 *  forces a full re-pairing even if a stored PairingTokenStore token would otherwise look valid,
 *  since the certificate identity a token was issued against has changed. */
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
