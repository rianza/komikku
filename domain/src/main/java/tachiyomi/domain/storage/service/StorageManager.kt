package tachiyomi.domain.storage.service

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import android.os.storage.StorageManager as AndroidStorageManager

class StorageManager(
    private val context: Context,
    private val storagePreferences: StoragePreferences,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    // KMK --> Guards every compound update of [settled]: the preference collector, [refresh] and
    // [invalidate] are independent coroutines on the same multi-threaded scope, and all three
    // read-then-write it.
    private val baseDirMutex = Mutex()

    /**
     * The storage base together with the raw-access state it was validated under.
     *
     * Keeping the two in one record makes the no-latch invariant structural: there is no way to
     * leave a directory committed while its accompanying raw-access state says something else.
     */
    private data class SettledStorage(val dir: UniFile, val rawAccess: Boolean)

    @Volatile
    private var settled: SettledStorage? =
        getBaseDir(storagePreferences.baseStorageDirectory().get())
            ?.let { SettledStorage(it, hasAllFilesAccess()) }

    /**
     * Whether the children of [settled] were last seen to be creatable.
     *
     * Separate from [settled] because recovery needs a *health* transition, not an identity one:
     * an SD card pulled or a folder deleted from outside leaves the URI unchanged. Starts false
     * because the constructor resolves the directory but does not validate it - [initialPrepare]
     * does that.
     *
     * Invariant: **false always wins.** [invalidate] writes false unconditionally, while
     * [applyBaseDir] may only write true if no [invalidate] has been observed since its
     * validation began - see [generation]. Every latch this class ever had came from recording
     * "already handled" too early, so a failure report is never allowed to lose a race.
     */
    private val healthy = AtomicBoolean(false)

    /**
     * Bumped by every [invalidate]. [applyBaseDir] samples it before validating and refuses to
     * publish health if it has moved since, which is what stops a slow re-validation from
     * overwriting a failure reported while it was running.
     */
    private val generation = AtomicInteger(0)

    /**
     * Serialises the repair throttle in [invalidate].
     *
     * [AtomicLong] with a compare-and-set rather than a plain field: dropping the CAS on [healthy]
     * means several reporters can now pass the health write concurrently, so the timestamp swap is
     * what elects a single repairer. Losing that CAS cannot latch - it only means another thread
     * is already repairing.
     */
    private val lastRepairAt = AtomicLong(0L)
    // KMK <--

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)

    /**
     * Emits when consumers must re-read the directories handed out by this class.
     *
     * KMK: this can now re-emit for an *unchanged* URI, when a previously broken base dir becomes
     * usable again. Identity alone cannot express that recovery.
     */
    val changes = _changes.receiveAsFlow()
        .shareIn(scope, SharingStarted.Lazily, 1)

    // KMK -->
    /**
     * The first validation pass over the directory resolved by the constructor.
     *
     * Deliberately not routed through [applyBaseDir]: that would notify on the very first settle
     * and cost every cold start a full download-index rescan. [reapply] joins this for the same
     * reason - an early foreground [refresh] that won the mutex first would re-settle the same URI
     * while health was still false, and notify.
     *
     * Upstream created these directories inside `getBaseDir` on every resolution, including the
     * constructor's; doing it here keeps the `.nomedia` guarantee without putting SAF writes on
     * the main thread during `Application.onCreate`. Guarded so a throwing provider cannot cancel
     * [scope] and take the preference collector and `changes` flow down with it.
     */
    private val initialPrepare: Job = scope.launch {
        try {
            baseDirMutex.withLock {
                val current = settled ?: return@withLock
                val gen = generation.get()
                prepareBaseDirectories(current.dir)
                settled = current.copy(rawAccess = hasAllFilesAccess())
                publishHealthy(gen)
            }
        } catch (e: Throwable) {
            this@StorageManager.logcat(LogPriority.ERROR, e) { "Failed to prepare base directories" }
        }
    }
    // KMK <--

    init {
        storagePreferences.baseStorageDirectory().changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { uri ->
                // KMK --> Notify consumers only when the new location actually resolved. An
                // unresolvable location still has to be reflected in [settled], but telling
                // consumers to invalidate would make them discard state describing a location
                // that is still the last usable one. Guarded so a throwing provider cannot
                // cancel [scope].
                val notify = try {
                    baseDirMutex.withLock { applyBaseDir(getBaseDir(uri), hasAllFilesAccess()) }
                } catch (e: Throwable) {
                    this@StorageManager.logcat(LogPriority.ERROR, e) { "Failed to apply storage directory for $uri" }
                    false
                }
                if (notify) _changes.send(Unit)
                // KMK <--
            }
            .launchIn(scope)
    }

    private fun getBaseDir(uri: String): UniFile? {
        // KMK --> Resolution is shared with [directoryAccessible] so validation can never
        // disagree with the backend actually used at runtime.
        return resolveStorageDirectory(context, uri)
        // KMK <--
    }

    // KMK -->
    /**
     * Re-resolves the storage backend while the process is alive.
     *
     * [settled] is otherwise only computed at construction and on preference change, so
     * "All files access" granted mid-session - which is exactly what the onboarding permission
     * step and the system settings shortcut do - would not take effect until the next cold start.
     *
     * The gate is identity **or** health: an unchanged raw-access state is only enough to skip the
     * work when the last settle is also still healthy. Nothing throttles this - while unhealthy,
     * every foreground retries, which is what keeps a reported failure from becoming terminal.
     *
     * Safe to call from a lifecycle callback: only the AppOps read happens on the caller's
     * thread, the resolution itself is dispatched to [scope].
     */
    fun refresh() {
        val rawAccess = hasAllFilesAccess()
        if (rawAccess == settled?.rawAccess && healthy.get()) return

        scope.launch {
            try {
                reapply(rawAccess)
            } catch (e: Throwable) {
                // Never let this cancel [scope]; that would silently kill the preference
                // collector and the `changes` flow for the rest of the process lifetime.
                this@StorageManager.logcat(LogPriority.ERROR, e) { "Failed to refresh storage backend" }
            }
        }
    }

    /**
     * Reports that a directory handed out by this class turned out to be unusable, so the next
     * [refresh] re-validates instead of trusting the last settle.
     *
     * Non-suspending and lock-free, so it is safe to call from a `changes` subscriber or from any
     * thread that just got a null out of one of the getters.
     *
     * The throttle here bounds a consumer that keeps hitting the same broken directory from
     * launching a repair per call. It deliberately does **not** gate [refresh]: the health flag
     * stays false, so every foreground still retries for free and a throttled report can never
     * delay recovery beyond the next foreground. The interval is therefore seconds, not minutes.
     */
    fun invalidate(reason: String) {
        // Unconditional, and before the health write: a failure report must always win, otherwise a
        // process that never sees ON_START - a WorkManager-only one, say - has no way back.
        generation.incrementAndGet()
        healthy.set(false)
        this.logcat(LogPriority.WARN) { "Storage directory unusable ($reason); will re-prepare" }

        val now = SystemClock.elapsedRealtime()
        val prev = lastRepairAt.get()
        if (now - prev < REPAIR_MIN_INTERVAL_MS) return
        // Elects a single repairer now that the health write no longer serialises reporters.
        // Losing here cannot latch: it means another thread is already repairing.
        if (!lastRepairAt.compareAndSet(prev, now)) return

        scope.launch {
            try {
                reapply(hasAllFilesAccess())
            } catch (e: Throwable) {
                this@StorageManager.logcat(LogPriority.ERROR, e) { "Failed to repair storage backend" }
            }
        }
    }

    /**
     * Re-resolves the configured location and re-validates it, shared by [refresh] and
     * [invalidate].
     *
     * A null resolution leaves [settled] alone: the preference has not changed, so the previous
     * directory is still the best guess and destroying it would only make consumers see nothing.
     * Health stays false, which keeps the [refresh] gate open for the next attempt.
     */
    private suspend fun reapply(rawAccess: Boolean) {
        // Wait for the constructor's validation pass. Without this, a foreground refresh can win
        // the mutex first, re-settle the same URI while health is still false, and notify - which
        // costs a full download-index rescan on every cold start.
        initialPrepare.join()

        val notify = baseDirMutex.withLock {
            val updated = getBaseDir(storagePreferences.baseStorageDirectory().get())
            if (updated == null) {
                healthy.set(false)
                this@StorageManager.logcat(LogPriority.WARN) {
                    "Storage backend unresolved; keeping ${settled?.dir?.uri}"
                }
                return@withLock false
            }
            applyBaseDir(updated, rawAccess)
        }
        if (notify) _changes.send(Unit)
    }

    /**
     * Commits [candidate] as the storage base, and reports whether consumers must be notified.
     *
     * Every fallible step runs on [candidate] BEFORE [settled] is written, so a throw leaves the
     * previous location in place. The one exception is [healthy]: if the candidate *is* the settled
     * directory, a failed validation is a genuine report about it and must be recorded, otherwise a
     * stale true would later suppress the recovery notify.
     *
     * Notifies on a URI change **or** on recovery from an unhealthy state, so a base dir whose
     * children were repaired reaches consumers even though its URI never moved. It stays silent on
     * a clean re-settle, which is what keeps a full download-index rescan tied to a real outage.
     *
     * A null [candidate] means the configured location is unusable. That is reflected in [settled],
     * but consumers are not notified, since telling them to invalidate would discard state
     * describing the last location that did work.
     *
     * Callers must hold [baseDirMutex].
     */
    private fun applyBaseDir(candidate: UniFile?, rawAccess: Boolean): Boolean {
        if (candidate == null) {
            settled = null
            healthy.set(false)
            return false
        }

        // Sampled before the slow part: a report arriving while we validate must not be overwritten
        // by the health write below.
        val gen = generation.get()
        try {
            prepareBaseDirectories(candidate)
        } catch (e: Throwable) {
            // Only the settled directory's health is ours to falsify. A candidate that is not yet
            // settled failing says nothing about the one still in use.
            //
            // URI is ACCESS-PATH identity, not folder identity: after a rawAccess flip the same
            // physical folder shows up as file:// vs content://. So this comparison never tests
            // "same folder" - the revoked-All-Files-Access case relies on
            // `settled?.rawAccess != rawAccess` in the [refresh] gate to reopen instead. Do not
            // drop that rawAccess comparison: it is what carries the load here.
            if (candidate.uri == settled?.dir?.uri) healthy.set(false)
            throw e
        }

        // Read after validation but before publishing: earlier would miss an [invalidate] that
        // landed during prepare and defer the rescan to the next foreground, later would see
        // [publishHealthy]'s own write and kill the recovery notify entirely.
        val wasHealthy = healthy.get()

        val previous = settled?.dir
        settled = SettledStorage(candidate, rawAccess)
        publishHealthy(gen)
        if (candidate.uri == previous?.uri && wasHealthy) return false

        this.logcat { "Storage backend settled: ${previous?.uri} -> ${candidate.uri}" }
        return true
    }

    /**
     * Publishes health only if no [invalidate] has been observed since [gen] was sampled, then
     * re-checks and stands down if one landed in between.
     *
     * Both halves are needed: the first check covers a report during validation, the re-check
     * covers one that lands between the check and the write. Together they make "false always
     * wins" hold without [invalidate] needing a lock.
     */
    private fun publishHealthy(gen: Int) {
        if (generation.get() != gen) return
        healthy.set(true)
        if (generation.get() != gen) healthy.set(false)
    }

    /**
     * Creates the subdirectories handed out by this class inside [dir].
     *
     * Takes the directory as a parameter instead of reading [settled], so a caller can validate a
     * candidate before committing it. Throws if any of them cannot be created - which is the
     * signal the caller needs, since a base dir whose children are missing is not usable.
     */
    private fun prepareBaseDirectories(dir: UniFile) {
        listOf(AUTOMATIC_BACKUPS_PATH, LOCAL_SOURCE_PATH, LOGS_PATH).forEach { name ->
            requireNotNull(dir.createDirectory(name)) { "Could not create $name in ${dir.uri}" }
        }
        val downloads = requireNotNull(dir.createDirectory(DOWNLOADS_PATH)) {
            "Could not create $DOWNLOADS_PATH in ${dir.uri}"
        }
        DiskUtil.createNoMediaFile(downloads, context)
    }

    /**
     * Resolves [name] under the settled base dir, reporting the base dir as unhealthy when the
     * child cannot be created. The `createDirectory` call is the cost the getters already paid, so
     * detection here is free - which is why recovery is pull-based rather than probed per
     * foreground.
     */
    private fun childDirectory(name: String): UniFile? {
        val dir = settled?.dir ?: return null
        val child = dir.createDirectory(name)
        if (child == null) invalidate("could not create $name in ${dir.uri}")
        return child
    }
    // KMK <--

    fun getAutomaticBackupsDirectory(): UniFile? {
        // KMK -->
        return childDirectory(AUTOMATIC_BACKUPS_PATH)
        // KMK <--
    }

    fun getDownloadsDirectory(): UniFile? {
        // KMK -->
        return childDirectory(DOWNLOADS_PATH)
        // KMK <--
    }

    fun getLocalSourceDirectory(): UniFile? {
        // KMK -->
        return childDirectory(LOCAL_SOURCE_PATH)
        // KMK <--
    }

    // SY -->
    fun getLogsDirectory(): UniFile? {
        // KMK -->
        return childDirectory(LOGS_PATH)
        // KMK <--
    }
    // SY <--

    companion object {
        // KMK -->
        /**
         * Extension property to check if a UniFile is an accessible directory.
         * Some DocumentsProvider implementations throw for stale or revoked URIs.
         */
        val UniFile.isAccessibleDirectory: Boolean
            get() = runCatching { exists() && isDirectory && canWrite() && canRead() }
                .getOrDefault(false)

        /**
         * Check if a directory is accessible through the same backend used at runtime.
         */
        fun directoryAccessible(context: Context, uri: String): Boolean {
            return resolveStorageDirectory(context, uri) != null
        }

        /**
         * Raw access to shared storage is only guaranteed on Android 11+ (R) with
         * "All files access" (MANAGE_EXTERNAL_STORAGE) granted.
         *
         * On Android 10 (Q) scoped storage still applies at this target SDK, so holding READ
         * alone does not guarantee raw access. Resolving to a raw path there risks producing an
         * inaccessible directory, so SAF is kept.
         */
        private fun hasAllFilesAccess(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        }

        /**
         * Keep SAF as the canonical storage permission, but bypass ExternalStorageProvider at
         * runtime when Android 11+ grants direct shared-storage access.
         *
         * Holding a SAF tree as the storage base keeps the app attached to the
         * ExternalStorageProvider process. When the platform cache-kills that provider
         * ("Killing ... com.android.externalstorage (adj 999): empty #9"), the app is
         * chain-killed with "depends on provider ... in dying proc", which surfaces as a splash
         * screen and a reader reload every time the app is resumed. Using a direct file path
         * drops that dependency, and is also faster than SAF.
         *
         * The persisted SAF URI stays untouched and is used as a fallback, so revoking
         * "All files access" degrades back to the previous behaviour instead of breaking.
         */
        private fun resolveStorageDirectory(context: Context, uriValue: String): UniFile? {
            if (uriValue.isBlank()) return null
            // The outcomes worth logging are the non-throwing nulls: a silent fallback from the
            // raw path to SAF, or a base dir that resolves to nothing at all. runCatching only
            // reports thrown exceptions, so those two are logged explicitly.
            val resolved = runCatching {
                val uri = uriValue.toUri()
                uri.toDirectFileDirectory(context)
                    ?.let { UniFile.fromFile(it) }
                    ?.takeIf { it.isAccessibleDirectory }
                    ?: UniFile.fromUri(context, uri)
                        ?.takeIf { it.isAccessibleDirectory }
                        ?.also { logcat(LogPriority.INFO) { "Raw path unavailable; using SAF for $uriValue" } }
            }
                .onFailure { logcat(LogPriority.WARN, it) { "Failed to resolve storage directory: $uriValue" } }
                .getOrNull()
            if (resolved == null) {
                logcat(LogPriority.WARN) { "Storage directory unresolved: $uriValue" }
            }
            return resolved
        }

        /**
         * Maps an ExternalStorageProvider tree URI to the raw directory it points at, or returns
         * null when raw access is not available or the URI is not one we can safely map.
         */
        private fun Uri.toDirectFileDirectory(context: Context): File? {
            if (!hasAllFilesAccess() ||
                scheme != "content" ||
                authority != EXTERNAL_STORAGE_PROVIDER_AUTHORITY ||
                !DocumentsContract.isTreeUri(this)
            ) {
                return null
            }

            // Mirrors UniFile's DocumentsContractApi21.prepareTreeUri: the document id takes
            // precedence over the tree id. Using the tree id alone would silently resolve a
            // tree URI whose document points at a descendant to the ancestor directory, moving
            // the whole storage base one or more levels up without any error.
            val documentId = runCatching { DocumentsContract.getDocumentId(this) }.getOrNull()
                ?: runCatching { DocumentsContract.getTreeDocumentId(this) }.getOrNull()
                ?: return null
            val (volumeId, relativePath) = documentId.split(":", limit = 2)
                .let { parts -> parts.first() to parts.getOrElse(1) { "" } }
            val volumeRoot = context.findStorageVolumeRoot(volumeId) ?: return null
            val canonicalRoot = runCatching { volumeRoot.canonicalFile }.getOrNull() ?: return null
            // isEmpty rather than isBlank: an all-whitespace document id must not silently
            // resolve to the whole volume, which would put .nomedia at the storage root.
            val directory = runCatching {
                if (relativePath.isEmpty()) canonicalRoot else File(canonicalRoot, relativePath).canonicalFile
            }.getOrNull() ?: return null

            // java.io.File does not normalise "..", so confirm the resolved path is still inside
            // the volume before handing it to the download, backup and log writers.
            val rootPath = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
            if (directory != canonicalRoot && !directory.path.startsWith(rootPath)) return null

            return directory.takeIf { it.isAccessibleDirectory }
        }

        private fun Context.findStorageVolumeRoot(volumeId: String): File? {
            if (volumeId.equals("primary", ignoreCase = true)) {
                return Environment.getExternalStorageDirectory()
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val storageManager = getSystemService(Context.STORAGE_SERVICE) as? AndroidStorageManager
                ?: return null
            return storageManager.storageVolumes
                .firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }
                ?.directory
        }

        private val File.isAccessibleDirectory: Boolean
            get() = exists() && isDirectory && canRead() && canWrite()

        /**
         * Call FilePicker to allow access to storage or request All Files Access Permission if not available.
         */
        fun allowAccessStorage(
            context: Context,
            storageDirPref: Preference<String>,
            pickStorageLocation: () -> Unit,
        ) {
            try {
                val documentTreeIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                if (isIntentAvailable(context, documentTreeIntent)) {
                    pickStorageLocation()
                } else {
                    handleStoragePermission(context, storageDirPref)
                }
            } catch (e: ActivityNotFoundException) {
                fallbackToScopedStorage(context, storageDirPref)
            }
        }

        /**
         * Handle storage permissions for Android R and above
         */
        private fun handleStoragePermission(
            context: Context,
            storageDirPref: Preference<String>,
        ) {
            if (hasManageExternalStoragePermission(context)) {
                updateStoragePreference(context, storageDirPref)
            } else {
                requestManageExternalStoragePermission(context)
            }
        }

        private fun hasManageExternalStoragePermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }

        private fun requestManageExternalStoragePermission(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    context.startActivity(intent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ActivityCompat.requestPermissions(
                    context as Activity,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    1001,
                )
            } else {
                ActivityCompat.requestPermissions(
                    context as Activity,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1001,
                )
            }
        }

        /**
         * Update storage preference with the selected directory
         */
        private fun updateStoragePreference(
            context: Context,
            storageDirPref: Preference<String>,
        ) {
            UniFile.fromUri(context, storageDirPref.get().toUri())?.let {
                it.mkdir()
                storageDirPref.set("") // Trigger recompose
                storageDirPref.set(it.uri.toString())
            }
        }

        /**
         * Fallback to scoped storage if no other options are available
         */
        private fun fallbackToScopedStorage(
            context: Context,
            storageDirPref: Preference<String>,
        ) {
            val fallbackDir = File(context.getExternalFilesDir(null), context.stringResource(MR.strings.app_name))
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            storageDirPref.set("") // Trigger recompose
            storageDirPref.set(fallbackDir.toUri().toString())
            context.toast("Using default directory: ${fallbackDir.absolutePath}")
        }

        /**
         * Used to check if system is able to open contract [ActivityResultContracts.OpenDocumentTree]
         * by checking if intent [Intent.ACTION_OPEN_DOCUMENT_TREE] is available and not being stub (on Android TV)
         */
        private fun isIntentAvailable(context: Context, intent: Intent): Boolean {
            val packageManager = context.packageManager
            // Android TV: ResolveInfo{c236166 com.android.tv.frameworkpackagestubs/.Stubs$DocumentsStub m=0x108000 userHandle=UserHandle{0}}
            val resolveInfo = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            return resolveInfo.any {
                it.activityInfo.packageName != null && it.activityInfo.packageName != "com.android.tv.frameworkpackagestubs"
            }
        }
        // KMK <--
    }
}

// KMK -->
private const val EXTERNAL_STORAGE_PROVIDER_AUTHORITY = "com.android.externalstorage.documents"

/**
 * Shortest gap between two repair attempts driven by [StorageManager.invalidate].
 *
 * Seconds by design: it only bounds a consumer that keeps reporting the same broken directory. It
 * never gates [StorageManager.refresh], so recovery is at worst one foreground away regardless.
 */
private const val REPAIR_MIN_INTERVAL_MS = 5_000L
// KMK <--
private const val AUTOMATIC_BACKUPS_PATH = "autobackup"
private const val DOWNLOADS_PATH = "downloads"
private const val LOCAL_SOURCE_PATH = "local"

// SY -->
private const val LOGS_PATH = "logs"
// SY <--
