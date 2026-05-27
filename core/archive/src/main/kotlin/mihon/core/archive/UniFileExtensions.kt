package mihon.core.archive

import android.content.Context
import android.os.ParcelFileDescriptor
import com.hippo.unifile.UniFile

internal fun UniFile.openFileDescriptor(context: Context, mode: String): ParcelFileDescriptor {
    val path = filePath
    if (path != null && path.startsWith("/")) {
        val file = java.io.File(path)
        if (file.exists() && file.canRead()) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
    return context.contentResolver.openFileDescriptor(uri, mode) ?: error("Failed to open file descriptor: ${filePath ?: uri}")
}

fun UniFile.archiveReader(context: Context) = openFileDescriptor(context, "r").use { ArchiveReader(it) }

fun UniFile.epubReader(context: Context) = EpubReader(archiveReader(context))
