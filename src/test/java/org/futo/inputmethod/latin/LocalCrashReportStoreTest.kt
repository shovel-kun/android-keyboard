package org.futo.inputmethod.latin

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalCrashReportStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun capturesFullExceptionChainAndDelegatesOriginalCrash() {
        val store = LocalCrashReportStore(temporary.newFolder())
        val thread = Thread("keyboard-worker")
        val error = IllegalStateException("failed", IllegalArgumentException("root cause"))
        error.addSuppressed(IllegalStateException("suppressed failure"))
        var delegated = false
        LocalCrashHandler(store, "Version: personal-debug", { actualThread, actualError ->
            assertSame(thread, actualThread)
            assertSame(error, actualError)
            delegated = true
        }, { throw AssertionError(it) }).uncaughtException(thread, error)

        assertTrue(delegated)
        val text = store.pending()!!.readText()
        assertTrue(text.contains("Version: personal-debug"))
        assertTrue(text.contains("Thread: keyboard-worker"))
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: root cause"))
        assertTrue(text.contains("Suppressed: java.lang.IllegalStateException: suppressed failure"))
    }

    @Test
    fun diskFailureStillDelegatesOriginalCrash() {
        val store = LocalCrashReportStore(temporary.newFile())
        val error = IllegalStateException("original crash")
        var delegated = false
        var failed = false
        LocalCrashHandler(store, "debug", { _, actualError ->
            assertSame(error, actualError)
            delegated = true
        }, { failed = true }).uncaughtException(Thread.currentThread(), error)
        assertTrue(failed)
        assertTrue(delegated)
    }

    @Test
    fun retainsTenReportsAndOnlyPromptsForUnacknowledgedLatest() {
        val directory = temporary.newFolder()
        repeat(12) { File(directory, "crash-${1000 + it}-test.txt").writeText("older report") }
        File(directory, "crash-9999999999999-incomplete.txt.tmp").writeText("partial report")
        val store = LocalCrashReportStore(directory)
        val latest = store.save(Thread.currentThread(), RuntimeException("latest"), "debug")
        assertEquals(10, directory.listFiles()!!.count { it.extension == "txt" })
        assertEquals(latest, store.pending())
        store.acknowledge(latest)
        assertNull(LocalCrashReportStore(directory).pending())
        assertEquals(latest, store.latest())
        assertTrue(latest.exists())
    }

    @Test
    fun rapidCrashesKeepNewestAndReopenPromptAfterDismissal() {
        val store = LocalCrashReportStore(temporary.newFolder())
        repeat(30) {
            val report = store.save(Thread.currentThread(), RuntimeException("crash $it"), "debug")
            assertEquals(report, store.pending())
            store.acknowledge(report)
            assertNull(store.pending())
        }
    }

    @Test
    fun freshInstallHasNoPendingReport() {
        val store = LocalCrashReportStore(File(temporary.root, "not-created-yet"))
        assertNull(store.latest())
        assertNull(store.pending())
    }
}
