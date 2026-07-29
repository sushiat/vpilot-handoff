package at.sushi.handoff.ui.theme

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Reserved [SavedRowColorTheme.id] values for the two built-in colorblind presets -- lets the
 *  "active theme" pointer address a preset the same way it addresses a real saved theme, without
 *  those presets actually living in the saved-themes list (they're not user-editable/deletable). */
const val DeuteranopiaPresetId = "__deuteranopia__"
const val ProtanopiaPresetId = "__protanopia__"

/** Same `ignoreUnknownKeys` setup as protocol/Messages.kt -- lets an older app build read a
 *  palette blob saved by a newer one (extra fields) without crashing, while RowColorPalette's own
 *  per-field defaults handle the opposite direction (a newer build reading an older, field-
 *  missing blob). */
private val rowColorThemeJson = Json { ignoreUnknownKeys = true }

/** SharedPreferences-backed store for issue #21's saved row-color themes -- mirrors how
 *  HandoffConnectionService.loadPersistedUiSettings loads other local-only UI settings, just with
 *  a JSON-encoded list instead of a single primitive since there can be several saved themes. */
object RowColorThemeStore {
    private const val KeySavedThemes = "row_color_themes"
    private const val KeyActiveThemeId = "row_color_active_theme_id"

    fun loadSavedThemes(prefs: SharedPreferences): List<SavedRowColorTheme> {
        val raw = prefs.getString(KeySavedThemes, null) ?: return emptyList()
        return runCatching { rowColorThemeJson.decodeFromString<List<SavedRowColorTheme>>(raw) }.getOrDefault(emptyList())
    }

    fun saveSavedThemes(prefs: SharedPreferences, themes: List<SavedRowColorTheme>) {
        prefs.edit { putString(KeySavedThemes, rowColorThemeJson.encodeToString(themes)) }
    }

    fun loadActiveThemeId(prefs: SharedPreferences): String? = prefs.getString(KeyActiveThemeId, null)

    fun saveActiveThemeId(prefs: SharedPreferences, id: String?) {
        prefs.edit { putString(KeyActiveThemeId, id) }
    }

    /** Resolves the persisted active-theme id against the saved list + built-in presets, falling
     *  back to [DefaultRowColorPalette] for a null/unrecognized/deleted id -- called once at
     *  startup (HandoffConnectionService.loadPersistedUiSettings) and again whenever the editor
     *  dialog needs to re-resolve after a save/delete. */
    fun resolveActivePalette(prefs: SharedPreferences): RowColorPalette {
        val activeId = loadActiveThemeId(prefs) ?: return DefaultRowColorPalette
        return resolvePaletteById(activeId, loadSavedThemes(prefs))
    }

    fun resolvePaletteById(id: String?, savedThemes: List<SavedRowColorTheme>): RowColorPalette = when (id) {
        null -> DefaultRowColorPalette
        DeuteranopiaPresetId -> DeuteranopiaSafeRowColorPalette
        ProtanopiaPresetId -> ProtanopiaSafeRowColorPalette
        else -> savedThemes.find { it.id == id }?.palette ?: DefaultRowColorPalette
    }
}
