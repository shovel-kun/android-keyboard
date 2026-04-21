package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.Context
import android.os.Build
import android.text.Html
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ClipboardLinkPreview(
    val snippet: String?,
    val imageFile: String?
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
private const val MaxPreviewImageBytes = 3_000_000

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
        if (preview.snippet == null && imageFile == null) return null

        return ClipboardLinkPreview(
            snippet = preview.snippet,
            imageFile = imageFile
        )
    }

    private fun fetchTwitterPreview(statusUrl: TwitterStatusUrl): RemotePreviewData? {
        val response = requestTwitterPreview(statusUrl) ?: return null
        val snippet = response.tweet?.let { tweet ->
            tweet.text.takeIf { it.isNotBlank() }
                ?: tweet.card?.title?.takeIf { it.isNotBlank() }
                ?: tweet.card?.description?.takeIf { it.isNotBlank() }
        }?.trim()?.takeIf { it.isNotBlank() }?.let { sanitizeClipboardText(it, 160) }

        val imageUrl = response.tweet?.let { tweet ->
            tweet.media?.mosaic?.formats?.jpeg
                ?: tweet.media?.photos?.firstOrNull()?.url?.toPreviewSizedImageUrl()
                ?: tweet.media?.videos?.firstOrNull()?.thumbnailUrl
                ?: tweet.card?.image?.url
        }

        return RemotePreviewData(
            snippet = snippet,
            imageUrl = imageUrl
        )
    }

    private fun fetchPixivPreview(artworkUrl: PixivArtworkUrl): RemotePreviewData? {
        val response = requestPixivPreview(artworkUrl) ?: return null
        val imageUrl = response.imageProxyUrls
            .getOrNull(artworkUrl.pageIndex ?: 0)
            ?: response.imageProxyUrls.firstOrNull()

        return RemotePreviewData(
            snippet = null,
            imageUrl = imageUrl
        )
    }

    private fun requestTwitterPreview(statusUrl: TwitterStatusUrl): FxTwitterStatusResponse? {
        val requestUrl = if (statusUrl.handle != null) {
            "https://api.fxtwitter.com/${statusUrl.handle}/status/${statusUrl.id}"
        } else {
            "https://api.fxtwitter.com/status/${statusUrl.id}"
        }
        return requestJson(requestUrl, MaxPreviewJsonBytes)
    }

    private fun requestPixivPreview(artworkUrl: PixivArtworkUrl): PhixivInfoResponse? {
        val requestUrl = "https://www.phixiv.net/api/info?id=${artworkUrl.id}&language=${artworkUrl.language}"
        return requestJson(requestUrl, MaxPreviewJsonBytes)
    }

    private fun cachePreviewImage(context: Context, imageUrl: String): String? {
        val fileName = "preview_${imageUrl.md5Hex()}.${imageUrl.fileExtensionHint()}"
        val outputFile = File(context.clipboardDir, fileName)
        if (outputFile.exists()) return fileName

        context.clipboardDir.mkdirs()

        val tempFile = File(context.cacheDir, "${fileName}.tmp")
        try {
            withConnection(imageUrl) { connection ->
                val contentType = connection.contentType.orEmpty()
                if (!contentType.startsWith("image/")) return null

                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyToCapped(output, MaxPreviewImageBytes)
                    }
                }
            }

            if (!tempFile.renameTo(outputFile)) {
                tempFile.copyTo(outputFile, overwrite = true)
                tempFile.delete()
            }

            ClipboardUtil.generateThumbnail(outputFile)
            return fileName
        } catch (_: Exception) {
            tempFile.delete()
            outputFile.delete()
            return null
        }
    }

    private inline fun <reified T> requestJson(url: String, maxBytes: Int): T =
        withConnection(url) { connection ->
            connection.inputStream.use { stream ->
                LinkPreviewJson.decodeFromString<T>(stream.readStringCapped(maxBytes))
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

private fun String.toPreviewSizedImageUrl(): String =
    replace("name=orig", "name=small")

private fun String.fileExtensionHint(): String = when {
    contains(".png", ignoreCase = true) -> "png"
    contains(".webp", ignoreCase = true) -> "webp"
    contains(".gif", ignoreCase = true) -> "gif"
    else -> "jpg"
}

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
) : PreviewRequest

private data class RemotePreviewData(
    val snippet: String?,
    val imageUrl: String?
)

@Serializable
private data class FxTwitterStatusResponse(
    val tweet: FxTwitterStatus? = null
)

@Serializable
private data class FxTwitterStatus(
    val text: String = "",
    val media: FxTwitterMedia? = null,
    val card: FxTwitterCard? = null
)

@Serializable
private data class FxTwitterMedia(
    val photos: List<FxTwitterPhoto>? = null,
    val videos: List<FxTwitterVideo>? = null,
    val mosaic: FxTwitterMosaic? = null
)

@Serializable
private data class FxTwitterPhoto(
    val url: String
)

@Serializable
private data class FxTwitterVideo(
    @SerialName("thumbnail_url")
    val thumbnailUrl: String? = null
)

@Serializable
private data class FxTwitterMosaic(
    val formats: FxTwitterMosaicFormats? = null
)

@Serializable
private data class FxTwitterMosaicFormats(
    val jpeg: String? = null
)

@Serializable
private data class FxTwitterCard(
    val title: String? = null,
    val description: String? = null,
    val image: FxTwitterCardImage? = null
)

@Serializable
private data class FxTwitterCardImage(
    val url: String? = null
)

@Serializable
private data class PhixivInfoResponse(
    @SerialName("image_proxy_urls")
    val imageProxyUrls: List<String> = emptyList(),
    val title: String = "",
    val description: String = ""
)
