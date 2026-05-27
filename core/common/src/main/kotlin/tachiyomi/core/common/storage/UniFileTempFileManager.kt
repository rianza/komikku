package tachiyomi.core.common.storage

import android.content.Context
import android.os.Build
import android.os.FileUtils
import com.hippo.unifile.UniFile
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException

class UniFileTempFileManager(
    private val context: Context,
) {

    private val dir = File(context.externalCacheDir ?: context.cacheDir, "tmp")

    fun createTempFile(file: UniFile): File {
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Failed to create temp directory: $dir")
        }

        val inputStream = context.contentResolver.openInputStream(file.uri)
            ?: throw IOException("Failed to open source file: ${file.uri}")

        return inputStream.use { input ->
            val rawPrefix = file.nameWithoutExtension.orEmpty()
            val safePrefix = rawPrefix
                .map { ch ->
                    when {
                        ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' -> ch
                        else -> '_'
                    }
                }
                .joinToString("")
                .take(50)
                .padEnd(3, '_')

            val tempFile = File.createTempFile(safePrefix, null, dir)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tempFile.outputStream().use { out ->
                        FileUtils.copy(input, out)
                    }
                } else {
                    BufferedOutputStream(tempFile.outputStream()).use { out ->
                        val buffer = ByteArray(8192)
                        var count: Int
                        while (input.read(buffer).also { count = it } > 0) {
                            out.write(buffer, 0, count)
                        }
                    }
                }
                return@use tempFile
            } catch (e: IOException) {
                tempFile.delete()
                throw e
            }
        }
    }

    fun deleteTempFiles() {
        dir.deleteRecursively()
    }
}
