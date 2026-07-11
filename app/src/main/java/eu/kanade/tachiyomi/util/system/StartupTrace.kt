package eu.kanade.tachiyomi.util.system

import android.os.Trace

// KMK -->
/**
 * Adds a short, balanced app trace section that is visible in Perfetto and Macrobenchmark traces.
 * Calls are effectively no-ops unless app tracing is enabled by the recorder.
 */
internal inline fun <T> startupTrace(
    sectionName: String,
    block: () -> T,
): T {
    Trace.beginSection("KMK:$sectionName")
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}
// KMK <--
