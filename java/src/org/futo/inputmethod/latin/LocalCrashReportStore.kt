package org.futo.inputmethod.latin

import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

internal const val MAX_CRASH_REPORTS = 10

internal class LocalCrashReportStore(private val directory: File) {
    @Synchronized
    fun save(thread: Thread, error: Throwable, buildDetails: String): File {
        directory.mkdirs()
        val now = System.currentTimeMillis()
        // Keep ordering stable even when crashes happen in the same millisecond or the clock moves back.
        val previousTime = latest()?.name?.substringAfter("crash-")?.substringBefore('-')?.toLong() ?: 0L
        val reportTime = maxOf(now, previousTime + 1)
        val report = File(directory, "crash-$reportTime-${UUID.randomUUID()}.txt")
        val temporary = File(directory, "${report.name}.tmp")
        temporary.bufferedWriter().use { writer ->
            writer.appendLine("Keyboard crash report")
            writer.appendLine("Time: ${Instant.ofEpochMilli(now)}")
            writer.appendLine(buildDetails)
            writer.appendLine("Thread: ${thread.name}")
            writer.appendLine()
            error.printStackTrace(java.io.PrintWriter(writer))
        }
        if(!temporary.renameTo(report)) throw IOException("Could not finish crash report")
        reports().drop(MAX_CRASH_REPORTS).forEach { it.delete() }
        return report
    }

    fun latest(): File? = reports().firstOrNull()

    fun pending(): File? = latest()?.takeUnless {
        val acknowledged = File(directory, "acknowledged")
        acknowledged.exists() && acknowledged.readText() == it.name
    }

    fun acknowledge(report: File) {
        File(directory, "acknowledged").writeText(report.name)
    }

    private fun reports(): List<File> = directory.listFiles()
        .orEmpty().filter { it.name.startsWith("crash-") && it.extension == "txt" }
        .sortedByDescending { it.name }
}

internal class LocalCrashHandler(
    private val store: LocalCrashReportStore,
    private val buildDetails: String,
    private val next: Thread.UncaughtExceptionHandler,
    private val onFailure: (Exception) -> Unit
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            store.save(thread, error, buildDetails)
        } catch(failure: Exception) {
            onFailure(failure)
        } finally {
            // Saving a report must never swallow the crash or prevent Android's normal termination.
            next.uncaughtException(thread, error)
        }
    }
}
