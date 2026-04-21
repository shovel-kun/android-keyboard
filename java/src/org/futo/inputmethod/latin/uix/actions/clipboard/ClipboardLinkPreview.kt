package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.Context
import android.os.Build
import android.text.Html
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ClipboardLinkPreview(
    val snippet: String?,
    val imageFile: String?,
    val metadata: ClipboardPreviewMetadata?
)

private val LinkPreviewJson = Json {
    ignoreUnknownKeys = true
}

private val LinkPreviewUrlRegex = """https?://[^\s<>()]+""".toRegex()

private val SupportedTwitterHosts = setOf(
    "twitter.com",
    "www.twitter.com",
    "mobile.twitter.com",
    "x.com",
    "www.x.com",
    "mobile.x.com",
    "fxtwitter.com",
    "www.fxtwitter.com",
    "fixupx.com",
    "www.fixupx.com",
    "twittpr.com",
    "www.twittpr.com",
    "vxtwitter.com",
    "www.vxtwitter.com",
    "fixvx.com",
    "www.fixvx.com"
)

private val SupportedPixivHosts = setOf(
    "pixiv.net",
    "www.pixiv.net",
    "phixiv.net",
    "www.phixiv.net"
)

private const val PreviewConnectTimeoutMillis = 5_000
private const val PreviewReadTimeoutMillis = 10_000
private const val MaxPreviewJsonBytes = 1_000_000
private const val MaxPreviewImageBytes = 20_000_000

object ClipboardLinkPreviewFetcher {
    fun supportsPreview(rawText: String): Boolean =
        extractPreviewRequest(rawText) != null

    fun prefersImagePreview(rawText: String): Boolean =
        extractPreviewRequest(rawText) is PixivArtworkUrl

    fun fetch(context: Context, rawText: String): ClipboardLinkPreview? {
        val request = extractPreviewRequest(rawText) ?: return null
        val preview = try {
            when (request) {
                is TwitterStatusUrl -> fetchTwitterPreview(request)
                is PixivArtworkUrl -> fetchPixivPreview(request)
            }
        } catch (_: Exception) {
            null
        } ?: return null

        val imageFile = preview.imageUrl?.let { cachePreviewImage(context, it) }
        if (preview.snippet == null && imageFile == null && preview.metadata == null) return null

        return ClipboardLinkPreview(
            snippet = preview.snippet,
            imageFile = imageFile,
            metadata = preview.metadata
        )
    }

    private fun fetchTwitterPreview(statusUrl: TwitterStatusUrl): RemotePreviewData? {
        return requestTwitterPreview(statusUrl)
    }

    private fun parseTwitterApiPreview(response: JsonObject, statusUrl: TwitterStatusUrl): RemotePreviewData? {
        val tweet = response.objectValue("tweet") ?: return null
        val rawBodyText = tweet.stringValue("text")?.trim()?.takeIf { it.isNotBlank() }
        val card = tweet.objectValue("card") ?: tweet.objectValue("twitter_card")
        val title = card?.stringValue("title")?.trim()?.takeIf { it.isNotBlank() }
        val description = card?.stringValue("description")?.trim()?.takeIf { it.isNotBlank() }
        val snippet = (rawBodyText ?: title ?: description)?.let { sanitizeClipboardText(it, 160) }

        val media = tweet.objectValue("media")
        val imageUrl = media?.arrayValue("photos")?.firstObject()?.stringValue("url")?.toOriginalSizedImageUrl()
            ?: media?.objectValue("mosaic")?.objectValue("formats")?.stringValue("jpeg")
            ?: media?.arrayValue("videos")?.firstObject()?.stringValue("thumbnail_url")
            ?: card?.objectValue("image")?.stringValue("url")

        val author = tweet.objectValue("author")
        val metadata = ClipboardPreviewMetadata(
            provider = ClipboardPreviewProvider.TWITTER,
            sourceUrl = tweet.stringValue("url") ?: statusUrl.canonicalUrl(),
            sourceId = tweet.stringValue("id") ?: statusUrl.id,
            title = title,
            bodyText = rawBodyText ?: description,
            authorName = author?.stringValue("name"),
            authorHandle = author?.stringValue("screen_name"),
            authorId = author?.stringValue("id"),
            createdAt = tweet.stringValue("created_at"),
            imageCount = media?.arrayValue("photos")?.size,
            selectedImageIndex = 0.takeIf { imageUrl != null },
            stats = ClipboardPreviewStats(
                likeCount = tweet.longValue("likes"),
                bookmarkCount = tweet.longValue("bookmarks"),
                viewCount = tweet.longValue("views"),
                replyCount = tweet.longValue("replies"),
                repostCount = tweet.longValue("retweets"),
                quoteCount = tweet.longValue("quotes")
            ),
            flags = ClipboardPreviewFlags(
                noteTweet = tweet.booleanValue("is_note_tweet") == true
            )
        ).nullIfEmpty()

        return RemotePreviewData(
            snippet = snippet,
            imageUrl = imageUrl,
            metadata = metadata
        )
    }

