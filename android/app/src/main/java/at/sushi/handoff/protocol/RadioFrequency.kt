package at.sushi.handoff.protocol

import java.util.Locale

/** Mirrors plugin/Shared/RadioFrequency.cs -- vPilot's compressed-integer frequency format
 *  (123.725 MHz -> 23725) is used throughout docs/protocol.md except for setCom1/2Frequency,
 *  which take plain MHz since the client constructs those values itself. */
object RadioFrequency {
    fun toMegahertz(compressed: Int): Double = (compressed + 100_000) / 1000.0

    /** Always a "." decimal (issue #202): the device's own locale would give "121,600" in German and
     *  other comma locales, which isn't how frequencies are written anywhere in aviation. */
    fun format(compressed: Int): String = String.format(Locale.ROOT, "%.3f", toMegahertz(compressed))

    /** Inverse of [toMegahertz] -- demo mode's local command handling (issue #169) needs to turn
     *  the plain-MHz values setCom1/2Frequency etc. carry back into the compressed format
     *  radioState itself uses, with nothing else (a real plugin) to do that conversion for it. */
    fun fromMegahertz(megahertz: Double): Int = Math.round(megahertz * 1000).toInt() - 100_000
}
