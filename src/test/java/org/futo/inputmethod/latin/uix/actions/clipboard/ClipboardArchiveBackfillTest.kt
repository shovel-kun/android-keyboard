package org.futo.inputmethod.latin.uix.actions.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ClipboardArchiveBackfillTest {
    @Test
    fun legacyPreviewedSupportedClip_isEligibleForArchiveBackfill() {
        val entry = samplePixivEntry()

        assertTrue(entry.isEligibleForArchiveBackfill(existingArchiveKeys = emptySet()))
    }

    @Test
    fun alreadyArchivedSupportedClip_isNotEligibleForArchiveBackfill() {
        val entry = samplePixivEntry()

        assertFalse(entry.isEligibleForArchiveBackfill(existingArchiveKeys = setOf("pixiv:123")))
    }

    @Test
    fun unsupportedTextClip_isNotEligibleForArchiveBackfill() {
        val entry = samplePixivEntry().copy(text = "plain text")

        assertFalse(entry.isEligibleForArchiveBackfill(existingArchiveKeys = emptySet()))
    }

    @Test
    fun archiveBackfillRequests_keepSelectedLegacyEntryForDuplicateLinks() {
        val legacyEntry = samplePixivEntry(
            metadata = ClipboardPreviewMetadata(
                provider = ClipboardPreviewProvider.PIXIV,
                sourceUrl = "https://www.phixiv.net/en/artworks/123",
                sourceId = "123",
                imageCount = 2
            )
        )
        val duplicateWithoutPreview = legacyEntry.copy(
            timestamp = 2L,
            previewText = null,
            previewMediaFiles = emptyList(),
            previewMetadata = null,
            previewFetchStatus = ClipboardPreviewFetchStatus.NeverAttempted,
            previewFetchLastAttemptAt = null
        )

        val requests = archiveBackfillRequests(
            entries = listOf(legacyEntry, duplicateWithoutPreview),
            existingArchiveKeys = emptySet()
        )

        assertEquals(1, requests.size)
        assertEquals("pixiv:123", requests.single().archiveKey)
        assertEquals(listOf("legacy.jpg"), requests.single().entry.previewMediaFileNames())
    }

    @Test
    fun archiveBackfillRequests_canRunAfterPreviouslyNoEligibleWork() {
        val firstPass = archiveBackfillRequests(
            entries = listOf(samplePixivEntry().copy(text = "plain text")),
            existingArchiveKeys = emptySet()
        )
        val secondPass = archiveBackfillRequests(
            entries = listOf(samplePixivEntry()),
            existingArchiveKeys = emptySet(),
            attemptedArchiveKeys = firstPass.map { it.archiveKey }.toSet()
        )

        assertTrue(firstPass.isEmpty())
        assertEquals(listOf("pixiv:123"), secondPass.map { it.archiveKey })
    }

    @Test
    fun archiveBackfillVersion_skipsOnlyWhenCompleteOrDisabled() {
        assertTrue(
            shouldRunArchiveBackfill(
                completedVersion = 0,
                currentVersion = 1,
                incognito = false,
                previewsEnabled = true
            )
        )
        assertFalse(
            shouldRunArchiveBackfill(
                completedVersion = 1,
                currentVersion = 1,
                incognito = false,
                previewsEnabled = true
            )
        )
        assertFalse(
            shouldRunArchiveBackfill(
                completedVersion = 0,
                currentVersion = 1,
                incognito = true,
                previewsEnabled = true
            )
        )
        assertFalse(
            shouldRunArchiveBackfill(
                completedVersion = 0,
                currentVersion = 1,
                incognito = false,
                previewsEnabled = false
            )
        )
    }

    @Test
    fun fallbackArchiveFromLegacyPreview_isPartialAndKeepsExpectedPlaceholders() {
        val entry = samplePixivEntry(
            metadata = ClipboardPreviewMetadata(
                provider = ClipboardPreviewProvider.PIXIV,
                sourceUrl = "https://www.phixiv.net/en/artworks/123",
                sourceId = "123",
                title = "Old title",
                imageCount = 3
            )
        )
        val metadata = entry.archiveBackfillMetadata()!!
        val archive = newFallbackArchiveFromEntry(
            entry = entry,
            metadata = metadata,
            savedMedia = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/0.jpg",
                    sourceIndex = 0,
                    fileName = "legacy.jpg",
                    status = ClipboardArchiveMediaStatus.Saved
                )
            ),
            now = 10L
        )!!

        assertEquals("pixiv:123", archive.key)
        assertFalse(archive.providerManifestAvailable)
        assertEquals(ClipboardLinkArchiveStatus.Partial, archive.status)
        assertEquals(
            listOf(
                ClipboardArchiveMediaStatus.Saved,
                ClipboardArchiveMediaStatus.Missing,
                ClipboardArchiveMediaStatus.Missing
            ),
            archive.media.map { it.status }
        )
        assertTrue(archive.hasRetryableMedia())
        assertEquals(ClipboardArchiveDisplayStatus.Retry, archive.displayStatus())
    }

    @Test
    fun fallbackArchiveCopy_preservesClipboardPreviewFile() {
        val clipboardDir = createTempDirectory().toFile()
        val archiveDir = createTempDirectory().toFile()
        try {
            File(clipboardDir, "legacy.jpg").writeText("legacy bytes")
            File(clipboardDir, ClipboardUtil.thumbnailForName("legacy.jpg")).writeText("thumb")
            val entry = samplePixivEntry()
            val metadata = entry.archiveBackfillMetadata()!!

            val media = copyLegacyPreviewMediaToArchive(
                entry = entry,
                metadata = metadata,
                clipboardDir = clipboardDir,
                archiveDir = archiveDir,
                now = 20L
            )

            assertEquals(1, media.size)
            assertEquals(ClipboardArchiveMediaStatus.Saved, media.single().status)
            assertEquals("legacy bytes", File(clipboardDir, "legacy.jpg").readText())
            assertEquals("legacy bytes", File(archiveDir, "legacy.jpg").readText())
            assertTrue(File(archiveDir, ClipboardUtil.thumbnailForName("legacy.jpg")).isFile)
        } finally {
            clipboardDir.deleteRecursively()
            archiveDir.deleteRecursively()
        }
    }

    @Test
    fun fallbackArchiveReload_keepsSavedMediaWhenLegacyClipboardFileIsGone() {
        val clipboardDir = createTempDirectory().toFile()
        val archiveDir = createTempDirectory().toFile()
        try {
            val legacyFile = File(clipboardDir, "legacy.jpg")
            legacyFile.writeText("legacy bytes")
            File(clipboardDir, ClipboardUtil.thumbnailForName("legacy.jpg")).writeText("thumb")
            val entry = samplePixivEntry()
            val metadata = entry.archiveBackfillMetadata()!!
            val savedMedia = copyLegacyPreviewMediaToArchive(
                entry = entry,
                metadata = metadata,
                clipboardDir = clipboardDir,
                archiveDir = archiveDir,
                now = 20L
            )
            val archive = newFallbackArchiveFromEntry(
                entry = entry,
                metadata = metadata,
                savedMedia = savedMedia,
                now = 30L
            )!!
            val encoded = encodeClipboardArchives(listOf(archive))

            assertTrue(legacyFile.delete())
            assertFalse(legacyFile.exists())

            val reloaded = decodeClipboardArchives(encoded)
            val reconciled = reconcileClipboardArchivesWithStorage(
                archives = reloaded,
                archiveDir = archiveDir,
                now = 40L
            )

            val media = reconciled.single().media.single()
            assertEquals(ClipboardArchiveMediaStatus.Saved, media.status)
            assertEquals("legacy.jpg", media.fileName)
            assertEquals(ClipboardLinkArchiveStatus.Complete, reconciled.single().status)
            assertEquals(listOf("legacy.jpg"), reconciled.single().savedPreviewMedia().map { it.fileName })
        } finally {
            clipboardDir.deleteRecursively()
            archiveDir.deleteRecursively()
        }
    }

    @Test
    fun fallbackArchiveCreation_doesNotMutateLegacyEntry() {
        val entry = samplePixivEntry(
            metadata = ClipboardPreviewMetadata(
                provider = ClipboardPreviewProvider.PIXIV,
                sourceUrl = "https://www.phixiv.net/en/artworks/123",
                sourceId = "123",
                title = "Old title"
            )
        )
        val before = entry.copy()

        newFallbackArchiveFromEntry(
            entry = entry,
            metadata = entry.archiveBackfillMetadata()!!,
            savedMedia = emptyList(),
            now = 30L
        )

        assertEquals(before, entry)
    }

    @Test
    fun fallbackArchiveMergeWithManifest_replacesSyntheticMediaBySourceIndex() {
        val entry = samplePixivEntry(
            metadata = ClipboardPreviewMetadata(
                provider = ClipboardPreviewProvider.PIXIV,
                sourceUrl = "https://www.phixiv.net/en/artworks/123",
                sourceId = "123",
                imageCount = 2
            )
        ).copy(
            previewMediaFiles = listOf(
                ClipboardPreviewMedia(
                    fileName = "legacy-0.jpg",
                    sourceIndex = 0,
                    mimeType = "image/jpeg"
                ),
                ClipboardPreviewMedia(
                    fileName = "legacy-1.jpg",
                    sourceIndex = 1,
                    mimeType = "image/jpeg"
                )
            )
        )
        val metadata = entry.archiveBackfillMetadata()!!
        val fallback = newFallbackArchiveFromEntry(
            entry = entry,
            metadata = metadata,
            savedMedia = entry.previewMedia().map {
                ClipboardArchiveMedia(
                    sourceUrl = "${metadata.sourceUrl}#legacy-media-${it.sourceIndex}",
                    sourceIndex = it.sourceIndex,
                    mimeType = it.mimeType,
                    fileName = it.fileName,
                    status = ClipboardArchiveMediaStatus.Saved
                )
            },
            now = 10L
        )!!

        val merged = mergeArchiveWithManifest(
            archive = fallback,
            manifest = ClipboardLinkPreviewManifest(
                snippet = "remote",
                mediaItems = listOf(
                    ClipboardLinkPreviewMedia(
                        url = "https://img.example/0.jpg",
                        sourceIndex = 0,
                        mimeType = "image/jpeg"
                    ),
                    ClipboardLinkPreviewMedia(
                        url = "https://img.example/1.jpg",
                        sourceIndex = 1,
                        mimeType = "image/jpeg"
                    )
                ),
                metadata = metadata
            ),
            now = 20L
        )

        assertTrue(merged.providerManifestAvailable)
        assertEquals(2, merged.media.size)
        assertEquals(listOf("https://img.example/0.jpg", "https://img.example/1.jpg"), merged.media.map { it.sourceUrl })
        assertEquals(listOf("legacy-0.jpg", "legacy-1.jpg"), merged.media.map { it.fileName })
        assertEquals(listOf(0, 1), merged.savedPreviewMedia().map { it.sourceIndex })
        assertEquals(listOf("legacy-0.jpg", "legacy-1.jpg"), merged.savedPreviewMedia().map { it.fileName })
    }

    @Test
    fun providerArchiveMergeWithChangedMediaUrl_preservesSavedMediaBySourceIndex() {
        val existing = ClipboardLinkArchive(
            key = "pixiv:123",
            provider = ClipboardPreviewProvider.PIXIV,
            sourceUrl = "https://www.phixiv.net/en/artworks/123",
            sourceId = "123",
            metadata = ClipboardPreviewMetadata(
                provider = ClipboardPreviewProvider.PIXIV,
                sourceUrl = "https://www.phixiv.net/en/artworks/123",
                sourceId = "123"
            ),
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/old-token.jpg",
                    sourceIndex = 0,
                    mimeType = "image/jpeg",
                    fileName = "preview_old.jpg",
                    status = ClipboardArchiveMediaStatus.Saved
                )
            ),
            providerManifestAvailable = true,
            createdAtEpochMs = 10L,
            updatedAtEpochMs = 10L
        )

        val merged = mergeArchiveWithManifest(
            archive = existing,
            manifest = ClipboardLinkPreviewManifest(
                snippet = "remote",
                mediaItems = listOf(
                    ClipboardLinkPreviewMedia(
                        url = "https://img.example/new-token.jpg",
                        sourceIndex = 0,
                        mimeType = "image/jpeg"
                    )
                ),
                metadata = existing.metadata
            ),
            now = 20L
        )

        assertEquals(ClipboardLinkArchiveStatus.Complete, merged.status)
        assertEquals(1, merged.media.size)
        assertEquals("https://img.example/new-token.jpg", merged.media.single().sourceUrl)
        assertEquals(ClipboardArchiveMediaStatus.Saved, merged.media.single().status)
        assertEquals("preview_old.jpg", merged.media.single().fileName)
        assertEquals(listOf("preview_old.jpg"), merged.savedPreviewMedia().map { it.fileName })
    }

    @Test
    fun remoteManifestArchive_isProviderBackedAndPendingForRetention() {
        val archive = newArchiveFromManifest(
            ClipboardLinkPreviewManifest(
                snippet = "remote",
                mediaItems = listOf(
                    ClipboardLinkPreviewMedia(
                        url = "https://img.example/remote.jpg",
                        sourceIndex = 0,
                        mimeType = "image/jpeg"
                    )
                ),
                metadata = ClipboardPreviewMetadata(
                    provider = ClipboardPreviewProvider.TWITTER,
                    sourceUrl = "https://x.com/futo/status/123",
                    sourceId = "123"
                )
            ),
            now = 40L
        )!!

        assertTrue(archive.providerManifestAvailable)
        assertEquals(ClipboardArchiveMediaStatus.Pending, archive.media.single().status)
    }

    @Test
    fun deleteArchivePreviewRetention_keepsClipboardOwnedLegacyPreview() {
        val clipboardDir = createTempDirectory().toFile()
        val archiveDir = createTempDirectory().toFile()
        try {
            File(clipboardDir, "legacy.jpg").writeText("legacy")
            val entry = samplePixivEntry(
                metadata = ClipboardPreviewMetadata(
                    provider = ClipboardPreviewProvider.PIXIV,
                    sourceUrl = "https://www.phixiv.net/en/artworks/123",
                    sourceId = "123"
                )
            )
            val retained = retainedPreviewMediaAfterArchiveDelete(
                entry = entry,
                archivedFileNames = setOf("legacy.jpg"),
                clipboardDir = clipboardDir
            )

            assertEquals(listOf("legacy.jpg"), retained.map { it.fileName })
        } finally {
            clipboardDir.deleteRecursively()
            archiveDir.deleteRecursively()
        }
    }

    @Test
    fun deleteArchivePreviewRetention_dropsArchiveOnlyPreview() {
        val clipboardDir = createTempDirectory().toFile()
        try {
            val entry = samplePixivEntry(
                metadata = ClipboardPreviewMetadata(
                    provider = ClipboardPreviewProvider.PIXIV,
                    sourceUrl = "https://www.phixiv.net/en/artworks/123",
                    sourceId = "123"
                )
            )
            val retained = retainedPreviewMediaAfterArchiveDelete(
                entry = entry,
                archivedFileNames = setOf("legacy.jpg"),
                clipboardDir = clipboardDir
            )

            assertTrue(retained.isEmpty())
        } finally {
            clipboardDir.deleteRecursively()
        }
    }

    private fun samplePixivEntry(
        metadata: ClipboardPreviewMetadata? = null
    ) = ClipboardEntry(
        timestamp = 1L,
        pinned = false,
        text = "https://www.pixiv.net/artworks/123",
        uri = null,
        mimeTypes = listOf("text/plain"),
        previewText = "legacy preview",
        previewMediaFiles = listOf(
            ClipboardPreviewMedia(
                fileName = "legacy.jpg",
                sourceIndex = 0,
                mimeType = "image/jpeg"
            )
        ),
        previewMetadata = metadata,
        previewFetchStatus = ClipboardPreviewFetchStatus.Success,
        previewFetchLastAttemptAt = 5L
    )
}