    private fun fetchPixivPreview(artworkUrl: PixivArtworkUrl): RemotePreviewData? {
        val response = requestPixivPreview(artworkUrl) ?: return null
        val imageUrls = response.stringArrayValue("image_proxy_urls")
        val imageUrl = imageUrls
            .getOrNull(artworkUrl.pageIndex ?: 0)
            ?: imageUrls.firstOrNull()
        val description = response.stringValue("description")?.stripSimpleHtml()?.takeIf { it.isNotBlank() }
        val metadata = ClipboardPreviewMetadata(
            provider = ClipboardPreviewProvider.PIXIV,
            sourceUrl = response.stringValue("url") ?: artworkUrl.canonicalUrl(),
            sourceId = response.stringValue("illust_id") ?: artworkUrl.id,
            title = response.stringValue("title")?.trim()?.takeIf { it.isNotBlank() },
            bodyText = description,
            authorName = response.stringValue("author_name"),
            authorId = response.stringValue("author_id"),
            createdAt = response.stringValue("create_date"),
            imageCount = imageUrls.size.takeIf { it > 0 },
            selectedImageIndex = (artworkUrl.pageIndex ?: 0).takeIf { imageUrls.isNotEmpty() },
            tags = response.stringArrayValue("tags"),
            stats = ClipboardPreviewStats(
                likeCount = response.longValue("like_count"),
                bookmarkCount = response.longValue("bookmark_count"),
                viewCount = response.longValue("view_count"),
                commentCount = response.longValue("comment_count")
            ),
            flags = ClipboardPreviewFlags(
                aiGenerated = response.booleanValue("ai_generated") == true,
                animated = response.booleanValue("is_ugoira") == true,
                restricted = (response.longValue("x_restrict") ?: 0L) > 0L
            )
        ).nullIfEmpty()

        return RemotePreviewData(
            snippet = null,
            imageUrl = imageUrl,
            metadata = metadata
        )
    }

    private fun requestTwitterPreview(statusUrl: TwitterStatusUrl): RemotePreviewData? {
        requestTwitterApiJson(statusUrl)?.let { response ->
            parseTwitterApiPreview(response, statusUrl)?.let { return it }
        }

        return requestTwitterHtmlPreview(statusUrl)
    }

    private fun requestTwitterApiJson(statusUrl: TwitterStatusUrl): JsonObject? {
        val requestUrl = if (statusUrl.handle != null) {
            "https://api.fxtwitter.com/${statusUrl.handle}/status/${statusUrl.id}"
        } else {
            "https://api.fxtwitter.com/status/${statusUrl.id}"
        }

        return runCatching {
            requestJsonObject(requestUrl, MaxPreviewJsonBytes)
        }.getOrNull()
    }

