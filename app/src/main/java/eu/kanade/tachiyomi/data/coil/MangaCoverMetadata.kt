package eu.kanade.tachiyomi.data.coil

import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import okio.BufferedSource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.MangaCover
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Object that holds info about a covers size ratio + dominant colors
 * @author Jays2Kings
 */
object MangaCoverMetadata {
    private val preferences by injectLazy<LibraryPreferences>()
    private val coverCache by injectLazy<CoverCache>()

    // KMK -->
    // Do not start workers during App startup; MangaCoverMetadata.load() only restores saved maps.
    private val coordinator by lazy { MetadataCoordinator() }
    // KMK <--

    fun load() {
        val ratios = preferences.coverRatios.get()
        MangaCover.coverRatioMap = ConcurrentHashMap(
            ratios.mapNotNull {
                val splits = it.split("|")
                val id = splits.firstOrNull()?.toLongOrNull()
                val ratio = splits.lastOrNull()?.toFloatOrNull()
                if (id != null && ratio != null) {
                    id to ratio
                } else {
                    null
                }
            }.toMap(),
        )
        val colors = preferences.coverColors.get()
        MangaCover.dominantCoverColorMap = ConcurrentHashMap(
            colors.mapNotNull {
                val splits = it.split("|")
                val id = splits.firstOrNull()?.toLongOrNull()
                val color = splits.getOrNull(1)?.toIntOrNull()
                val textColor = splits.getOrNull(2)?.toIntOrNull()
                if (id != null && color != null) {
                    id to (color to (textColor ?: 0))
                } else {
                    null
                }
            }.toMap(),
        )
    }

    // KMK -->
    /**
     * Queue cover metadata generation without allowing rapid scrolling to start an unbounded
     * number of bitmap decodes. Duplicate work for the same cover and metadata mode is ignored.
     *
     * Ownership of [bufferedSource] is always transferred to this function. It is closed when the
     * request is processed, rejected, or evicted from the bounded queue.
     */
    fun enqueue(
        mangaCover: MangaCover,
        bufferedSource: BufferedSource? = null,
        ogFile: File? = null,
        onlyDominantColor: Boolean = true,
        force: Boolean = false,
    ) {
        if (!shouldGenerate(mangaCover, onlyDominantColor, force)) {
            closeSource(bufferedSource)
            return
        }

        val key = MetadataKey(
            mangaId = mangaCover.mangaId,
            sourceId = mangaCover.sourceId,
            coverUrl = mangaCover.url,
            lastModified = mangaCover.lastModified,
            onlyDominantColor = onlyDominantColor,
            force = force,
        )
        coordinator.enqueue(
            MetadataRequest(
                key = key,
                mangaCover = mangaCover,
                bufferedSource = bufferedSource,
                ogFile = ogFile,
                onlyDominantColor = onlyDominantColor,
                force = force,
            ),
        )
    }
    // KMK <--

