package tachiyomi.domain.updates.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum

class UpdatesPreferences(
    preferenceStore: PreferenceStore,
) {

    val filterDownloaded: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_updates_downloaded",
        TriState.DISABLED,
    )

    val filterUnread: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_updates_unread",
        TriState.DISABLED,
    )

    val filterStarted: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_updates_started",
        TriState.DISABLED,
    )

    val filterBookmarked: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_updates_bookmarked",
        TriState.DISABLED,
    )

    val filterExcludedScanlators: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_filter_updates_hide_excluded_scanlators",
        false,
    )

    // KMK -->
    val usePanoramaCover = preferenceStore.getBoolean(
        USE_PANORAMA_COVER_PREF,
        false,
    )
    // KMK <--
}

// KMK -->
const val USE_PANORAMA_COVER_PREF = "pref_updates_history_screen_use_panorama_cover"
// KMK <--
