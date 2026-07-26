package org.futo.inputmethod.latin.uix.actions.clipboard

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ClipboardImageTagCoordinatorTest {
    @Test
    fun drainsOneSessionInFifoOrderAndDeduplicatesRequests() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val directory = createTempDirectory().toFile()
        try {
            val first = File(directory, "first.jpg").apply { writeText("first") }
            val second = File(directory, "second.jpg").apply { writeText("second") }
            val completed = CompletableDeferred<Unit>()
            val tagged = mutableListOf<String>()
            var factoryCalls = 0
            var closeCalls = 0
            val coordinator = ClipboardImageTagCoordinator(
                scope = scope,
                taggerFactory = {
                    factoryCalls += 1
                    object : ClipboardImageTagger {
                        override fun tag(
                            file: File,
                            attemptedAtEpochMs: Long
                        ): ClipboardImageTaggingResult = ClipboardImageTaggingResult(
                            modelRevision = ClipboardImageTagModelRevision,
                            attemptedAtEpochMs = attemptedAtEpochMs,
                            tags = listOf(
                                ClipboardImageTag(file.name, 0.9f, ClipboardImageTagCategory.General)
                            )
                        )

                        override fun close() {
                            closeCalls += 1
                        }
                    }
                },
                onResult = { _, result ->
                    tagged += result.tags.single().name
                    if(tagged.size == 2) completed.complete(Unit)
                },
                workerDispatcher = Dispatchers.Unconfined,
                now = { 10L }
            )
            val firstRequest = ClipboardImageTagRequest("archive", 0, first)
            coordinator.enqueue(listOf(firstRequest, firstRequest, ClipboardImageTagRequest("archive", 1, second)))

            completed.await()

            assertEquals(listOf("first.jpg", "second.jpg"), tagged)
            assertEquals(1, factoryCalls)
            assertEquals(1, closeCalls)
            assertEquals(0, coordinator.state.value.remainingCount)
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}
