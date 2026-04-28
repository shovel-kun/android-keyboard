package org.futo.inputmethod.latin.uix.actions.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ClipboardBackupTest {
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
    fun reconcileClipboardEntriesWithStorage_retainsArchiveBackedPreviewMedia() {
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
                clipboardDir = clipboardDir,
                archiveDir = archiveDir
            )

            assertEquals(listOf("archived.jpg"), reconciled.single().previewMediaFileNames())
        } finally {
            clipboardDir.deleteRecursively()
            archiveDir.deleteRecursively()
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
                archiveDir = dir,
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
    fun retryableMedia_selectsPendingFailedAndMissingOnly() {
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
                "https://img.example/missing.jpg"
            ),
            archive.retryableMedia().map { it.sourceUrl }
        )
        assertTrue(archive.hasRetryableMedia())
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