    private fun requestTwitterHtmlPreview(statusUrl: TwitterStatusUrl): RemotePreviewData? {
        val html = runCatching {
            requestText(statusUrl.fixupxUrl(), MaxPreviewJsonBytes)
        }.getOrNull() ?: return null

        val snippet = html.htmlMetaContent("og:description")
            ?.stripSimpleHtml()
            ?.takeIf { it.isNotBlank() }
            ?.let { sanitizeClipboardText(it, 160) }

        val authorHandle = sequenceOf("twitter:creator", "twitter:site")
            .mapNotNull { html.htmlMetaContent(it) }
            .map { it.removePrefix("@").trim() }
            .firstOrNull { it.isNotBlank() }

        val authorName = html.htmlMetaContent("og:title")
            ?.stripSimpleHtml()
            ?.substringBefore(" (@")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val imageUrl = html.htmlMetaContent("og:image")
            ?.takeIf { it.isNotBlank() && !it.contains("/profile_images/") }

        val metadata = ClipboardPreviewMetadata(
            provider = ClipboardPreviewProvider.TWITTER,
            sourceUrl = html.htmlCanonicalUrl() ?: statusUrl.canonicalUrl(),
            sourceId = statusUrl.id,
            bodyText = snippet,
            authorName = authorName,
            authorHandle = authorHandle
        ).nullIfEmpty()

        if (snippet == null && imageUrl == null && metadata == null) return null

        return RemotePreviewData(
            snippet = snippet,
            imageUrl = imageUrl,
            metadata = metadata
        )
    }

    private fun requestPixivPreview(artworkUrl: PixivArtworkUrl): JsonObject? {
        val requestUrl = "https://www.phixiv.net/api/info?id=${artworkUrl.id}&language=${artworkUrl.language}"
        return requestJsonObject(requestUrl, MaxPreviewJsonBytes)
    }

    private fun cachePreviewImage(context: Context, imageUrl: String): String? {
        val fileBaseName = "preview_${imageUrl.md5Hex()}"
        context.clipboardDir.mkdirs()
        findCachedPreviewFile(context, fileBaseName)?.let { return it }

        val tempFile = File(context.cacheDir, "${fileBaseName}.tmp")
        try {
            var outputFile: File? = null
            withConnection(imageUrl) { connection ->
                val contentType = connection.contentType.orEmpty()
                val mimeType = contentType.normalizedMimeType()
                if (!mimeType.startsWith("image/")) return null

                val extension = mimeType.fileExtensionForMimeType() ?: imageUrl.fileExtensionHint()
                val fileName = "$fileBaseName.$extension"
                outputFile = File(context.clipboardDir, fileName)
                if (outputFile!!.exists()) return fileName

                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyToCapped(output, MaxPreviewImageBytes)
                    }
                }

                fileName
            }?.let { existingFileName ->
                if (outputFile?.exists() == true) {
                    tempFile.delete()
                    return existingFileName
                }
            }

