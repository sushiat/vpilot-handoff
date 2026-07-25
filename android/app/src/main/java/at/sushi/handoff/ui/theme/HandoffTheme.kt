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

/** Facility hues from the "Color & badge logic" table -- full saturation, shared between
 *  light/dark themes (only the desaturated "unrelated station" background varies by theme,
 *  see [RowColors]). */
object FacilityColors {
    val del = oklch(0.55f, 0.17f, 255f)
    val gnd = oklch(0.55f, 0.17f, 140f)
    // "Bumped to L48/C0.22" per the doc -- plain L58/C16 red read as too pale.
    val twr = oklch(0.48f, 0.22f, 25f)
    val appDep = oklch(0.55f, 0.17f, 60f)
    val ctr = oklch(0.55f, 0.17f, 300f)
    // "Boosted to L85/C0.19" -- bright yellow/gold, not brownish.
    val atis = oklch(0.85f, 0.19f, 95f)
    val current = oklch(0.55f, 0.17f, 195f)
    val hazardYellow = oklch(0.88f, 0.19f, 98f)
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
