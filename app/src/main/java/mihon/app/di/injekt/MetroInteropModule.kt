package mihon.app.di.injekt

import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.source.interactor.UpdateMangaFromRemote
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.interactor.UpdateCategory
import tachiyomi.domain.chapter.interactor.DeleteChapters
import tachiyomi.domain.chapter.interactor.GetChapterByUrl
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetAllManga
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetExhFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaBySource
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.interactor.GetSearchMetadata
import tachiyomi.domain.manga.interactor.InsertFavoriteEntryAlternative
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourcePreferences
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.sync.SyncPreferences
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.presentation.widget.WidgetManager
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory

@Inject
class MetroInteropModule(
    private val json: Json,
    private val protoBuf: ProtoBuf,
    private val xml: XML,

    private val preferenceStore: PreferenceStore,
    private val basePreferences: BasePreferences,
    private val uiPreferences: UiPreferences,
    private val readerPreferences: ReaderPreferences,
    private val networkPreferences: NetworkPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val sourcePreferences: SourcePreferences,
    private val trackPreferences: TrackPreferences,
    private val backupPreferences: BackupPreferences,
    private val storagePreferences: StoragePreferences,
    private val privacyPreferences: PrivacyPreferences,
    private val securityPreferences: SecurityPreferences,
    private val downloadPreferences: DownloadPreferences,
    private val connectionsPreferences: ConnectionsPreferences,
    private val syncPreferences: SyncPreferences,

    private val networkHelper: NetworkHelper,
    private val javaScriptEngine: JavaScriptEngine,
    private val sourceManager: SourceManager,
    private val trackerManager: TrackerManager,
    private val extensionManager: ExtensionManager,
    private val chapterCache: ChapterCache,
    private val coverCache: CoverCache,
    private val downloadManager: DownloadManager,
    private val downloadCache: DownloadCache,
    private val storageManager: StorageManager,
    private val database: Database,
    private val databaseHandler: DatabaseHandler,
    private val widgetManager: WidgetManager,

    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,

    private val getManga: GetManga,
    private val getCustomMangaInfo: GetCustomMangaInfo,
    private val setCustomMangaInfo: SetCustomMangaInfo,
    private val getFlatMetadataById: GetFlatMetadataById,
    private val insertFlatMetadata: InsertFlatMetadata,
    private val getExhFavoriteMangaWithMetadata: GetExhFavoriteMangaWithMetadata,
    private val getSearchMetadata: GetSearchMetadata,
    private val getAllManga: GetAllManga,
    private val getMangaBySource: GetMangaBySource,
    private val getFavorites: GetFavorites,
    private val getCategories: GetCategories,
    private val getTracks: GetTracks,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getChapterByUrl: GetChapterByUrl,
    private val getLibraryManga: GetLibraryManga,
    private val getHistory: GetHistory,
    private val removeHistory: RemoveHistory,
    private val upsertHistory: UpsertHistory,
    private val getExtensionStores: GetExtensionStores,
    private val updateExtensionStores: UpdateExtensionStores,
    private val trustExtension: TrustExtension,
    private val updateManga: UpdateManga,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val setMangaCategories: SetMangaCategories,
    private val updateCategory: UpdateCategory,
    private val createCategoryWithName: CreateCategoryWithName,
    private val getMergedReferencesById: GetMergedReferencesById,
    private val networkToLocalManga: NetworkToLocalManga,
    private val insertFavoriteEntryAlternative: InsertFavoriteEntryAlternative,
    private val deleteChapters: DeleteChapters,
) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingleton(json)
        addSingleton(protoBuf)
        addSingleton(xml)

        addSingleton(preferenceStore)
        addSingleton(basePreferences)
        addSingleton(uiPreferences)
        addSingleton(readerPreferences)
        addSingleton(networkPreferences)
        addSingleton(libraryPreferences)
        addSingleton(sourcePreferences)
        addSingleton(trackPreferences)
        addSingleton(backupPreferences)
        addSingleton(storagePreferences)
        addSingleton(privacyPreferences)
        addSingleton(securityPreferences)
        addSingleton(downloadPreferences)
        addSingleton(connectionsPreferences)
        addSingleton(syncPreferences)

        addSingleton(networkHelper)
        addSingleton(javaScriptEngine)
        addSingleton(sourceManager)
        addSingleton(trackerManager)
        addSingleton(extensionManager)
        addSingleton(chapterCache)
        addSingleton(coverCache)
        addSingleton(downloadManager)
        addSingleton(downloadCache)
        addSingleton(storageManager)
        addSingleton(database)
        addSingleton(databaseHandler)
        addSingleton(widgetManager)

        addSingleton(mangaRepository)
        addSingleton(chapterRepository)

        addFactory { getManga }
        addFactory { getCustomMangaInfo }
        addFactory { setCustomMangaInfo }
        addFactory { getFlatMetadataById }
        addFactory { insertFlatMetadata }
        addFactory { getExhFavoriteMangaWithMetadata }
        addFactory { getSearchMetadata }
        addFactory { getAllManga }
        addFactory { getMangaBySource }
        addFactory { getFavorites }
        addFactory { getCategories }
        addFactory { getTracks }
        addFactory { getChaptersByMangaId }
        addFactory { getChapterByUrl }
        addFactory { getLibraryManga }
        addFactory { getHistory }
        addFactory { removeHistory }
        addFactory { upsertHistory }
        addFactory { getExtensionStores }
        addFactory { updateExtensionStores }
        addFactory { trustExtension }
        addFactory { updateManga }
        addFactory { updateMangaFromRemote }
        addFactory { setMangaCategories }
        addFactory { updateCategory }
        addFactory { createCategoryWithName }
        addFactory { getMergedReferencesById }
        addFactory { networkToLocalManga }
        addFactory { insertFavoriteEntryAlternative }
        addFactory { deleteChapters }
    }
}