            val finalFile = outputFile ?: return null
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            ClipboardUtil.generateThumbnail(finalFile)
            return finalFile.name
        } catch (_: Exception) {
            tempFile.delete()
            return null
        }
    }

    private fun requestJsonObject(url: String, maxBytes: Int): JsonObject =
        withConnection(url) { connection ->
            connection.inputStream.use { stream ->
                LinkPreviewJson.parseToJsonElement(stream.readStringCapped(maxBytes)).jsonObject
            }
        }

    private fun requestText(url: String, maxBytes: Int): String =
        withConnection(url) { connection ->
            connection.inputStream.use { stream ->
                stream.readStringCapped(maxBytes)
            }
        }

    private inline fun <T> withConnection(url: String, block: (HttpURLConnection) -> T): T {
        val connection = openConnection(url)
        return try {
            block(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = PreviewConnectTimeoutMillis
        connection.readTimeout = PreviewReadTimeoutMillis
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) FutoKeyboardLinkPreview/1.0")
        connection.setRequestProperty("Accept", "application/json,image/*,*/*")
        return connection
    }

    private fun extractPreviewRequest(rawText: String): PreviewRequest? {
        return LinkPreviewUrlRegex.findAll(rawText).firstNotNullOfOrNull { match ->
            val url = match.value.trimEnd('.', ',', ';', ':', ')', ']', '}')
            parseTwitterStatusUrl(url) ?: parsePixivArtworkUrl(url)
        }
    }

    private fun parseTwitterStatusUrl(url: String): TwitterStatusUrl? {
        val uri = runCatching { URL(url).toURI() }.getOrNull() ?: return null
        if (!SupportedTwitterHosts.contains(uri.host?.lowercase())) return null

        val segments = uri.path.split('/').filter { it.isNotBlank() }
        val parsed = when {
            segments.size >= 3 && (segments[1] == "status" || segments[1] == "statuses") ->
                TwitterStatusUrl(handle = segments[0], id = segments[2])
            segments.size >= 3 && segments[0] == "i" && (segments[1] == "status" || segments[1] == "statuses") ->
                TwitterStatusUrl(handle = null, id = segments[2])
            segments.size >= 2 && (segments[0] == "status" || segments[0] == "statuses") ->
                TwitterStatusUrl(handle = null, id = segments[1])
            else -> null
        } ?: return null

        val id = parsed.id.takeWhile { it.isDigit() }
        if (id.length < 2) return null

        return parsed.copy(id = id)
    }

    private fun parsePixivArtworkUrl(url: String): PixivArtworkUrl? {
        val uri = runCatching { URL(url).toURI() }.getOrNull() ?: return null
        if (!SupportedPixivHosts.contains(uri.host?.lowercase())) return null

        val segments = uri.path.split('/').filter { it.isNotBlank() }
        val parsed = when {
            segments.size >= 2 && segments[0] == "artworks" ->
                PixivArtworkUrl(
                    id = segments[1],
                    pageIndex = segments.getOrNull(2)?.toIntOrNull(),
                    language = "en"
                )
            segments.size >= 3 && segments[1] == "artworks" ->
                PixivArtworkUrl(
                    id = segments[2],
                    pageIndex = segments.getOrNull(3)?.toIntOrNull(),
                    language = segments[0]
                )
            uri.path.endsWith("/member_illust.php") || uri.path == "/member_illust.php" ->
                PixivArtworkUrl(
                    id = uri.queryParameters()["illust_id"] ?: return null,
                    pageIndex = null,
                    language = "en"
                )
            else -> null
        } ?: return null

        val id = parsed.id.takeWhile { it.isDigit() }
        if (id.length < 2) return null

        return parsed.copy(
            id = id,
            language = parsed.language.ifBlank { "en" }
        )
    }
}

private fun InputStream.readBytesCapped(limit: Int): ByteArray {
    val output = ByteArrayOutputStream(limit.coerceAtMost(8 * 1024))
    copyToCapped(output, limit)
    return output.toByteArray()
}

private fun InputStream.copyToCapped(output: OutputStream, limit: Int) {
    val buffer = ByteArray(8 * 1024)
    var total = 0

    while (true) {
        val read = read(buffer)
        if (read <= 0) break
        total += read
        if (total > limit) throw IllegalStateException("Preview payload too large")
        output.write(buffer, 0, read)
    }
}

private fun InputStream.readStringCapped(limit: Int): String =
    readBytesCapped(limit).toString(Charsets.UTF_8)

private fun String.stripSimpleHtml(): String =
    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY)
    } else {
        @Suppress("DEPRECATION")
        Html.fromHtml(this)
    })
        .toString()
        .replace('\u00A0', ' ')
        .trim()

private fun java.net.URI.queryParameters(): Map<String, String> =
    rawQuery
        ?.split("&")
        ?.mapNotNull { part ->
            val pieces = part.split("=", limit = 2)
            val key = pieces.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val value = pieces.getOrNull(1).orEmpty()
            key to value
        }
        ?.toMap()
        ?: emptyMap()

private fun JsonObject.objectValue(key: String): JsonObject? =
    this[key] as? JsonObject

private fun JsonObject.arrayValue(key: String): JsonArray? =
    this[key] as? JsonArray

private fun JsonObject.stringValue(key: String): String? =
    this[key].stringValue()

private fun JsonObject.longValue(key: String): Long? =
    this[key].longValue()

private fun JsonObject.booleanValue(key: String): Boolean? =
    this[key].booleanValue()

private fun JsonObject.stringArrayValue(key: String): List<String> =
    arrayValue(key)?.mapNotNull { element ->
        when (element) {
            is JsonObject -> element.stringValue("name")
            else -> element.stringValue()
        }?.trim()?.takeIf { it.isNotBlank() }
    }.orEmpty()

private fun JsonArray.firstObject(): JsonObject? =
    firstOrNull() as? JsonObject

