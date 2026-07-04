package eu.kanade.domain.ui

import androidx.compose.material3.FabPosition
import com.materialkolor.PaletteStyle
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.domain.ui.model.ThemeMode
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class UiPreferences(
    preferenceStore: PreferenceStore,
) {

    val themeMode: Preference<ThemeMode> = preferenceStore.getEnum("pref_theme_mode_key", ThemeMode.SYSTEM)

    val appTheme: Preference<AppTheme> = preferenceStore.getEnum(
        "pref_app_theme",
        AppTheme.MONET,
    )

    val themeDarkAmoled: Preference<Boolean> = preferenceStore.getBoolean("pref_theme_dark_amoled_key", false)

    // KMK -->
    val colorTheme: Preference<Int> = preferenceStore.getInt("pref_color_theme", 0xFFDF0090.toInt())

    val customThemeStyle: Preference<PaletteStyle> = preferenceStore.getEnum("pref_custom_theme_style_key", PaletteStyle.Fidelity)

    val themeCoverBased: Preference<Boolean> = preferenceStore.getBoolean("pref_theme_cover_based_key", true)

    val themeCoverBasedStyle: Preference<PaletteStyle> = preferenceStore.getEnum("pref_theme_cover_based_style_key", PaletteStyle.Vibrant)

    val preloadLibraryColor: Preference<Boolean> = preferenceStore.getBoolean("pref_preload_library_color_key", true)
    // KMK <--

    val relativeTime: Preference<Boolean> = preferenceStore.getBoolean("relative_time_v2", true)

    val dateFormat: Preference<String> = preferenceStore.getString("app_date_format", "")

    val tabletUiMode: Preference<TabletUiMode> = preferenceStore.getEnum("tablet_ui_mode", TabletUiMode.AUTOMATIC)

    val imagesInDescription: Preference<Boolean> = preferenceStore.getBoolean("pref_render_images_description", true)

    // SY -->
    val expandFilters: Preference<Boolean> = preferenceStore.getBoolean("eh_expand_filters", false)

    val hideFeedTab: Preference<Boolean> = preferenceStore.getBoolean("hide_latest_tab", false)

    val feedTabInFront: Preference<Boolean> = preferenceStore.getBoolean("latest_tab_position", false)

    // KMK -->
    val expandRelatedMangas: Preference<Boolean> = preferenceStore.getBoolean("expand_related_mangas", true)

    val relatedMangasInOverflow: Preference<Boolean> = preferenceStore.getBoolean("related_mangas_in_overflow", false)

    val showHomeOnRelatedMangas: Preference<Boolean> = preferenceStore.getBoolean("show_home_on_related_mangas", true)

    val readButtonPosition: Preference<String> = preferenceStore.getString("reading_button_position", FabPosition.End.toString())

    val usePanoramaCoverFlow: Preference<Boolean> = preferenceStore.getBoolean("use_panorama_cover_flow", false)

    val usePanoramaCoverAlways: Preference<Boolean> = preferenceStore.getBoolean("use_panorama_cover_grid", true)

    val usePanoramaCoverMangaInfo: Preference<Boolean> = preferenceStore.getBoolean("use_panorama_cover_manga_info", false)

    val topAlignCover: Preference<Boolean> = preferenceStore.getBoolean("top_align_cover", false)
    // KMK <--

    val recommendsInOverflow: Preference<Boolean> = preferenceStore.getBoolean("recommends_in_overflow", false)

    val mergeInOverflow: Preference<Boolean> = preferenceStore.getBoolean("merge_in_overflow", true)

    val previewsRowCount: Preference<Int> = preferenceStore.getInt("pref_previews_row_count", 4)

    val useNewSourceNavigation: Preference<Boolean> = preferenceStore.getBoolean("use_new_source_navigation", true)

    val bottomBarLabels: Preference<Boolean> = preferenceStore.getBoolean("pref_show_bottom_bar_labels", true)

    val showNavUpdates: Preference<Boolean> = preferenceStore.getBoolean("pref_show_updates_button", true)

    val showNavHistory: Preference<Boolean> = preferenceStore.getBoolean("pref_show_history_button", true)
    // SY <--

    companion object {
        fun dateFormat(format: String): DateTimeFormatter = when (format) {
            "" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            else -> DateTimeFormatter.ofPattern(format, Locale.getDefault())
        }
    }
}
