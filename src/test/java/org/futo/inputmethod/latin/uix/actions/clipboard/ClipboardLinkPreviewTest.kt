package org.futo.inputmethod.latin.uix.actions.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class ClipboardLinkPreviewTest {
    @Test
    fun parsePixivPreviewMediaUrls_returnsAllImagesInOrder() {
        val urls = parsePixivPreviewMediaUrlsForTest(
            """
            {
              "image_proxy_urls": [
                "https://img.example/0.jpg",
                "https://img.example/1.jpg",
                "https://img.example/2.jpg"
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://img.example/0.jpg",
                "https://img.example/1.jpg",
                "https://img.example/2.jpg"
            ),
            urls
        )
    }

    @Test
    fun parsePixivPreviewMediaUrls_prioritizesRequestedPageAndKeepsRest() {
        val urls = parsePixivPreviewMediaUrlsForTest(
            """
            {
              "image_proxy_urls": [
                "https://img.example/0.jpg",
                "https://img.example/1.jpg",
                "https://img.example/2.jpg"
              ]
            }
            """.trimIndent(),
            pageIndex = 2
        )

        assertEquals(
            listOf(
                "https://img.example/2.jpg",
                "https://img.example/0.jpg",
                "https://img.example/1.jpg"
            ),
            urls
        )
    }

    @Test
    fun parsePixivPreviewMediaUrls_keepsLargePostsForArchivalRetention() {
        val urls = parsePixivPreviewMediaUrlsForTest(
            """
            {
              "image_proxy_urls": [
                "https://img.example/0.jpg",
                "https://img.example/1.jpg",
                "https://img.example/2.jpg",
                "https://img.example/3.jpg",
                "https://img.example/4.jpg",
                "https://img.example/5.jpg"
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://img.example/0.jpg",
                "https://img.example/1.jpg",
                "https://img.example/2.jpg",
                "https://img.example/3.jpg",
                "https://img.example/4.jpg",
                "https://img.example/5.jpg"
            ),
            urls
        )
    }

    @Test
    fun parseTwitterApiPreviewMediaUrls_returnsAllPhotos() {
        val urls = parseTwitterApiPreviewMediaUrlsForTest(
            """
            {
              "tweet": {
                "id": "123",
                "media": {
                  "photos": [
                    {"url": "https://pbs.twimg.com/media/a.jpg"},
                    {"url": "https://pbs.twimg.com/media/b.jpg?name=small"}
                  ]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://pbs.twimg.com/media/a.jpg?name=orig",
                "https://pbs.twimg.com/media/b.jpg?name=orig"
            ),
            urls
        )
    }

    @Test
    fun parseTwitterApiPreviewMediaUrls_returnsPhotosAndVideos() {
        val urls = parseTwitterApiPreviewMediaUrlsForTest(
            """
            {
              "tweet": {
                "id": "123",
                "media": {
                  "photos": [
                    {"url": "https://pbs.twimg.com/media/a.jpg"}
                  ],
                  "videos": [
                    {"url": "https://video.twimg.com/ext_tw_video/clip.mp4"},
                    {"thumbnail_url": "https://pbs.twimg.com/ext_tw_video/thumb.jpg"}
                  ]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://pbs.twimg.com/media/a.jpg?name=orig",
                "https://video.twimg.com/ext_tw_video/clip.mp4",
                "https://pbs.twimg.com/ext_tw_video/thumb.jpg"
            ),
            urls
        )
    }

    @Test
    fun parseTwitterHtmlPreviewMediaUrls_returnsSingleOpenGraphMedia() {
        val urls = parseTwitterHtmlPreviewMediaUrlsForTest(
            """
            <html>
              <head>
                <meta property="og:description" content="hello" />
                <meta property="og:image" content="https://pbs.twimg.com/card.jpg" />
              </head>
            </html>
            """.trimIndent()
        )

        assertEquals(listOf("https://pbs.twimg.com/card.jpg"), urls)
    }

    @Test
    fun previewRequestCatching_preservesRateLimitFailure() {
        assertEquals(
            null,
            ClipboardLinkPreviewFetcher.runPreviewRequestCatchingForTest(IOException("network"))
        )

        val rateLimited = ClipboardLinkPreviewFetcher.previewRateLimitedExceptionForTest(
            retryAfterEpochMs = 220_000L,
            message = "HTTP 429"
        )

        try {
            ClipboardLinkPreviewFetcher.runPreviewRequestCatchingForTest(rateLimited)
        } catch (e: Exception) {
            assertTrue(e === rateLimited)
            return
        }

        throw AssertionError("Expected preview rate limit failure to be rethrown")
    }

    @Test
    fun fetchManifestResult_reportsUnsupportedUrlFailureDetail() {
        val result = ClipboardLinkPreviewFetcher.fetchManifestResult("plain text")

        assertEquals(null, result.manifest)
        assertTrue(result.failureDetail?.contains("Unsupported preview URL") == true)
    }

    @Test
    fun unavailablePreviewFailure_detectsSuspendedCopyWithoutMedia() {
        val failure = unavailablePreviewFailureForTest(
            snippet = "This post is from a suspended account."
        )

        assertTrue(failure is ClipboardPreviewFetchFailure.Unavailable)
        assertTrue(failure?.detail?.contains("suspended account") == true)
    }

    @Test
    fun unavailablePreviewFailure_doesNotClassifyRealMediaManifest() {
        val failure = unavailablePreviewFailureForTest(
            snippet = "This post is from a suspended account.",
            mediaItems = listOf(ClipboardLinkPreviewMedia("https://img.example/one.jpg", 0, "image/jpeg"))
        )

        assertEquals(null, failure)
    }

    @Test
    fun retryAfterEpochMs_parsesSeconds() {
        assertEquals(220_000L, retryAfterEpochMs("120", nowEpochMs = 100_000L))
    }

    @Test
    fun retryAfterEpochMs_parsesHttpDate() {
        assertEquals(
            784111777000L,
            retryAfterEpochMs("Sun, 06 Nov 1994 08:49:37 GMT", nowEpochMs = 100_000L)
        )
    }

    @Test
    fun retryAfterEpochMs_defaultsWhenMissingOrInvalid() {
        assertEquals(
            100_000L + DefaultPreviewRateLimitCooldownMillis,
            retryAfterEpochMs(null, nowEpochMs = 100_000L)
        )
        assertEquals(
            100_000L + DefaultPreviewRateLimitCooldownMillis,
            retryAfterEpochMs("not a date", nowEpochMs = 100_000L)
        )
    }

    @Test
    fun copyToCapped_reportsKnownTotalProgress() {
        val inputBytes = ByteArray(20_000) { it.toByte() }
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<ClipboardPreviewMediaDownloadProgress>()

        ByteArrayInputStream(inputBytes).copyToCapped(
            output = output,
            limit = 50_000,
            totalBytes = inputBytes.size.toLong(),
            onProgress = progress::add
        )

        assertTrue(progress.size > 1)
        assertEquals(inputBytes.size.toLong(), progress.last().completedBytes)
        assertEquals(inputBytes.size.toLong(), progress.last().totalBytes)
        assertEquals(inputBytes.toList(), output.toByteArray().toList())
    }

    @Test
    fun copyToCapped_reportsUnknownTotalProgress() {
        val inputBytes = ByteArray(12_000) { 1 }
        val progress = mutableListOf<ClipboardPreviewMediaDownloadProgress>()

        ByteArrayInputStream(inputBytes).copyToCapped(
            output = ByteArrayOutputStream(),
            limit = 50_000,
            totalBytes = null,
            onProgress = progress::add
        )

        assertEquals(inputBytes.size.toLong(), progress.last().completedBytes)
        assertEquals(null, progress.last().totalBytes)
    }

    @Test
    fun copyToCapped_throwsWhenLimitExceeded() {
        val inputBytes = ByteArray(12_000) { 1 }

        try {
            ByteArrayInputStream(inputBytes).copyToCapped(
                output = ByteArrayOutputStream(),
                limit = 8_000
            )
        } catch (_: IllegalStateException) {
            return
        }

        throw AssertionError("Expected capped preview copy to fail when the limit is exceeded")
    }
}
