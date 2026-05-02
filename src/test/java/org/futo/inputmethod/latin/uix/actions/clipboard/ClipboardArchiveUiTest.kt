package org.futo.inputmethod.latin.uix.actions.clipboard

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.futo.inputmethod.latin.R
import java.io.File
import kotlin.io.path.createTempDirectory

class ClipboardArchiveUiTest {
    @Test
    fun sortedClipboardArchives_usesNewestArchiveFirst() {
        val oldest = sampleArchive(
            media = emptyList()
        ).copy(key = "pixiv:old", updatedAtEpochMs = 10L, createdAtEpochMs = 10L)
        val newest = sampleArchive(
            media = emptyList()
        ).copy(key = "pixiv:new", updatedAtEpochMs = 30L, createdAtEpochMs = 20L)
        val middle = sampleArchive(
            media = emptyList()
        ).copy(key = "twitter:middle", updatedAtEpochMs = 20L, createdAtEpochMs = 30L)

        assertEquals(
            listOf("pixiv:new", "twitter:middle", "pixiv:old"),
            sortedClipboardArchives(listOf(oldest, newest, middle)).map { it.key }
        )
    }

    @Test
    fun archiveFilters_matchProviderAndStatus() {
        val completePixiv = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/one.jpg",
                    sourceIndex = 0,
                    fileName = "one.jpg",
                    status = ClipboardArchiveMediaStatus.Saved
                )
            )
        )
        val partialTwitter = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/one.jpg",
                    sourceIndex = 0,
                    fileName = "one.jpg",
                    status = ClipboardArchiveMediaStatus.Saved
                ),
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/two.jpg",
                    sourceIndex = 1,
                    status = ClipboardArchiveMediaStatus.Failed
                )
            )
        ).copy(
            key = "twitter:456",
            provider = ClipboardPreviewProvider.TWITTER
        )
        val pendingTwitter = partialTwitter.copy(
            key = "twitter:789",
            media = listOf(ClipboardArchiveMedia("https://img.example/pending.jpg", 0))
        )

        assertTrue(completePixiv.matchesProviderFilter(ClipboardArchiveProviderFilter.Pixiv))
        assertFalse(completePixiv.matchesProviderFilter(ClipboardArchiveProviderFilter.Twitter))
        assertTrue(partialTwitter.matchesStatusFilter(ClipboardArchiveStatusFilter.Partial))
        assertTrue(partialTwitter.matchesStatusFilter(ClipboardArchiveStatusFilter.FailedInProgress))
        assertTrue(pendingTwitter.matchesStatusFilter(ClipboardArchiveStatusFilter.FailedInProgress))
        assertFalse(completePixiv.matchesStatusFilter(ClipboardArchiveStatusFilter.FailedInProgress))
    }

    @Test
    fun galleryItems_preserveExpectedOrderAndPlaceholders() {
        val archiveDir = createTempDirectory().toFile()
        try {
            File(archiveDir, "one.jpg").writeText("image")
            val archive = sampleArchive(
                media = listOf(
                    ClipboardArchiveMedia(
                        sourceUrl = "https://img.example/two.jpg",
                        sourceIndex = 1,
                        status = ClipboardArchiveMediaStatus.Failed
                    ),
                    ClipboardArchiveMedia(
                        sourceUrl = "https://img.example/one.jpg",
                        sourceIndex = 0,
                        fileName = "one.jpg",
                        status = ClipboardArchiveMediaStatus.Saved
                    )
                )
            )

            val items = archive.galleryItems(archiveDir)

            assertEquals(listOf(0, 1), items.map { it.media.sourceIndex })
            assertTrue(items.first().isShareable)
            assertFalse(items.last().isShareable)
            assertEquals(ClipboardArchiveDisplayStatus.Retry, items.last().displayStatus)
        } finally {
            archiveDir.deleteRecursively()
        }
    }

    @Test
    fun archiveDisplayStatus_usesPlainUserFacingStates() {
        val complete = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/one.jpg",
                    sourceIndex = 0,
                    fileName = "one.jpg",
                    status = ClipboardArchiveMediaStatus.Saved
                )
            )
        )
        val retryable = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/one.jpg",
                    sourceIndex = 0,
                    status = ClipboardArchiveMediaStatus.Failed
                )
            )
        )
        val skipped = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/one.jpg",
                    sourceIndex = 0,
                    status = ClipboardArchiveMediaStatus.SkippedTooLarge
                )
            )
        )
        val pending = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/one.jpg",
                    sourceIndex = 0,
                    status = ClipboardArchiveMediaStatus.Pending
                )
            )
        )

        assertEquals(ClipboardArchiveDisplayStatus.Complete, complete.displayStatus())
        assertEquals(ClipboardArchiveDisplayStatus.Retry, retryable.displayStatus())
        assertEquals(ClipboardArchiveDisplayStatus.Retry, skipped.displayStatus())
        assertEquals(ClipboardArchiveDisplayStatus.Waiting, pending.displayStatus())
        assertEquals(ClipboardArchiveDisplayStatus.Saving, pending.displayStatus(loading = true))
    }

    @Test
    fun failureSummaryLabels_areConciseAndDoNotExposeRawDetails() {
        val failed = ClipboardArchiveMedia(
            sourceUrl = "https://img.example/one.jpg",
            sourceIndex = 0,
            status = ClipboardArchiveMediaStatus.Failed,
            failureDetail = "java.io.IOException: full raw timeout"
        )
        val skipped = failed.copy(status = ClipboardArchiveMediaStatus.SkippedTooLarge)
        val missing = failed.copy(status = ClipboardArchiveMediaStatus.Missing)

        assertEquals(R.string.clipboard_history_archive_failure_download_failed, failed.failureSummaryLabelRes())
        assertEquals(R.string.clipboard_history_archive_failure_too_large, skipped.failureSummaryLabelRes())
        assertEquals(R.string.clipboard_history_archive_failure_file_missing, missing.failureSummaryLabelRes())
        assertEquals(
            R.string.clipboard_history_archive_failure_download_failed,
            sampleArchive(listOf(failed)).failureSummaryLabelRes()
        )
    }

    @Test
    fun archiveDownloadProgress_reportsPercentOnlyForKnownTotals() {
        val known = ClipboardArchiveDownloadProgress(
            archiveKey = "pixiv:123",
            sourceUrl = "https://img.example/one.jpg",
            sourceIndex = 0,
            completedBytes = 42L,
            totalBytes = 100L,
            savedCount = 3,
            expectedCount = 8
        )
        val unknown = known.copy(totalBytes = null)

        assertEquals(42, known.progressPercent())
        assertEquals(null, unknown.progressPercent())
    }

    @Test
    fun archiveMediaShareMimeType_prefersActualVideoFileOverStaleImageMetadata() {
        val staleGifMetadata = ClipboardArchiveMedia(
            sourceUrl = "https://example.com/animated.gif",
            sourceIndex = 0,
            mimeType = "image/gif",
            fileName = "preview_hash.mp4",
            status = ClipboardArchiveMediaStatus.Saved
        )

        val mimeType = archiveMediaShareMimeType(
            media = staleGifMetadata,
            targetFile = File("preview_hash.mp4")
        )

        assertEquals("video/mp4", mimeType)
    }

    @Test
    fun archiveDownloadItems_ordersActiveBeforeFailedAndExcludesComplete() {
        val complete = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/complete.jpg",
                    sourceIndex = 0,
                    fileName = "complete.jpg",
                    status = ClipboardArchiveMediaStatus.Saved
                )
            )
        ).copy(key = "pixiv:complete")
        val failed = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/failed.jpg",
                    sourceIndex = 0,
                    status = ClipboardArchiveMediaStatus.Failed,
                    lastAttemptAtEpochMs = 20L,
                    failureDetail = "raw timeout"
                )
            )
        ).copy(key = "pixiv:failed")
        val active = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/active.jpg",
                    sourceIndex = 0,
                    status = ClipboardArchiveMediaStatus.Pending
                )
            )
        ).copy(key = "pixiv:active")
        val progress = ClipboardArchiveDownloadProgress(
            archiveKey = active.key,
            sourceUrl = "https://img.example/active.jpg",
            sourceIndex = 0,
            completedBytes = 50L,
            totalBytes = 100L,
            savedCount = 0,
            expectedCount = 1
        )

        val items = archiveDownloadItems(
            archives = listOf(complete, failed, active),
            progressByArchiveKey = mapOf(active.key to progress),
            loadingArchiveKeys = setOf(active.key)
        )

        assertEquals(listOf(active.key, failed.key), items.map { it.archiveKey })
        assertEquals(ClipboardArchiveDownloadRowStatus.Active, items.first().status)
        assertTrue(items.first().canStop)
        assertFalse(items.first().canRetry)
        assertEquals(ClipboardArchiveDownloadRowStatus.Failed, items.last().status)
        assertFalse(items.last().canStop)
        assertTrue(items.last().canRetry)
    }

    @Test
    fun archiveDownloadItems_ignoresCurrentEmptyArchive() {
        val archive = sampleArchive(media = emptyList())

        val items = archiveDownloadItems(
            archives = listOf(archive),
            progressByArchiveKey = emptyMap(),
            loadingArchiveKeys = emptySet()
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun archiveDownloadItems_includesLegacyEmptyArchivePlaceholder() {
        val archive = sampleArchive(media = emptyList()).withLegacyRetryablePlaceholderIfEmpty()

        val item = archiveDownloadItems(
            archives = listOf(archive),
            progressByArchiveKey = emptyMap(),
            loadingArchiveKeys = emptySet()
        ).single()

        assertEquals(archive.key, item.archiveKey)
        assertEquals(archive.sourceUrl, item.sourceUrl)
        assertEquals(R.string.clipboard_history_archive_failure_file_missing, item.failureSummaryLabelRes)
        assertTrue(item.canRetry)
    }

    @Test
    fun savedMediaMissingOnDiskAppearsRetryableInGalleryAndDownloads() {
        val archiveDir = createTempDirectory().toFile()
        try {
            val archive = sampleArchive(
                media = listOf(
                    ClipboardArchiveMedia(
                        sourceUrl = "https://img.example/missing.jpg",
                        sourceIndex = 0,
                        fileName = "missing.jpg",
                        status = ClipboardArchiveMediaStatus.Saved
                    )
                )
            )

            val galleryItem = archive.galleryItems(archiveDir).single()
            val downloadItem = archiveDownloadItems(
                archives = listOf(archive),
                progressByArchiveKey = emptyMap(),
                loadingArchiveKeys = emptySet(),
                archiveDir = archiveDir
            ).single()

            assertEquals(ClipboardArchiveMediaStatus.Missing, galleryItem.media.status)
            assertEquals(ClipboardArchiveDisplayStatus.Retry, galleryItem.displayStatus)
            assertFalse(galleryItem.isShareable)
            assertEquals(ClipboardArchiveDownloadRowStatus.Failed, downloadItem.status)
            assertEquals(R.string.clipboard_history_archive_failure_file_missing, downloadItem.failureSummaryLabelRes)
            assertTrue(downloadItem.canRetry)
        } finally {
            archiveDir.deleteRecursively()
        }
    }

    @Test
    fun savedMediaPresentOnDiskStaysOutOfDownloads() {
        val archiveDir = createTempDirectory().toFile()
        try {
            File(archiveDir, "complete.jpg").writeText("image")
            val archive = sampleArchive(
                media = listOf(
                    ClipboardArchiveMedia(
                        sourceUrl = "https://img.example/complete.jpg",
                        sourceIndex = 0,
                        fileName = "complete.jpg",
                        status = ClipboardArchiveMediaStatus.Saved
                    )
                )
            )

            val items = archiveDownloadItems(
                archives = listOf(archive),
                progressByArchiveKey = emptyMap(),
                loadingArchiveKeys = emptySet(),
                archiveDir = archiveDir
            )

            assertEquals(emptyList<ClipboardArchiveDownloadListItem>(), items)
        } finally {
            archiveDir.deleteRecursively()
        }
    }

    @Test
    fun archiveDownloadItems_includesTooLargeWithoutRawFailureDetails() {
        val archive = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/large.jpg",
                    sourceIndex = 0,
                    status = ClipboardArchiveMediaStatus.SkippedTooLarge,
                    failureDetail = "Raw size detail that should stay out of the row"
                )
            )
        )

        val item = archiveDownloadItems(
            archives = listOf(archive),
            progressByArchiveKey = emptyMap(),
            loadingArchiveKeys = emptySet()
        ).single()

        assertEquals(R.string.clipboard_history_archive_failure_too_large, item.failureSummaryLabelRes)
        assertTrue(item.canRetry)
    }

    @Test
    fun archiveDownloadItems_disablesRetryDuringProviderCooldown() {
        val archive = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/failed.jpg",
                    sourceIndex = 0,
                    status = ClipboardArchiveMediaStatus.Failed,
                    failureDetail = "HTTP 429 Too Many Requests"
                )
            )
        )
        val retryAfter = 123_456L

        val item = archiveDownloadItems(
            archives = listOf(archive),
            progressByArchiveKey = emptyMap(),
            loadingArchiveKeys = emptySet(),
            cooldownsByProvider = mapOf(
                ClipboardPreviewProvider.PIXIV to ClipboardPreviewProviderCooldown(
                    provider = ClipboardPreviewProvider.PIXIV,
                    retryAfterEpochMs = retryAfter,
                    detail = "rate limited"
                )
            )
        ).single()

        assertEquals(R.string.clipboard_history_archive_failure_rate_limited, item.failureSummaryLabelRes)
        assertEquals(retryAfter, item.retryAvailableAtEpochMs)
        assertFalse(item.canRetry)
    }

    @Test
    fun archiveDownloadActionCount_matchesFullRowsForRetryableStates() {
        val complete = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/complete.jpg",
                    sourceIndex = 0,
                    fileName = "complete.jpg",
                    status = ClipboardArchiveMediaStatus.Saved
                )
            )
        ).copy(key = "pixiv:complete")
        val savedMissing = complete.copy(
            key = "pixiv:missing",
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/missing.jpg",
                    sourceIndex = 0,
                    fileName = "missing.jpg",
                    status = ClipboardArchiveMediaStatus.Saved
                )
            )
        )
        val failed = complete.copy(
            key = "pixiv:failed",
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/failed.jpg",
                    sourceIndex = 0,
                    status = ClipboardArchiveMediaStatus.Failed
                )
            )
        )
        val tooLarge = complete.copy(
            key = "pixiv:large",
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/large.jpg",
                    sourceIndex = 0,
                    status = ClipboardArchiveMediaStatus.SkippedTooLarge
                )
            )
        )
        val cooldownBlocked = failed.copy(key = "pixiv:cooldown")
        val active = complete.copy(
            key = "pixiv:active",
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/active.jpg",
                    sourceIndex = 0,
                    status = ClipboardArchiveMediaStatus.Pending
                )
            )
        )
        val archives = listOf(complete, savedMissing, failed, tooLarge, cooldownBlocked, active)
        val existingArchiveFileNames = setOf("complete.jpg")
        val progress = ClipboardArchiveDownloadProgress(
            archiveKey = active.key,
            sourceUrl = "https://img.example/active.jpg",
            sourceIndex = 0,
            completedBytes = 20L,
            totalBytes = 100L,
            savedCount = 0,
            expectedCount = 1
        )

        val rows = archiveDownloadItems(
            archives = archives,
            progressByArchiveKey = mapOf(active.key to progress),
            loadingArchiveKeys = setOf(active.key),
            cooldownsByProvider = mapOf(
                ClipboardPreviewProvider.PIXIV to ClipboardPreviewProviderCooldown(
                    provider = ClipboardPreviewProvider.PIXIV,
                    retryAfterEpochMs = 123_456L,
                    detail = "rate limited"
                )
            ),
            existingArchiveFileNames = existingArchiveFileNames
        )

        assertEquals(rows.size, archiveDownloadActionCount(archives, existingArchiveFileNames))
    }

    @Test
    fun archiveMetadataDetailsText_includesCoreArchiveAndSavedMediaFields() {
        val archive = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/complete.jpg",
                    sourceIndex = 0,
                    mimeType = "image/jpeg",
                    fileName = "complete.jpg",
                    status = ClipboardArchiveMediaStatus.Saved,
                    lastAttemptAtEpochMs = 42L
                )
            )
        ).copy(
            key = "pixiv:complete",
            createdAtEpochMs = 10L,
            updatedAtEpochMs = 20L,
            metadata = sampleMetadata().copy(title = "Archive title")
        )

        val details = archive.archiveMetadataDetailsText()

        assertTrue(details.contains("Provider: Pixiv"))
        assertTrue(details.contains("Archive key: pixiv:complete"))
        assertTrue(details.contains("Archive status: Complete"))
        assertTrue(details.contains("Source URL: https://www.phixiv.net/en/artworks/123"))
        assertTrue(details.contains("Title: Archive title"))
        assertTrue(details.contains("Saved media: 1/1"))
        assertTrue(details.contains("Archive created: 1970"))
        assertTrue(details.contains("Archive updated: 1970"))
        assertFalse(details.contains("Archive created: 10"))
        assertFalse(details.contains("Archive updated: 20"))
        assertTrue(details.contains("Media 1: Saved"))
        assertTrue(details.contains("MIME type: image/jpeg"))
        assertTrue(details.contains("File name: complete.jpg"))
        assertTrue(details.contains("Last attempted: 1970"))
    }

    @Test
    fun archiveMetadataDetailsText_rendersTagsStatsAndFlagsOnlyWhenPresent() {
        val plain = sampleArchive(media = emptyList())
        val rich = plain.copy(
            metadata = sampleMetadata().copy(
                tags = listOf("illustration", "bookmark"),
                stats = ClipboardPreviewStats(
                    likeCount = 12L,
                    bookmarkCount = 4L,
                    viewCount = 99L
                ),
                flags = ClipboardPreviewFlags(
                    aiGenerated = true,
                    animated = true
                )
            )
        )

        val plainDetails = plain.archiveMetadataDetailsText()
        val richDetails = rich.archiveMetadataDetailsText()

        assertFalse(plainDetails.contains("Tags:"))
        assertFalse(plainDetails.contains("Stats:"))
        assertFalse(plainDetails.contains("Flags:"))
        assertTrue(richDetails.contains("Tags: illustration, bookmark"))
        assertTrue(richDetails.contains("Stats: likes=12, bookmarks=4, views=99"))
        assertTrue(richDetails.contains("Flags: aiGenerated, animated"))
    }

    @Test
    fun archiveMetadataDetailsText_includesPendingFailedAndMissingMediaRows() {
        val archive = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/pending.jpg",
                    sourceIndex = 0,
                    status = ClipboardArchiveMediaStatus.Pending
                ),
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/failed.jpg",
                    sourceIndex = 1,
                    status = ClipboardArchiveMediaStatus.Failed,
                    failureDetail = "HTTP 429 Too Many Requests"
                ),
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/missing.jpg",
                    sourceIndex = 2,
                    fileName = "missing.jpg",
                    status = ClipboardArchiveMediaStatus.Missing
                )
            )
        )

        val details = archive.archiveMetadataDetailsText()

        assertTrue(details.contains("Media 1: Pending"))
        assertTrue(details.contains("Media 2: Failed"))
        assertTrue(details.contains("Failure: Rate limited"))
        assertTrue(details.contains("Media 3: Missing"))
        assertTrue(details.contains("File name: missing.jpg"))
        assertTrue(details.contains("Failure: File missing"))
    }

    @Test
    fun failureDetailsText_usesReadableAttemptTimeAndKeepsRawDetail() {
        val media = ClipboardArchiveMedia(
            sourceUrl = "https://img.example/failed.jpg",
            sourceIndex = 1,
            mimeType = "image/jpeg",
            fileName = "failed.jpg",
            status = ClipboardArchiveMediaStatus.Failed,
            lastAttemptAtEpochMs = 42L,
            failureDetail = "HTTP 500 server exploded"
        )

        val details = media.failureDetailsText()

        assertTrue(details.contains("Summary: Download failed"))
        assertTrue(details.contains("Source URL: https://img.example/failed.jpg"))
        assertTrue(details.contains("Source index: 2"))
        assertTrue(details.contains("Last attempted: 1970"))
        assertFalse(details.contains("Last attempted: 42"))
        assertTrue(details.contains("Raw detail"))
        assertTrue(details.contains("HTTP 500 server exploded"))
    }

    @Test
    fun clipboardPreviewShareTarget_usesCurrentPreviewPageMimeType() {
        val dir = createTempDirectory().toFile()
        try {
            val first = File(dir, "first.jpg").apply { writeText("first") }
            val second = File(dir, "second.mp4").apply { writeText("second") }
            val entry = ClipboardEntry(
                timestamp = 1L,
                pinned = false,
                text = "https://x.com/futo/status/123",
                uri = null,
                mimeTypes = listOf("text/plain"),
                previewMediaFiles = listOf(
                    ClipboardPreviewMedia(fileName = first.name, mimeType = "image/jpeg"),
                    ClipboardPreviewMedia(fileName = second.name, mimeType = "video/mp4")
                )
            )

            val target = clipboardPreviewShareTarget(
                entry = entry,
                previewState = ClipboardPreviewState(
                    linkPreviewsEnabled = true,
                    embedDisplayMode = ClipboardEmbedDisplayMode.ShowEmbed
                ),
                mediaFiles = listOf(first, second),
                page = 1
            )

            assertEquals(second, target?.file)
            assertEquals("video/mp4", target?.mimeType)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun clipboardPreviewShareTarget_fallsBackToBackingFileMimeType() {
        val dir = createTempDirectory().toFile()
        try {
            val file = File(dir, "clip.dat").apply { writeText("clip") }
            val entry = ClipboardEntry(
                timestamp = 1L,
                pinned = false,
                text = null,
                uri = null,
                mimeTypes = listOf("image/png"),
                backingFile = file.name
            )

            val target = clipboardPreviewShareTarget(
                entry = entry,
                previewState = ClipboardPreviewState(
                    linkPreviewsEnabled = true,
                    embedDisplayMode = ClipboardEmbedDisplayMode.ShowEmbed
                ),
                mediaFiles = listOf(file),
                page = 0
            )

            assertEquals(file, target?.file)
            assertEquals("image/png", target?.mimeType)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun clipboardPreviewShareTarget_returnsNullForMissingPageOrFile() {
        val dir = createTempDirectory().toFile()
        try {
            val file = File(dir, "first.jpg").apply { writeText("first") }
            val missing = File(dir, "missing.jpg")
            val entry = ClipboardEntry(
                timestamp = 1L,
                pinned = false,
                text = "https://x.com/futo/status/123",
                uri = null,
                mimeTypes = listOf("text/plain"),
                previewMediaFiles = listOf(
                    ClipboardPreviewMedia(fileName = file.name, mimeType = "image/jpeg"),
                    ClipboardPreviewMedia(fileName = missing.name, mimeType = "image/jpeg")
                )
            )
            val previewState = ClipboardPreviewState(
                linkPreviewsEnabled = true,
                embedDisplayMode = ClipboardEmbedDisplayMode.ShowEmbed
            )

            assertEquals(
                null,
                clipboardPreviewShareTarget(
                    entry = entry,
                    previewState = previewState,
                    mediaFiles = listOf(file, missing),
                    page = 1
                )
            )
            assertEquals(
                null,
                clipboardPreviewShareTarget(
                    entry = entry,
                    previewState = previewState,
                    mediaFiles = listOf(file),
                    page = 2
                )
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun clipboardBitmapCacheKey_changesWhenFileMetadataChanges() {
        val archiveDir = createTempDirectory().toFile()
        try {
            val file = File(archiveDir, "preview.jpg")
            file.writeText("old")
            file.setLastModified(1_000L)
            val oldKey = clipboardBitmapCacheKey(file, preferThumbnail = true)

            file.writeText("new content")
            file.setLastModified(2_000L)
            val newKey = clipboardBitmapCacheKey(file, preferThumbnail = true)

            assertNotEquals(oldKey, newKey)
        } finally {
            archiveDir.deleteRecursively()
        }
    }

    @Test
    fun zoomedPreviewOffset_clampsToVisibleImageBounds() {
        val offset = clampClipboardHistoryPreviewOffset(
            offset = Offset(500f, -500f),
            zoom = 2f,
            baseImageWidthPx = 300f,
            baseImageHeightPx = 200f,
            containerWidthPx = 200f,
            containerHeightPx = 100f
        )

        assertEquals(200f, offset.x)
        assertEquals(-150f, offset.y)
    }

    @Test
    fun zoomedPreviewOffset_resetsWhenImageDoesNotOverflowAxis() {
        val offset = clampClipboardHistoryPreviewOffset(
            offset = Offset(40f, 40f),
            zoom = 1f,
            baseImageWidthPx = 120f,
            baseImageHeightPx = 80f,
            containerWidthPx = 200f,
            containerHeightPx = 100f
        )

        assertEquals(Offset.Zero, offset)
    }

    @Test
    fun zoomedPreviewEdgeSwipe_accumulatesOnlyAtHorizontalEdges() {
        val partial = clipboardHistoryPreviewEdgeSwipeProgress(
            offsetX = -100f,
            panX = -20f,
            maxOffsetX = 100f,
            accumulatedPx = 0f,
            thresholdPx = 48f
        )
        val complete = clipboardHistoryPreviewEdgeSwipeProgress(
            offsetX = -100f,
            panX = -30f,
            maxOffsetX = 100f,
            accumulatedPx = partial.accumulatedPx,
            thresholdPx = 48f
        )
        val notAtEdge = clipboardHistoryPreviewEdgeSwipeProgress(
            offsetX = -60f,
            panX = -80f,
            maxOffsetX = 100f,
            accumulatedPx = 20f,
            thresholdPx = 48f
        )

        assertEquals(null, partial.direction)
        assertEquals(20f, partial.accumulatedPx)
        assertEquals(ClipboardHistoryPreviewEdgeSwipe.Next, complete.direction)
        assertEquals(0f, complete.accumulatedPx)
        assertEquals(null, notAtEdge.direction)
        assertEquals(0f, notAtEdge.accumulatedPx)
    }

    @Test
    fun zoomedPreviewEdgeSwipe_ignoresImagesWithoutHorizontalOverflow() {
        val progress = clipboardHistoryPreviewEdgeSwipeProgress(
            offsetX = 0f,
            panX = 80f,
            maxOffsetX = 0f,
            accumulatedPx = 20f,
            thresholdPx = 48f
        )

        assertEquals(null, progress.direction)
        assertEquals(0f, progress.accumulatedPx)
    }

    @Test
    fun zoomedPreviewState_usesSmallDeadZoneAboveOneToAvoidPagerFlicker() {
        assertFalse(isClipboardHistoryPreviewZoomed(1f))
        assertFalse(isClipboardHistoryPreviewZoomed(1.01f))
        assertTrue(isClipboardHistoryPreviewZoomed(1.02f))
    }

    private fun sampleMetadata() = ClipboardPreviewMetadata(
        provider = ClipboardPreviewProvider.PIXIV,
        sourceUrl = "https://www.phixiv.net/en/artworks/123",
        sourceId = "123",
        title = "Original title",
        bodyText = "Original body",
        authorName = "Original artist",
        authorHandle = "artist_handle",
        authorId = "artist-123",
        createdAt = "2026-01-02T03:04:05Z",
        imageCount = 1,
        selectedImageIndex = 0
    )

    private fun sampleArchive(
        media: List<ClipboardArchiveMedia>
    ) = ClipboardLinkArchive(
        key = "pixiv:123",
        provider = ClipboardPreviewProvider.PIXIV,
        sourceUrl = "https://www.phixiv.net/en/artworks/123",
        sourceId = "123",
        metadata = sampleMetadata(),
        media = media,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L
    )
}
