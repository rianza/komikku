package tachiyomi.data.source

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.model.FeedSavedSearchUpdate
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.domain.source.repository.FeedSavedSearchRepository

class FeedSavedSearchRepositoryImpl(
    private val database: Database,
) : FeedSavedSearchRepository {

    override suspend fun getGlobal(): List<FeedSavedSearch> {
        return database.feed_saved_searchQueries
            .selectAllGlobal(FeedSavedSearchMapper::map)
            .awaitAsList()
    }

    override fun getGlobalAsFlow(): Flow<List<FeedSavedSearch>> {
        return database.feed_saved_searchQueries
            .selectAllGlobal(FeedSavedSearchMapper::map)
            .subscribeToList()
    }

    override suspend fun getGlobalFeedSavedSearch(): List<SavedSearch> {
        return database.feed_saved_searchQueries
            .selectGlobalFeedSavedSearch(SavedSearchMapper::map)
            .awaitAsList()
    }

    override suspend fun countGlobal(): Long {
        return database.feed_saved_searchQueries
            .countGlobal()
            .awaitAsOne()
    }

    override suspend fun getBySourceId(sourceId: Long): List<FeedSavedSearch> {
        return database.feed_saved_searchQueries
            .selectBySource(sourceId, FeedSavedSearchMapper::map)
            .awaitAsList()
    }

    override fun getBySourceIdAsFlow(sourceId: Long): Flow<List<FeedSavedSearch>> {
        return database.feed_saved_searchQueries
            .selectBySource(sourceId, FeedSavedSearchMapper::map)
            .subscribeToList()
    }

    override suspend fun getBySourceIdFeedSavedSearch(sourceId: Long): List<SavedSearch> {
        return database.feed_saved_searchQueries
            .selectSourceFeedSavedSearch(sourceId, SavedSearchMapper::map)
            .awaitAsList()
    }

    override suspend fun countBySourceId(sourceId: Long): Long {
        return database.feed_saved_searchQueries
            .countSourceFeedSavedSearch(sourceId)
            .awaitAsOne()
    }

    override suspend fun delete(feedSavedSearchId: Long) {
        database.feed_saved_searchQueries
            .deleteById(feedSavedSearchId)
    }

    override suspend fun insert(feedSavedSearch: FeedSavedSearch): Long {
        // KMK -->
        // Keep duplicate detection, insertion, and query invalidation in one transaction. This
        // prevents Feed observers from seeing the pre-insert list until a later mutation occurs.
        return database.transactionWithResult {
            val currentFeeds = if (feedSavedSearch.global) {
                database.feed_saved_searchQueries
                    .selectAllGlobal(FeedSavedSearchMapper::map)
                    .awaitAsList()
            } else {
                database.feed_saved_searchQueries
                    .selectBySource(feedSavedSearch.source, FeedSavedSearchMapper::map)
                    .awaitAsList()
            }
            val existingId = currentFeeds.find { currentFeed ->
                currentFeed.source == feedSavedSearch.source &&
                    currentFeed.savedSearch == feedSavedSearch.savedSearch &&
                    currentFeed.global == feedSavedSearch.global
            }?.id

            existingId ?: run {
                database.feed_saved_searchQueries.insert(
                    feedSavedSearch.source,
                    feedSavedSearch.savedSearch,
                    feedSavedSearch.global,
                )
                database.feed_saved_searchQueries.selectLastInsertedRowId().awaitAsOne()
            }
        }
        // KMK <--
    }

    override suspend fun insertAll(feedSavedSearch: List<FeedSavedSearch>) {
        return database.transaction {
            feedSavedSearch.forEach {
                database.feed_saved_searchQueries.insert(
                    it.source,
                    it.savedSearch,
                    it.global,
                )
            }
        }
    }

    // KMK -->
    override suspend fun updatePartial(update: FeedSavedSearchUpdate) {
        database.feed_saved_searchQueries.update(
            source = update.source,
            saved_search = update.savedSearch,
            global = update.global,
            feed_order = update.feedOrder,
            id = update.id,
        )
    }

    override suspend fun updatePartial(updates: List<FeedSavedSearchUpdate>) {
        database.transaction {
            updates.forEach { update ->
                database.feed_saved_searchQueries.update(
                    source = update.source,
                    saved_search = update.savedSearch,
                    global = update.global,
                    feed_order = update.feedOrder,
                    id = update.id,
                )
            }
        }
    }
    // KMK <--
}
