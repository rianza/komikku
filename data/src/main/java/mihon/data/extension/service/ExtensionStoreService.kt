package mihon.data.extension.service

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.data.extension.model.NetworkExtensionStore
import mihon.data.extension.model.NetworkLegacyExtension
import mihon.data.extension.model.NetworkLegacyExtensionRepo
import mihon.data.extension.model.toAvailableExtensions
import mihon.domain.extension.model.ExtensionStore
import okio.BufferedSource
import okio.buffer
import okio.gzip
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.cancellation.CancellationException

class ExtensionStoreService(
    private val network: NetworkHelper,
    private val json: Json,
    private val protoBuf: ProtoBuf,
) {
    suspend fun fetch(indexUrl: String): Result<ExtensionStore> {
        return fetch(indexUrl, forceV2 = false)
    }

    private suspend fun fetch(indexUrl: String, forceV2: Boolean): Result<ExtensionStore> {
        var updatedIndexUrl = indexUrl
        return try {
            val store = network.noCookiesClient
                .newCall(GET(indexUrl))
                .awaitSuccess()
                .body
                .source()
                .decompressIfGzipped()
                .use { source ->
                    try {
                        protoBuf.decodeFromByteArray<NetworkExtensionStore>(source.peek().readByteArray())
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logcat(LogPriority.ERROR, e) {
                            "Failed to parse extension store as protobuf '$updatedIndexUrl'"
                        }

                        try {
                            json.decodeFromBufferedSource<NetworkExtensionStore>(source.peek())
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            if (forceV2) throw e
                            logcat(LogPriority.ERROR, e) {
                                "Failed to parse extension store as v2 json '$updatedIndexUrl'"
                            }

                            val legacyIndex = try {
                                json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(source.peek())
                            } catch (e: IllegalArgumentException) {
                                if (!indexUrl.endsWith("/index.min.json")) {
                                    throw e
                                }
                                logcat(LogPriority.ERROR, e) {
                                    "Failed to parse legacy extension repo from '$updatedIndexUrl'"
                                }

                                updatedIndexUrl = indexUrl.replace("/index.min.json", "/repo.json")
                                network.noCookiesClient
                                    .newCall(GET(updatedIndexUrl))
                                    .awaitSuccess()
                                    .body
                                    .source()
                                    .decompressIfGzipped()
                                    .use {
                                        json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(it)
                                    }
                            }

                            if (legacyIndex.indexV2 != null) {
                                return fetch(legacyIndex.indexV2, forceV2 = true)
                            } else {
                                legacyIndex
                            }
                        }
                    }
                        .toExtensionStore(updatedIndexUrl)
                }

            Result.success(store)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) {
                "Failed to add extension store '$updatedIndexUrl'"
            }
            Result.failure(e)
        }
    }

    suspend fun getExtensions(store: ExtensionStore): Result<List<Extension.Available>> {
        return try {
            val extensions = if (store.extensionListUrl != null) {
                network.noCookiesClient
                    .newCall(GET(store.extensionListUrl!!))
                    .awaitSuccess()
                    .body
                    .source()
                    .decompressIfGzipped()
                    .use { source ->
                        when (source.peek().readByte()) {
                            // "{..."
                            0x7B.toByte() -> json.decodeFromBufferedSource<NetworkExtensionStore.ExtensionList>(source)
                            else -> protoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(
                                source.readByteArray(),
                            )
                        }
                            .toAvailableExtensions(store)
                    }
            } else if (!store.isLegacy) {
                network.noCookiesClient
                    .newCall(GET(store.indexUrl))
                    .awaitSuccess()
                    .body
                    .source()
                    .decompressIfGzipped()
                    .use { source ->
                        when (source.peek().readByte()) {
                            // "{..."
                            0x7B.toByte() -> json.decodeFromBufferedSource<NetworkExtensionStore>(source)
                            else -> protoBuf.decodeFromByteArray<NetworkExtensionStore>(source.readByteArray())
                        }
                            .extensionList!!
                            .toAvailableExtensions(store)
                    }
            } else {
                val storeBaseUrl = store.indexUrl.removeSuffix("/repo.json")
                network.noCookiesClient
                    .newCall(GET("$storeBaseUrl/index.min.json"))
                    .awaitSuccess()
                    .body
                    .source()
                    .use { source ->
                        json.decodeFromBufferedSource<List<NetworkLegacyExtension>>(source)
                            .map { it.toAvailableExtension(store, storeBaseUrl) }
                    }
            }
            Result.success(extensions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip = peek().use { peeked ->
            try {
                peeked.readShort().toInt() == 0x1f8b
            } catch (_: Exception) {
                false
            }
        }

        return if (isGzip) gzip().buffer() else this
    }
}
