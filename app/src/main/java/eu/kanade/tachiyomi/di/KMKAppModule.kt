package eu.kanade.tachiyomi.di

import android.app.Application
import eu.kanade.tachiyomi.data.BackupRestoreStatus
import eu.kanade.tachiyomi.data.LibraryUpdateStatus
import eu.kanade.tachiyomi.data.SyncStatus
import eu.kanade.tachiyomi.data.cache.PagePreviewCache
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveService
import exh.eh.EHentaiUpdateHelper
import tachiyomi.core.common.storage.UniFileTempFileManager
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory

class KMKAppModule(val app: Application) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory { PagePreviewCache(app) }

        // AM (CONNECTIONS) -->
        addSingletonFactory { ConnectionsManager() }
        // <-- AM (CONNECTIONS)

        addSingletonFactory { GoogleDriveService(app) }
        addSingletonFactory { EHentaiUpdateHelper(app) }
        addSingletonFactory { UniFileTempFileManager(app) }

        // KMK -->
        addSingletonFactory { BackupRestoreStatus() }
        addSingletonFactory { SyncStatus() }
        addSingletonFactory { LibraryUpdateStatus() }
        // KMK <--
    }
}
