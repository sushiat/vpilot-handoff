package at.sushi.handoff.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import at.sushi.handoff.ThemeMode

/** One color per design token from the issue's "Design Tokens" table. Facility/status hues are
 *  kept separate in [FacilityColors] since they're shared across light/dark (only the
 *  "unrelated station" desaturated background differs per theme, per the doc). */
data class HandoffColors(
    val bg: Color,
    val panel: Color,
    val panelAlt: Color,
    val text: Color,
    val textMuted: Color,
    val border: Color,
    val accent: Color,
    val accentBg: Color,
    val attention: Color,
    val attentionBg: Color,
    val ok: Color,
    val isDark: Boolean
)

val LightHandoffColors = HandoffColors(
    bg = oklch(0.97f, 0.006f, 250f),
    panel = Color.White,
    panelAlt = oklch(0.98f, 0.004f, 250f),
    text = oklch(0.22f, 0.01f, 250f),
    textMuted = oklch(0.50f, 0.01f, 250f),
    border = oklch(0.88f, 0.006f, 250f),
    accent = oklch(0.55f, 0.17f, 250f),
    accentBg = oklch(0.93f, 0.035f, 250f),
    attention = oklch(0.58f, 0.17f, 45f),
    attentionBg = oklch(0.93f, 0.05f, 45f),
    ok = oklch(0.58f, 0.13f, 150f),
    isDark = false
)

val DarkHandoffColors = HandoffColors(
    bg = oklch(0.15f, 0.01f, 250f),
    panel = oklch(0.20f, 0.012f, 250f),
    panelAlt = oklch(0.24f, 0.014f, 250f),
    text = oklch(0.93f, 0.006f, 250f),
    textMuted = oklch(0.62f, 0.01f, 250f),
    border = oklch(0.32f, 0.012f, 250f),
    accent = oklch(0.72f, 0.15f, 250f),
    accentBg = oklch(0.30f, 0.05f, 250f),
    attention = oklch(0.72f, 0.15f, 45f),
    attentionBg = oklch(0.34f, 0.06f, 45f),
    ok = oklch(0.70f, 0.13f, 150f),
    isDark = true
)

/** A facility color pair: filled background + its border (border is `transparent` for the
 *  desaturated/"faded" variant, per the design reference's own `fadedColor()`). */
data class FacilityColor(val bg: Color, val border: Color, val lightnessPercent: Float)

/** Facility hue/lightness/chroma constants and the two color-building functions
 *  (`fullColor`/`fadedColor`), transcribed directly from issue #13's JS reference (not
 *  re-derived from the prose spec, which only gives hues) -- default full-saturation is
 *  L58%/C0.16, with TWR (L48/C0.22) and ATIS-when-flagged (L85/C0.19) as the only overrides. */
object FacilityColors {
    const val DEL_HUE = 255f
    const val GND_HUE = 140f
    const val TWR_HUE = 25f
    const val APP_DEP_HUE = 60f
    const val CTR_HUE = 300f
    const val ATIS_HUE = 95f
    const val TUNED_HUE = 195f

    val hazardYellow = oklch(0.88f, 0.19f, 98f)

    /** The saturated background/border shown for isCurrent / an unresolved contact-me / next /
     *  approaching row. [lightnessPercent]/[chromaAt100] default to the reference's L58/C0.16;
     *  TWR and flagged-ATIS pass their own overrides. */
    fun fullColor(hue: Float, lightnessPercent: Float = 58f, chroma: Float = 0.16f): FacilityColor {
        val bg = oklch(lightnessPercent / 100f, chroma, hue)
        val border = oklch((lightnessPercent - 12f) / 100f, chroma + 0.01f, hue)
        return FacilityColor(bg, border, lightnessPercent)
    }

    /** The desaturated background shown for a row with no active flags -- border is
     *  transparent, matching the reference exactly (no border ring on faded rows). */
    fun fadedColor(hue: Float, isDark: Boolean): FacilityColor {
        val lightnessPercent = if (isDark) 26f else 92f
        val chroma = if (isDark) 0.02f else 0.025f
        return FacilityColor(oklch(lightnessPercent / 100f, chroma, hue), Color.Transparent, lightnessPercent)
    }
}

val LocalHandoffColors: ProvidableCompositionLocal<HandoffColors> =
    staticCompositionLocalOf { LightHandoffColors }

@Composable
fun HandoffTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val useDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (useDark) DarkHandoffColors else LightHandoffColors

    androidx.compose.runtime.CompositionLocalProvider(LocalHandoffColors provides colors) {
        MaterialTheme(colorScheme = if (useDark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
            content()
        }
    }
}
