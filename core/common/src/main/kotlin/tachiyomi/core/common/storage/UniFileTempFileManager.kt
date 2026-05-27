package tachiyomi.core.common.storage

import android.content.Context
import android.os.Build
import android.os.FileUtils
import com.hippo.unifile.UniFile
import java.io.BufferedOutputStream
import java.io.File

class UniFileTempFileManager(
    private val context: Context,
) {

    private val dir = File(context.externalCacheDir ?: context.cacheDir, "tmp")

    fun createTempFile(file: UniFile): File {
        dir.mkdirs()

        val tempFile = File.createTempFile(
            file.nameWithoutExtension.orEmpty().padEnd(3),
            null,
            dir,
        )

        (context.contentResolver.openInputStream(file.uri)
            ?: throw java.io.IOException("Cannot open input stream for ${file.uri}")).use { input ->
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
        }

        return tempFile
    }

    fun deleteTempFiles() {
        dir.deleteRecursively()
    }
}
