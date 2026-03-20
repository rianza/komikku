package tachiyomi.domain.storage.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.storage.FolderProvider

class StoragePreferences(
    folderProvider: FolderProvider,
    preferenceStore: PreferenceStore,
) {
// Storing URI of the directory (either file:/// or storage://
    val baseStorageDirectory: Preference<String> = preferenceStore.getString(Preference.appStateKey("storage_dir"), folderProvider.path())
}

