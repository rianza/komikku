package tachiyomi.domain.backup.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class BackupPreferences(
    preferenceStore: PreferenceStore,
) {

    val backupInterval: Preference<Int> = preferenceStore.getInt("backup_interval", 12)

    val lastAutoBackupTimestamp = preferenceStore.getLong(Preference.appStateKey("last_auto_backup_timestamp"), 0L)

    // KMK -->
    val showRestoringProgressBanner = preferenceStore.getBoolean(
        Preference.appStateKey("pref_show_restoring_progress_banner_key"),
        true,
    )
    // KMK <--
}
