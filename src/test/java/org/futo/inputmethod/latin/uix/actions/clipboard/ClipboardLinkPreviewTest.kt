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
    fun parseTwitterApiPreviewMediaUrls_ignoresStatusPageUrls() {
        val urls = parseTwitterApiPreviewMediaUrlsForTest(
            """
            {
              "tweet": {
                "id": "123",
                "media": {
                  "photos": [
                    {"url": "https://x.com/com/status/1234567890"},
                    {"url": "https://pbs.twimg.com/media/a.jpg"}
                  ]
                },
                "card": {
                  "image": {"url": "https://x.com/com/status/1234567890"}
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(listOf("https://pbs.twimg.com/media/a.jpg?name=orig"), urls)
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
    fun parseTwitterHtmlPreviewMediaUrls_ignoresStatusPageUrls() {
        val urls = parseTwitterHtmlPreviewMediaUrlsForTest(
            """
            <html>
              <head>
                <meta property="og:description" content="hello" />
                <meta property="og:image" content="https://x.com/com/status/1234567890" />
              </head>
            </html>
            """.trimIndent()
        )

        assertEquals(emptyList<String>(), urls)
    }

    @Test
    fun metadataForSupportedUrl_normalizesRedditPostToRxddit() {
        val metadata = ClipboardLinkPreviewFetcher.metadataForSupportedUrl(
            "https://www.reddit.com/r/futo/comments/abc123/a_title/"
        )

        assertEquals(ClipboardPreviewProvider.REDDIT, metadata?.provider)
        assertEquals("https://rxddit.com/r/futo/comments/abc123/a_title", metadata?.sourceUrl)
        assertEquals("abc123", metadata?.sourceId)
        assertEquals("reddit:abc123", metadata?.archiveKey())
    }

    @Test
    fun metadataForSupportedUrl_normalizesRedditCommentToStableKey() {
        val metadata = ClipboardLinkPreviewFetcher.metadataForSupportedUrl(
            "https://old.reddit.com/r/futo/comments/abc123/a_title/def456?context=3"
        )

        assertEquals(ClipboardPreviewProvider.REDDIT, metadata?.provider)
        assertEquals("https://rxddit.com/r/futo/comments/abc123/a_title/def456", metadata?.sourceUrl)
        assertEquals("abc123:def456", metadata?.sourceId)
        assertEquals("reddit:abc123:def456", metadata?.archiveKey())
    }

    @Test
    fun metadataForSupportedUrl_supportsRedditShortLinks() {
        val metadata = ClipboardLinkPreviewFetcher.metadataForSupportedUrl("https://redd.it/abc123")

        assertEquals(ClipboardPreviewProvider.REDDIT, metadata?.provider)
        assertEquals("https://rxddit.com/abc123", metadata?.sourceUrl)
        assertEquals("abc123", metadata?.sourceId)
    }

    @Test
    fun metadataForSupportedUrl_normalizesYouTubeVideoUrls() {
        val watch = ClipboardLinkPreviewFetcher.metadataForSupportedUrl(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=playlist"
        )
        val short = ClipboardLinkPreviewFetcher.metadataForSupportedUrl(
            "https://youtu.be/dQw4w9WgXcQ?si=share"
        )
        val shorts = ClipboardLinkPreviewFetcher.metadataForSupportedUrl(
            "https://m.youtube.com/shorts/dQw4w9WgXcQ"
        )
        val music = ClipboardLinkPreviewFetcher.metadataForSupportedUrl(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ"
        )

        assertEquals(ClipboardPreviewProvider.YOUTUBE, watch?.provider)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", watch?.sourceUrl)
        assertEquals("dQw4w9WgXcQ", watch?.sourceId)
        assertEquals("youtube:dQw4w9WgXcQ", watch?.archiveKey())
        assertEquals(watch, short)
        assertEquals(watch, shorts)
        assertEquals(watch, music)
    }

    @Test
    fun metadataForSupportedUrl_ignoresYouTubeUrlsWithoutVideoId() {
        assertEquals(
            null,
            ClipboardLinkPreviewFetcher.metadataForSupportedUrl("https://www.youtube.com/@futo")
        )
        assertEquals(
            null,
            ClipboardLinkPreviewFetcher.metadataForSupportedUrl("https://www.youtube.com/playlist?list=abc123")
        )
    }

    @Test
    fun metadataForSupportedUrl_ignoresRedditListingUrls() {
        assertEquals(
            null,
            ClipboardLinkPreviewFetcher.metadataForSupportedUrl("https://www.reddit.com/r/futo/")
        )
    }

    @Test
    fun parseRedditHtmlPreviewMediaUrls_returnsCardMediaInOrder() {
        val urls = parseRedditHtmlPreviewMediaUrlsForTest(
            """
            <html>
              <head>
                <meta property="og:description" content="hello" />
                <meta property="og:image" content="https://i.redd.it/one.jpg" />
                <meta name="twitter:image" content="https://i.redd.it/two.jpg" />
                <meta property="og:video" content="https://v.redd.it/clip/DASH_720.mp4" />
              </head>
            </html>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://v.redd.it/clip/DASH_720.mp4",
                "https://i.redd.it/one.jpg",
                "https://i.redd.it/two.jpg"
            ),
            urls
        )
    }

    @Test
    fun parseYouTubeOEmbedPreview_returnsTitleAndThumbnailOnly() {
        val manifest = parseYouTubeOEmbedPreviewForTest(
            """
            {
              "version": "1.0",
              "type": "video",
              "provider_name": "YouTube",
              "title": "Keyboard preview demo",
              "author_name": "FUTO",
              "thumbnail_url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
              "html": "<iframe src=\"https://www.youtube.com/embed/dQw4w9WgXcQ\"></iframe>"
            }
            """.trimIndent()
        )

        assertEquals("Keyboard preview demo", manifest?.snippet)
        assertEquals(
            listOf("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"),
            manifest?.mediaItems?.map { it.url }
        )
        assertEquals(listOf("image/jpeg"), manifest?.mediaItems?.map { it.mimeType })
        assertEquals(ClipboardPreviewProvider.YOUTUBE, manifest?.metadata?.provider)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", manifest?.metadata?.sourceUrl)
        assertEquals("dQw4w9WgXcQ", manifest?.metadata?.sourceId)
        assertEquals("Keyboard preview demo", manifest?.metadata?.title)
        assertEquals("FUTO", manifest?.metadata?.authorName)
        assertEquals(1, manifest?.metadata?.imageCount)
        assertEquals(0, manifest?.metadata?.selectedImageIndex)
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
    fun metadataForSupportedUrl_routesToProviderAdapters() {
        val twitter = ClipboardLinkPreviewFetcher.metadataForSupportedUrl("https://x.com/futo/status/1234567890")
        val pixiv = ClipboardLinkPreviewFetcher.metadataForSupportedUrl("https://www.pixiv.net/en/artworks/107946644")
        val reddit = ClipboardLinkPreviewFetcher.metadataForSupportedUrl("https://www.reddit.com/r/futo/comments/abc123/title/")
        val youtube = ClipboardLinkPreviewFetcher.metadataForSupportedUrl("https://www.youtube.com/embed/dQw4w9WgXcQ")

        assertEquals(ClipboardPreviewProvider.TWITTER, twitter?.provider)
        assertEquals("1234567890", twitter?.sourceId)
        assertEquals(ClipboardPreviewProvider.PIXIV, pixiv?.provider)
        assertEquals("107946644", pixiv?.sourceId)
        assertEquals(ClipboardPreviewProvider.REDDIT, reddit?.provider)
        assertEquals("abc123", reddit?.sourceId)
        assertEquals(ClipboardPreviewProvider.YOUTUBE, youtube?.provider)
        assertEquals("dQw4w9WgXcQ", youtube?.sourceId)
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
