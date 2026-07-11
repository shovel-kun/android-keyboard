package org.futo.inputmethod.latin.uix.actions.clipboard

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardArchiveDownloadCoordinatorTest {
    @Test
    fun ingestedArchiveDownloadsPersistsAndReloadsBeforeCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coordinator = ClipboardArchiveDownloadCoordinator(scope) { 20L }
            var liveArchive = pendingArchive()
            var storedArchive: ClipboardLinkArchive? = null
            var reloadedArchive: ClipboardLinkArchive? = null

            coordinator.launch(
                archiveKey = liveArchive.key,
                block = {
                    liveArchive = requireNotNull(
                        reduceArchive(
                            liveArchive,
                            ClipboardArchiveEvent.MediaDownloadSaved(
                                sourceUrl = "https://img/1.jpg",
                                fileName = "one.jpg",
                                mimeType = "image/jpeg",
                                now = 20L
                            )
                        )
                    )
                },
                onFinished = {
                    storedArchive = liveArchive
                    reloadedArchive = storedArchive
                }
            )?.join()

            assertEquals(ClipboardArchiveMediaStatus.Saved, reloadedArchive?.media?.single()?.status)
            assertEquals("one.jpg", reloadedArchive?.media?.single()?.fileName)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun duplicateRetryQueuesOneFollowUpAfterActiveDownloadPersists() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coordinator = ClipboardArchiveDownloadCoordinator(scope)
            val gate = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()

            coordinator.launch(
                archiveKey = "pixiv:1",
                block = {
                    events += "download"
                    gate.await()
                },
                onFinished = { events += "persist" }
            )
            coordinator.queueSourceUrls("pixiv:1", setOf("https://img/1.jpg"))
            val duplicate = coordinator.launch("pixiv:1", block = { events += "duplicate" }, onFinished = {})

            assertNull(duplicate)
            assertEquals(setOf("https://img/1.jpg"), coordinator.queuedSourceUrls("pixiv:1"))
            gate.complete(Unit)
            yield()

            assertEquals(listOf("download", "persist"), events)
            assertFalse(coordinator.isActive("pixiv:1"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun deleteDuringDownloadCancelsWorkAndClearsQueuedSnapshotState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coordinator = ClipboardArchiveDownloadCoordinator(scope)
            val cancelled = CompletableDeferred<Unit>()
            coordinator.queueSourceUrls("twitter:1", setOf("https://img/1.jpg"))
            coordinator.launch(
                archiveKey = "twitter:1",
                block = {
                    try {
                        CompletableDeferred<Unit>().await()
                    } finally {
                        cancelled.complete(Unit)
                    }
                },
                onFinished = {}
            )

            coordinator.cancel("twitter:1")
            cancelled.await()

            assertFalse(coordinator.isActive("twitter:1"))
            assertTrue(coordinator.queuedSourceUrls("twitter:1").isEmpty())
            assertNull(coordinator.progress("twitter:1"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun snapshotsRemainImmutableAcrossQueueAndCooldownUpdates() {
        var now = 10L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coordinator = ClipboardArchiveDownloadCoordinator(scope) { now }
            coordinator.queueSourceUrls("reddit:1", setOf("one"))
            coordinator.setProviderCooldown(
                ClipboardPreviewProviderCooldown(ClipboardPreviewProvider.REDDIT, 20L, "wait")
            )
            val before = coordinator.snapshot()

            coordinator.queueSourceUrls("reddit:1", setOf("two"))
            now = 21L

            assertEquals(setOf("one"), before.queuedSourceUrlsByArchiveKey["reddit:1"])
            assertTrue(ClipboardPreviewProvider.REDDIT in before.cooldownsByProvider)
            assertEquals(setOf("one", "two"), coordinator.queuedSourceUrls("reddit:1"))
            assertNull(coordinator.providerCooldown(ClipboardPreviewProvider.REDDIT))
        } finally {
            scope.cancel()
        }
    }

    private fun pendingArchive() = ClipboardLinkArchive(
        key = "pixiv:1",
        provider = ClipboardPreviewProvider.PIXIV,
        sourceUrl = "https://pixiv.example/1",
        sourceId = "1",
        metadata = ClipboardPreviewMetadata(
            provider = ClipboardPreviewProvider.PIXIV,
            sourceUrl = "https://pixiv.example/1",
            sourceId = "1"
        ),
        media = listOf(ClipboardArchiveMedia("https://img/1.jpg", 0)),
        createdAtEpochMs = 10L,
        updatedAtEpochMs = 10L
    )
}
