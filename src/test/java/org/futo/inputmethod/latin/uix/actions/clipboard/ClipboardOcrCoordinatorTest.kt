package org.futo.inputmethod.latin.uix.actions.clipboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ClipboardOcrCoordinatorTest {
    @Test
    fun cancelAll_closesSessionAndDiscardsResultsBeforeStorageReplacement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val directory = createTempDirectory().toFile()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val file = File(directory, "image.jpg").apply { writeText("image") }
            var closed = 0
            var published = 0
            val coordinator = ClipboardOcrCoordinator(
                scope,
                workerDispatcher = Dispatchers.Default.limitedParallelism(1),
                factory = {
                    object : ClipboardOcr {
                        override fun extract(file: File, input: ClipboardOcrInput, attemptedAtEpochMs: Long): ClipboardOcrResult {
                            started.countDown()
                            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            return ClipboardOcrResult(input, ClipboardOcrModelRevision, attemptedAtEpochMs)
                        }
                        override fun close() { closed++ }
                    }
                },
                onResult = { _, _ -> published++ }
            )
            coordinator.enqueue(listOf(ClipboardOcrRequest("archive", 0, ClipboardOcrInput("url", file.name, 1L), file)))
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val cancelling = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { coordinator.cancelAll() }
            release.countDown()
            cancelling.join()
            assertEquals(1, closed)
            assertEquals(0, published)
            assertEquals(0, coordinator.requests.value.size)
        } finally {
            release.countDown()
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}
