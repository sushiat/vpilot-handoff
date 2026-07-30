package at.sushi.handoff.network

import android.content.SharedPreferences
import androidx.core.content.edit

/** SharedPreferences-backed store for the bearer token issued by a successful pairing (issue
 *  #15) -- same shape as CertTrustStore: a single value, since the token is meaningless once the
 *  pinned certificate fingerprint it was issued alongside changes (HandoffConnectionService
 *  always re-pairs on a fingerprint mismatch regardless of what's stored here). */
object PairingTokenStore {
    private const val KeyToken = "pairing_token"

    fun loadToken(prefs: SharedPreferences): String? = prefs.getString(KeyToken, null)

    fun saveToken(prefs: SharedPreferences, token: String) {
        prefs.edit { putString(KeyToken, token) }
    }

    fun clearToken(prefs: SharedPreferences) {
        prefs.edit { remove(KeyToken) }
    }
}
