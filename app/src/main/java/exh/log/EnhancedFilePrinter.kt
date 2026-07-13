package exh.log

import com.elvishew.xlog.internal.DefaultsFactory
import com.elvishew.xlog.printer.Printer
import com.elvishew.xlog.printer.file.backup.BackupStrategy
import com.elvishew.xlog.printer.file.naming.FileNameGenerator
import com.hippo.unifile.UniFile
import exh.log.EnhancedFilePrinter.Builder
import java.io.BufferedWriter
import java.io.IOException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import kotlin.time.Duration.Companion.days
import com.elvishew.xlog.flattener.Flattener2 as Flattener

/**
 * Log [Printer] using file system. When print a log, it will print it to the specified file.
 *
 * Use the [Builder] to construct a [EnhancedFilePrinter] object.
 *
 * @param folder The folder path of log file.
 * @param fileNameGenerator the file name generator for log file.
 * @param backupStrategy the backup strategy for log file.
 * @param flattener The flattener when print a log.
 */
@Suppress("unused")
class EnhancedFilePrinter internal constructor(
    folder: UniFile,
    private val fileNameGenerator: FileNameGenerator,
    private val backupStrategy: BackupStrategy,
    private val flattener: Flattener,
) : Printer {

    private var folder: UniFile? = folder
    private val writer = Writer()
    private val writeLock = Any()

    @Volatile
    private var fileLoggingEnabled = false

    private val worker = Worker()

    override fun println(logLevel: Int, tag: String, msg: String) {
        if (!fileLoggingEnabled) return

        val log = LogItem(System.currentTimeMillis(), logLevel, tag, msg)
        if (USE_WORKER) {
            worker.enqueue(log)
        } else {
            doPrintln(log)
        }
    }

    /** Replace the storage backend after the related permission changes. */
    fun updateFolder(folder: UniFile?) {
        synchronized(writeLock) {
            writer.close()
            this.folder = folder
        }
    }

    /** Enable file logging when the application enters the foreground. */
    fun resumeFileLogging() {
        fileLoggingEnabled = true
    }

    /**
     * Stop accepting file logs and synchronously close the current stream.
     *
     * SAF streams keep a stable ContentProvider reference until closed. Keeping one open while
     * the app is cached can make Android kill this process when ExternalStorageProvider dies.
     */
    fun pauseFileLogging() {
        fileLoggingEnabled = false
        worker.clear()
        synchronized(writeLock) {
            writer.close()
        }
    }

    /** Do the real job of writing a log to file. */
    private fun doPrintln(log: LogItem) {
        synchronized(writeLock) {
            // Check again under the lock so queued work cannot reopen the stream after onStop().
            if (!fileLoggingEnabled) return

            val newFileName = fileNameGenerator.generateFileName(log.level, log.timeMillis)
                ?.takeIf { it.isNotBlank() }
                ?: return

            try {
                val currentFolder = folder ?: return
                if (!writer.isOpened || writer.lastFileName != newFileName) {
                    writer.close()
                    cleanLogFilesIfNecessary(currentFolder)
                    val file = currentFolder.findFile(newFileName) ?: currentFolder.createFile(newFileName)
                    if (file == null || !writer.open(file)) return
                }

                val flattenedLog = flattener.flatten(log.timeMillis, log.level, log.tag, log.msg).toString()
                if (!writer.appendLog(flattenedLog)) {
                    writer.close()
                }
            } catch (_: Exception) {
                writer.close()
            }
        }
    }

    private val maxTimeMillis = 7.days.inWholeMilliseconds

    private fun shouldClean(file: UniFile): Boolean {
        val currentTimeMillis = System.currentTimeMillis()
        val lastModified = file.lastModified()
        return currentTimeMillis - lastModified > maxTimeMillis
    }

    /** Clean expired log files. */
    private fun cleanLogFilesIfNecessary(folder: UniFile) {
        folder.listFiles().orEmpty()
            .asSequence()
            .filter { shouldClean(it) }
            .forEach { it.delete() }
    }

    /** Builder for [EnhancedFilePrinter]. */
    class Builder(private val folder: UniFile) {
        var fileNameGenerator: FileNameGenerator? = null
        var backupStrategy: BackupStrategy? = null
        var flattener: Flattener? = null

        fun fileNameGenerator(fileNameGenerator: FileNameGenerator): Builder {
            this.fileNameGenerator = fileNameGenerator
            return this
        }

        fun backupStrategy(backupStrategy: BackupStrategy): Builder {
            this.backupStrategy = backupStrategy
            return this
        }

        fun flattener(flattener: Flattener): Builder {
            this.flattener = flattener
            return this
        }

        fun build(): EnhancedFilePrinter {
            return EnhancedFilePrinter(
                folder,
                fileNameGenerator ?: DefaultsFactory.createFileNameGenerator(),
                backupStrategy ?: DefaultsFactory.createBackupStrategy(),
                flattener ?: DefaultsFactory.createFlattener2(),
            )
        }

        companion object {
            operator fun invoke(folder: UniFile, block: Builder.() -> Unit): EnhancedFilePrinter {
                return Builder(folder).apply(block).build()
            }
        }
    }

    private data class LogItem(
        val timeMillis: Long,
        val level: Int,
        val tag: String,
        val msg: String,
    )

    /** Dispatch file writes from a single worker thread. */
    private inner class Worker : Runnable {
        private val logs: BlockingQueue<LogItem> = LinkedBlockingQueue()

        @Volatile
        private var started = false

        fun enqueue(log: LogItem) {
            logs.offer(log)
            ensureStarted()
        }

        fun clear() {
            logs.clear()
        }

        private fun ensureStarted() {
            synchronized(this) {
                if (started) return
                started = true
                Thread(this, "XLogFilePrinter").start()
            }
        }

        override fun run() {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            } catch (_: Exception) {}
            try {
                while (true) {
                    doPrintln(logs.take())
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                synchronized(this) {
                    started = false
                }
            }
        }
    }

    /** Writer owning the SAF stream and its associated stable provider reference. */
    private inner class Writer {
        var lastFileName: String? = null
            private set

        private var bufferedWriter: BufferedWriter? = null

        val isOpened: Boolean
            get() = bufferedWriter != null

        fun open(file: UniFile): Boolean {
            return try {
                // Append because the writer is intentionally closed whenever the app is backgrounded.
                bufferedWriter = file.openOutputStream(true).bufferedWriter()
                lastFileName = file.name
                true
            } catch (_: Exception) {
                false
            }
        }

        fun close() {
            try {
                bufferedWriter?.close()
            } catch (_: IOException) {
            } finally {
                bufferedWriter = null
                lastFileName = null
            }
        }

        fun appendLog(flattenedLog: String): Boolean {
            val currentWriter = bufferedWriter ?: return false
            return try {
                currentWriter.write(flattenedLog)
                currentWriter.newLine()
                currentWriter.flush()
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    companion object {
        private const val USE_WORKER = true
    }
}
