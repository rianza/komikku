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
        dir.mkdirs()

        val inputStream = context.contentResolver.openInputStream(file.uri)
            ?: throw IOException("Failed to open source file: ${file.uri}")

        return inputStream.use { input ->
            val tempFile = File.createTempFile(
                file.nameWithoutExtension.orEmpty().padEnd(3),
                null,
                dir,
            )
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
