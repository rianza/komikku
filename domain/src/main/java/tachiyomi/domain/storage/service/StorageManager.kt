package tachiyomi.domain.storage.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
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
import tachiyomi.core.common.preference.Preference
import java.io.File

class StorageManager(
    private val context: Context,
    storagePreferences: StoragePreferences,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    private var baseDir: UniFile? = getBaseDir(storagePreferences.baseStorageDirectory().get())

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .shareIn(scope, SharingStarted.Lazily, 1)

    init {
        storagePreferences.baseStorageDirectory().changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { uri ->
                baseDir = getBaseDir(uri)
                baseDir?.let { parent ->
                    parent.createDirectory(AUTOMATIC_BACKUPS_PATH)
                    parent.createDirectory(LOCAL_SOURCE_PATH)
                    parent.createDirectory(DOWNLOADS_PATH).also {
                        DiskUtil.createNoMediaFile(it, context)
                    }
                }
                _changes.send(Unit)
            }
            .launchIn(scope)
    }

    private fun getBaseDir(uri: String): UniFile? {
        val selected = runCatching {
            val parsedUri = uri.toUri()
            if (parsedUri.scheme == "file") {
                parsedUri.toAccessibleFileDirectory()?.let { UniFile.fromFile(it) }
            } else {
                parsedUri.toDirectFileDirectoryIfPossible()?.let { UniFile.fromFile(it) }
                    ?: UniFile.fromUri(context, parsedUri)
                        ?.takeIf { it.isAccessibleDirectory }
            }
        }.getOrNull()

        return selected
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
        /**
         * Extension property to check if a UniFile is an accessible directory.
         * Wrapped in runCatching because some OEM DocumentsProviders throw while
         * resolving stale/revoked SAF URIs.
         */
        val UniFile.isAccessibleDirectory: Boolean
            get() = runCatching { exists() && isDirectory && canWrite() && canRead() }
                .getOrDefault(false)

        /**
         * Check if a directory is accessible.
         *
         * For direct file:// storage (/storage/emulated/0/<app_name>), create the
         * folder if possible. This keeps the default public storage path usable on
         * Android 11+ when All files access is granted.
         */
        fun directoryAccessible(context: Context, uri: String): Boolean {
            return runCatching {
                val parsedUri = uri.toUri()
                if (parsedUri.scheme == "file") {
                    parsedUri.toAccessibleFileDirectory() != null
                } else {
                    parsedUri.toDirectFileDirectoryIfPossible() != null ||
                        UniFile.fromUri(context, parsedUri)?.isAccessibleDirectory == true
                }
            }.getOrDefault(false)
        }

        private fun Uri.toAccessibleFileDirectory(): File? {
            if (scheme != "file") return null
            val file = File(path ?: return null)
            if (!file.exists()) {
                file.mkdirs()
            }

            return file.takeIf { it.exists() && it.isDirectory && it.canRead() && it.canWrite() }
        }

        /**
         * Existing installs may already have a SAF ExternalStorageProvider URI saved.
         * On Android 11+ with All files access, use the equivalent direct file path
         * at runtime to avoid holding a dependency on ExternalStorageProvider.
         */
        private fun Uri.toDirectFileDirectoryIfPossible(): File? {
            if (scheme != "content" || authority != "com.android.externalstorage.documents") {
                return null
            }
            if (!hasManageExternalStoragePermission()) {
                return null
            }

            val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(this) }.getOrNull()
                ?: return null
            val split = treeDocumentId.split(":", limit = 2)
            val volume = split.getOrNull(0).orEmpty()
            val relativePath = split.getOrNull(1).orEmpty()

            val root = when {
                volume.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory()
                volume.isNotBlank() -> File("/storage", volume)
                else -> return null
            }
            val file = if (relativePath.isBlank()) root else File(root, relativePath)
            if (!file.exists()) {
                file.mkdirs()
            }

            return file.takeIf { it.exists() && it.isDirectory && it.canRead() && it.canWrite() }
        }

        fun hasManageExternalStoragePermission(): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        }

        /**
         * Call FilePicker to allow access to storage.
         *
         * Open SAF picker when available. If unavailable, keep the public default
         * storage path; do not silently create Android/data/<package>/files/<app>
         * fallback folders.
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
                    fallbackToDefaultStorage(storageDirPref)
                }
            } catch (_: ActivityNotFoundException) {
                fallbackToDefaultStorage(storageDirPref)
            }
        }

        /**
         * If SAF is unavailable (Android TV, broken file picker implementations,
         * etc.), keep the public default storage preference instead of silently
         * creating an app-specific Android/data fallback directory.
         */
        private fun fallbackToDefaultStorage(
            storageDirPref: Preference<String>,
        ) {
            storageDirPref.set("") // Trigger recompose
            storageDirPref.set(storageDirPref.defaultValue())
        }

        /**
         * Used to check if system is able to open contract [ActivityResultContracts.OpenDocumentTree]
         * by checking if intent [Intent.ACTION_OPEN_DOCUMENT_TREE] is available and not being stub (on Android TV)
         */
        private fun isIntentAvailable(context: Context, intent: Intent): Boolean {
            val packageManager = context.packageManager
            // Android TV: ResolveInfo{c236166 com.android.tv.frameworkpackagestubs/.Stubs$DocumentsStub m=0x108000 userHandle=UserHandle{0}}
            val resolveInfo = packageManager.queryIntentActivities(intent, 0)
            return resolveInfo.any {
                it.activityInfo.packageName != null && it.activityInfo.packageName != "com.android.tv.frameworkpackagestubs"
            }
        }
    }
}

private const val AUTOMATIC_BACKUPS_PATH = "autobackup"
private const val DOWNLOADS_PATH = "downloads"
private const val LOCAL_SOURCE_PATH = "local"

// SY -->
private const val LOGS_PATH = "logs"
// SY <--
