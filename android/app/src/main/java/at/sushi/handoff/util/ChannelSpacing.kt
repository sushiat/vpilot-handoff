package at.sushi.handoff.util

import at.sushi.handoff.ChannelSpacing
import kotlin.math.abs

/** COM frequency band/grid math for the tuning dialog's keypad -- see issue #13's "Channel
 *  spacing validity" section, verified there against Eurocontrol's 8.33VCS Annex C table.
 *
 *  Frequencies are handled here as plain integer "thousandths of a MHz" (123.725 -> 123725),
 *  matching the 3+3 digit entry field (3 whole-MHz digits, 3 decimal digits) -- not vPilot's
 *  compressed-integer protocol format, which is a different encoding used only on the wire.
 */
object ChannelGrid {
    const val BAND_MIN = 118_000
    const val BAND_MAX = 136_990

    private val khz25DecimalValues: List<Int> = (0 until 1000 step 25).toList()

    private val khz833DecimalValues: List<Int> = buildList {
        for (h in 0 until 1000 step 100) {
            for (base in 0 until 100 step 25) {
                for (off in 0 until 20 step 5) {
                    add(h + base + off)
                }
            }
        }
    }

    fun validDecimalValues(spacing: ChannelSpacing): List<Int> =
        if (spacing == ChannelSpacing.KHZ_25) khz25DecimalValues else khz833DecimalValues

    fun isInBand(thousandths: Int): Boolean = thousandths in BAND_MIN..BAND_MAX

    /** Snaps [thousandths] to the nearest [spacing]-valid decimal (the whole-MHz part is left
     *  alone -- only the decimal digits are grid-constrained), then clamps into the civil band.
     *  Used when the user commits (Set active/Set standby). */
    fun nearestValid(thousandths: Int, spacing: ChannelSpacing): Int {
        val wholeMhz = thousandths / 1000
        val decimal = thousandths % 1000
        val nearestDecimal = validDecimalValues(spacing).minByOrNull { abs(it - decimal) } ?: decimal
        return (wholeMhz * 1000 + nearestDecimal).coerceIn(BAND_MIN, BAND_MAX)
    }

    /** Whether some completion of [prefix] (0-6 typed digits, 3 whole + 3 decimal) could still
     *  produce an in-band, grid-valid frequency -- drives live keypad digit disabling. */
    fun isValidPrefix(prefix: String, spacing: ChannelSpacing): Boolean {
        require(prefix.length <= 6) { "prefix must be at most 6 digits" }
        val wholePrefix = prefix.take(3)
        val decimalPrefix = if (prefix.length > 3) prefix.substring(3) else ""
        val decimalCandidates = validDecimalValues(spacing)
            .map { it.toString().padStart(3, '0') }
            .filter { it.startsWith(decimalPrefix) }
        if (decimalCandidates.isEmpty()) return false

        for (wholeCandidate in 0..999) {
            val wholeStr = wholeCandidate.toString().padStart(3, '0')
            if (!wholeStr.startsWith(wholePrefix)) continue
            for (decStr in decimalCandidates) {
                if (isInBand(wholeCandidate * 1000 + decStr.toInt())) return true
            }
        }
        return false
    }

    /** The smallest in-band, grid-valid completion consistent with [prefix], for the entry
     *  readout's placeholder digits -- null if [prefix] has no valid completion at all. */
    fun completePrefix(prefix: String, spacing: ChannelSpacing): Int? {
        val wholePrefix = prefix.take(3)
        val decimalPrefix = if (prefix.length > 3) prefix.substring(3) else ""
        val decimalCandidates = validDecimalValues(spacing)
            .map { it.toString().padStart(3, '0') }
            .filter { it.startsWith(decimalPrefix) }
            .sorted()

        var best: Int? = null
        for (wholeCandidate in 0..999) {
            val wholeStr = wholeCandidate.toString().padStart(3, '0')
            if (!wholeStr.startsWith(wholePrefix)) continue
            for (decStr in decimalCandidates) {
                val candidate = wholeCandidate * 1000 + decStr.toInt()
                if (isInBand(candidate) && (best == null || candidate < best!!)) {
                    best = candidate
                }
            }
        }
        return best
    }

    fun toMegahertz(thousandths: Int): Double = thousandths / 1000.0
}
