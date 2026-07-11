package org.futo.inputmethod.latin.uix.actions.clipboard

import org.futo.inputmethod.latin.uix.clipboardBackupMediaFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class ClipboardBackupTest {
    @Test
    fun archiveStore_interruptedPromotionRestoresPreviousState() {
        val root = createTempDirectory().toFile()
        val oldMedia = createTempDirectory().toFile()
        val newMedia = createTempDirectory().toFile()
        try {
            File(oldMedia, "old.jpg").writeText("old")
            File(newMedia, "new.jpg").writeText("new")
            val oldArchive = sampleArchive(
                media = listOf(savedArchiveMedia("old.jpg", 0))
            )
            ClipboardArchiveStore(root).stageAndPromote(
                state = ClipboardArchiveStoreState(
                    entries = listOf(sampleEntry("old")),
                    archives = listOf(oldArchive),
                    tombstones = emptyList()
                ),
                importedMediaDirs = listOf(oldMedia),
                preserveExistingMedia = false
            )

            val failingStore = ClipboardArchiveStore(
                filesDir = root,
                promotionStep = { step ->
                    if(step == 3) throw IllegalStateException("interrupted")
                }
            )
            try {
                failingStore.stageAndPromote(
                    state = ClipboardArchiveStoreState(
                        entries = listOf(sampleEntry("new")),
                        archives = listOf(sampleArchive(media = listOf(savedArchiveMedia("new.jpg", 0)))),
                        tombstones = emptyList()
                    ),
                    importedMediaDirs = listOf(newMedia),
                    preserveExistingMedia = false
                )
                throw AssertionError("Expected promotion to fail")
            } catch(_: IllegalStateException) {
                // Expected.
            }

            assertEquals("old", File(root, ClipboardFileName).decodeClipboardEntries().single().text)
            assertEquals(oldArchive, ClipboardArchiveStore(root).load().archives.single())
            assertEquals("old", File(File(root, ClipboardBackupFilesDirectoryName), "old.jpg").readText())
            assertFalse(File(File(root, ClipboardBackupFilesDirectoryName), "new.jpg").exists())
            assertFalse(File(root, ".clipboard-store-previous").exists())
        } finally {
            root.deleteRecursively()
            oldMedia.deleteRecursively()
            newMedia.deleteRecursively()
        }
    }

    @Test
    fun archiveStore_failedRollbackPreservesRecoveryTreeForNextLoad() {
        val root = createTempDirectory().toFile()
        val oldMedia = createTempDirectory().toFile()
        val newMedia = createTempDirectory().toFile()
        try {
            File(oldMedia, "old.jpg").writeText("old")
            File(newMedia, "new.jpg").writeText("new")
            val oldArchive = sampleArchive(media = listOf(savedArchiveMedia("old.jpg", 0)))
            ClipboardArchiveStore(root).stageAndPromote(
                ClipboardArchiveStoreState(
                    entries = listOf(sampleEntry("old")),
                    archives = listOf(oldArchive),
                    tombstones = emptyList()
                ),
                importedMediaDirs = listOf(oldMedia),
                preserveExistingMedia = false
            )

            var failNextRestore = true
            val failingStore = ClipboardArchiveStore(
                filesDir = root,
                promotionStep = { step ->
                    if(step == 3) throw IllegalStateException("interrupted")
                },
                move = { source, destination ->
                    if(failNextRestore && source.parentFile?.name == ".clipboard-store-previous") {
                        failNextRestore = false
                        false
                    } else {
                        source.renameTo(destination)
                    }
                }
            )
            val failure = try {
                failingStore.stageAndPromote(
                    ClipboardArchiveStoreState(
                        entries = listOf(sampleEntry("new")),
                        archives = listOf(sampleArchive(media = listOf(savedArchiveMedia("new.jpg", 0)))),
                        tombstones = emptyList()
                    ),
                    importedMediaDirs = listOf(newMedia),
                    preserveExistingMedia = false
                )
                throw AssertionError("Expected promotion to fail")
            } catch(e: IllegalStateException) {
                e
            }

            val recoveryDir = File(root, ".clipboard-store-previous")
            assertTrue(failure.message!!.contains("Could not restore previous clipboard path"))
            assertEquals("interrupted", failure.cause?.message)
            assertTrue(File(recoveryDir, "promotion-in-progress").isFile)

            val recovered = ClipboardArchiveStore(root).load()
            assertEquals("old", File(root, ClipboardFileName).decodeClipboardEntries().single().text)
            assertEquals(oldArchive, recovered.archives.single())
            assertEquals("old", File(File(root, ClipboardBackupFilesDirectoryName), "old.jpg").readText())
            assertFalse(File(File(root, ClipboardBackupFilesDirectoryName), "new.jpg").exists())
            assertFalse(recoveryDir.exists())
        } finally {
            root.deleteRecursively()
            oldMedia.deleteRecursively()
            newMedia.deleteRecursively()
        }
    }

    @Test
    fun archiveStore_replacePromotesCompleteValidatedGraph() {
        val root = createTempDirectory().toFile()
        val media = createTempDirectory().toFile()
        try {
            File(media, "saved.jpg").writeText("image")
            val archive = sampleArchive(media = listOf(savedArchiveMedia("saved.jpg", 0)))
            val tombstone = ClipboardArchiveTombstone("twitter:deleted", 10L)
            val installed = ClipboardArchiveStore(root).stageAndPromote(
                state = ClipboardArchiveStoreState(
                    entries = listOf(sampleEntry("replacement")),
                    archives = listOf(archive),
                    tombstones = listOf(tombstone)
                ),
                importedMediaDirs = listOf(media),
                preserveExistingMedia = false
            )

            val loaded = ClipboardArchiveStore(root).load()
            assertEquals(installed.archives, loaded.archives)
            assertEquals(listOf(tombstone), loaded.tombstones)
            assertEquals(installed.entries, File(root, ClipboardFileName).decodeClipboardEntries())
            assertTrue(File(File(root, ClipboardBackupFilesDirectoryName), "saved.jpg").isFile)
            assertFalse(File(root, ".clipboard-store-previous").exists())
        } finally {
            root.deleteRecursively()
            media.deleteRecursively()
        }
    }

    @Test
    fun archiveStore_mergeKeepsExistingAndImportedRicherMedia() {
        val root = createTempDirectory().toFile()
        val oldMedia = createTempDirectory().toFile()
        val importedMedia = createTempDirectory().toFile()
        try {
            File(oldMedia, "one.jpg").writeText("one")
            File(importedMedia, "two.jpg").writeText("two")
            val store = ClipboardArchiveStore(root)
            store.stageAndPromote(
                ClipboardArchiveStoreState(
                    entries = emptyList(),
                    archives = listOf(sampleArchive(media = listOf(savedArchiveMedia("one.jpg", 0)))),
                    tombstones = emptyList()
                ),
                importedMediaDirs = listOf(oldMedia),
                preserveExistingMedia = false
            )
            val richer = sampleArchive(
                media = listOf(savedArchiveMedia("one.jpg", 0), savedArchiveMedia("two.jpg", 1))
            )
            store.stageAndPromote(
                ClipboardArchiveStoreState(emptyList(), listOf(richer), emptyList()),
                importedMediaDirs = listOf(importedMedia),
                preserveExistingMedia = true
            )

            assertEquals(richer, store.load().archives.single())
            assertEquals(
                setOf("one.jpg", "two.jpg"),
                File(root, ClipboardBackupFilesDirectoryName).listFiles()!!.map { it.name }.toSet()
            )
        } finally {
            root.deleteRecursively()
            oldMedia.deleteRecursively()
            importedMedia.deleteRecursively()
        }
    }

    @Test
    fun archiveStore_corruptPrimaryRecoversValidBackup() {
        val root = createTempDirectory().toFile()
        try {
            val metadataDir = File(root, ClipboardArchiveMetadataDirectoryName).apply { mkdirs() }
            val archive = sampleArchive(media = emptyList())
            val primary = metadataDir.clipboardArchiveMetadataFile(archive.key)
            primary.writeText("corrupt")
            File(metadataDir, "${primary.name}.bak").writeText(encodeClipboardArchive(archive))

            val loaded = ClipboardArchiveStore(root).load()

            assertEquals(listOf(archive), loaded.archives)
            assertTrue(loaded.corruptRecords.single().recoveredFromBackup)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun archiveStore_corruptPrimaryAndBackupArePreservedFromStaleCleanup() {
        val root = createTempDirectory().toFile()
        try {
            val metadataDir = File(root, ClipboardArchiveMetadataDirectoryName).apply { mkdirs() }
            val archive = sampleArchive(media = emptyList())
            val primary = metadataDir.clipboardArchiveMetadataFile(archive.key)
            val backup = File(metadataDir, "${primary.name}.bak")
            primary.writeText("corrupt-primary")
            backup.writeText("corrupt-backup")
            val store = ClipboardArchiveStore(root)

            val loaded = store.load()
            store.deleteStaleArchiveMetadata(emptySet())

            assertTrue(loaded.archives.isEmpty())
            assertFalse(loaded.corruptRecords.single().recoveredFromBackup)
            assertTrue(primary.isFile)
            assertTrue(backup.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun archiveStore_replaceArchiveMetadataRemovesStaleRecords() {
        val root = createTempDirectory().toFile()
        try {
            val store = ClipboardArchiveStore(root)
            val stale = sampleArchive(media = emptyList()).copy(key = "twitter:stale")
            val replacement = sampleArchive(media = emptyList()).copy(key = "pixiv:replacement")
            store.saveArchive(stale)

            store.replaceArchiveMetadata(listOf(replacement))

            assertEquals(listOf(replacement), store.load().archives)
            assertFalse(
                File(root, ClipboardArchiveMetadataDirectoryName)
                    .clipboardArchiveMetadataFile(stale.key)
                    .exists()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun archiveStore_storageInventoryClassifiesPhysicalMediaByReferences() {
        val root = createTempDirectory().toFile()
        try {
            val mediaDir = File(root, ClipboardBackupFilesDirectoryName).apply { mkdirs() }
            File(mediaDir, "clip.jpg").writeBytes(ByteArray(3))
            File(mediaDir, "archive.jpg").writeBytes(ByteArray(4))
            File(mediaDir, "shared.jpg").writeBytes(ByteArray(5))
            File(mediaDir, "unused.jpg").writeBytes(ByteArray(6))
            val legacyMediaDir = File(root, ClipboardArchiveFilesDirectoryName).apply { mkdirs() }
            File(legacyMediaDir, "legacy-unused.webp").writeBytes(ByteArray(7))
            val entries = listOf(
                sampleEntry("clip").copy(backingFile = "clip.jpg"),
                sampleEntry("shared").copy(backingFile = "shared.jpg")
            )
            val archive = sampleArchive(
                media = listOf(
                    savedArchiveMedia("archive.jpg", 0),
                    savedArchiveMedia("shared.jpg", 1)
                )
            )

            val inventory = ClipboardArchiveStore(root).storageInventory(entries, listOf(archive))

            assertEquals(25L, inventory.mediaBytes)
            assertEquals(8L, inventory.clipboardMediaBytes)
            assertEquals(9L, inventory.archiveMediaBytes)
            assertEquals(5L, inventory.sharedMediaBytes)
            assertEquals(13L, inventory.unreferencedMediaBytes)
            assertEquals(2, inventory.unreferencedMediaFileCount)
            assertEquals(setOf("unused.jpg", "legacy-unused.webp"), inventory.unreferencedMediaFileNames)
            assertEquals(
                listOf(
                    ClipboardStorageFile(
                        fileName = "legacy-unused.webp",
                        relativePath = "$ClipboardArchiveFilesDirectoryName/legacy-unused.webp",
                        bytes = 7L
                    ),
                    ClipboardStorageFile(
                        fileName = "unused.jpg",
                        relativePath = "$ClipboardBackupFilesDirectoryName/unused.jpg",
                        bytes = 6L
                    )
                ),
                inventory.unreferencedMediaFiles
            )
            assertEquals(9L, inventory.archiveBytesByKey[archive.key])
            assertEquals(
                setOf("clip.jpg", "archive.jpg", "shared.jpg", "unused.jpg", "legacy-unused.webp"),
                inventory.mediaFileNames
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun archiveStore_deleteUnreferencedMediaPreservesClipAndArchiveFiles() {
        val root = createTempDirectory().toFile()
        try {
            val mediaDir = File(root, ClipboardBackupFilesDirectoryName).apply { mkdirs() }
            val clip = File(mediaDir, "clip.jpg").apply { writeText("clip") }
            val archived = File(mediaDir, "archive.jpg").apply { writeText("archive") }
            val unused = File(mediaDir, "unused.jpg").apply { writeText("unused") }
            val entries = listOf(sampleEntry("clip").copy(backingFile = clip.name))
            val archive = sampleArchive(media = listOf(savedArchiveMedia(archived.name, 0)))

            val inventory = ClipboardArchiveStore(root).deleteUnreferencedMedia(
                entries,
                listOf(archive),
                setOf(clip.name, archived.name, unused.name)
            )

            assertTrue(clip.isFile)
            assertTrue(archived.isFile)
            assertFalse(unused.exists())
            assertEquals(0L, inventory.unreferencedMediaBytes)
            assertEquals(0, inventory.unreferencedMediaFileCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractClipboardBackup_roundTripsProductionZipContract() {
        val dir = createTempDirectory().toFile()
        try {
            val entry = ClipboardEntry(
                timestamp = 123L,
                pinned = false,
                text = "hello",
                uri = null,
                mimeTypes = listOf("text/plain"),
                backingFile = "clip.png"
            )
            val archive = sampleArchive(media = emptyList())
            val extracted = extractClipboardBackup(
                zip(
                    ClipboardBackupManifestFileName to manifestJson().toByteArray(),
                    ClipboardFileName to encodeClipboardEntries(listOf(entry)).toByteArray(),
                    ClipboardArchiveFileName to encodeClipboardArchives(listOf(archive)).toByteArray(),
                    "$ClipboardBackupFilesDirectoryName/clip.png" to "image".toByteArray()
                ),
                dir
            )

            assertEquals(ClipboardBackupCurrentVersion, extracted.manifest.version)
            assertEquals(listOf(entry), extracted.entries)
            assertEquals(archive.key, extracted.archives.single().key)
            assertEquals(archive.provider, extracted.archives.single().provider)
            assertEquals(archive.sourceUrl, extracted.archives.single().sourceUrl)
            assertEquals("image", File(extracted.filesDir, "clip.png").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun sampleEntry(text: String) = ClipboardEntry(
        timestamp = 1L,
        pinned = false,
        text = text,
        uri = null,
        mimeTypes = listOf("text/plain")
    )

    private fun savedArchiveMedia(fileName: String, sourceIndex: Int) = ClipboardArchiveMedia(
        sourceUrl = "https://img.example/$fileName",
        sourceIndex = sourceIndex,
        mimeType = "image/jpeg",
        fileName = fileName,
        status = ClipboardArchiveMediaStatus.Saved,
        lastAttemptAtEpochMs = 10L
    )

    @Test
    fun extractClipboardBackup_rejectsOversizedManifest() {
        assertExtractionFails(
            zip(ClipboardBackupManifestFileName to manifestJson().toByteArray()),
            ClipboardBackupExtractionLimits(manifestMaxBytes = 8)
        )
    }

    @Test
    fun extractClipboardBackup_rejectsOversizedMedia() {
        assertExtractionFails(
            zip(
                ClipboardBackupManifestFileName to manifestJson().toByteArray(),
                "$ClipboardBackupFilesDirectoryName/clip.png" to ByteArray(5)
            ),
            ClipboardBackupExtractionLimits(mediaMaxBytes = 4)
        )
    }

    @Test
    fun extractClipboardBackup_rejectsOversizedAggregate() {
        val manifest = manifestJson().toByteArray()
        assertExtractionFails(
            zip(
                ClipboardBackupManifestFileName to manifest,
                ClipboardFileName to "[]".toByteArray(),
                "$ClipboardBackupFilesDirectoryName/clip.png" to byteArrayOf(1)
            ),
            ClipboardBackupExtractionLimits(totalExpandedMaxBytes = manifest.size.toLong() + 2L)
        )
    }

    @Test
    fun extractClipboardBackup_rejectsTooManyEntries() {
        assertExtractionFails(
            zip(
                ClipboardBackupManifestFileName to manifestJson().toByteArray(),
                ClipboardFileName to "[]".toByteArray()
            ),
            ClipboardBackupExtractionLimits(maxEntryCount = 1)
        )
    }

    @Test
    fun extractClipboardBackup_rejectsDuplicateSingletonEntry() {
        assertExtractionFails(
            duplicateManifestZip()
        )
    }

    @Test
    fun extractClipboardBackup_rejectsUnsupportedPathBeforeWritingFiles() {
        val liveDir = createTempDirectory().toFile()
        val tempDir = createTempDirectory().toFile()
        try {
            val liveFile = File(liveDir, ClipboardFileName).apply { writeText("live clipboard") }
            val backup = zip(
                ClipboardBackupManifestFileName to manifestJson().toByteArray(),
                "unsupported/file.bin" to "data".toByteArray()
            )

            try {
                extractClipboardBackup(backup, tempDir)
                throw AssertionError("Expected extraction to fail")
            } catch(_: IllegalArgumentException) {
                // Expected.
            }

            assertEquals("live clipboard", liveFile.readText())
            assertFalse(File(tempDir, "unsupported").exists())
        } finally {
            liveDir.deleteRecursively()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun extractClipboardBackup_rejectsMalformedDecodedRecords() {
        assertExtractionFails(
            zip(
                ClipboardBackupManifestFileName to manifestJson().toByteArray(),
                ClipboardFileName to "not-json".toByteArray()
            )
        )
    }

    @Test
    fun describeClipboardStorageFile_redactsContentsAndReportsMetadata() {
        val dir = createTempDirectory().toFile()
        try {
            val secretMarker = "SECRET_CLIPBOARD_MARKER"
            val file = File(dir, "clipboard.json")
            file.writeText(
                """
                [
                  {
                    "timestamp": 123,
                    "pinned": false,
                    "text": "$secretMarker",
                    "uri": null,
                    "mimeTypes": ["text/plain"]
                  }
                ]
                """.trimIndent()
            )

            val description = describeClipboardStorageFile("main", file)

            assertFalse(description.contains(secretMarker))
            assertTrue(description.contains("role=main"))
            assertTrue(description.contains("name=clipboard.json"))
            assertTrue(description.contains("exists=true"))
            assertTrue(description.contains("byteSize=${file.length()}"))
            assertTrue(description.contains("decodeSuccess=true"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun describeClipboardStorageFile_reportsMissingAndInvalidFiles() {
        val dir = createTempDirectory().toFile()
        try {
            val missing = File(dir, "missing.json")
            val invalid = File(dir, "invalid.json").apply {
                writeText("SECRET_INVALID_CLIPBOARD_MARKER")
            }

            val missingDescription = describeClipboardStorageFile("backup", missing)
            val invalidDescription = describeClipboardStorageFile("swap", invalid)

            assertTrue(missingDescription.contains("exists=false"))
            assertTrue(missingDescription.contains("byteSize=0"))
            assertTrue(missingDescription.contains("decodeSuccess=false"))
            assertFalse(invalidDescription.contains("SECRET_INVALID_CLIPBOARD_MARKER"))
            assertTrue(invalidDescription.contains("exists=true"))
            assertTrue(invalidDescription.contains("byteSize=${invalid.length()}"))
            assertTrue(invalidDescription.contains("decodeSuccess=false"))
        } finally {
            dir.deleteRecursively()
        }
    }

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
    fun deduplicateClipboardEntries_preservesDuplicatePositionAndArchiveMetadata() {
        val deduplicated = deduplicateClipboardEntries(
            listOf(
                ClipboardEntry(
                    timestamp = 1L,
                    pinned = false,
                    text = "https://x.com/futo/status/123",
                    uri = null,
                    mimeTypes = listOf("text/plain"),
                    previewMediaFiles = listOf(ClipboardPreviewMedia("one.jpg")),
                    deletedArchiveKeys = setOf("twitter:old")
                ),
                ClipboardEntry(
                    timestamp = 2L,
                    pinned = false,
                    text = "middle",
                    uri = null,
                    mimeTypes = listOf("text/plain")
                ),
                ClipboardEntry(
                    timestamp = 3L,
                    pinned = true,
                    text = "https://x.com/futo/status/123",
                    uri = null,
                    mimeTypes = listOf("text/plain"),
                    previewMediaFiles = listOf(
                        ClipboardPreviewMedia("one.jpg"),
                        ClipboardPreviewMedia("two.jpg")
                    ),
                    previewMetadata = ClipboardPreviewMetadata(
                        provider = ClipboardPreviewProvider.TWITTER,
                        sourceUrl = "https://x.com/futo/status/123",
                        sourceId = "123"
                    ),
                    deletedArchiveKeys = setOf("twitter:new")
                )
            )
        )

        assertEquals(listOf("https://x.com/futo/status/123", "middle"), deduplicated.map { it.text })
        val merged = deduplicated.first()
        assertEquals(3L, merged.timestamp)
        assertTrue(merged.pinned)
        assertEquals(listOf("one.jpg", "two.jpg"), merged.previewMediaFileNames())
        assertEquals("123", merged.previewMetadata?.sourceId)
        assertEquals(setOf("twitter:old", "twitter:new"), merged.deletedArchiveKeys)
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

    private fun manifestJson(): String =
        """{"version":$ClipboardBackupCurrentVersion,"createdAtEpochMs":123}"""

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(output.toByteArray())
    }

    private fun duplicateManifestZip(): ByteArrayInputStream {
        val alternateName = "qanifest.json"
        val bytes = zip(
            ClipboardBackupManifestFileName to manifestJson().toByteArray(),
            alternateName to manifestJson().toByteArray()
        ).readBytes()
        val encodedAlternateName = alternateName.toByteArray()
        bytes.indices.forEach { index ->
            if(index + encodedAlternateName.size <= bytes.size &&
                bytes.copyOfRange(index, index + encodedAlternateName.size).contentEquals(encodedAlternateName)
            ) {
                bytes[index] = 'm'.code.toByte()
            }
        }
        return ByteArrayInputStream(bytes)
    }

    private fun assertExtractionFails(
        backup: ByteArrayInputStream,
        limits: ClipboardBackupExtractionLimits = ClipboardBackupExtractionLimits()
    ) {
        val dir = createTempDirectory().toFile()
        try {
            try {
                extractClipboardBackup(backup, dir, limits)
                throw AssertionError("Expected extraction to fail")
            } catch(_: IllegalArgumentException) {
                // Expected.
            }
        } finally {
            dir.deleteRecursively()
        }
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
