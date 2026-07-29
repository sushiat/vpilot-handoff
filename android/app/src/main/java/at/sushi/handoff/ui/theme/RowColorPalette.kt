package at.sushi.handoff.ui.theme

import kotlinx.serialization.Serializable

/** The user-customizable subset of [FacilityColors]' hues, plus the black/white text threshold --
 *  issue #21. Every field defaults to today's hardcoded constant, so `RowColorPalette()` renders
 *  identically to the pre-#21 app, and decoding a saved JSON blob that's missing a field (an
 *  older save) or has an extra one (a newer save, read by an older app build) degrades gracefully
 *  via these defaults + [rowColorThemeJson]'s `ignoreUnknownKeys` rather than needing explicit
 *  migration code -- only lightness/chroma stay fixed per row-state formula (see RowColors.kt),
 *  hue is the only per-token thing a saved palette actually varies. The 6 facility hues are the
 *  only customizable ones -- COM1/COM2 tuned are deliberately NOT here (feedback: after trying a
 *  custom border/dedicated hue for them, they're fixed constants instead, the same way the
 *  contact-me alert yellow and SELCAL red are -- see FacilityColors.TUNED_HUE/COM2_TUNED_HUE). */
@Serializable
data class RowColorPalette(
    val delHue: Float = FacilityColors.DEL_HUE,
    val gndHue: Float = FacilityColors.GND_HUE,
    val twrHue: Float = FacilityColors.TWR_HUE,
    val appDepHue: Float = FacilityColors.APP_DEP_HUE,
    val ctrHue: Float = FacilityColors.CTR_HUE,
    val atisHue: Float = FacilityColors.ATIS_HUE,
    val textLightnessThreshold: Float = 54f,
    // Where a default (non-highlighted) row's background sits on the white<->highlight<->black
    // continuum -- see FacilityColors.fadedColor. 0 = matches the row's own highlighted color
    // exactly, -1 = black, +1 = white. Theme-independent (the highlight color itself doesn't vary
    // between light/dark), so this one value governs both themes' faded appearance.
    val fadedBrightnessOffset: Float = -0.5f,
    // Extra darkening applied uniformly on top of every row's rendered color -- highlighted rows
    // included, unlike fadedBrightnessOffset above -- only when the app is in dark theme. 0 = no
    // extra darkening, 1 = fully black. See RowColors.kt's darkenTowardBlack. Default (0.33) is
    // the user's own on-device call, not a derived value.
    val darkModeOffset: Float = 0.33f
)

val DefaultRowColorPalette = RowColorPalette()

/** Colorblind-safe starting points requested on issue #21's comment thread -- both keep every
 *  token clearly separated on the deuteranopia/protanopia confusion axis (red<->green) by leaning
 *  on blue/orange/yellow/purple hues instead. Hand-picked, not derived from a simulator, so (like
 *  the original palette's hues -- see RowColors.kt's facilityHue) these should get the same
 *  on-device confirmation before being treated as final. */
val DeuteranopiaSafeRowColorPalette = RowColorPalette(
    delHue = 250f,   // blue
    gndHue = 45f,    // amber/orange -- clear of green
    twrHue = 320f,   // magenta -- clear of red
    appDepHue = 200f, // cyan
    ctrHue = 280f,   // violet
    atisHue = 95f   // yellow-green, only ever paired against the others, not GND directly
)

val ProtanopiaSafeRowColorPalette = RowColorPalette(
    delHue = 245f,
    gndHue = 50f,
    twrHue = 300f,
    appDepHue = 205f,
    ctrHue = 270f,
    atisHue = 90f
)

/** A user-named, locally-saved palette -- [id] is stable across renames (the name is what's
 *  editable/user-facing) so [RowColorThemeStore]'s "active theme" pointer survives a rename. */
@Serializable
data class SavedRowColorTheme(
    val id: String,
    val name: String,
    val palette: RowColorPalette
)
