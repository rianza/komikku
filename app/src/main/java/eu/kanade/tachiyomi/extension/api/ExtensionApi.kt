package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import exh.source.BlacklistedSources
import exh.source.ExhPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
<<<<<<< HEAD
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
=======
>>>>>>> a0ae52671f (Change extension repo to extension store and add support for newer extension index format (#3349))
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import kotlin.time.Duration.Companion.days

internal class ExtensionApi {

    private val repository: ExtensionStoreRepository by injectLazy()

    private val preferenceStore: PreferenceStore by injectLazy()
    private val updateExtensionStores: UpdateExtensionStores by injectLazy()
    private val extensionManager: ExtensionManager by injectLazy()

    // SY -->
    private val sourcePreferences: SourcePreferences by injectLazy()

    // SY <--
<<<<<<< HEAD

    private val json: Json by injectLazy()
=======
>>>>>>> a0ae52671f (Change extension repo to extension store and add support for newer extension index format (#3349))

    private val lastExtCheck: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_ext_check"), 0)
    }

    suspend fun findExtensions(): List<Extension.Available> {
<<<<<<< HEAD
        // KMK -->
        val disabledRepos = sourcePreferences.disabledRepos().get()
        // KMK <--
        return withIOContext {
            getExtensionRepo.getAll()
                // KMK -->
                .filterNot { it.baseUrl in disabledRepos }
                // KMK <--
                .map { async { getExtensions(it) } }
                .awaitAll()
                .flatten()
        }
    }

    private suspend fun getExtensions(extRepo: ExtensionRepo): List<Extension.Available> {
        val repoBaseUrl = extRepo.baseUrl
        return try {
            val response = networkService.client
                .newCall(GET("$repoBaseUrl/index.min.json"))
                .awaitSuccess()

            with(json) {
                response
                    .parseAs<List<ExtensionJsonObject>>()
                    .toExtensions(
                        repoBaseUrl,
                        // KMK -->
                        signature = extRepo.signingKeyFingerprint,
                        repoName = extRepo.shortName ?: extRepo.name,
                        // KMK <--
                    )
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to get extensions from $repoBaseUrl" }
            emptyList()
        }
=======
        return withIOContext { repository.fetchExtensions() }
>>>>>>> a0ae52671f (Change extension repo to extension store and add support for newer extension index format (#3349))
    }

    suspend fun checkForUpdates(
        context: Context,
        fromAvailableExtensionList: Boolean = false,
    ): List<Extension.Installed>? {
        // Limit checks to once a day at most
        if (!fromAvailableExtensionList &&
            Instant.now().toEpochMilli() < lastExtCheck.get() + 1.days.inWholeMilliseconds
        ) {
            return null
        }

        updateExtensionStores()

        val extensions = if (fromAvailableExtensionList) {
            extensionManager.availableExtensionsFlow.value
        } else {
            findExtensions().also { lastExtCheck.set(Instant.now().toEpochMilli()) }
        }

        // SY -->
        val blacklistEnabled = sourcePreferences.enableSourceBlacklist.get()
        // SY <--

        val installedExtensions = ExtensionLoader.loadExtensions(context)
            .filterIsInstance<LoadResult.Success>()
            .map { it.extension }
            // SY -->
            .filterNot { it.isBlacklisted(blacklistEnabled) }
        // SY <--

        val extensionsWithUpdate = mutableListOf<Extension.Installed>()
        for (installedExt in installedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue
            val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
            val hasUpdatedLib = availableExt.libVersion > installedExt.libVersion
            val hasUpdate = hasUpdatedVer || hasUpdatedLib
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        if (extensionsWithUpdate.isNotEmpty()) {
            ExtensionUpdateNotifier(context).promptUpdates(extensionsWithUpdate.map { it.name })
        }

        return extensionsWithUpdate
    }

<<<<<<< HEAD
    private fun List<ExtensionJsonObject>.toExtensions(
        repoUrl: String,
        // KMK -->
        signature: String,
        repoName: String,
        // KMK <--
    ): List<Extension.Available> {
        return this
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX
            }
            .map {
                Extension.Available(
                    name = it.name.substringAfter("Tachiyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    sources = it.sources?.map(extensionSourceMapper).orEmpty(),
                    apkName = it.apk,
                    iconUrl = "$repoUrl/icon/${it.pkg}.png",
                    repoUrl = repoUrl,
                    // KMK -->
                    signatureHash = signature,
                    repoName = repoName,
                    // KMK <--
                )
            }
    }

    fun getApkUrl(extension: Extension.Available): String {
        return "${extension.repoUrl}/apk/${extension.apkName}"
    }

    private fun ExtensionJsonObject.extractLibVersion(): Double {
        return version.substringBeforeLast('.').toDouble()
    }

=======
>>>>>>> a0ae52671f (Change extension repo to extension store and add support for newer extension index format (#3349))
    // SY -->
    private fun Extension.isBlacklisted(
        blacklistEnabled: Boolean = sourcePreferences.enableSourceBlacklist.get(),
        // KMK -->
        isHentaiEnabled: Boolean = Injekt.get<ExhPreferences>().isHentaiEnabled.get(),
        // KMK <--
    ): Boolean {
        return pkgName in BlacklistedSources.BLACKLISTED_EXTENSIONS &&
            blacklistEnabled &&
            // KMK -->
            isHentaiEnabled
        // KMK <--
    }
    // SY <--
}
