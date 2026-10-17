package at.sushi.handoff.protocol

/** Mirrors plugin/Shared/RadioFrequency.cs -- vPilot's compressed-integer frequency format
 *  (123.725 MHz -> 23725) is used throughout docs/protocol.md except for setCom1/2Frequency,
 *  which take plain MHz since the client constructs those values itself. */
object RadioFrequency {
    fun toMegahertz(compressed: Int): Double = (compressed + 100_000) / 1000.0

    fun format(compressed: Int): String = "%.3f".format(toMegahertz(compressed))

    /** Inverse of [toMegahertz] -- demo mode's local command handling (issue #169) needs to turn
     *  the plain-MHz values setCom1/2Frequency etc. carry back into the compressed format
     *  radioState itself uses, with nothing else (a real plugin) to do that conversion for it. */
    fun fromMegahertz(megahertz: Double): Int = Math.round(megahertz * 1000).toInt() - 100_000
}