private fun String.htmlMetaContent(property: String): String? {
    val propertyPatterns = listOf(
        """<meta\b[^>]*\bproperty=["']${Regex.escape(property)}["'][^>]*\bcontent=["']([^"']*)["'][^>]*>""",
        """<meta\b[^>]*\bcontent=["']([^"']*)["'][^>]*\bproperty=["']${Regex.escape(property)}["'][^>]*>"""
    )

    return propertyPatterns.firstNotNullOfOrNull { pattern ->
        Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.stripSimpleHtml()
            ?.takeIf { it.isNotBlank() }
    }
}

private fun String.htmlCanonicalUrl(): String? {
    val patterns = listOf(
        """<link\b[^>]*\brel=["']canonical["'][^>]*\bhref=["']([^"']+)["'][^>]*>""",
        """<link\b[^>]*\bhref=["']([^"']+)["'][^>]*\brel=["']canonical["'][^>]*>"""
    )

    return patterns.firstNotNullOfOrNull { pattern ->
        Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}

private fun JsonElement?.stringValue(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.content.trim().takeIf { it.isNotBlank() }
}

private fun JsonElement?.longValue(): Long? =
    stringValue()?.toLongOrNull()

private fun JsonElement?.booleanValue(): Boolean? {
    val normalized = stringValue()?.lowercase() ?: return null
    return when (normalized) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}

private fun String.toOriginalSizedImageUrl(): String = when {
    contains("name=", ignoreCase = true) ->
        replace(Regex("name=[^&#]+", RegexOption.IGNORE_CASE), "name=orig")
    contains("?") -> "$this&name=orig"
    else -> "$this?name=orig"
}

private fun String.fileExtensionHint(): String = when {
    contains(".png", ignoreCase = true) -> "png"
    contains(".webp", ignoreCase = true) -> "webp"
    contains(".gif", ignoreCase = true) -> "gif"
    contains(".bmp", ignoreCase = true) -> "bmp"
    contains(".avif", ignoreCase = true) -> "avif"
    else -> "jpg"
}

private fun String.normalizedMimeType(): String =
    substringBefore(';').trim().lowercase()

private fun String.fileExtensionForMimeType(): String? = when (normalizedMimeType()) {
    "image/jpeg", "image/jpg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/bmp" -> "bmp"
    "image/avif" -> "avif"
    else -> null
}

private fun findCachedPreviewFile(context: Context, fileBaseName: String): String? =
    context.clipboardDir.listFiles()
        ?.firstOrNull { file ->
            file.isFile &&
                file.name.startsWith("$fileBaseName.") &&
                !file.name.contains(".thumb.")
        }
        ?.name

private fun String.md5Hex(): String =
    MessageDigest.getInstance("MD5").digest(toByteArray()).joinToString("") { "%02x".format(it) }

private sealed interface PreviewRequest

private data class TwitterStatusUrl(
    val handle: String?,
    val id: String
) : PreviewRequest

private data class PixivArtworkUrl(
    val id: String,
    val pageIndex: Int?,
    val language: String
) : PreviewRequest {
    fun canonicalUrl(): String = "https://www.phixiv.net/$language/artworks/$id"
}

private data class RemotePreviewData(
    val snippet: String?,
    val imageUrl: String?,
    val metadata: ClipboardPreviewMetadata?
)

private fun TwitterStatusUrl.canonicalUrl(): String = when (handle) {
    null -> "https://x.com/i/status/$id"
    else -> "https://x.com/$handle/status/$id"
}

private fun TwitterStatusUrl.fixupxUrl(): String = when (handle) {
    null -> "https://fixupx.com/i/status/$id"
    else -> "https://fixupx.com/$handle/status/$id"
}

private fun ClipboardPreviewMetadata.nullIfEmpty(): ClipboardPreviewMetadata? =
    takeIf {
        sourceUrl != null ||
            sourceId != null ||
            title != null ||
            bodyText != null ||
            authorName != null ||
            authorHandle != null ||
            authorId != null ||
            createdAt != null ||
            imageCount != null ||
            selectedImageIndex != null ||
            tags.isNotEmpty() ||
            stats != null ||
            flags != ClipboardPreviewFlags()
    }