    /**
     * [setRatioAndColors] generate cover's color & ratio by reading cover's bitmap from [CoverCache].
     * It's called along with [MangaCoverFetcher.fetch] everytime a cover is **displayed** (anywhere).
     *
     * When called:
     *  - It removes saved colors from saved Prefs of [MangaCover.dominantCoverColorMap] if manga is not favorite.
     *  - If a favorite manga already restored [MangaCover.dominantCoverColors] then it
     * will skip actually reading bitmap, only extract ratio. Except when [MangaCover.vibrantCoverColor]
     * is not loaded then it will read bitmap & extract vibrant color.
     * => always set [force] to true so it will always re-calculate ratio & color.
     *
     * Set [MangaCover.dominantCoverColors] for favorite manga only.
     * Set [MangaCover.vibrantCoverColor] for all mangas.
     *
     * @param bufferedSource if not null then it will load bitmap from [BufferedSource], regardless of [ogFile]
     * @param ogFile if not null then it will load bitmap from [File]. If it's null then it will try to load bitmap
     *  from [CoverCache] using either [CoverCache.customCoverCacheDir] or [CoverCache.cacheDir]
     * @param force if true then it will always re-calculate ratio & color for favorite mangas.
     *
     * This is only for loading color first time it appears on Library/Browse. Any new colors caused by loading new
     * cover when open a manga detail or change cover will be updated separately on [MangaScreenModel.setPaletteColor].
     *
     * @author Jays2Kings, cuong-tran
     */
    private fun setRatioAndColors(
        mangaCover: MangaCover,
        bufferedSource: BufferedSource? = null,
        ogFile: File? = null,
        onlyDominantColor: Boolean = true,
        force: Boolean = false,
    ) {
        // KMK -->
        // A queued request can become redundant while it waits for a worker.
        if (!shouldGenerate(mangaCover, onlyDominantColor, force)) return
        // KMK <--

        val options = BitmapFactory.Options().apply {
            inSampleSize = SUB_SAMPLE
        }

        val file = ogFile
            ?: coverCache.getCustomCoverFile(mangaCover.mangaId).takeIf { it.exists() }
            ?: coverCache.getCoverFile(mangaCover.url)

        val bitmap = when {
            bufferedSource != null -> BitmapFactory.decodeStream(bufferedSource.inputStream(), null, options)
            // if the file exists and the there was still an error then the file is corrupted
            file?.exists() == true -> BitmapFactory.decodeFile(file.path, options)
            else -> return
        }

        if (mangaCover.isMangaFavorite && options.outWidth != -1 && options.outHeight != -1) {
            mangaCover.ratio = options.outWidth / options.outHeight.toFloat()
        }
        if (bitmap == null) return

        // KMK -->
        // Palette's callback API starts another AsyncTask and releases this worker immediately,
        // defeating the concurrency bound and retaining its bitmap. Generate synchronously on this
        // background worker so the bitmap has one deterministic owner and lifetime.
        try {
            val palette = Palette.from(bitmap).generate()
            if (mangaCover.isMangaFavorite) {
                palette.dominantSwatch?.let { swatch ->
                    mangaCover.dominantCoverColors = swatch.rgb to swatch.titleTextColor
                }
            }
            palette.getBestColor()?.let { color ->
                mangaCover.vibrantCoverColor = color
            }
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        // KMK <--
    }

    // KMK -->
    private fun shouldGenerate(
        mangaCover: MangaCover,
        onlyDominantColor: Boolean,
        force: Boolean,
    ): Boolean {
        if (!mangaCover.isMangaFavorite) {
            mangaCover.remove()
            if (mangaCover.vibrantCoverColor != null) return false
        }

        if (mangaCover.isMangaFavorite && onlyDominantColor && mangaCover.dominantCoverColors != null) {
            return false
        }

        return (mangaCover.isMangaFavorite && mangaCover.dominantCoverColors == null) ||
            (!onlyDominantColor && mangaCover.vibrantCoverColor == null) ||
            force
    }

    private fun closeSource(source: BufferedSource?) {
        try {
            source?.close()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to close unused cover metadata source" }
        }
    }

    private data class MetadataKey(
        val mangaId: Long,
        val sourceId: Long,
        val coverUrl: String?,
        val lastModified: Long,
        val onlyDominantColor: Boolean,
        val force: Boolean,
    )

    private data class MetadataRequest(
        val key: MetadataKey,
        val mangaCover: MangaCover,
        val bufferedSource: BufferedSource?,
        val ogFile: File?,
        val onlyDominantColor: Boolean,
        val force: Boolean,
    )

    private class MetadataCoordinator {
        private val inFlight = ConcurrentHashMap.newKeySet<MetadataKey>()
        private val requests = Channel<MetadataRequest>(
            capacity = MAX_PENDING_METADATA_REQUESTS,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { request -> release(request) },
        )
        // KMK -->
        // Use dedicated single-threaded dispatcher to isolate metadata processing from Coil decoders.
        // This prevents decodeBitmap contention (470ms+ in v13 log) on the shared IO dispatcher.
        private val metadataDispatcher = Dispatchers.IO.limitedParallelism(1)
        private val scope = CoroutineScope(SupervisorJob() + metadataDispatcher)
        // KMK <--

        init {
            repeat(MAX_CONCURRENT_METADATA_REQUESTS) {
                scope.launch { processRequests() }
            }
        }

        fun enqueue(request: MetadataRequest) {
            if (!inFlight.add(request.key)) {
                MangaCoverMetadata.closeSource(request.bufferedSource)
                return
            }
            if (requests.trySend(request).isFailure) {
                release(request)
            }
        }

        private suspend fun processRequests() {
            // KMK -->
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            } catch (_: Exception) {}
            // KMK <--
            for (request in requests) {
                try {
                    kotlinx.coroutines.delay(50)
                    kotlinx.coroutines.yield()
                    request.bufferedSource.use { source ->
                        MangaCoverMetadata.setRatioAndColors(
                            mangaCover = request.mangaCover,
                            bufferedSource = source,
                            ogFile = request.ogFile,
                            onlyDominantColor = request.onlyDominantColor,
                            force = request.force,
                        )
                    }
                    kotlinx.coroutines.yield()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) {
                        "Failed to generate cover metadata for manga ${request.mangaCover.mangaId}"
                    }
                } finally {
                    inFlight.remove(request.key)
                }
            }
        }

        private fun release(request: MetadataRequest) {
            MangaCoverMetadata.closeSource(request.bufferedSource)
            inFlight.remove(request.key)
        }
    }
    // KMK <--

    fun MangaCover.remove() {
        MangaCover.coverRatioMap.remove(mangaId)
        MangaCover.dominantCoverColorMap.remove(mangaId)
    }

    fun savePrefs() {
        val mapCopy = MangaCover.coverRatioMap.toMap()
        preferences.coverRatios.set(mapCopy.map { "${it.key}|${it.value}" }.toSet())
        val mapColorCopy = MangaCover.dominantCoverColorMap.toMap()
        preferences.coverColors.set(mapColorCopy.map { "${it.key}|${it.value.first}|${it.value.second}" }.toSet())
    }

    private const val SUB_SAMPLE = 16

    // KMK -->
    private const val MAX_CONCURRENT_METADATA_REQUESTS = 1
    private const val MAX_PENDING_METADATA_REQUESTS = 10
    // KMK <--
}
