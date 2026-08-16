package mihon.app.di

import android.content.Context
import android.os.Build
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.core.archive.CbzCrypto
import mihon.core.metro.IsDebugBuild
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter

// SY -->
private const val LEGACY_DATABASE_NAME = "tachiyomi.db"
// SY <--

@BindingContainer
object AppBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun providesSqlDriver(
        context: Context,
        securityPreferences: SecurityPreferences,
        @IsDebugBuild isDebugBuild: Boolean,
    ): SqlDriver {
        // SY -->
        val encrypted = securityPreferences.encryptDatabase().get()
        if (encrypted) System.loadLibrary("sqlcipher")
        // SY <--
        return AndroidSqliteDriver(
            schema = Database.Schema,
            context = context,
            // SY -->
            name = if (encrypted) CbzCrypto.DATABASE_NAME else LEGACY_DATABASE_NAME,
            factory = when {
                encrypted -> SupportOpenHelperFactory(CbzCrypto.getDecryptedPasswordSql(), null, false, 25)
                isDebugBuild && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    // Support database inspector in Android Studio
                    FrameworkSQLiteOpenHelperFactory()
                }
                else -> RequerySQLiteOpenHelperFactory()
            },
            // SY <--
            callback = object : AndroidSqliteDriver.Callback(Database.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    setPragma(db, "foreign_keys = ON")
                    setPragma(db, "journal_mode = WAL")
                    setPragma(db, "synchronous = NORMAL")
                }

                private fun setPragma(db: SupportSQLiteDatabase, pragma: String) {
                    val cursor = db.query("PRAGMA $pragma")
                    cursor.moveToFirst()
                    cursor.close()
                }
            },
        )
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesDatabase(driver: SqlDriver): Database {
        return Database(
            driver = driver,
            historyAdapter = History.Adapter(
                last_readAdapter = DateColumnAdapter,
            ),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
            chaptersAdapter = Chapters.Adapter(
                memoAdapter = MemoColumnAdapter,
            ),
        )
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesDatabaseHandler(database: Database, driver: SqlDriver): DatabaseHandler {
        return AndroidDatabaseHandler(database, driver)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesXML(): XML = XML.v1 {
        policy {
            ignoreUnknownChildren()
            autoPolymorphic = true
        }
        xmlDeclMode = XmlDeclMode.Charset
        xmlVersion = XmlVersion.XML10
        setIndent(2)
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesProtoBuf(): ProtoBuf = ProtoBuf
}
