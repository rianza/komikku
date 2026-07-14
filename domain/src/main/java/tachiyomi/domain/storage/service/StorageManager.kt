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
import androidx.annotation.RequiresApi
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
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import java.io.File
import android.os.storage.StorageManager as AndroidStorageManager

class StorageManager(
    private val context: Context,
    private val storagePreferences: StoragePreferences,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var allFilesAccess = hasAllFilesAccess()

    @Volatile
    private var baseDir: UniFile? = getBaseDir(storagePreferences.baseStorageDirectory.get())

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .shareIn(scope, SharingStarted.Lazily, 1)

    init {
        storagePreferences.baseStorageDirectory.changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { uri ->
                baseDir = getBaseDir(uri)
                prepareBaseDirectories()
                _changes.send(Unit)
            }
            .launchIn(scope)
    }

    private fun getBaseDir(uri: String): UniFile? {
        return resolveStorageDirectory(context, uri)
    }

    /**
     * Re-resolves the configured storage backend after returning from system settings.
     * This switches between SAF and direct file access when All files access changes,
     * while keeping the persisted SAF URI as the canonical preference value.
     */
    fun refresh(): Boolean {
        val updatedAllFilesAccess = hasAllFilesAccess()
        if (updatedAllFilesAccess == allFilesAccess) return false

        allFilesAccess = updatedAllFilesAccess
        val updatedBaseDir = getBaseDir(storagePreferences.baseStorageDirectory.get())
        if (updatedBaseDir?.uri == baseDir?.uri) return false

        baseDir = updatedBaseDir
        prepareBaseDirectories()
        _changes.trySend(Unit)
        return true
    }

    private fun prepareBaseDirectories() {
        baseDir?.let { parent ->
            parent.createDirectory(AUTOMATIC_BACKUPS_PATH)
            parent.createDirectory(LOCAL_SOURCE_PATH)
            parent.createDirectory(DOWNLOADS_PATH).also {
                DiskUtil.createNoMediaFile(it, context)
            }
        }
    }

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

        private fun hasAllFilesAccess(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        }

        /**
         * Keep SAF as the canonical storage permission, but bypass ExternalStorageProvider
         * at runtime when Android 11+ grants direct shared-storage access. Open descriptors
         * from a content URI hold a stable provider reference until they are closed; using
         * a direct file path avoids cascading client-process death if that provider is killed.
         */
        private fun resolveStorageDirectory(context: Context, uriValue: String): UniFile? {
            return runCatching {
                val uri = uriValue.toUri()
                val directDirectory = uri.toDirectFileDirectory(context)
                    ?.let { UniFile.fromFile(it) }
                    ?.takeIf { it.isAccessibleDirectory }

                directDirectory ?: UniFile.fromUri(context, uri)
                    ?.takeIf { it.isAccessibleDirectory }
            }.getOrNull()
        }

        private fun Uri.toDirectFileDirectory(context: Context): File? {
            if (scheme == "file") {
                val directory = File(path ?: return null)
                if (!directory.exists()) directory.mkdirs()
                return directory.takeIf { it.isAccessibleDirectory }
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                !Environment.isExternalStorageManager() ||
                scheme != "content" ||
                authority != EXTERNAL_STORAGE_PROVIDER_AUTHORITY ||
                !DocumentsContract.isTreeUri(this)
            ) {
                return null
            }

            val documentId = runCatching { DocumentsContract.getTreeDocumentId(this) }
                .getOrNull()
                ?: return null
            val (volumeId, relativePath) = documentId.split(":", limit = 2)
                .let { parts -> parts.first() to parts.getOrElse(1) { "" } }
            val volumeRoot = context.findStorageVolumeRoot(volumeId) ?: return null
            val canonicalRoot = runCatching { volumeRoot.canonicalFile }.getOrNull() ?: return null
            val directory = runCatching {
                if (relativePath.isBlank()) canonicalRoot else File(canonicalRoot, relativePath).canonicalFile
            }.getOrNull() ?: return null

            val rootPath = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
            if (directory != canonicalRoot && !directory.path.startsWith(rootPath)) return null

            return directory.takeIf { it.isAccessibleDirectory }
        }

        @RequiresApi(Build.VERSION_CODES.R)
        private fun Context.findStorageVolumeRoot(volumeId: String): File? {
            if (volumeId.equals("primary", ignoreCase = true)) {
                return Environment.getExternalStorageDirectory()
            }

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
            val uri = storageDirPref.get().toUri()
            if (uri.scheme == "file") {
                uri.path?.let(::File)?.mkdirs()
            }

            UniFile.fromUri(context, uri)?.let {
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

private const val EXTERNAL_STORAGE_PROVIDER_AUTHORITY = "com.android.externalstorage.documents"
private const val AUTOMATIC_BACKUPS_PATH = "autobackup"
private const val DOWNLOADS_PATH = "downloads"
private const val LOCAL_SOURCE_PATH = "local"

// SY -->
private const val LOGS_PATH = "logs"
// SY <--
