package at.sushi.handoff.util

import at.sushi.handoff.network.AppUpdateClient

/** Issue #171 -- pure rules behind the connect-to-VATSIM dialog, kept free of Compose so they can be
 *  unit tested. */
object NetworkConnect {
    /** First plugin release that understands connectNetwork/disconnectNetwork (docs/protocol.md). */
    const val MinPluginVersion = "0.8.0"

    // Deliberately permissive: vPilot and the network enforce the real callsign/type limits and
    // report a rejection back, so these only catch obvious typos before sending. Not yet verified
    // against VATSIM's exact rules (issue #171 asks for that).
    private val CallsignPattern = Regex("^[A-Z0-9_-]{1,10}$")
    private val TypePattern = Regex("^[A-Z0-9]{2,4}$")

    /** Whether the connected plugin is new enough to offer the control. An unknown version (null)
     *  means an old plugin that never reported one, so it is hidden too. */
    fun isSupported(pluginVersion: String?): Boolean =
        pluginVersion != null && !AppUpdateClient.isNewer(MinPluginVersion, pluginVersion)

    fun normalize(text: String): String = text.trim().uppercase()

    /** Why Connect must be disabled, or null when the form can be sent. Ordered by what the pilot
     *  most likely needs to fix first. */
    fun connectBlockedReason(
        callsign: String,
        aircraftType: String,
        observer: Boolean,
        simulatorConnected: Boolean
    ): String? {
        val cs = normalize(callsign)
        val type = normalize(aircraftType)
        return when {
            !simulatorConnected -> "vPilot isn't connected to the simulator yet."
            cs.isEmpty() -> "Enter a callsign."
            !CallsignPattern.matches(cs) -> "Callsign can only use letters, digits, - and _ (max 10)."
            observer -> null
            type.isEmpty() -> "Enter the aircraft type."
            !TypePattern.matches(type) -> "Aircraft type is the 2-4 character ICAO code, e.g. A320."
            else -> null
        }
    }
}
