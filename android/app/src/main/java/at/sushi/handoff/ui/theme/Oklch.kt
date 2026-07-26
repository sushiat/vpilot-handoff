package at.sushi.handoff.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Converts an OKLCH color (as used throughout the design doc's token table) to a Compose
 *  [Color]. Compose has no OKLCH constructor, so this implements Björn Ottosson's OKLab/OKLCH
 *  <-> linear sRGB formulas directly -- no extra dependency needed for ~15 colors.
 *
 *  @param l lightness, 0..1 (the design doc writes this as a percentage, e.g. "58%" -> 0.58f)
 *  @param c chroma, roughly 0..0.4 in practice
 *  @param h hue angle in degrees
 */
fun oklch(l: Float, c: Float, h: Float, alpha: Float = 1f): Color {
    val hRad = Math.toRadians(h.toDouble())
    val a = c * cos(hRad).toFloat()
    val b = c * sin(hRad).toFloat()

    val l_ = l + 0.3963377774f * a + 0.2158037573f * b
    val m_ = l - 0.1055613458f * a - 0.0638541728f * b
    val s_ = l - 0.0894841775f * a - 1.2914855480f * b

    val lCubed = l_ * l_ * l_
    val mCubed = m_ * m_ * m_
    val sCubed = s_ * s_ * s_

    val rLinear = +4.0767416621f * lCubed - 3.3077115913f * mCubed + 0.2309699292f * sCubed
    val gLinear = -1.2684380046f * lCubed + 2.6097574011f * mCubed - 0.3413193965f * sCubed
    val bLinear = -0.0041960863f * lCubed - 0.7034186147f * mCubed + 1.7076147010f * sCubed

    return Color(
        red = linearToSrgb(rLinear),
        green = linearToSrgb(gLinear),
        blue = linearToSrgb(bLinear),
        alpha = alpha
    )
}

private fun linearToSrgb(value: Float): Float {
    val clamped = value.coerceIn(0f, 1f)
    val encoded = if (clamped <= 0.0031308f) {
        clamped * 12.92f
    } else {
        1.055f * clamped.pow(1f / 2.4f) - 0.055f
    }
    return encoded.coerceIn(0f, 1f)
}

/** The perceptual lightness (0..100) Compose would report for [color] if it were expressed in
 *  OKLCH -- used for the "auto-pick black or white text based on background L, threshold ~62"
 *  rule. Approximated via relative luminance rather than a full sRGB->OKLab round trip, which is
 *  adequate for a threshold decision (the design doc's own reference implementation likely does
 *  the equivalent in reverse, from an OKLCH value it already had in hand).
 */
fun perceptualLightness(color: Color): Float {
    fun channel(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    val r = channel(color.red)
    val g = channel(color.green)
    val b = channel(color.blue)
    val relativeLuminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
    // CIE lightness formula (L*), which maps luminance to a ~0..100 perceptual scale close
    // enough to OKLCH's L for a "flip text color" threshold.
    return if (relativeLuminance <= 0.008856f) {
        relativeLuminance * 903.3f
    } else {
        relativeLuminance.pow(1f / 3f) * 116f - 16f
    }
}
