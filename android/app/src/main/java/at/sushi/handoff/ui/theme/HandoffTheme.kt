package at.sushi.handoff.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import at.sushi.handoff.ThemeMode
import kotlinx.coroutines.delay

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
 *  desaturated/"faded" variant, per the design reference's own `fadedColor()`). Text color is
 *  decided separately, from [perceptualLightness] of the actual rendered [bg] -- see
 *  RowColors.kt's controllerRowColors -- not carried on this type. */
data class FacilityColor(val bg: Color, val border: Color)

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
    const val TUNED_HUE = 195f // COM1
    // Sits in the one open stretch of the hue wheel (300 CTR -> 25 TWR, wrapping through
    // magenta/red) that no facility color already occupies, and is far enough around the wheel
    // from COM1's teal to read as clearly distinct at a glance, not just a similar shade.
    const val COM2_TUNED_HUE = 340f

    // Exposed as a named hue (not just baked into hazardYellow below) so the row-color theme
    // editor (issue #21) can warn when a user-picked facility hue drifts close enough to make the
    // contact-me flash unreadable -- a yellow-on-yellow "blink" that's really no blink at all.
    // Deliberately not itself user-editable: moving this fixed point doesn't prevent a chosen
    // facility hue from converging on wherever it landed, it just relocates the same risk.
    const val HAZARD_YELLOW_HUE = 98f
    val hazardYellow = oklch(0.88f, 0.19f, HAZARD_YELLOW_HUE)

    /** The saturated background/border shown for isCurrent / an unresolved contact-me / next /
     *  approaching row. [lightnessPercent]/[chromaAt100] default to the reference's L58/C0.16;
     *  TWR and flagged-ATIS pass their own overrides. */
    fun fullColor(hue: Float, lightnessPercent: Float = 58f, chroma: Float = 0.16f): FacilityColor {
        val bg = oklch(lightnessPercent / 100f, chroma, hue)
        val border = oklch((lightnessPercent - 12f) / 100f, chroma + 0.01f, hue)
        return FacilityColor(bg, border)
    }

    /** The desaturated background shown for a row with no active flags -- border is transparent,
     *  matching the reference exactly (no border ring on faded rows). issue #21 feedback landed
     *  on a proper white <-> highlight <-> black continuum: [offset] is 0 at dead center (matches
     *  [fullColor]'s own output for this hue *exactly*, including the TWR/ATIS overrides -- the
     *  earlier fixed-L58/C0.16 "none" anchor didn't account for those, so a TWR/ATIS faded row
     *  could never actually reach its own highlighted color even at the brightest setting),
     *  -1 is pure black, +1 is pure white -- both ends fully desaturated (chroma 0), same as
     *  [fullColor]'s hue-independent black/white extremes would be. Deliberately unclamped in
     *  intent beyond -1..1 by the UI (not by this function) -- full creative range, including
     *  choices that hurt to look at, is the point of a user-editable theme. Theme-independent by
     *  construction (the highlight color itself doesn't vary between light/dark), unlike the
     *  earlier per-theme-branching version -- the same [offset] now renders identically in both
     *  themes, and RowColorPalette.fadedBrightnessOffset is the single knob governing both
     *  instead of only ever affecting dark mode. */
    fun fadedColor(hue: Float, isAtis: Boolean, isTower: Boolean, offset: Float = -0.5f): FacilityColor {
        val (baseLightness, baseChroma) = when {
            isAtis -> 85f to 0.19f
            isTower -> 48f to 0.22f
            else -> 58f to 0.16f
        }
        val lightnessPercent = if (offset >= 0f) baseLightness + (100f - baseLightness) * offset else baseLightness * (1f + offset)
        val chroma = baseChroma * (1f - kotlin.math.abs(offset))
        return FacilityColor(oklch(lightnessPercent / 100f, chroma, hue), Color.Transparent)
    }
}

/** A value that alternates between two states every 500ms, hard-cut (not eased), while
 *  [isFlashing] is true -- matches the reference's own `@keyframes contactFlash{0%,49%{a}
 *  50%,99%{b}}` over a 1s cycle exactly. Shared by the controller list's contact-me row flash
 *  (`ControllerList.kt`) and the top bar's directed-unread MSG badge flash (`TopBar.kt`), so both
 *  alternate on the same clock/cadence. */
@Composable
internal fun rememberFlashPhaseA(isFlashing: Boolean): Boolean {
    var phaseA by remember { mutableStateOf(true) }
    LaunchedEffect(isFlashing) {
        if (!isFlashing) {
            phaseA = true
            return@LaunchedEffect
        }
        while (true) {
            delay(500)
            phaseA = !phaseA
        }
    }
    return phaseA
}

val LocalHandoffColors: ProvidableCompositionLocal<HandoffColors> =
    staticCompositionLocalOf { LightHandoffColors }

/** The active row-color palette (issue #21) -- defaults to the pre-#21 hardcoded hues so any
 *  composable that reads this outside of [HandoffTheme] (e.g. a preview) still renders correctly. */
val LocalRowColorPalette: ProvidableCompositionLocal<RowColorPalette> =
    staticCompositionLocalOf { DefaultRowColorPalette }

@Composable
fun HandoffTheme(
    themeMode: ThemeMode,
    rowColorPalette: RowColorPalette = DefaultRowColorPalette,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (useDark) DarkHandoffColors else LightHandoffColors

    androidx.compose.runtime.CompositionLocalProvider(
        LocalHandoffColors provides colors,
        LocalRowColorPalette provides rowColorPalette
    ) {
        MaterialTheme(colorScheme = if (useDark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
            content()
        }
    }
}
