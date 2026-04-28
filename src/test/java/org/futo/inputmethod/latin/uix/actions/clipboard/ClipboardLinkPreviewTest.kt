package org.futo.inputmethod.latin.uix.actions.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
