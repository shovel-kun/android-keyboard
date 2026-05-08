package org.futo.inputmethod.latin.uix.actions.clipboard

import org.futo.inputmethod.latin.uix.clipboardBackupMediaFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ClipboardBackupTest {
    @Test
    fun shouldObserveScreenshots_requiresAllGates() {
        assertFalse(
            shouldObserveScreenshots(
                historyEnabled = false,
                incognitoMode = false,
                saveScreenshots = true,
                hasPermission = true
            )
        )
        assertFalse(
            shouldObserveScreenshots(
                historyEnabled = true,
                incognitoMode = true,
                saveScreenshots = true,
                hasPermission = true
            )
        )
        assertFalse(
            shouldObserveScreenshots(
                historyEnabled = true,
                incognitoMode = false,
                saveScreenshots = false,
                hasPermission = true
            )
        )
        assertFalse(
            shouldObserveScreenshots(
                historyEnabled = true,
                incognitoMode = false,
                saveScreenshots = true,
                hasPermission = false
            )
        )
        assertTrue(
            shouldObserveScreenshots(
                historyEnabled = true,
                incognitoMode = false,
                saveScreenshots = true,
                hasPermission = true
            )
        )
    }

    @Test
    fun upsertClipboardMediaEntry_deduplicatesBackingFileAndPreservesPinnedState() {
        val entries = mutableListOf(
            ClipboardEntry(
                timestamp = 1L,
                pinned = true,
                text = null,
                uri = null,
                backingFile = "screenshot.png",
                mimeTypes = listOf("image/png")
            )
        )

        upsertClipboardMediaEntry(
            entries,
            ClipboardEntry(
                timestamp = 2L,
                pinned = false,
                text = null,
                uri = null,
                backingFile = "screenshot.png",
                mimeTypes = listOf("image/png")
            )
        )

        assertEquals(1, entries.size)
        assertEquals(2L, entries.single().timestamp)
        assertTrue(entries.single().pinned)
    }

    @Test
    fun clipboardArchiveTombstones_roundTripSortedByKey() {
        val encoded = encodeClipboardArchiveTombstones(
            listOf(
                ClipboardArchiveTombstone(
                    key = "twitter:2",
                    deletedAtEpochMs = 20L,
                    reason = "user"
                ),
                ClipboardArchiveTombstone(
                    key = "pixiv:1",
                    deletedAtEpochMs = 10L
                )
            )
        )

        assertEquals(
            listOf("pixiv:1", "twitter:2"),
            decodeClipboardArchiveTombstones(encoded).map { it.key }
        )
    }

    @Test
    fun mergeArchiveTombstones_migratesLegacyEntryKeys() {
        val merged = mergeArchiveTombstones(
            existing = listOf(
                ClipboardArchiveTombstone(
                    key = "pixiv:kept",
                    deletedAtEpochMs = 5L
                )
            ),
            migratedKeys = listOf("twitter:deleted", "pixiv:kept"),
            now = 20L
        )

        assertEquals(listOf("pixiv:kept", "twitter:deleted"), merged.map { it.key })
        assertEquals(5L, merged.first { it.key == "pixiv:kept" }.deletedAtEpochMs)
        assertEquals(20L, merged.first { it.key == "twitter:deleted" }.deletedAtEpochMs)
    }

    @Test
    fun tombstonesRetainedAfterArchiveImport_allowsImportedArchiveToRestoreDeletedKey() {
        val restored = sampleArchive(media = emptyList()).copy(key = "twitter:restored")
        val retained = ClipboardArchiveTombstone(
            key = "pixiv:still-deleted",
            deletedAtEpochMs = 10L
        )

        assertEquals(
            listOf("pixiv:still-deleted"),
            tombstonesRetainedAfterArchiveImport(
                tombstones = listOf(
                    ClipboardArchiveTombstone(
                        key = "twitter:restored",
                        deletedAtEpochMs = 5L
                    ),
                    retained
                ),
                importedArchives = listOf(restored)
            ).map { it.key }
        )
    }

    @Test
    fun decodeClipboardEntries_ignoresUnknownFieldsFromNewerBackups() {
        val decoded = decodeClipboardEntries(
            """
            [
              {
                "timestamp": 123,
                "pinned": false,
                "text": "hello",
                "uri": null,
                "mimeTypes": ["text/plain"],
                "brandNewField": "future"
              }
            ]
            """.trimIndent()
        )

        assertEquals(1, decoded.size)
        assertEquals(123L, decoded.single().timestamp)
        assertEquals("hello", decoded.single().text)
        assertNull(decoded.single().uri)
    }

    @Test
    fun decodeClipboardEntries_normalizesLegacyPreviewImageFile() {
        val decoded = decodeClipboardEntries(
            """
            [
              {
                "timestamp": 123,
                "pinned": false,
                "text": "https://x.com/futo/status/123",
                "uri": null,
                "mimeTypes": ["text/plain"],
                "previewImageFile": "preview.jpg"
              }
            ]
            """.trimIndent()
        )

        assertEquals(listOf("preview.jpg"), decoded.single().previewMediaFileNames())
    }

    @Test
    fun encodeClipboardEntries_stripsLegacyPreviewImageFile() {
        val encoded = encodeClipboardEntries(
            listOf(
                ClipboardEntry(
                    timestamp = 123,
                    pinned = false,
                    text = "https://x.com/futo/status/123",
                    uri = null,
                    mimeTypes = listOf("text/plain"),
                    previewImageFile = "legacy.jpg"
                )
            )
        )
        val decoded = decodeClipboardEntries(encoded).single()

        assertFalse(encoded.contains("previewImageFile"))
        assertEquals(listOf("legacy.jpg"), decoded.previewMediaFileNames())
    }

    @Test
    fun referencedClipboardFileNames_includesAllPreviewMediaAndThumbnails() {
        val names = referencedClipboardFileNames(
            listOf(
                ClipboardEntry(
                    timestamp = 1L,
                    pinned = false,
                    text = "https://x.com/futo/status/123",
                    uri = null,
                    mimeTypes = listOf("text/plain"),
                    previewMediaFiles = listOf(
                        ClipboardPreviewMedia("one.jpg"),
                        ClipboardPreviewMedia("two.mp4")
                    )
                )
            )
        )

        assertEquals(
            setOf(
                "one.jpg",
                ClipboardUtil.thumbnailForName("one.jpg"),
                "two.mp4",
                ClipboardUtil.thumbnailForName("two.mp4")
            ),
            names
        )
    }

    @Test
    fun lazyListKey_isUniqueForDuplicateTextEntries() {
        val first = ClipboardEntry(
            timestamp = 1L,
            pinned = false,
            text = "https://www.pixiv.net/en/artworks/107946644",
            uri = null,
            mimeTypes = listOf("text/plain")
        )
        val second = first.copy(timestamp = 2L)

        assertFalse(first.lazyListKey(0) == second.lazyListKey(1))
    }

    @Test
    fun reconcileClipboardEntriesWithStorage_dropsOnlyMissingPreviewMedia() {
        val dir = createTempDirectory().toFile()
        try {
            File(dir, "one.jpg").writeText("image")
            val reconciled = reconcileClipboardEntriesWithStorage(
                listOf(
                    ClipboardEntry(
                        timestamp = 1L,
                        pinned = false,
                        text = "https://x.com/futo/status/123",
                        uri = null,
                        mimeTypes = listOf("text/plain"),
                        previewMediaFiles = listOf(
                            ClipboardPreviewMedia("one.jpg"),
                            ClipboardPreviewMedia("missing.jpg")
                        ),
                        previewFetchStatus = ClipboardPreviewFetchStatus.Success
                    )
                ),
                dir
            )

            val entry = reconciled.single()
            assertEquals(listOf("one.jpg"), entry.previewMediaFileNames())
            assertEquals(ClipboardPreviewFetchStatus.Success, entry.previewFetchStatus)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun reconcileClipboardEntriesWithStorage_dropsLegacyArchiveBackedPreviewMedia() {
        val clipboardDir = createTempDirectory().toFile()
        val archiveDir = createTempDirectory().toFile()
        try {
            File(archiveDir, "archived.jpg").writeText("image")
            val reconciled = reconcileClipboardEntriesWithStorage(
                entries = listOf(
                    ClipboardEntry(
                        timestamp = 1L,
                        pinned = false,
                        text = "https://www.pixiv.net/artworks/123",
                        uri = null,
                        mimeTypes = listOf("text/plain"),
                        previewMediaFiles = listOf(ClipboardPreviewMedia("archived.jpg")),
                        previewFetchStatus = ClipboardPreviewFetchStatus.Success
                    )
                ),
                clipboardDir = clipboardDir
            )

            assertTrue(reconciled.single().previewMediaFileNames().isEmpty())
        } finally {
            clipboardDir.deleteRecursively()
            archiveDir.deleteRecursively()
        }
    }

    @Test
    fun replaceFileWithBackup_allowsRepeatedSavesWithExistingBackup() {
        val dir = createTempDirectory().toFile()
        try {
            val target = File(dir, "clipboard_archives.json")
            val backup = File(dir, "clipboard_archives.json.bak")
            val swap = File(dir, "clipboard_archives.json.swap")

            target.writeText("pending")
            swap.writeText("saved")
            replaceFileWithBackup(
                swapFile = swap,
                targetFile = target,
                backupFile = backup
            )

            swap.writeText("saved-again")
            replaceFileWithBackup(
                swapFile = swap,
                targetFile = target,
                backupFile = backup
            )

            assertEquals("saved-again", target.readText())
            assertEquals("saved", backup.readText())
            assertFalse(swap.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mergeClipboardEntries_preservesRicherPreviewMediaList() {
        val merged = mergeClipboardEntries(
            currentEntries = listOf(
                ClipboardEntry(
                    timestamp = 1L,
                    pinned = false,
                    text = "https://x.com/futo/status/123",
                    uri = null,
                    mimeTypes = listOf("text/plain"),
                    previewMediaFiles = listOf(ClipboardPreviewMedia("one.jpg"))
                )
            ),
            importedEntries = listOf(
                ClipboardEntry(
                    timestamp = 2L,
                    pinned = false,
                    text = "https://x.com/futo/status/123",
                    uri = null,
                    mimeTypes = listOf("text/plain"),
                    previewMediaFiles = listOf(
                        ClipboardPreviewMedia("one.jpg"),
                        ClipboardPreviewMedia("two.jpg")
                    )
                )
            )
        )

        assertEquals(listOf("one.jpg", "two.jpg"), merged.single().previewMediaFileNames())
        assertFalse(merged.single().pinned)
    }

    @Test
    fun mergeClipboardEntries_preservesDeletedArchiveKeysFromOlderDuplicate() {
        val merged = mergeClipboardEntries(
            currentEntries = listOf(
                ClipboardEntry(
                    timestamp = 2L,
                    pinned = false,
                    text = "https://x.com/futo/status/123",
                    uri = null,
                    mimeTypes = listOf("text/plain"),
                    deletedArchiveKeys = setOf("twitter:123")
                )
            ),
            importedEntries = listOf(
                ClipboardEntry(
                    timestamp = 1L,
                    pinned = false,
                    text = "https://x.com/futo/status/123",
                    uri = null,
                    mimeTypes = listOf("text/plain"),
                    previewMetadata = ClipboardPreviewMetadata(
                        provider = ClipboardPreviewProvider.TWITTER,
                        sourceUrl = "https://x.com/futo/status/123",
                        sourceId = "123"
                    )
                )
            )
        )

        assertEquals(setOf("twitter:123"), merged.single().deletedArchiveKeys)
    }

    @Test
    fun deduplicateClipboardEntries_mergesSameTextEntriesWithDifferentTimestamps() {
        val deduplicated = deduplicateClipboardEntries(
            listOf(
                ClipboardEntry(
                    timestamp = 1L,
                    pinned = false,
                    text = "https://www.pixiv.net/en/artworks/107946644",
                    uri = null,
                    mimeTypes = listOf("text/plain"),
                    previewMediaFiles = listOf(ClipboardPreviewMedia("one.jpg"))
                ),
                ClipboardEntry(
                    timestamp = 2L,
                    pinned = true,
                    text = "https://www.pixiv.net/en/artworks/107946644",
                    uri = null,
                    mimeTypes = listOf("text/plain"),
                    previewMediaFiles = listOf(
                        ClipboardPreviewMedia("one.jpg"),
                        ClipboardPreviewMedia("two.jpg")
                    )
                )
            )
        )

        assertEquals(1, deduplicated.size)
        assertEquals(2L, deduplicated.single().timestamp)
        assertTrue(deduplicated.single().pinned)
        assertEquals(listOf("one.jpg", "two.jpg"), deduplicated.single().previewMediaFileNames())
    }

    @Test
    fun deduplicateClipboardEntries_keepsRicherPreviewMediaListAboveOneHundredItems() {
        val fullMedia = List(650) { index ->
            ClipboardPreviewMedia("full-$index.jpg", sourceIndex = index)
        }
        val partialMedia = List(100) { index ->
            ClipboardPreviewMedia("partial-$index.jpg", sourceIndex = index)
        }
        val deduplicated = deduplicateClipboardEntries(
            listOf(
                ClipboardEntry(
                    timestamp = 1L,
                    pinned = false,
                    text = "https://www.pixiv.net/en/artworks/107946644",
                    uri = null,
                    mimeTypes = listOf("text/plain"),
                    previewMediaFiles = fullMedia
                ),
                ClipboardEntry(
                    timestamp = 2L,
                    pinned = false,
                    text = "https://www.pixiv.net/en/artworks/107946644",
                    uri = null,
                    mimeTypes = listOf("text/plain"),
                    previewMediaFiles = partialMedia
                )
            )
        )

        assertEquals(1, deduplicated.size)
        assertEquals(650, deduplicated.single().previewMedia().size)
        assertEquals(fullMedia.map { it.fileName }, deduplicated.single().previewMediaFileNames())
    }

    @Test
    fun clipboardArchivesFromPreviewEntries_recoversArchiveRecordsFromSavedPreviewMedia() {
        val archives = clipboardArchivesFromPreviewEntries(
            entries = listOf(
                ClipboardEntry(
                    timestamp = 1L,
                    pinned = false,
                    text = "https://www.pixiv.net/en/artworks/107946644",
                    uri = null,
                    mimeTypes = listOf("text/plain"),
                    previewMediaFiles = List(650) { index ->
                        ClipboardPreviewMedia(
                            fileName = "preview-$index.jpg",
                            sourceUrl = "https://img.example/$index.jpg",
                            sourceIndex = index,
                            mimeType = "image/jpeg"
                        )
                    },
                    previewMetadata = ClipboardPreviewMetadata(
                        provider = ClipboardPreviewProvider.PIXIV,
                        sourceUrl = "https://www.pixiv.net/en/artworks/107946644",
                        sourceId = "107946644",
                        imageCount = 650
                    )
                )
            ),
            now = 10L
        )

        val archive = archives.single()
        assertEquals("pixiv:107946644", archive.key)
        assertEquals(650, archive.media.size)
        assertEquals(650, archive.savedMediaCount())
        assertEquals(ClipboardLinkArchiveStatus.Complete, archive.status)
        assertFalse(archive.providerManifestAvailable)
    }

    @Test
    fun newArchiveFromManifest_recordsAllExpectedMedia() {
        val archive = newArchiveFromManifest(
            ClipboardLinkPreviewManifest(
                snippet = null,
                metadata = ClipboardPreviewMetadata(
                    provider = ClipboardPreviewProvider.PIXIV,
                    sourceUrl = "https://www.phixiv.net/en/artworks/123",
                    sourceId = "123"
                ),
                mediaItems = listOf(
                    ClipboardLinkPreviewMedia("https://img.example/0.jpg", 0, "image/jpeg"),
                    ClipboardLinkPreviewMedia("https://img.example/1.jpg", 1, "image/jpeg")
                )
            ),
            now = 5L
        )

        assertEquals("pixiv:123", archive?.key)
        assertEquals(2, archive?.media?.size)
        assertEquals(ClipboardLinkArchiveStatus.Pending, archive?.status)
        assertEquals(listOf(0, 1), archive?.media?.map { it.sourceIndex })
    }

    @Test
    fun referencedClipboardArchiveFileNames_includesSavedMediaAndThumbnails() {
        val names = referencedClipboardArchiveFileNames(
            listOf(
                sampleArchive(
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
                )
            )
        )

        assertEquals(
            setOf(
                "one.jpg",
                ClipboardUtil.thumbnailForName("one.jpg")
            ),
            names
        )
    }

    @Test
    fun clipboardArchiveMetadataFileName_isStableAndFilesystemSafe() {
        val first = clipboardArchiveMetadataFileName("pixiv:123")
        val second = clipboardArchiveMetadataFileName("pixiv:123")
        val other = clipboardArchiveMetadataFileName("pixiv:456")

        assertEquals(first, second)
        assertFalse(first == other)
        assertTrue(first.endsWith(".json"))
        assertFalse(first.contains(':'))
        assertFalse(first.contains('/'))
    }

    @Test
    fun loadClipboardArchivesFromMetadataDir_readsPerArchiveFilesWithoutAddingPlaceholders() {
        val dir = createTempDirectory().toFile()
        try {
            val first = sampleArchive(
                media = listOf(
                    ClipboardArchiveMedia(
                        sourceUrl = "https://img.example/one.jpg",
                        sourceIndex = 0,
                        fileName = "one.jpg",
                        status = ClipboardArchiveMediaStatus.Saved
                    )
                )
            )
            val second = sampleArchive(
                media = emptyList()
            ).copy(
                key = "pixiv:456",
                sourceId = "456"
            )

            dir.clipboardArchiveMetadataFile(second.key).writeText(encodeClipboardArchive(second))
            dir.clipboardArchiveMetadataFile(first.key).writeText(encodeClipboardArchive(first))

            assertEquals(
                listOf(first.key, second.key),
                loadClipboardArchivesFromMetadataDir(dir).map { it.key }
            )
            val loadedSecond = loadClipboardArchivesFromMetadataDir(dir).single { it.key == second.key }
            assertTrue(loadedSecond.media.isEmpty())
            assertFalse(loadedSecond.hasRetryableMedia())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun loadClipboardArchivesFromMetadataDir_recoversCorruptFileFromBackup() {
        val dir = createTempDirectory().toFile()
        try {
            val archive = sampleArchive(
                media = listOf(
                    ClipboardArchiveMedia(
                        sourceUrl = "https://img.example/one.jpg",
                        sourceIndex = 0,
                        fileName = "one.jpg",
                        status = ClipboardArchiveMediaStatus.Saved
                    )
                )
            )
            val archiveFile = dir.clipboardArchiveMetadataFile(archive.key)

            archiveFile.writeText("{")
            File(dir, "${archiveFile.name}.bak").writeText(encodeClipboardArchive(archive))

            assertEquals(
                listOf(archive),
                loadClipboardArchivesFromMetadataDir(dir)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mergeStoredClipboardArchives_keepsLegacyRecordsDuringPartialMigration() {
        val unchangedLegacy = sampleArchive(
            media = emptyList()
        ).copy(
            key = "pixiv:unchanged",
            sourceId = "unchanged",
            updatedAtEpochMs = 10L
        )
        val legacyToUpdate = sampleArchive(
            media = listOf(ClipboardArchiveMedia("https://img.example/pending.jpg", 0))
        ).copy(
            key = "pixiv:updated",
            sourceId = "updated",
            updatedAtEpochMs = 20L
        )
        val metadataUpdate = legacyToUpdate.copy(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/pending.jpg",
                    sourceIndex = 0,
                    fileName = "saved.jpg",
                    status = ClipboardArchiveMediaStatus.Saved
                )
            ),
            updatedAtEpochMs = 30L
        )

        val merged = mergeStoredClipboardArchives(
            legacyArchives = listOf(unchangedLegacy, legacyToUpdate),
            metadataArchives = listOf(metadataUpdate)
        )

        assertEquals(setOf("pixiv:unchanged", "pixiv:updated"), merged.map { it.key }.toSet())
        assertEquals(10L, merged.first { it.key == "pixiv:unchanged" }.updatedAtEpochMs)
        assertEquals(
            listOf(ClipboardArchiveMediaStatus.Missing),
            merged.first { it.key == "pixiv:unchanged" }.media.map { it.status }
        )
        assertEquals(
            ClipboardArchiveMediaStatus.Saved,
            merged.first { it.key == "pixiv:updated" }.media.single().status
        )
    }

    @Test
    fun mergeStoredClipboardArchives_addsPlaceholderOnlyForLegacyAggregateRecords() {
        val legacy = sampleArchive(media = emptyList()).copy(key = "pixiv:legacy", sourceId = "legacy")
        val current = sampleArchive(media = emptyList()).copy(key = "pixiv:current", sourceId = "current")

        val legacyOnly = mergeStoredClipboardArchives(
            legacyArchives = listOf(legacy),
            metadataArchives = emptyList()
        ).single()
        val metadataOnly = mergeStoredClipboardArchives(
            legacyArchives = emptyList(),
            metadataArchives = listOf(current)
        ).single()

        assertEquals(listOf(ClipboardArchiveMediaStatus.Missing), legacyOnly.media.map { it.status })
        assertTrue(legacyOnly.hasRetryableMedia())
        assertTrue(metadataOnly.media.isEmpty())
        assertFalse(metadataOnly.hasRetryableMedia())
    }

    @Test
    fun reconcileClipboardArchivesWithStorage_marksMissingSavedMedia() {
        val dir = createTempDirectory().toFile()
        try {
            val reconciled = reconcileClipboardArchivesWithStorage(
                archives = listOf(
                    sampleArchive(
                        media = listOf(
                            ClipboardArchiveMedia(
                                sourceUrl = "https://img.example/missing.jpg",
                                sourceIndex = 0,
                                fileName = "missing.jpg",
                                status = ClipboardArchiveMediaStatus.Saved
                            )
                        )
                    )
                ),
                clipboardDir = dir,
                now = 10L
            )

            val media = reconciled.single().media.single()
            assertEquals(ClipboardArchiveMediaStatus.Missing, media.status)
            assertEquals("missing.jpg", media.fileName)
            assertEquals(ClipboardLinkArchiveStatus.Failed, reconciled.single().status)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun reconcileClipboardArchivesWithStorage_retainsSharedClipboardMedia() {
        val archiveDir = createTempDirectory().toFile()
        val clipboardDir = createTempDirectory().toFile()
        try {
            File(clipboardDir, "shared.jpg").writeText("image")
            val reconciled = reconcileClipboardArchivesWithStorage(
                archives = listOf(
                    sampleArchive(
                        media = listOf(
                            ClipboardArchiveMedia(
                                sourceUrl = "https://img.example/shared.jpg",
                                sourceIndex = 0,
                                fileName = "shared.jpg",
                                status = ClipboardArchiveMediaStatus.Saved
                            )
                        )
                    )
                ),
                clipboardDir = clipboardDir,
                legacyArchiveDir = archiveDir,
                now = 10L
            )

            val media = reconciled.single().media.single()
            assertEquals(ClipboardArchiveMediaStatus.Saved, media.status)
            assertEquals(ClipboardLinkArchiveStatus.Complete, reconciled.single().status)
        } finally {
            archiveDir.deleteRecursively()
            clipboardDir.deleteRecursively()
        }
    }

    @Test
    fun legacyAwareClipboardMediaFile_prefersClipboardStoreWhenBothCopiesExist() {
        val archiveDir = createTempDirectory().toFile()
        val clipboardDir = createTempDirectory().toFile()
        try {
            File(archiveDir, "shared.jpg").writeText("archive copy")
            val clipboardFile = File(clipboardDir, "shared.jpg")
            clipboardFile.writeText("clipboard copy")

            assertEquals(
                clipboardFile,
                legacyAwareClipboardMediaFile(
                    clipboardDir = clipboardDir,
                    legacyArchiveDir = archiveDir,
                    fileName = "shared.jpg"
                )
            )
        } finally {
            archiveDir.deleteRecursively()
            clipboardDir.deleteRecursively()
        }
    }

    @Test
    fun migrateLegacyArchiveMediaFiles_preservesUnreferencedMediaInClipboardStore() {
        val archiveDir = createTempDirectory().toFile()
        val clipboardDir = createTempDirectory().toFile()
        try {
            File(archiveDir, "saved.jpg").writeText("archive bytes")
            File(archiveDir, ClipboardUtil.thumbnailForName("saved.jpg")).writeText("thumb")
            File(archiveDir, "unreferenced.jpg").writeText("unused")

            migrateLegacyArchiveMediaFiles(
                legacyArchiveDir = archiveDir,
                clipboardDir = clipboardDir,
                referencedFileNames = setOf("saved.jpg", ClipboardUtil.thumbnailForName("saved.jpg"))
            )

            assertEquals("archive bytes", File(clipboardDir, "saved.jpg").readText())
            assertEquals("thumb", File(clipboardDir, ClipboardUtil.thumbnailForName("saved.jpg")).readText())
            assertEquals("unused", File(clipboardDir, "unreferenced.jpg").readText())
            assertFalse(archiveDir.exists())
        } finally {
            archiveDir.deleteRecursively()
            clipboardDir.deleteRecursively()
        }
    }

    @Test
    fun migrateLegacyArchiveMediaFiles_keepsClipboardCopyWhenDuplicateExists() {
        val archiveDir = createTempDirectory().toFile()
        val clipboardDir = createTempDirectory().toFile()
        try {
            File(archiveDir, "saved.jpg").writeText("archive bytes")
            val clipboardFile = File(clipboardDir, "saved.jpg")
            clipboardFile.writeText("clipboard bytes")

            migrateLegacyArchiveMediaFiles(
                legacyArchiveDir = archiveDir,
                clipboardDir = clipboardDir,
                referencedFileNames = setOf("saved.jpg")
            )

            assertEquals("clipboard bytes", clipboardFile.readText())
            assertFalse(archiveDir.exists())
        } finally {
            archiveDir.deleteRecursively()
            clipboardDir.deleteRecursively()
        }
    }

    @Test
    fun clipboardBackupMediaFiles_exportsEverythingUnderClipboardFiles() {
        val archiveDir = createTempDirectory().toFile()
        val clipboardDir = createTempDirectory().toFile()
        try {
            val clipFile = File(clipboardDir, "clip.jpg").apply { writeText("clip") }
            File(archiveDir, "clip.jpg").writeText("duplicate")
            val archiveOnlyFile = File(archiveDir, "archive-only.jpg").apply { writeText("archive") }
            val orphanFile = File(clipboardDir, "orphan.jpg").apply { writeText("orphan") }

            val entries = clipboardBackupMediaFiles(
                referencedFiles = setOf("clip.jpg"),
                referencedArchiveFiles = setOf("clip.jpg", "archive-only.jpg"),
                clipboardDir = clipboardDir,
                archiveDir = archiveDir
            )

            assertEquals(
                setOf(
                    "clipboardfiles/clip.jpg" to clipFile,
                    "clipboardfiles/archive-only.jpg" to archiveOnlyFile,
                    "clipboardfiles/orphan.jpg" to orphanFile
                ),
                entries.toSet()
            )
        } finally {
            archiveDir.deleteRecursively()
            clipboardDir.deleteRecursively()
        }
    }

    @Test
    fun reconcileClipboardArchivesWithStorage_doesNotAddPlaceholderForCurrentEmptyArchive() {
        val dir = createTempDirectory().toFile()
        try {
            val reconciled = reconcileClipboardArchivesWithStorage(
                archives = listOf(sampleArchive(media = emptyList())),
                clipboardDir = dir,
                now = 10L
            )

            assertTrue(reconciled.single().media.isEmpty())
            assertEquals(ClipboardLinkArchiveStatus.Complete, reconciled.single().status)
            assertFalse(reconciled.single().hasRetryableMedia())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun retryableMedia_selectsPendingFailedMissingAndTooLargeOnly() {
        val archive = sampleArchive(
            media = listOf(
                ClipboardArchiveMedia("https://img.example/pending.jpg", 0),
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/saved.jpg",
                    sourceIndex = 1,
                    fileName = "saved.jpg",
                    status = ClipboardArchiveMediaStatus.Saved
                ),
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/failed.jpg",
                    sourceIndex = 2,
                    status = ClipboardArchiveMediaStatus.Failed
                ),
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/large.jpg",
                    sourceIndex = 3,
                    status = ClipboardArchiveMediaStatus.SkippedTooLarge
                ),
                ClipboardArchiveMedia(
                    sourceUrl = "https://img.example/missing.jpg",
                    sourceIndex = 4,
                    fileName = "missing.jpg",
                    status = ClipboardArchiveMediaStatus.Missing
                )
            )
        )

        assertEquals(
            listOf(
                "https://img.example/pending.jpg",
                "https://img.example/failed.jpg",
                "https://img.example/large.jpg",
                "https://img.example/missing.jpg"
            ),
            archive.retryableMedia().map { it.sourceUrl }
        )
        assertTrue(archive.hasRetryableMedia())
        assertEquals(
            listOf(
                "https://img.example/pending.jpg",
                "https://img.example/missing.jpg",
            ),
            archive.autoDownloadableMedia().map { it.sourceUrl }
        )
        assertTrue(archive.hasAutoDownloadableMedia())
    }

    @Test
    fun mergeClipboardArchives_preservesSavedMediaOverFailedImport() {
        val merged = mergeClipboardArchives(
            currentArchives = listOf(
                sampleArchive(
                    media = listOf(
                        ClipboardArchiveMedia(
                            sourceUrl = "https://img.example/one.jpg",
                            sourceIndex = 0,
                            fileName = "one.jpg",
                            status = ClipboardArchiveMediaStatus.Saved
                        )
                    )
                )
            ),
            importedArchives = listOf(
                sampleArchive(
                    media = listOf(
                        ClipboardArchiveMedia(
                            sourceUrl = "https://img.example/one.jpg",
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
            )
        )

        assertEquals(
            listOf(
                ClipboardArchiveMediaStatus.Saved,
                ClipboardArchiveMediaStatus.Pending
            ),
            merged.single().media.map { it.status }
        )
        assertEquals("one.jpg", merged.single().media.first().fileName)
    }

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
}
