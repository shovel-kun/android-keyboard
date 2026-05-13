package org.futo.inputmethod.latin.uix.actions.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ClipboardArchiveBackfillTest {
    @Test
    fun rawClipboardDisplayModeStillAllowsArchivePreviewIngestion() {
        val state = previewState(
            linkPreviewsEnabled = true,
            storedEmbedDisplayMode = ClipboardEmbedDisplayMode.ShowRawClipboard.storedValue
        )

        assertFalse(state.shouldFetchPreviews)
        assertTrue(state.shouldArchivePreviews)
    }

    @Test
    fun disabledLinkPreviewsDisableArchivePreviewIngestion() {
        val state = previewState(
            linkPreviewsEnabled = false,
            storedEmbedDisplayMode = ClipboardEmbedDisplayMode.ShowEmbed.storedValue
        )

        assertFalse(state.shouldFetchPreviews)
        assertFalse(state.shouldArchivePreviews)
    }

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
    fun localPreviewArchiveRecovery_addsMissingArchiveFromExistingClipMedia() {
        val dir = createTempDirectory().toFile()
        try {
            File(dir, "legacy.jpg").writeText("image")

            val recovered = clipboardArchivesFromLocalPreviewEntries(
                entries = listOf(samplePixivEntry()),
                clipboardDir = dir,
                existingArchiveKeys = emptySet(),
                deletedArchiveKeys = emptySet(),
                now = 10L
            )

            val archive = recovered.single()
            assertEquals("pixiv:123", archive.key)
            assertEquals(ClipboardLinkArchiveStatus.Complete, archive.status)
            assertEquals(listOf("legacy.jpg"), archive.savedPreviewMedia().map { it.fileName })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun localPreviewArchiveRecovery_skipsTombstonedArchiveKey() {
        val dir = createTempDirectory().toFile()
        try {
            File(dir, "legacy.jpg").writeText("image")

            val recovered = clipboardArchivesFromLocalPreviewEntries(
                entries = listOf(samplePixivEntry()),
                clipboardDir = dir,
                existingArchiveKeys = emptySet(),
                deletedArchiveKeys = setOf("pixiv:123"),
                now = 10L
            )

            assertTrue(recovered.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun localPreviewArchiveRecovery_skipsExistingArchiveKey() {
        val dir = createTempDirectory().toFile()
        try {
            File(dir, "legacy.jpg").writeText("image")

            val recovered = clipboardArchivesFromLocalPreviewEntries(
                entries = listOf(samplePixivEntry()),
                clipboardDir = dir,
                existingArchiveKeys = setOf("pixiv:123"),
                deletedArchiveKeys = emptySet(),
                now = 10L
            )

            assertTrue(recovered.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun localPreviewArchiveRecovery_ignoresMissingLocalPreviewFile() {
        val dir = createTempDirectory().toFile()
        try {
            val recovered = clipboardArchivesFromLocalPreviewEntries(
                entries = listOf(samplePixivEntry()),
                clipboardDir = dir,
                existingArchiveKeys = emptySet(),
                deletedArchiveKeys = emptySet(),
                now = 10L
            )

            assertTrue(recovered.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
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
    fun archiveBackfillVersion_forcedImportBypassesOnlyCompletedVersion() {
        assertTrue(
            shouldRunArchiveBackfill(
                completedVersion = 1,
                currentVersion = 1,
                incognito = false,
                previewsEnabled = true,
                forceCompletedVersion = true
            )
        )
        assertFalse(
            shouldRunArchiveBackfill(
                completedVersion = 1,
                currentVersion = 1,
                incognito = true,
                previewsEnabled = true,
                forceCompletedVersion = true
            )
        )
        assertFalse(
            shouldRunArchiveBackfill(
                completedVersion = 1,
                currentVersion = 1,
                incognito = false,
                previewsEnabled = false,
                forceCompletedVersion = true
            )
        )
    }

    @Test
    fun archiveBackfillRequests_afterSecondLegacyImportSchedulesOnlyMissingArchiveKey() {
        val firstImportArchiveKey = "pixiv:123"
        val laterImportedEntry = samplePixivEntry(
            metadata = ClipboardPreviewMetadata(
                provider = ClipboardPreviewProvider.PIXIV,
                sourceUrl = "https://www.phixiv.net/en/artworks/456",
                sourceId = "456",
                imageCount = 1
            )
        ).copy(
            text = "https://www.pixiv.net/en/artworks/456",
            previewImageFile = "legacy-456.jpg",
            previewMediaFiles = emptyList()
        )
        val alreadyArchivedEntry = samplePixivEntry()

        val requests = archiveBackfillRequests(
            entries = listOf(alreadyArchivedEntry, laterImportedEntry),
            existingArchiveKeys = setOf(firstImportArchiveKey)
        )

        assertEquals(listOf("pixiv:456"), requests.map { it.archiveKey })
        assertEquals(laterImportedEntry, requests.single().entry)
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
    fun fallbackArchiveBackfill_referencesClipboardPreviewWithoutCopyingFile() {
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
                legacyArchiveDir = archiveDir,
                now = 20L
            )

            assertEquals(1, media.size)
            assertEquals(ClipboardArchiveMediaStatus.Saved, media.single().status)
            assertEquals("legacy.jpg", media.single().fileName)
            assertEquals("legacy bytes", File(clipboardDir, "legacy.jpg").readText())
            assertFalse(File(archiveDir, "legacy.jpg").exists())
            assertFalse(File(archiveDir, ClipboardUtil.thumbnailForName("legacy.jpg")).exists())
        } finally {
            clipboardDir.deleteRecursively()
            archiveDir.deleteRecursively()
        }
    }

    @Test
    fun fallbackArchiveReload_keepsSavedMediaFromSharedClipboardFile() {
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
                legacyArchiveDir = archiveDir,
                now = 20L
            )
            val archive = newFallbackArchiveFromEntry(
                entry = entry,
                metadata = metadata,
                savedMedia = savedMedia,
                now = 30L
            )!!
            val encoded = encodeClipboardArchives(listOf(archive))

            val reloaded = decodeClipboardArchives(encoded)
            val reconciled = reconcileClipboardArchivesWithStorage(
                archives = reloaded,
                clipboardDir = clipboardDir,
                legacyArchiveDir = archiveDir,
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
    fun fallbackArchiveReload_marksMissingWhenSharedClipboardFileIsGone() {
        val clipboardDir = createTempDirectory().toFile()
        val archiveDir = createTempDirectory().toFile()
        try {
            val legacyFile = File(clipboardDir, "legacy.jpg")
            legacyFile.writeText("legacy bytes")
            val entry = samplePixivEntry()
            val metadata = entry.archiveBackfillMetadata()!!
            val archive = newFallbackArchiveFromEntry(
                entry = entry,
                metadata = metadata,
                savedMedia = copyLegacyPreviewMediaToArchive(
                    entry = entry,
                    metadata = metadata,
                    clipboardDir = clipboardDir,
                    legacyArchiveDir = archiveDir,
                    now = 20L
                ),
                now = 30L
            )!!

            assertTrue(legacyFile.delete())

            val reconciled = reconcileClipboardArchivesWithStorage(
                archives = listOf(archive),
                clipboardDir = clipboardDir,
                legacyArchiveDir = archiveDir,
                now = 40L
            )

            assertEquals(ClipboardArchiveMediaStatus.Missing, reconciled.single().media.single().status)
            assertEquals(ClipboardLinkArchiveStatus.Failed, reconciled.single().status)
        } finally {
            clipboardDir.deleteRecursively()
            archiveDir.deleteRecursively()
        }
    }

    @Test
    fun archiveDeleteKeepsOnlySharedFilesStillReferencedByClips() {
        val retainedClip = samplePixivEntry().copy(
            previewMediaFiles = listOf(ClipboardPreviewMedia("retained.jpg")),
            previewImageFile = null
        )

        val orphaned = orphanedSharedArchiveFileNamesAfterArchiveDelete(
            archivedFileNames = setOf("retained.jpg", "orphaned.jpg"),
            entries = listOf(retainedClip)
        )

        assertEquals(
            setOf("orphaned.jpg", ClipboardUtil.thumbnailForName("orphaned.jpg")),
            orphaned
        )
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

    @Test
    fun deleteArchivePreviewRetention_clearsTextWhenNoMediaRemains() {
        val entry = samplePixivEntry()

        assertEquals(null, retainedPreviewTextAfterArchiveDelete(entry, retainedPreviewMedia = emptyList()))
    }

    @Test
    fun deleteArchivePreviewRetention_keepsTextWhenClipboardOwnedMediaRemains() {
        val entry = samplePixivEntry()

        assertEquals(
            "legacy preview",
            retainedPreviewTextAfterArchiveDelete(
                entry,
                retainedPreviewMedia = listOf(
                    ClipboardPreviewMedia(
                        fileName = "legacy.jpg",
                        sourceIndex = 0,
                        mimeType = "image/jpeg"
                    )
                )
            )
        )
    }

    @Test
    fun entryLevelDeleteTombstoneDoesNotOwnBackfillAfterCutover() {
        val deletedEntry = samplePixivEntry().copy(
            previewText = null,
            previewImageFile = null,
            previewMediaFiles = emptyList(),
            previewMetadata = null,
            deletedArchiveKeys = setOf("pixiv:123")
        )

        assertFalse(deletedEntry.isEligibleForArchiveBackfill(existingArchiveKeys = emptySet()))
    }

    @Test
    fun archiveStoreTombstoneBlocksBackfill() {
        val deletedEntry = samplePixivEntry().copy(
            deletedArchiveKeys = setOf("pixiv:123")
        )

        assertFalse(
            deletedEntry.isEligibleForArchiveBackfill(
                existingArchiveKeys = emptySet(),
                deletedArchiveKeys = setOf("pixiv:123")
            )
        )
        assertTrue(
            archiveBackfillRequests(
                entries = listOf(samplePixivEntry()),
                existingArchiveKeys = emptySet()
            ).isNotEmpty()
        )
        assertEquals(
            emptyList<ClipboardArchiveBackfillRequest>(),
            archiveBackfillRequests(
                entries = listOf(deletedEntry),
                existingArchiveKeys = emptySet(),
                deletedArchiveKeys = setOf("pixiv:123")
            )
        )
    }

    @Test
    fun textImportRevivesMatchingDeletedArchiveKey() {
        assertEquals(
            setOf("pixiv:456"),
            deletedArchiveKeysAfterTextImport(
                text = "https://www.pixiv.net/en/artworks/123",
                deletedArchiveKeys = setOf("pixiv:123", "pixiv:456")
            )
        )
    }

    @Test
    fun textImportKeepsUnrelatedDeletedArchiveKeys() {
        assertEquals(
            setOf("pixiv:123"),
            deletedArchiveKeysAfterTextImport(
                text = "plain text",
                deletedArchiveKeys = setOf("pixiv:123")
            )
        )
    }

    @Test
    fun previewManifestRevivesResolvedDeletedArchiveKey() {
        val manifest = ClipboardLinkPreviewManifest(
            snippet = "is there a way",
            mediaItems = emptyList(),
            metadata = ClipboardPreviewMetadata(
                provider = ClipboardPreviewProvider.REDDIT,
                sourceUrl = "https://www.rxddit.com/r/ComedyHell/comments/1t4mfwe/is_there_a_way",
                sourceId = "1t4mfwe",
                title = "is there a way"
            )
        )

        assertEquals(
            setOf("reddit:j7xGCXRsdR"),
            deletedArchiveKeysAfterPreviewManifest(
                manifest = manifest,
                deletedArchiveKeys = setOf("reddit:j7xGCXRsdR", "reddit:1t4mfwe")
            )
        )
    }

    @Test
    fun deleteArchiveMatchesEntryFromUrlWhenPreviewMetadataWasAlreadyCleared() {
        val entry = samplePixivEntry().copy(
            previewMetadata = null
        )

        assertTrue(entry.matchesDeletedArchiveKey("pixiv:123"))
    }

    @Test
    fun deleteArchiveMatchesBareSupportedUrlEntry() {
        val entry = samplePixivEntry().copy(
            previewText = null,
            previewMediaFiles = emptyList(),
            previewMetadata = null,
            previewFetchStatus = ClipboardPreviewFetchStatus.NeverAttempted
        )

        assertTrue(entry.matchesDeletedArchiveKey("pixiv:123"))
    }

    @Test
    fun deleteArchiveDoesNotMatchPlainTextEntry() {
        val entry = samplePixivEntry().copy(
            text = "plain text",
            previewMetadata = null
        )

        assertFalse(entry.matchesDeletedArchiveKey("pixiv:123"))
    }

    @Test
    fun startupPreviewFetchTexts_boundsNewestSupportedClipsOnly() {
        val ids = (101..110).toList()
        val entries = ids.mapIndexed { index, id ->
            samplePixivEntry().copy(
                timestamp = index.toLong(),
                text = "https://www.pixiv.net/artworks/$id",
                previewText = null,
                previewMediaFiles = emptyList(),
                previewMetadata = null,
                previewFetchStatus = ClipboardPreviewFetchStatus.NeverAttempted
            )
        } + listOf(
            samplePixivEntry().copy(
                timestamp = 11L,
                text = "plain text",
                previewText = null,
                previewMediaFiles = emptyList(),
                previewMetadata = null,
                previewFetchStatus = ClipboardPreviewFetchStatus.NeverAttempted
            )
        )

        assertEquals(
            listOf(
                "https://www.pixiv.net/artworks/110",
                "https://www.pixiv.net/artworks/109",
                "https://www.pixiv.net/artworks/108"
            ),
            startupPreviewFetchTexts(entries = entries, limit = 3)
        )
    }

    @Test
    fun archiveBackfillRequests_stayUnboundedWhenStartupPreviewFetchIsBounded() {
        val ids = (101..110).toList()
        val entries = ids.mapIndexed { index, id ->
            samplePixivEntry().copy(
                timestamp = index.toLong(),
                text = "https://www.pixiv.net/artworks/$id"
            )
        }
        val previewFetchEntries = entries.map {
            it.copy(
                previewText = null,
                previewMediaFiles = emptyList(),
                previewMetadata = null,
                previewFetchStatus = ClipboardPreviewFetchStatus.NeverAttempted
            )
        }

        assertEquals(3, startupPreviewFetchTexts(entries = previewFetchEntries, limit = 3).size)
        assertEquals(
            ids.map { "pixiv:$it" },
            archiveBackfillRequests(
                entries = entries,
                existingArchiveKeys = emptySet()
            ).map { it.archiveKey }
        )
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
