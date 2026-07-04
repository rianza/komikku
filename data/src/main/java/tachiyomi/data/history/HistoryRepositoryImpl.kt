package tachiyomi.data.history

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga

class HistoryRepositoryImpl(
    private val database: Database,
) : HistoryRepository {

    override fun getHistory(
        query: String,
        // KMK -->
        unfinishedManga: Boolean?,
        unfinishedChapter: Boolean?,
        nonLibraryEntries: Boolean?,
        // KMK <--
    ): Flow<List<HistoryWithRelations>> {
        // KMK -->
        return database.historyViewQueries
            .history(
                Manga.CHAPTER_SHOW_NOT_BOOKMARKED,
                Manga.CHAPTER_SHOW_BOOKMARKED,
                unfinishedManga?.toLong(),
                unfinishedChapter,
                nonLibraryEntries,
                query,
                HistoryMapper::mapHistoryWithRelations,
            )
            .subscribeToList()
        // KMK <--
    }

    override suspend fun getLastHistory(): HistoryWithRelations? {
        // KMK -->
        return database.historyViewQueries
            .getLatestHistory(
                Manga.CHAPTER_SHOW_NOT_BOOKMARKED,
                Manga.CHAPTER_SHOW_BOOKMARKED,
                HistoryMapper::mapHistoryWithRelations,
            )
            .awaitAsOneOrNull()
        // KMK <--
    }

    override suspend fun getTotalReadDuration(): Long {
        return database.historyQueries
            .getReadDuration()
            .awaitAsOne()
    }

    override suspend fun getHistoryByMangaId(mangaId: Long): List<History> {
        return database.historyQueries
            .getHistoryByMangaId(mangaId, HistoryMapper::mapHistory)
            .awaitAsList()
    }

    // KMK -->
    override suspend fun resetHistory(historyIds: List<Long>) {
        try {
            database.historyQueries.resetHistoryByIds(historyIds)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }
    // KMK <--

    // KMK -->
    override suspend fun resetHistoryByMangaIds(mangaIds: List<Long>) {
        try {
            database.historyQueries.resetHistoryByMangaIds(mangaIds)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }
    // KMK <--

    override suspend fun deleteAllHistory(): Boolean {
        return try {
            database.historyQueries.removeAllHistory()
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
            false
        }
    }

    override suspend fun upsertHistory(historyUpdate: HistoryUpdate) {
        // SY -->
        partialUpdate(historyUpdate)
        // SY <--
    }

    // SY -->
    override suspend fun upsertAllHistory(historyUpdate: List<HistoryUpdate>) {
        partialUpdate(*historyUpdate.toTypedArray())
    }

    private suspend fun partialUpdate(vararg historyUpdates: HistoryUpdate) {
        try {
            database.transaction {
                historyUpdates.forEach { historyUpdate ->
                    database.historyQueries.upsert(
                        chapterId = historyUpdate.chapterId,
                        readAt = historyUpdate.readAt,
                        time_read = historyUpdate.sessionReadDuration,
                    )
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }
    // SY <--
}
