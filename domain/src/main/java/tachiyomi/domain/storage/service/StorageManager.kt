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
import android.os.storage.StorageManager as AndroidStorageManager

class StorageManager(
    private val context: Context,
    private val storagePreferences: StoragePreferences,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    // KMK --> Guards every compound update of [baseDir]: the preference collector and [refresh]
    // are independent coroutines on the same multi-threaded scope, and both read-then-write it.
    private val baseDirMutex = Mutex()

    // Raw-access state of the last resolve+prepare that fully succeeded, so a failed attempt
    // leaves the next [refresh] free to retry. `null` means nothing has settled yet, which is why
    // this can never become a terminal state: only success narrows it.
    @Volatile
    private var settledRawAccess: Boolean? = null

    @Volatile
    private var baseDir: UniFile? = getBaseDir(storagePreferences.baseStorageDirectory().get())
    // KMK <--

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .shareIn(scope, SharingStarted.Lazily, 1)

    init {
        // KMK --> Upstream created these inside getBaseDir on every resolution, including the
        // constructor's. Doing it here keeps the .nomedia guarantee without putting SAF writes
        // on the main thread during Application.onCreate. Guarded so a throwing provider cannot
        // cancel [scope] and take the preference collector and `changes` flow down with it.
        scope.launch {
            try {
                baseDirMutex.withLock {
                    val dir = baseDir ?: return@withLock
                    prepareBaseDirectories(dir)
                    settledRawAccess = hasAllFilesAccess()
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Failed to prepare base directories" }
            }
        }
        // KMK <--
        storagePreferences.baseStorageDirectory().changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { uri ->
                // KMK --> Notify consumers only when the new location actually resolved. An
                // unresolvable location still has to be reflected in [baseDir], but telling
                // consumers to invalidate would make them discard state describing a location
                // that is still the last usable one. Guarded so a throwing provider cannot
                // cancel [scope].
                val notify = try {
                    baseDirMutex.withLock { applyBaseDir(getBaseDir(uri), hasAllFilesAccess()) }
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Failed to apply storage directory for $uri" }
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
     * [baseDir] is otherwise only computed at construction and on preference change, so
     * "All files access" granted mid-session - which is exactly what the onboarding permission
     * step and the system settings shortcut do - would not take effect until the next cold start.
     *
     * Safe to call from a lifecycle callback: only the AppOps read happens on the caller's
     * thread, the resolution itself is dispatched to [scope].
     */
    fun refresh() {
        // Only a raw-access state that differs from the last fully successful one is worth disk
        // work. A failed attempt never settles, so this gate cannot latch.
        val rawAccess = hasAllFilesAccess()
        if (rawAccess == settledRawAccess) return

        scope.launch {
            try {
                val notify = baseDirMutex.withLock {
                    val updated = getBaseDir(storagePreferences.baseStorageDirectory().get())
                    if (updated == null) {
                        // Nothing is committed and nothing settles, so the next foreground
                        // retries rather than being locked out with a stale [baseDir].
                        logcat(LogPriority.WARN) { "Storage backend unresolved; keeping ${baseDir?.uri}" }
                        return@withLock false
                    }
                    applyBaseDir(updated, rawAccess)
                }
                if (notify) _changes.send(Unit)
            } catch (e: Throwable) {
                // Never let this cancel [scope]; that would silently kill the preference
                // collector and the `changes` flow for the rest of the process lifetime.
                logcat(LogPriority.ERROR, e) { "Failed to refresh storage backend" }
            }
        }
    }

    /**
     * Commits [candidate] as the storage base, and reports whether consumers must be notified.
     *
     * Every fallible step runs on [candidate] BEFORE a field is written, so a throw leaves both
     * [baseDir] and [settledRawAccess] exactly as they were. That is what makes the transition
     * atomic: a partial failure cannot record itself as handled and lock out later retries.
     *
     * A null [candidate] means the configured location is unusable. That is reflected in
     * [baseDir], but [settledRawAccess] is cleared rather than recorded, so a retry stays
     * possible, and consumers are not notified.
     *
     * Callers must hold [baseDirMutex].
     */
    private fun applyBaseDir(candidate: UniFile?, rawAccess: Boolean): Boolean {
        if (candidate == null) {
            baseDir = null
            settledRawAccess = null
            return false
        }

        prepareBaseDirectories(candidate)

        val previous = baseDir
        baseDir = candidate
        settledRawAccess = rawAccess
        if (candidate.uri == previous?.uri) return false

        logcat { "Storage backend switched: ${previous?.uri} -> ${candidate.uri}" }
        return true
    }

    /**
     * Creates the subdirectories handed out by this class inside [dir].
     *
     * Takes the directory as a parameter instead of reading [baseDir], so a caller can validate a
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
    // KMK <--

    fun getAutomaticBackupsDirectory(): UniFile? {
        return baseDir?.createDirectory(AUTOMATIC_BACKUPS_PATH)
    }

    fun getDownloadsDirectory(): UniFile? {
        return baseDir?.createDirectory(DOWNLOADS_PATH)
    }

    fun getLocalSourceDirectory(): UniFile? {
        return baseDir?.createDirectory(LOCAL_SOURCE_PATH)
    }

    // SY -->
    fun getLogsDirectory(): UniFile? {
        return baseDir?.createDirectory(LOGS_PATH)
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
// KMK <--
private const val AUTOMATIC_BACKUPS_PATH = "autobackup"
private const val DOWNLOADS_PATH = "downloads"
private const val LOCAL_SOURCE_PATH = "local"

// SY -->
private const val LOGS_PATH = "logs"
// SY <--
