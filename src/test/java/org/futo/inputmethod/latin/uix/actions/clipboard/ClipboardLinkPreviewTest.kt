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
    fun parsePixivPreviewMediaUrls_readsPixivPagesApi() {
        val urls = parsePixivPreviewMediaUrlsForTest(
            """
            {
              "error": false,
              "message": "",
              "body": [
                {
                  "urls": {
                    "regular": "https://i.pximg.net/img-master/img/2026/01/01/00/00/00/123_p0_master1200.jpg",
                    "original": "https://i.pximg.net/img-original/img/2026/01/01/00/00/00/123_p0.png"
                  }
                },
                {
                  "urls": {
                    "regular": "https://i.pximg.net/img-master/img/2026/01/01/00/00/00/123_p1_master1200.jpg",
                    "original": "https://i.pximg.net/img-original/img/2026/01/01/00/00/00/123_p1.jpg"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://i.pximg.net/img-original/img/2026/01/01/00/00/00/123_p0.png",
                "https://i.pximg.net/img-original/img/2026/01/01/00/00/00/123_p1.jpg"
            ),
            urls
        )
    }

    @Test
    fun pixivSessionCookieHeader_acceptsRawSessionIdOrCookieFragment() {
        assertEquals(
            "PHPSESSID=abc123",
            ClipboardLinkPreviewFetcher.pixivSessionCookieHeaderForTest("abc123")
        )
        assertEquals(
            "PHPSESSID=abc123",
            ClipboardLinkPreviewFetcher.pixivSessionCookieHeaderForTest("foo=bar; PHPSESSID=abc123; baz=qux")
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
    fun parseTwitterApiPreview_keepsQuotedPostAsReferencedManifest() {
        val manifest = parseTwitterApiPreviewForTest(
            """
            {
              "tweet": {
                "id": "123",
                "url": "https://x.com/futo/status/123",
                "text": "parent post",
                "author": {
                  "name": "FUTO",
                  "screen_name": "futo",
                  "id": "1"
                },
                "media": {
                  "photos": [
                    {"url": "https://pbs.twimg.com/media/parent.jpg"}
                  ]
                },
                "quote": {
                  "id": "456",
                  "url": "https://x.com/quote/status/456",
                  "text": "quoted post",
                  "author": {
                    "name": "Quote",
                    "screen_name": "quote",
                    "id": "2"
                  },
                  "media": {
                    "photos": [
                      {"url": "https://pbs.twimg.com/media/quote.jpg"}
                    ]
                  }
                }
              }
            }
            """.trimIndent()
        )!!

        assertEquals("twitter:123", manifest.archiveKey())
        assertEquals(listOf("https://pbs.twimg.com/media/parent.jpg?name=orig"), manifest.mediaItems.map { it.url })
        assertEquals(listOf(0), manifest.mediaItems.map { it.sourceIndex })

        val quoteManifest = manifest.referencedManifests.single()
        assertEquals("twitter:456", quoteManifest.archiveKey())
        assertEquals("quoted post", quoteManifest.snippet)
        assertEquals(listOf("https://pbs.twimg.com/media/quote.jpg?name=orig"), quoteManifest.mediaItems.map { it.url })
        assertEquals(listOf(0), quoteManifest.mediaItems.map { it.sourceIndex })
        assertEquals(emptyList<ClipboardLinkPreviewManifest>(), quoteManifest.referencedManifests)
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
    fun metadataForSupportedUrl_normalizesRedditPostToWwwReddit() {
        val metadata = ClipboardLinkPreviewFetcher.metadataForSupportedUrl(
            "https://www.reddit.com/r/futo/comments/abc123/a_title/"
        )

        assertEquals(ClipboardPreviewProvider.REDDIT, metadata?.provider)
        assertEquals("https://www.reddit.com/r/futo/comments/abc123/a_title", metadata?.sourceUrl)
        assertEquals("abc123", metadata?.sourceId)
        assertEquals("reddit:abc123", metadata?.archiveKey())
    }

    @Test
    fun previewCandidate_matchesExistingSupportMetadataAndImagePreferenceHelpers() {
        val text = "saved this https://www.pixiv.net/en/artworks/107946644"
        val candidate = ClipboardLinkPreviewFetcher.previewCandidateFor(text)

        assertTrue(ClipboardLinkPreviewFetcher.supportsPreview(text))
        assertEquals(ClipboardLinkPreviewFetcher.metadataForSupportedUrl(text), candidate?.metadata)
        assertEquals(ClipboardLinkPreviewFetcher.prefersImagePreview(text), candidate?.prefersImagePreview)
        assertEquals(ClipboardPreviewProvider.PIXIV, candidate?.provider)
        assertEquals("pixiv:107946644", candidate?.archiveKey)
    }

    @Test
    fun metadataForSupportedUrl_normalizesRedditCommentToStableKey() {
        val metadata = ClipboardLinkPreviewFetcher.metadataForSupportedUrl(
            "https://old.reddit.com/r/futo/comments/abc123/a_title/def456?context=3"
        )

        assertEquals(ClipboardPreviewProvider.REDDIT, metadata?.provider)
        assertEquals("https://www.reddit.com/r/futo/comments/abc123/a_title/def456", metadata?.sourceUrl)
        assertEquals("abc123:def456", metadata?.sourceId)
        assertEquals("reddit:abc123:def456", metadata?.archiveKey())
    }

    @Test
    fun metadataForSupportedUrl_supportsRedditShortLinks() {
        val metadata = ClipboardLinkPreviewFetcher.metadataForSupportedUrl("https://redd.it/abc123")

        assertEquals(ClipboardPreviewProvider.REDDIT, metadata?.provider)
        assertEquals("https://www.reddit.com/abc123", metadata?.sourceUrl)
        assertEquals("abc123", metadata?.sourceId)
    }

    @Test
    fun metadataForSupportedUrl_supportsRedditShareLinks() {
        val metadata = ClipboardLinkPreviewFetcher.metadataForSupportedUrl(
            "https://www.reddit.com/r/ComedyHell/s/j7xGCXRsdR"
        )

        assertEquals(ClipboardPreviewProvider.REDDIT, metadata?.provider)
        assertEquals("https://www.reddit.com/r/ComedyHell/s/j7xGCXRsdR", metadata?.sourceUrl)
        assertEquals("j7xGCXRsdR", metadata?.sourceId)
        assertEquals("reddit:j7xGCXRsdR", metadata?.archiveKey())
    }

    @Test
    fun normalizedTextForClipboardImport_rewritesUrlOnlyRedditLinksToWwwReddit() {
        assertEquals(
            "https://www.reddit.com/r/futo/comments/abc123/a_title",
            ClipboardLinkPreviewFetcher.normalizedTextForClipboardImport(
                "https://www.reddit.com/r/futo/comments/abc123/a_title/"
            )
        )
        assertEquals(
            "https://www.reddit.com/r/futo/comments/abc123/a_title",
            ClipboardLinkPreviewFetcher.normalizedTextForClipboardImport(
                "https://old.reddit.com/r/futo/comments/abc123/a_title/"
            )
        )
        assertEquals(
            "https://www.reddit.com/r/futo/comments/abc123/a_title",
            ClipboardLinkPreviewFetcher.normalizedTextForClipboardImport(
                "https://rxddit.com/r/futo/comments/abc123/a_title"
            )
        )
    }

    @Test
    fun normalizedTextForClipboardImport_keepsEmbeddedRedditAndOtherProvidersUnchanged() {
        val embedded = "read this https://www.reddit.com/r/futo/comments/abc123/a_title/"
        val twitter = "https://x.com/futo/status/1234567890"

        assertEquals(embedded, ClipboardLinkPreviewFetcher.normalizedTextForClipboardImport(embedded))
        assertEquals(twitter, ClipboardLinkPreviewFetcher.normalizedTextForClipboardImport(twitter))
    }

    @Test
    fun phixivArtworkPasteSession_incrementsRepeatedUrlOnlyArtworkPastes() {
        val session = PhixivArtworkPasteSession()
        val url = "https://www.phixiv.net/en/artworks/39832455"

        assertEquals(url, session.textForPaste(url))
        assertEquals("\n$url/2", session.textForPaste(url))
        assertEquals("\n$url/3", session.textForPaste(url))
    }

    @Test
    fun phixivArtworkPasteSession_sharesCounterAcrossPlainAndWrappedPasteCalls() {
        val session = PhixivArtworkPasteSession()
        val url = "https://www.phixiv.net/en/artworks/39832455"

        assertEquals(url, session.textForPaste(url))
        assertEquals("\n||$url/2||", session.wrappedTextForPaste(url))
        assertEquals("\n$url/3", session.textForPaste(url))
    }

    @Test
    fun phixivArtworkPasteSession_incrementsStoredSpoilerWrappedUrls() {
        val session = PhixivArtworkPasteSession()
        val url = "https://www.phixiv.net/en/artworks/39832455"

        assertEquals("||$url||", session.textForPaste("||$url||"))
        assertEquals("\n$url/2", session.textForPaste(url))
        assertEquals("\n||$url/3||", session.textForPaste("||$url||"))
        assertEquals("\n||$url/4||", session.wrappedTextForPaste("||$url||"))
    }

    @Test
    fun phixivArtworkPasteSession_usesExistingPageSuffixAsStart() {
        val session = PhixivArtworkPasteSession()
        val baseUrl = "https://www.phixiv.net/en/artworks/39832455"

        assertEquals("$baseUrl/2", session.textForPaste("$baseUrl/2"))
        assertEquals("\n$baseUrl/3", session.textForPaste("$baseUrl/2"))
    }

    @Test
    fun phixivArtworkPasteSession_normalizesPixivAndLanguageArtworkPaths() {
        val session = PhixivArtworkPasteSession()

        assertEquals(
            "https://www.phixiv.net/en/artworks/39832455",
            session.textForPaste("https://www.pixiv.net/artworks/39832455")
        )
        assertEquals(
            "https://www.phixiv.net/ja/artworks/39832455",
            session.textForPaste("https://www.pixiv.net/ja/artworks/39832455")
        )
    }

    @Test
    fun phixivArtworkPasteSession_keepsEmbeddedAndNonPixivTextUnchanged() {
        val session = PhixivArtworkPasteSession()
        val embedded = "saved https://www.phixiv.net/en/artworks/39832455"
        val twitter = "https://x.com/futo/status/1234567890"

        assertEquals(embedded, session.textForPaste(embedded))
        assertEquals(twitter, session.textForPaste(twitter))
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
    fun parseRedditEmbedPreview_returnsTitleAuthorAndThumbnail() {
        val manifest = parseRedditEmbedPreviewForTest(
            """
            {
              "author_name": "futo",
              "html": "<blockquote class=\"reddit-embed-bq\"><a href=\"https://www.reddit.com/r/futo/comments/abc123/title/\">I'm hungry</a></blockquote>",
              "provider_name": "reddit",
              "provider_url": "https://www.reddit.com",
              "thumbnail_url": "https://preview.redd.it/post.jpg?width=960&amp;format=pjpg",
              "title": "I'm hungry",
              "type": "rich"
            }
            """.trimIndent()
        )

        assertEquals("I'm hungry", manifest?.snippet)
        assertEquals("I'm hungry", manifest?.metadata?.title)
        assertEquals("futo", manifest?.metadata?.authorName)
        assertEquals(
            listOf("https://preview.redd.it/post.jpg?width=960&format=pjpg"),
            manifest?.mediaItems?.map { it.url }
        )
    }

    @Test
    fun parseRedditEmbedPreview_allowsTextOnlyEmbed() {
        val manifest = parseRedditEmbedPreviewForTest(
            """
            {
              "author_name": "futo",
              "html": "<blockquote class=\"reddit-embed-bq\"></blockquote>",
              "provider_name": "reddit",
              "provider_url": "https://www.reddit.com",
              "title": "is there a way",
              "type": "rich"
            }
            """.trimIndent()
        )

        assertEquals("is there a way", manifest?.snippet)
        assertEquals("is there a way", manifest?.metadata?.title)
        assertEquals(emptyList<String>(), manifest?.mediaItems?.map { it.url })
    }

    @Test
    fun parseRedditApiPreview_returnsGalleryMediaInOrder() {
        val manifest = parseRedditApiPreviewForTest(
            """
            {
              "data": {
                "children": [
                  {
                    "data": {
                      "id": "abc123",
                      "title": "Gallery post",
                      "author": "futo",
                      "permalink": "/r/futo/comments/abc123/gallery_post/",
                      "created_utc": 1717000000,
                      "ups": 42,
                      "num_comments": 7,
                      "over_18": false,
                      "gallery_data": {
                        "items": [
                          {"media_id": "one"},
                          {"media_id": "two"}
                        ]
                      },
                      "media_metadata": {
                        "one": {"s": {"u": "https://preview.redd.it/one.jpg?width=1200&amp;format=pjpg"}},
                        "two": {"s": {"u": "https://preview.redd.it/two.png?width=1200&amp;format=png"}}
                      }
                    }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals("Gallery post", manifest?.snippet)
        assertEquals("futo", manifest?.metadata?.authorHandle)
        assertEquals("https://www.reddit.com/r/futo/comments/abc123/gallery_post/", manifest?.metadata?.sourceUrl)
        assertEquals(
            listOf(
                "https://preview.redd.it/one.jpg?width=1200&format=pjpg",
                "https://preview.redd.it/two.png?width=1200&format=png"
            ),
            manifest?.mediaItems?.map { it.url }
        )
    }

    @Test
    fun parseRedditApiPreview_deduplicatesOriginalAndPreviewImage() {
        val manifest = parseRedditApiPreviewForTest(
            """
            {
              "data": {
                "children": [
                  {
                    "data": {
                      "id": "abc123",
                      "title": "Single image post",
                      "url_overridden_by_dest": "https://i.redd.it/one.jpg",
                      "preview": {
                        "images": [
                          {
                            "source": {
                              "url": "https://preview.redd.it/one.jpg?width=960&amp;format=pjpg"
                            }
                          }
                        ]
                      }
                    }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals(
            listOf("https://i.redd.it/one.jpg"),
            manifest?.mediaItems?.map { it.url }
        )
    }

    @Test
    fun parseRedditApiPreview_returnsVideoFallbackBeforePreviewImage() {
        val manifest = parseRedditApiPreviewForTest(
            """
            {
              "data": {
                "children": [
                  {
                    "data": {
                      "id": "abc123",
                      "title": "Video post",
                      "secure_media": {
                        "reddit_video": {
                          "fallback_url": "https://v.redd.it/clip/DASH_720.mp4?source=fallback"
                        }
                      },
                      "preview": {
                        "images": [
                          {
                            "source": {
                              "url": "https://preview.redd.it/thumb.jpg?width=960&amp;format=pjpg"
                            }
                          }
                        ]
                      }
                    }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "https://v.redd.it/clip/DASH_720.mp4?source=fallback",
                "https://preview.redd.it/thumb.jpg?width=960&format=pjpg"
            ),
            manifest?.mediaItems?.map { it.url }
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
