package eu.kanade.tachiyomi.util.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import eu.kanade.tachiyomi.BuildConfig
import java.io.File

val Context.cacheImageDir: File
    get() = File(cacheDir, "shared_image")

/**
 * Returns the uri of a file
 *
 * @param context context of application
 */
fun File.getUriCompat(context: Context): Uri {
    return FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", this)
}

/**
 * Copies this file to the given [target] file while marking the file as read-only.
 *
 * @see File.copyTo
 */
fun File.copyAndSetReadOnlyTo(
    target: File,
    overwrite: Boolean = false,
    bufferSize: Int = DEFAULT_BUFFER_SIZE,
): File {
    if (!this.exists()) {
        throw NoSuchFileException(file = this, reason = "The source file doesn't exist.")
    }

    if (target.exists()) {
        if (!overwrite) {
            throw FileAlreadyExistsException(
                file = this,
                other = target,
                reason = "The destination file already exists.",
            )
        } else if (!target.delete()) {
            throw FileAlreadyExistsException(
                file = this,
                other = target,
                reason = "Tried to overwrite the destination, but failed to delete it.",
            )
        }
    }

    if (this.isDirectory) {
        if (!target.mkdirs()) {
            throw FileSystemException(file = this, other = target, reason = "Failed to create target directory.")
        }
    } else {
        target.parentFile?.mkdirs()

        this.inputStream().use { input ->
            target.outputStream().use { output ->
                // Set read-only
                target.setReadOnly()

                input.copyTo(output, bufferSize)
            }
        }
    }

    return target
}

/**
 * Converts a SAF content:// URI to a direct file:// URI when
 * MANAGE_EXTERNAL_STORAGE permission is granted (Android 11+).
 *
 * This avoids creating a persistent dependency on ExternalStorageProvider,
 * which can be killed by the system under memory pressure — causing any app
 * that holds a SAF connection to also be killed.
 */
fun Uri.toDirectFileUriIfPossible(): Uri {
    if (scheme == "file") return this
    if (scheme != "content") return this
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return this
    if (!Environment.isExternalStorageManager()) return this

    return try {
        val docId = try {
            DocumentsContract.getDocumentId(this)
        } catch (_: Exception) {
            DocumentsContract.getTreeDocumentId(this)
        }

        val split = docId.split(":")
        val type = split[0]
        val relativePath = split.getOrNull(1)?.trimEnd('/').orEmpty()

        val absolutePath = when {
            type.equals("primary", ignoreCase = true) -> {
                val base = Environment.getExternalStorageDirectory().absolutePath
                if (relativePath.isEmpty()) base else "$base/$relativePath"
            }
            else -> {
                val base = "/storage/$type"
                if (relativePath.isEmpty()) base else "$base/$relativePath"
            }
        }

        val file = File(absolutePath)
        if (file.canRead()) file.toUri() else this
    } catch (_: Exception) {
        this
    }
}
