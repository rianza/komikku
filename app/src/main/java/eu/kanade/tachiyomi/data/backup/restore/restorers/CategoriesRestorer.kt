package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.library.service.LibraryPreferences

@Inject
class CategoriesRestorer(
    private val handler: DatabaseHandler,
    private val getCategories: GetCategories,
    private val libraryPreferences: LibraryPreferences,
) {

    suspend operator fun invoke(backupCategories: List<BackupCategory>) {
        if (backupCategories.isNotEmpty()) {
            val dbCategories = getCategories.await()
            val dbCategoriesByName = dbCategories.associateBy { it.name }
            var nextOrder = dbCategories.maxOfOrNull { it.order }?.plus(1) ?: 0

            val categories = backupCategories
                .sortedBy { it.order }
                .map {
                    val dbCategory = dbCategoriesByName[it.name]
                    if (dbCategory != null) return@map dbCategory
                    val order = nextOrder++
                    handler.awaitOneExecutable {
                        categoriesQueries.insert(
                            it.name,
                            order,
                            it.flags,
                            // KMK -->
                            hidden = if (it.hidden) 1L else 0L,
                            // KMK <--
                        )
                        categoriesQueries.selectLastInsertedRowId()
                    }
                        .let { id -> it.toCategory(id).copy(order = order) }
                }

            libraryPreferences.categorizedDisplaySettings().set(
                (dbCategories + categories)
                    .distinctBy { it.flags }
                    .size > 1,
            )
        }
    }
}
