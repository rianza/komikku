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

    private val dir = File(context.externalCacheDir, "tmp")

    fun createTempFile(file: UniFile): File {
        dir.mkdirs()

        val tempFile = File.createTempFile(
            file.nameWithoutExtension.orEmpty().padEnd(3), // Prefix must be 3+ chars
            null,
            dir,
        )

        context.contentResolver.openInputStream(file.uri)!!.use { input ->
            tempFile.outputStream().use { out ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    FileUtils.copy(input, out)
                } else {
                    BufferedOutputStream(out).use { tmpOut ->
                        val buffer = ByteArray(8192)
                        var count: Int
                        while (input.read(buffer).also { count = it } > 0) {
                            tmpOut.write(buffer, 0, count)
                        }
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
