package at.sushi.handoff.network

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val ignoredDeviceJson = Json { ignoreUnknownKeys = true }

/** Persisted "don't ask me to pair with this again" list (issue #15), keyed by certificate
 *  fingerprint -- set when the pilot explicitly chooses "Ignore this machine" after cancelling a
 *  pairing prompt (see HandoffConnectionService.cancelPairing). Checked before ever showing a
 *  pairing prompt for a given fingerprint again. There's no per-device management UI yet, just a
 *  clear-everything row in SettingsDialog -- a reasonable way back without over-building this
 *  for a v1. */
object IgnoredDeviceStore {
    private const val KeyIgnoredFingerprints = "ignored_device_fingerprints"

    fun loadIgnored(prefs: SharedPreferences): Set<String> {
        val raw = prefs.getString(KeyIgnoredFingerprints, null) ?: return emptySet()
        return runCatching { ignoredDeviceJson.decodeFromString<Set<String>>(raw) }.getOrDefault(emptySet())
    }

    fun addIgnored(prefs: SharedPreferences, fingerprint: String) {
        val updated = loadIgnored(prefs) + fingerprint
        prefs.edit { putString(KeyIgnoredFingerprints, ignoredDeviceJson.encodeToString(updated)) }
    }

    fun clearAll(prefs: SharedPreferences) {
        prefs.edit { remove(KeyIgnoredFingerprints) }
    }
}
