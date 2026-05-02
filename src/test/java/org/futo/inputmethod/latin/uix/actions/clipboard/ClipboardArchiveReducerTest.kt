package org.futo.inputmethod.latin.uix.actions.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ClipboardArchiveReducerTest {
    @Test
    fun manifestSeen_createsPendingArchiveForNewMedia() {
        val archive = reduceArchive(
            archive = null,
            event = ClipboardArchiveEvent.ManifestSeen(sampleManifest(), now = 10L)
        )!!

        assertEquals("pixiv:123", archive.key)
        assertEquals(ClipboardLinkArchiveStatus.Pending, archive.status)
        assertEquals(listOf(ClipboardArchiveMediaStatus.Pending), archive.media.map { it.status })
    }

    @Test
    fun mediaDownloadSaved_movesPendingToSaved() {
        val archive = sampleArchive(
            media = listOf(ClipboardArchiveMedia("https://img.example/one.jpg", 0))
        )

        val updated = reduceArchive(
            archive = archive,
            event = ClipboardArchiveEvent.MediaDownloadSaved(
                sourceUrl = "https://img.example/one.jpg",
                fileName = "one.jpg",
                mimeType = "image/jpeg",
                now = 20L
            )
        )!!

        assertEquals(ClipboardLinkArchiveStatus.Complete, updated.status)
        assertEquals(ClipboardArchiveMediaStatus.Saved, updated.media.single().status)
        assertEquals("one.jpg", updated.media.single().fileName)
    }

    @Test
    fun mediaDownloadFailure_doesNotDowngradeSavedMedia() {
        val archive = sampleArchive(
            media = listOf(savedMedia())
        )

        val failed = reduceArchive(
            archive = archive,
            event = ClipboardArchiveEvent.MediaDownloadFailed(
                sourceUrl = "https://img.example/one.jpg",
                now = 20L,
                failureDetail = "network failed"
            )
        )!!
        val skipped = reduceArchive(
            archive = archive,
            event = ClipboardArchiveEvent.MediaSkippedTooLarge(
                sourceUrl = "https://img.example/one.jpg",
                now = 30L,
                failureDetail = "too large"
            )
        )!!

        assertEquals(ClipboardArchiveMediaStatus.Saved, failed.media.single().status)
        assertEquals("one.jpg", failed.media.single().fileName)
        assertEquals(ClipboardArchiveMediaStatus.Saved, skipped.media.single().status)
        assertEquals("one.jpg", skipped.media.single().fileName)
    }

    @Test
    fun mediaFailures_storeDetailsAndSuccessClearsThem() {
        val archive = sampleArchive(
            media = listOf(ClipboardArchiveMedia("https://img.example/one.jpg", 0))
        )

        val failed = reduceArchive(
            archive = archive,
            event = ClipboardArchiveEvent.MediaDownloadFailed(
                sourceUrl = "https://img.example/one.jpg",
                now = 20L,
                failureDetail = "java.io.IOException: timeout"
            )
        )!!
        val saved = reduceArchive(
            archive = failed,
            event = ClipboardArchiveEvent.MediaDownloadSaved(
                sourceUrl = "https://img.example/one.jpg",
                fileName = "one.jpg",
                mimeType = "image/jpeg",
                now = 30L
            )
        )!!

        assertEquals("java.io.IOException: timeout", failed.media.single().failureDetail)
        assertEquals(null, saved.media.single().failureDetail)
    }

    @Test
    fun skippedTooLarge_isRetryableAndKeepsFailureDetails() {
        val archive = sampleArchive(
            media = listOf(ClipboardArchiveMedia("https://img.example/large.jpg", 0))
        )

        val skipped = reduceArchive(
            archive = archive,
            event = ClipboardArchiveEvent.MediaSkippedTooLarge(
                sourceUrl = "https://img.example/large.jpg",
                now = 20L,
                failureDetail = "Preview media exceeded 50000000 bytes"
            )
        )!!

        assertEquals(ClipboardArchiveMediaStatus.SkippedTooLarge, skipped.media.single().status)
        assertEquals("Preview media exceeded 50000000 bytes", skipped.media.single().failureDetail)
        assertEquals(listOf("https://img.example/large.jpg"), skipped.retryableMedia().map { it.sourceUrl })
        assertEquals(emptyList<String>(), skipped.autoDownloadableMedia().map { it.sourceUrl })
    }

    @Test
    fun manifestUrlChurn_preservesSavedMediaBySourceIndex() {
        val archive = sampleArchive(
            media = listOf(savedMedia(sourceUrl = "https://img.example/old.jpg"))
        )

        val updated = reduceArchive(
            archive = archive,
            event = ClipboardArchiveEvent.ManifestSeen(
                manifest = sampleManifest(url = "https://img.example/new.jpg"),
                now = 20L
            )
        )!!

        assertEquals(1, updated.media.size)
        assertEquals("https://img.example/new.jpg", updated.media.single().sourceUrl)
        assertEquals(ClipboardArchiveMediaStatus.Saved, updated.media.single().status)
        assertEquals("one.jpg", updated.media.single().fileName)
    }

    @Test
    fun manifestWithNewSourceIndex_addsOnlyNewPendingMedia() {
        val archive = sampleArchive(
            media = listOf(savedMedia())
        )

        val updated = reduceArchive(
            archive = archive,
            event = ClipboardArchiveEvent.ManifestSeen(
                manifest = sampleManifest(
                    mediaItems = listOf(
                        ClipboardLinkPreviewMedia("https://img.example/one-refreshed.jpg", 0, "image/jpeg"),
                        ClipboardLinkPreviewMedia("https://img.example/two.jpg", 1, "image/jpeg")
                    )
                ),
                now = 20L
            )
        )!!

        assertEquals(listOf(0, 1), updated.media.map { it.sourceIndex })
        assertEquals(
            listOf(ClipboardArchiveMediaStatus.Saved, ClipboardArchiveMediaStatus.Pending),
            updated.media.map { it.status }
        )
        assertEquals(listOf("one.jpg"), updated.savedPreviewMedia().map { it.fileName })
    }

    @Test
    fun diskReconcile_isOnlyReducerPathFromSavedToMissing() {
        val archive = sampleArchive(
            media = listOf(savedMedia())
        )

        val refreshed = reduceArchive(
            archive = archive,
            event = ClipboardArchiveEvent.ManifestSeen(sampleManifest(url = "https://img.example/refreshed.jpg"), 20L)
        )!!
        val reconciledPresent = reduceArchive(
            archive = archive,
            event = ClipboardArchiveEvent.DiskReconciled(existingFileNames = setOf("one.jpg"), now = 30L)
        )!!
        val reconciledMissing = reduceArchive(
            archive = archive,
            event = ClipboardArchiveEvent.DiskReconciled(existingFileNames = emptySet(), now = 40L)
        )!!

        assertEquals(ClipboardArchiveMediaStatus.Saved, refreshed.media.single().status)
        assertEquals(ClipboardArchiveMediaStatus.Saved, reconciledPresent.media.single().status)
        assertEquals(ClipboardArchiveMediaStatus.Missing, reconciledMissing.media.single().status)
    }

    @Test
    fun unavailableManifest_doesNotDowngradeSavedArchive() {
        val archive = sampleArchive(
            media = listOf(savedMedia())
        ).copy(
            metadata = ClipboardPreviewMetadata(
                provider = ClipboardPreviewProvider.PIXIV,
                sourceUrl = "https://www.phixiv.net/en/artworks/123",
                sourceId = "123",
                title = "Original title",
                bodyText = "Original archived body",
                authorName = "Original artist"
            )
        )

        val updated = reduceArchive(
            archive = archive,
            event = ClipboardArchiveEvent.ManifestSeen(
                manifest = ClipboardLinkPreviewManifest(
                    snippet = "This post is from a suspended account.",
                    mediaItems = emptyList(),
                    metadata = ClipboardPreviewMetadata(
                        provider = ClipboardPreviewProvider.PIXIV,
                        sourceUrl = "https://www.phixiv.net/en/artworks/123",
                        sourceId = "123",
                        title = "This post is from a suspended account.",
                        bodyText = "This post is from a suspended account.",
                        authorName = "Not found"
                    )
                ),
                now = 50L
            )
        )!!

        assertEquals(listOf(ClipboardArchiveMediaStatus.Saved), updated.media.map { it.status })
        assertEquals(listOf("one.jpg"), updated.savedPreviewMedia().map { it.fileName })
        assertEquals("Original title", updated.metadata?.title)
        assertEquals("Original archived body", updated.metadata?.bodyText)
        assertEquals("Original artist", updated.metadata?.authorName)
        assertEquals(true, updated.providerManifestAvailable)
    }

    @Test
    fun unavailableManifest_doesNotCreateNewArchive() {
        val archive = reduceArchive(
            archive = null,
            event = ClipboardArchiveEvent.ManifestSeen(
                manifest = ClipboardLinkPreviewManifest(
                    snippet = "This post is unavailable.",
                    mediaItems = emptyList(),
                    metadata = ClipboardPreviewMetadata(
                        provider = ClipboardPreviewProvider.TWITTER,
                        sourceUrl = "https://x.com/futo/status/123",
                        sourceId = "123",
                        bodyText = "This post is unavailable."
                    )
                ),
                now = 50L
            )
        )

        assertEquals(null, archive)
    }

    @Test
    fun reducerNormalizesDuplicateSourceIndexes() {
        val archive = sampleArchive(
            media = listOf(
                savedMedia(fileName = "one.jpg"),
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/duplicate.jpg",
                    sourceIndex = 0,
                    status = ClipboardArchiveMediaStatus.Pending
                )
            )
        )

        val updated = reduceArchive(
            archive = archive,
            event = ClipboardArchiveEvent.DiskReconciled(existingFileNames = setOf("one.jpg"), now = 20L)
        )!!

        assertEquals(1, updated.media.size)
        assertEquals(0, updated.media.single().sourceIndex)
        assertEquals(ClipboardArchiveMediaStatus.Saved, updated.media.single().status)
        assertEquals("one.jpg", updated.media.single().fileName)
    }

    @Test
    fun fallbackArchiveFillsMissingSourceIndexesWithoutDuplicates() {
        val entry = ClipboardEntry(
            timestamp = 1L,
            pinned = false,
            text = "https://www.pixiv.net/artworks/123",
            uri = null,
            mimeTypes = listOf("text/plain"),
            previewMediaFiles = listOf(
                ClipboardPreviewMedia(
                    fileName = "second.jpg",
                    sourceIndex = 1,
                    mimeType = "image/jpeg"
                )
            )
        )
        val archive = newFallbackArchiveFromEntry(
            entry = entry,
            metadata = ClipboardPreviewMetadata(
                provider = ClipboardPreviewProvider.PIXIV,
                sourceUrl = "https://www.phixiv.net/en/artworks/123",
                sourceId = "123",
                imageCount = 2
            ),
            savedMedia = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/second.jpg",
                    sourceIndex = 1,
                    mimeType = "image/jpeg",
                    fileName = "second.jpg",
                    status = ClipboardArchiveMediaStatus.Saved
                )
            ),
            now = 10L
        )!!

        assertEquals(listOf(0, 1), archive.media.map { it.sourceIndex })
        assertEquals(
            listOf(ClipboardArchiveMediaStatus.Missing, ClipboardArchiveMediaStatus.Saved),
            archive.media.map { it.status }
        )
    }

    @Test
    fun fallbackArchiveWithoutSavedMediaCreatesRetryablePlaceholder() {
        val entry = ClipboardEntry(
            timestamp = 1L,
            pinned = false,
            text = "https://www.pixiv.net/artworks/123",
            uri = null,
            mimeTypes = listOf("text/plain")
        )
        val archive = newFallbackArchiveFromEntry(
            entry = entry,
            metadata = ClipboardPreviewMetadata(
                provider = ClipboardPreviewProvider.PIXIV,
                sourceUrl = "https://www.phixiv.net/en/artworks/123",
                sourceId = "123"
            ),
            savedMedia = emptyList(),
            now = 10L
        )!!

        assertEquals(false, archive.providerManifestAvailable)
        assertEquals(listOf(0), archive.media.map { it.sourceIndex })
        assertEquals(listOf(ClipboardArchiveMediaStatus.Missing), archive.media.map { it.status })
        assertEquals(listOf("https://www.phixiv.net/en/artworks/123"), archive.retryableMedia().map { it.sourceUrl })
    }

    @Test
    fun importedArchive_preservesSavedMediaOverPendingOrFailedCopies() {
        val existing = sampleArchive(
            media = listOf(savedMedia())
        )
        val incoming = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/one-imported.jpg",
                    sourceIndex = 0,
                    status = ClipboardArchiveMediaStatus.Failed
                ),
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/two.jpg",
                    sourceIndex = 1,
                    status = ClipboardArchiveMediaStatus.Pending
                )
            )
        )

        val updated = reduceArchive(
            archive = existing,
            event = ClipboardArchiveEvent.ImportedArchive(incoming)
        )!!

        assertEquals(listOf(0, 1), updated.media.map { it.sourceIndex })
        assertEquals(
            listOf(ClipboardArchiveMediaStatus.Saved, ClipboardArchiveMediaStatus.Pending),
            updated.media.map { it.status }
        )
        assertEquals("one.jpg", updated.media.first().fileName)
    }

    @Test
    fun importedArchiveWithoutMediaCreatesRetryablePlaceholder() {
        val incoming = sampleArchive(media = emptyList())

        val updated = reduceArchive(
            archive = null,
            event = ClipboardArchiveEvent.ImportedArchive(incoming)
        )!!

        assertEquals(false, updated.providerManifestAvailable)
        assertEquals(ClipboardLinkArchiveStatus.Failed, updated.status)
        assertEquals(listOf(ClipboardArchiveMediaStatus.Missing), updated.media.map { it.status })
        assertEquals(listOf(incoming.sourceUrl), updated.retryableMedia().map { it.sourceUrl })
    }

    @Test
    fun savedMediaWithoutFileNameIsNormalizedAwayFromSaved() {
        val archive = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/broken.jpg",
                    sourceIndex = 0,
                    status = ClipboardArchiveMediaStatus.Saved
                )
            )
        )

        val updated = reduceArchive(
            archive = archive,
            event = ClipboardArchiveEvent.DiskReconciled(existingFileNames = emptySet(), now = 20L)
        )!!

        assertEquals(ClipboardArchiveMediaStatus.Missing, updated.media.single().status)
        assertNotNull(updated.media.single().lastAttemptAtEpochMs)
    }

    private fun sampleManifest(
        url: String = "https://img.example/one.jpg",
        mediaItems: List<ClipboardLinkPreviewMedia> = listOf(
            ClipboardLinkPreviewMedia(url, 0, "image/jpeg")
        )
    ) = ClipboardLinkPreviewManifest(
        snippet = "remote",
        mediaItems = mediaItems,
        metadata = ClipboardPreviewMetadata(
            provider = ClipboardPreviewProvider.PIXIV,
            sourceUrl = "https://www.phixiv.net/en/artworks/123",
            sourceId = "123"
        )
    )

    private fun sampleArchive(
        media: List<ClipboardArchiveMedia>
    ) = ClipboardLinkArchive(
        key = "pixiv:123",
        provider = ClipboardPreviewProvider.PIXIV,
        sourceUrl = "https://www.phixiv.net/en/artworks/123",
        sourceId = "123",
        metadata = ClipboardPreviewMetadata(
            provider = ClipboardPreviewProvider.PIXIV,
            sourceUrl = "https://www.phixiv.net/en/artworks/123",
            sourceId = "123"
        ),
        media = media,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L
    )

    private fun savedMedia(
        sourceUrl: String = "https://img.example/one.jpg",
        fileName: String = "one.jpg"
    ) = ClipboardArchiveMedia(
        sourceUrl = sourceUrl,
        sourceIndex = 0,
        mimeType = "image/jpeg",
        fileName = fileName,
        status = ClipboardArchiveMediaStatus.Saved
    )
}
