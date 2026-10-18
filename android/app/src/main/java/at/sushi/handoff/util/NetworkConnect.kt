package at.sushi.handoff.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
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

    /** SELCAL as just its letters, however it was typed or stored ("AB-CD", "ab cd", "ABCD"). */
    fun normalizeSelcal(text: String): String = text.uppercase().filter { it in 'A'..'Z' }

    /** What the SELCAL field holds: uppercase letters and digits, no dash or spaces, at most four.
     *  Digits are kept (not dropped) so a plan's "IAZ9" stays on screen to be corrected in place;
     *  [selcalWarning] then says vPilot currently doesn't support them. */
    fun cleanSelcalInput(text: String): String =
        text.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(4)

    /** The plan's SELCAL for prefilling the field, passed through as is (even when it isn't valid,
     *  e.g. SimBrief takes "IAZ9") so the pilot can fix it rather than retype it. */
    fun prefillSelcal(planSelcal: String?): String = cleanSelcalInput(planSelcal.orEmpty())

    /** The form vPilot accepts and the field shows: capped at four characters, with the dash inserted
     *  after the second as soon as there is a third ("AB", "AB-C", "AB-CD"). SimBrief's "ABCD"
     *  becomes "AB-CD" here too. */
    fun formatSelcal(text: String): String {
        val chars = cleanSelcalInput(text)
        return if (chars.length > 2) chars.take(2) + "-" + chars.drop(2) else chars
    }

    /** Why Connect must be disabled, or null when the form can be sent. Ordered by what the pilot
     *  most likely needs to fix first. The simulator link is deliberately not checked here:
     *  subsystemStatus.simulatorConnected is Handoff's own RadioHost link, which only comes up once
     *  on the network, not vPilot's -- the plugin relays vPilot's SimNotReadyException instead. */
    fun connectBlockedReason(
        callsign: String,
        aircraftType: String,
        observer: Boolean
    ): String? {
        val cs = normalize(callsign)
        val type = normalize(aircraftType)
        return when {
            cs.isEmpty() -> "Enter a callsign."
            !CallsignPattern.matches(cs) -> "Callsign can only use letters, digits, - and _ (max 10)."
            observer -> null
            type.isEmpty() -> "Enter the aircraft type."
            !TypePattern.matches(type) -> "Aircraft type is the 2-4 character ICAO code, e.g. A320."
            else -> null
        }
    }

    // Classic SELCAL-16 alphabet: A-S without I, N and O.
    private const val SelcalLetters = "ABCDEFGHJKLMPQRS"

    /** A heads-up, never a block (vPilot accepts or refuses the code itself, with its own message):
     *  the code isn't four characters, it has digits (SELCAL32 -- vPilot currently refuses them,\n     *  worded as "currently" so it stays true if that changes), or a complete letter code breaks the classic SELCAL rules:
     *  letters from [SelcalLetters], none repeated, each pair ascending ("AB-CD"). vPilot was checked
     *  and takes "AB-XZ", "AB-PI" and "IA-ZZ", so these are advice about codes the aircraft/ATC may
     *  not be able to use, not something vPilot refuses. Null while empty or fine. */
    fun selcalWarning(selcal: String): String? {
        val chars = cleanSelcalInput(selcal)
        return when {
            chars.isEmpty() -> null
            chars.length != 4 -> "SELCAL is normally 4 characters, e.g. AB-CD."
            chars.any { it in '0'..'9' } -> "vPilot currently doesn't support SELCAL with digits (SELCAL32)."
            chars.any { it !in SelcalLetters } -> "Unusual SELCAL: standard codes use the letters A-S without I, N and O."
            chars.toSet().size != 4 -> "Unusual SELCAL: standard codes don't repeat a letter."
            chars[0] > chars[1] || chars[2] > chars[3] -> "Unusual SELCAL: standard codes have each pair in alphabetical order, e.g. AB-CD."
            else -> null
        }
    }}

/** Shows a SELCAL field's four letters as "AB-CD" without storing the dash, so the field's text stays
 *  plain letters and the cursor keeps up with typing (see [NetworkConnect.formatSelcal] for the sent form). */
object SelcalVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val shown = NetworkConnect.formatSelcal(text.text)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset > 2) offset + 1 else offset
            override fun transformedToOriginal(offset: Int) = if (offset > 2) offset - 1 else offset
        }
        return TransformedText(AnnotatedString(shown), mapping)
    }
}
