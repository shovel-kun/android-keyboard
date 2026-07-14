package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.Context
import android.os.Build
import android.text.Html
import android.util.Base64
import kotlinx.coroutines.CancellationException
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
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.futo.inputmethod.latin.uix.getSetting

data class ClipboardLinkPreview(
    val snippet: String?,
    val mediaFiles: List<ClipboardPreviewMedia>,
    val metadata: ClipboardPreviewMetadata?
)

data class ClipboardLinkPreviewManifest(
    val snippet: String?,
    val mediaItems: List<ClipboardLinkPreviewMedia>,
    val metadata: ClipboardPreviewMetadata?,
    val referencedManifests: List<ClipboardLinkPreviewManifest> = emptyList()
)

data class ClipboardLinkPreviewMedia(
    val url: String,
    val sourceIndex: Int,
    val mimeType: String? = null,
    val thumbnailUrl: String? = null
)

sealed interface ClipboardPreviewMediaCacheResult {
    data class Saved(
        val fileName: String,
        val mimeType: String?
    ) : ClipboardPreviewMediaCacheResult

    data class Failed(
        val detail: String
    ) : ClipboardPreviewMediaCacheResult

    data class SkippedTooLarge(
        val detail: String
    ) : ClipboardPreviewMediaCacheResult

    data class RateLimited(
        val provider: ClipboardPreviewProvider,
        val retryAfterEpochMs: Long,
        val detail: String
    ) : ClipboardPreviewMediaCacheResult
}

data class ClipboardPreviewMediaDownloadProgress(
    val completedBytes: Long,
    val totalBytes: Long?
)

data class ClipboardLinkPreviewManifestResult(
    val manifest: ClipboardLinkPreviewManifest?,
    val failureDetail: String?,
    val failure: ClipboardPreviewFetchFailure? = null
)

internal data class ClipboardPreviewCandidate(
    val request: PreviewRequest,
    val provider: ClipboardPreviewProvider,
    val metadata: ClipboardPreviewMetadata,
    val archiveKey: String?,
    val prefersImagePreview: Boolean
)

internal data class PhixivArtworkPasteUrl(
    val baseUrl: String,
    val pageIndex: Int?
)

internal class PhixivArtworkPasteSession(private val targetDomain: String = "www.phixiv.net") {
    private val normalizedTargetDomain = targetDomain
        .trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')

    private val lastPageByBaseUrl = mutableMapOf<String, Int>()

    fun textForPaste(rawText: String): String {
        val trimmed = rawText.trim()
        val spoilerWrapped = trimmed.startsWith("||") && trimmed.endsWith("||") && trimmed.length > 4
        val text = if(spoilerWrapped) trimmed.substring(2, trimmed.length - 2).trim() else rawText
        val artworkUrl = ClipboardLinkPreviewFetcher.phixivArtworkPasteUrl(text, normalizedTargetDomain)
            ?: return ClipboardLinkPreviewFetcher.normalizedTextForClipboardImport(rawText)
        val startingPage = artworkUrl.pageIndex ?: 1
        val lastPage = lastPageByBaseUrl[artworkUrl.baseUrl]
        val page = lastPage
            ?.let { maxOf(it + 1, startingPage) }
            ?: startingPage

        lastPageByBaseUrl[artworkUrl.baseUrl] = page
        val pasteText = if(page <= 1) artworkUrl.baseUrl else "${artworkUrl.baseUrl}/$page"
        val wrappedPasteText = if(spoilerWrapped) "||$pasteText||" else pasteText
        return if(lastPage != null) "\n$wrappedPasteText" else wrappedPasteText
    }

    fun wrappedTextForPaste(rawText: String): String {
        val text = textForPaste(rawText)
        val prefix = if(text.startsWith("\n")) "\n" else ""
        val body = text.removePrefix("\n")
        val trimmed = body.trim()
        return if(trimmed.startsWith("||") && trimmed.endsWith("||") && trimmed.length > 4) {
            text
        } else {
            "$prefix||$body||"
        }
    }
}

internal class XLinkPasteSession(private val targetDomain: String) {
    private val normalizedTargetDomain = targetDomain
        .trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')

    fun textForPaste(rawText: String): String {
        val trimmed = rawText.trim()
        val spoilerWrapped = trimmed.startsWith("||") && trimmed.endsWith("||") && trimmed.length > 4
        val text = if(spoilerWrapped) trimmed.substring(2, trimmed.length - 2).trim() else rawText
        val pasteUrl = ClipboardLinkPreviewFetcher.xLinkPasteUrl(text, normalizedTargetDomain)
            ?: return ClipboardLinkPreviewFetcher.normalizedTextForClipboardImport(rawText)
        val wrappedPasteUrl = if(spoilerWrapped) "||$pasteUrl||" else pasteUrl
        return wrappedPasteUrl
    }

    fun wrappedTextForPaste(rawText: String): String {
        val text = textForPaste(rawText)
        val prefix = if(text.startsWith("\n")) "\n" else ""
        val body = text.removePrefix("\n")
        val trimmed = body.trim()
        return if(trimmed.startsWith("||") && trimmed.endsWith("||") && trimmed.length > 4) {
            text
        } else {
            "$prefix||$body||"
        }
    }
}

internal class MastodonLinkPasteSession(private val targetDomain: String) {
    private val normalizedTargetDomain = targetDomain
        .trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')

    fun textForPaste(rawText: String): String {
        val trimmed = rawText.trim()
        val spoilerWrapped = trimmed.startsWith("||") && trimmed.endsWith("||") && trimmed.length > 4
        val text = if(spoilerWrapped) trimmed.substring(2, trimmed.length - 2).trim() else rawText
        val pasteUrl = ClipboardLinkPreviewFetcher.mastodonLinkPasteUrl(text, normalizedTargetDomain)
            ?: return ClipboardLinkPreviewFetcher.normalizedTextForClipboardImport(rawText)
        return if(spoilerWrapped) "||$pasteUrl||" else pasteUrl
    }
}

sealed interface ClipboardPreviewFetchFailure {
    data class RateLimited(
        val provider: ClipboardPreviewProvider,
        val retryAfterEpochMs: Long,
        val detail: String
    ) : ClipboardPreviewFetchFailure

    data class Unavailable(
        val provider: ClipboardPreviewProvider,
        val sourceUrl: String,
        val detail: String
    ) : ClipboardPreviewFetchFailure
}

private class ClipboardPreviewRateLimitedException(
    val retryAfterEpochMs: Long,
    override val message: String
) : Exception(message)

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

private val XLinkPasteHosts = setOf(
    "x.com",
    "www.x.com",
    "mobile.x.com",
    "twitter.com",
    "www.twitter.com",
    "mobile.twitter.com"
)

private val SupportedPixivHosts = setOf(
    "pixiv.net",
    "www.pixiv.net",
    "phixiv.net",
    "www.phixiv.net"
)

private val SupportedRedditHosts = setOf(
    "reddit.com",
    "www.reddit.com",
    "old.reddit.com",
    "new.reddit.com",
    "m.reddit.com",
    "rxddit.com",
    "www.rxddit.com",
    "old.rxddit.com",
    "redd.it",
    "www.redd.it"
)

private val SupportedYouTubeHosts = setOf(
    "youtube.com",
    "www.youtube.com",
    "m.youtube.com",
    "music.youtube.com",
    "youtube-nocookie.com",
    "www.youtube-nocookie.com",
    "youtu.be",
    "www.youtu.be"
)

private const val PreviewConnectTimeoutMillis = 5_000
private const val PreviewReadTimeoutMillis = 10_000
private const val MaxPreviewJsonBytes = 1_000_000
private const val MaxPreviewMediaBytes = 50_000_000
private const val HttpTooManyRequests = 429
private const val RedditInstalledClientDeviceId = "android_keyboard_preview"
private const val RedditUserAgent = "android:org.futo.inputmethod.latin:clipboard-preview (by /u/EbisuzawaKurumi_)"
internal const val DefaultPreviewRateLimitCooldownMillis = 15L * 60L * 1000L

object ClipboardLinkPreviewFetcher {
    fun supportsPreview(rawText: String): Boolean =
        previewCandidateFor(rawText) != null

    fun metadataForSupportedUrl(rawText: String): ClipboardPreviewMetadata? =
        previewCandidateFor(rawText)?.metadata

    fun normalizedTextForClipboardImport(rawText: String): String {
        val trimmed = rawText.trim()
        if(!LinkPreviewUrlRegex.matches(trimmed)) return rawText

        val candidate = extractPreviewRequest(trimmed)?.toCandidate() ?: return rawText
        if(candidate.provider != ClipboardPreviewProvider.REDDIT) return rawText

        return candidate.metadata.sourceUrl ?: rawText
    }

    internal fun phixivArtworkPasteUrl(
        rawText: String,
        targetDomain: String = "www.phixiv.net"
    ): PhixivArtworkPasteUrl? {
        val trimmed = rawText.trim()
        if(!LinkPreviewUrlRegex.matches(trimmed)) return null

        val artworkUrl = parsePixivArtworkUrl(trimmed) ?: return null
        if(targetDomain.isBlank()) return null
        return PhixivArtworkPasteUrl(
            baseUrl = artworkUrl.pasteUrl(targetDomain),
            pageIndex = artworkUrl.pageIndex
        )
    }

    internal fun xLinkPasteUrl(rawText: String, targetDomain: String): String? {
        val trimmed = rawText.trim()
        if(!LinkPreviewUrlRegex.matches(trimmed)) return null
        if(targetDomain.isBlank()) return null

        val uri = runCatching { URL(trimmed).toURI() }.getOrNull() ?: return null
        if(!XLinkPasteHosts.contains(uri.host?.lowercase())) return null

        return buildString {
            append("https://").append(targetDomain)
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }

    internal fun mastodonLinkPasteUrl(rawText: String, targetDomain: String): String? {
        val trimmed = rawText.trim()
        if(!LinkPreviewUrlRegex.matches(trimmed)) return null
        if(targetDomain.isBlank()) return null

        val uri = runCatching { URL(trimmed).toURI() }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val segments = uri.path.trim('/').split('/')
        val postPath = when {
            segments.size == 2 &&
                segments[0].startsWith('@') &&
                segments[0].length > 1 &&
                segments[1].all(Char::isDigit) -> uri.rawPath
            segments.size == 4 &&
                segments[0] == "users" &&
                segments[1].isNotBlank() &&
                segments[2] == "statuses" &&
                segments[3].all(Char::isDigit) -> "/@${segments[1]}/${segments[3]}"
            segments.size == 3 &&
                segments[0] == "web" &&
                segments[1] == "statuses" &&
                segments[2].all(Char::isDigit) -> "/${segments[2]}"
            else -> return null
        }
        val sourceDomain = if(uri.port == -1) host else "$host:${uri.port}"

        return buildString {
            append("https://").append(targetDomain).append('/').append(sourceDomain)
            append(postPath)
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }

    fun prefersImagePreview(rawText: String): Boolean =
        previewCandidateFor(rawText)?.prefersImagePreview == true

    internal fun previewCandidateFor(rawText: String): ClipboardPreviewCandidate? =
        extractPreviewRequest(rawText)?.toCandidate()

    fun fetchManifest(
        rawText: String,
        pixivSessionId: String? = null,
        redditAccessToken: String? = null
    ): ClipboardLinkPreviewManifest? {
        return fetchManifestResult(rawText, pixivSessionId, redditAccessToken).manifest
    }

    fun fetchManifestResult(
        rawText: String,
        pixivSessionId: String? = null,
        redditAccessToken: String? = null
    ): ClipboardLinkPreviewManifestResult {
        val candidate = previewCandidateFor(rawText) ?: return ClipboardLinkPreviewManifestResult(
            manifest = null,
            failureDetail = "Unsupported preview URL: $rawText"
        )
        return fetchManifestResult(candidate, rawText, pixivSessionId, redditAccessToken)
    }

    internal fun fetchManifestResult(
        candidate: ClipboardPreviewCandidate,
        pixivSessionId: String? = null,
        redditAccessToken: String? = null
    ): ClipboardLinkPreviewManifestResult =
        fetchManifestResult(
            candidate,
            candidate.metadata.sourceUrl ?: candidate.archiveKey.orEmpty(),
            pixivSessionId,
            redditAccessToken
        )

    private fun fetchManifestResult(
        candidate: ClipboardPreviewCandidate,
        rawText: String,
        pixivSessionId: String?,
        redditAccessToken: String?
    ): ClipboardLinkPreviewManifestResult {
        val request = candidate.request
        return try {
            request.fetchPreview(pixivSessionId, redditAccessToken)
        } catch (e: ClipboardPreviewRateLimitedException) {
            val provider = candidate.provider
            val detail = "Rate limited by ${provider.name} while fetching preview manifest for $rawText. Retry after ${e.retryAfterEpochMs}. ${e.message}"
            return ClipboardLinkPreviewManifestResult(
                manifest = null,
                failureDetail = detail,
                failure = ClipboardPreviewFetchFailure.RateLimited(
                    provider = provider,
                    retryAfterEpochMs = e.retryAfterEpochMs,
                    detail = detail
                )
            )
        } catch (e: Exception) {
            return ClipboardLinkPreviewManifestResult(
                manifest = null,
                failureDetail = "Failed to fetch preview manifest for $rawText: ${e.failureDetail()}"
            )
        }.let {
            val manifest = it?.toManifest()
            val unavailableFailure = manifest?.unavailablePreviewFailure(request, rawText)
            if(unavailableFailure != null) {
                return ClipboardLinkPreviewManifestResult(
                    manifest = null,
                    failureDetail = unavailableFailure.detail,
                    failure = unavailableFailure
                )
            }
            ClipboardLinkPreviewManifestResult(
                manifest = manifest,
                failureDetail = if(it == null) "Preview provider returned no usable manifest for $rawText" else null
            )
        }
    }

    fun fetch(context: Context, rawText: String): ClipboardLinkPreview? {
        val preview = fetchManifest(
            rawText = rawText,
            pixivSessionId = context.getSetting(ClipboardPixivSessionId).takeIf { it.isNotBlank() },
            redditAccessToken = context.getSetting(ClipboardRedditAccessToken).takeIf { it.isNotBlank() }
        ) ?: return null
        val provider = preview.metadata?.provider

        val mediaFiles = preview.mediaItems.mapNotNull { media ->
            when (val result = cachePreviewMedia(context, media.url, context.clipboardDir, provider = provider)) {
                is ClipboardPreviewMediaCacheResult.Saved -> {
                    ClipboardPreviewMedia(
                        fileName = result.fileName,
                        sourceUrl = media.url,
                        sourceIndex = media.sourceIndex,
                        mimeType = result.mimeType ?: media.mimeType
                    )
                }
                is ClipboardPreviewMediaCacheResult.Failed,
                is ClipboardPreviewMediaCacheResult.SkippedTooLarge,
                is ClipboardPreviewMediaCacheResult.RateLimited -> null
            }
        }
        if (preview.snippet == null && mediaFiles.isEmpty() && preview.metadata == null) return null

        return ClipboardLinkPreview(
            snippet = preview.snippet,
            mediaFiles = mediaFiles,
            metadata = preview.metadata
        )
    }

    internal fun parseTwitterApiPreviewMediaUrlsForTest(responseText: String): List<String> =
        parseTwitterApiPreview(
            LinkPreviewJson.parseToJsonElement(responseText).jsonObject,
            TwitterStatusUrl(handle = "futo", id = "123")
        )?.mediaItems?.map { it.url }.orEmpty()

    internal fun parseTwitterApiPreviewForTest(responseText: String): ClipboardLinkPreviewManifest? =
        parseTwitterApiPreview(
            LinkPreviewJson.parseToJsonElement(responseText).jsonObject,
            TwitterStatusUrl(handle = "futo", id = "123")
        )?.toManifest()

    internal fun parsePixivPreviewMediaUrlsForTest(responseText: String, pageIndex: Int? = null): List<String> =
        parsePixivPreviewData(
            LinkPreviewJson.parseToJsonElement(responseText).jsonObject,
            pagesResponse = null,
            PixivArtworkUrl(id = "123", pageIndex = pageIndex, language = "en")
        )?.mediaItems?.map { it.url }.orEmpty()

    internal fun pixivSessionCookieHeaderForTest(value: String): String? =
        value.pixivSessionCookieHeader()

    internal fun parseTwitterHtmlPreviewMediaUrlsForTest(html: String): List<String> =
        parseTwitterHtmlPreview(
            html = html,
            statusUrl = TwitterStatusUrl(handle = "futo", id = "123")
        )?.mediaItems?.map { it.url }.orEmpty()

    internal fun parseRedditEmbedPreviewForTest(responseText: String): ClipboardLinkPreviewManifest? =
        parseRedditEmbedPreview(
            response = LinkPreviewJson.parseToJsonElement(responseText).jsonObject,
            redditUrl = RedditPostUrl(
                pathSegments = listOf("r", "futo", "comments", "abc123", "title"),
                postId = "abc123"
            )
        )?.let { preview ->
            ClipboardLinkPreviewManifest(
                snippet = preview.snippet,
                mediaItems = preview.mediaItems,
                metadata = preview.metadata
            )
        }

    internal fun parseRedditApiPreviewForTest(responseText: String): ClipboardLinkPreviewManifest? =
        parseRedditApiPreview(
            response = LinkPreviewJson.parseToJsonElement(responseText).jsonObject,
            redditUrl = RedditPostUrl(
                pathSegments = listOf("r", "futo", "comments", "abc123", "title"),
                postId = "abc123"
            )
        )?.let { preview ->
            ClipboardLinkPreviewManifest(
                snippet = preview.snippet,
                mediaItems = preview.mediaItems,
                metadata = preview.metadata
            )
        }

    internal fun parseYouTubeOEmbedPreviewForTest(responseText: String): ClipboardLinkPreviewManifest? =
        parseYouTubeOEmbedPreview(
            response = LinkPreviewJson.parseToJsonElement(responseText).jsonObject,
            videoUrl = YouTubeVideoUrl(id = "dQw4w9WgXcQ")
        )?.let { preview ->
            ClipboardLinkPreviewManifest(
                snippet = preview.snippet,
                mediaItems = preview.mediaItems,
                metadata = preview.metadata
            )
        }

    internal fun previewRateLimitedExceptionForTest(retryAfterEpochMs: Long, message: String): Exception =
        ClipboardPreviewRateLimitedException(retryAfterEpochMs, message)

    internal fun runPreviewRequestCatchingForTest(exception: Exception): String? =
        runPreviewRequestCatching { throw exception }

    internal fun unavailablePreviewFailureForTest(
        snippet: String?,
        title: String? = null,
        bodyText: String? = null,
        mediaItems: List<ClipboardLinkPreviewMedia> = emptyList()
    ): ClipboardPreviewFetchFailure.Unavailable? =
        ClipboardLinkPreviewManifest(
            snippet = snippet,
            mediaItems = mediaItems,
            metadata = ClipboardPreviewMetadata(
                provider = ClipboardPreviewProvider.TWITTER,
                sourceUrl = "https://x.com/futo/status/123",
                sourceId = "123",
                title = title,
                bodyText = bodyText
            )
        ).unavailablePreviewFailure(
            request = TwitterStatusUrl(handle = "futo", id = "123"),
            rawText = "https://x.com/futo/status/123"
        )

    private interface ClipboardPreviewProviderAdapter {
        val provider: ClipboardPreviewProvider
        fun parse(url: String): PreviewRequest?
        fun seedMetadata(request: PreviewRequest): ClipboardPreviewMetadata
        fun fetch(request: PreviewRequest): RemotePreviewData?
        fun canonicalSourceUrl(request: PreviewRequest): String
        fun ownsMediaHost(host: String): Boolean
        fun prefersImagePreview(request: PreviewRequest): Boolean = false
    }

    private val PreviewProviders = listOf(
        TwitterPreviewProvider,
        PixivPreviewProvider,
        RedditPreviewProvider,
        YouTubePreviewProvider
    )

    private object TwitterPreviewProvider : ClipboardPreviewProviderAdapter {
        override val provider = ClipboardPreviewProvider.TWITTER

        override fun parse(url: String): PreviewRequest? =
            parseTwitterStatusUrl(url)

        override fun seedMetadata(request: PreviewRequest): ClipboardPreviewMetadata {
            val statusUrl = request as TwitterStatusUrl
            return ClipboardPreviewMetadata(
                provider = provider,
                sourceUrl = statusUrl.canonicalUrl(),
                sourceId = statusUrl.id,
                authorHandle = statusUrl.handle
            )
        }

        override fun fetch(request: PreviewRequest): RemotePreviewData? =
            fetchTwitterPreview(request as TwitterStatusUrl)

        override fun canonicalSourceUrl(request: PreviewRequest): String =
            (request as TwitterStatusUrl).canonicalUrl()

        override fun ownsMediaHost(host: String): Boolean =
            SupportedTwitterHosts.contains(host) ||
                host.endsWith(".twimg.com") ||
                host.endsWith(".twitter.com") ||
                host.endsWith(".x.com")
    }

    private object PixivPreviewProvider : ClipboardPreviewProviderAdapter {
        override val provider = ClipboardPreviewProvider.PIXIV

        override fun parse(url: String): PreviewRequest? =
            parsePixivArtworkUrl(url)

        override fun seedMetadata(request: PreviewRequest): ClipboardPreviewMetadata {
            val artworkUrl = request as PixivArtworkUrl
            return ClipboardPreviewMetadata(
                provider = provider,
                sourceUrl = artworkUrl.canonicalUrl(),
                sourceId = artworkUrl.id,
                selectedImageIndex = artworkUrl.pageIndex
            )
        }

        override fun fetch(request: PreviewRequest): RemotePreviewData? =
            fetchPixivPreview(request as PixivArtworkUrl)

        override fun canonicalSourceUrl(request: PreviewRequest): String =
            (request as PixivArtworkUrl).canonicalUrl()

        override fun ownsMediaHost(host: String): Boolean =
            SupportedPixivHosts.contains(host) ||
                host.endsWith(".pximg.net") ||
                host.endsWith(".pixiv.net") ||
                host.endsWith(".phixiv.net")

        override fun prefersImagePreview(request: PreviewRequest): Boolean = true
    }

    private object RedditPreviewProvider : ClipboardPreviewProviderAdapter {
        override val provider = ClipboardPreviewProvider.REDDIT

        override fun parse(url: String): PreviewRequest? =
            parseRedditPostUrl(url)

        override fun seedMetadata(request: PreviewRequest): ClipboardPreviewMetadata {
            val redditUrl = request as RedditPostUrl
            return ClipboardPreviewMetadata(
                provider = provider,
                sourceUrl = redditUrl.canonicalUrl(),
                sourceId = redditUrl.sourceId()
            )
        }

        override fun fetch(request: PreviewRequest): RemotePreviewData? =
            fetchRedditPreview(request as RedditPostUrl, redditAccessToken = null)

        override fun canonicalSourceUrl(request: PreviewRequest): String =
            (request as RedditPostUrl).canonicalUrl()

        override fun ownsMediaHost(host: String): Boolean =
            SupportedRedditHosts.contains(host) ||
                host.endsWith(".reddit.com") ||
                host.endsWith(".redd.it") ||
                host.endsWith(".redditmedia.com")
    }

    private object YouTubePreviewProvider : ClipboardPreviewProviderAdapter {
        override val provider = ClipboardPreviewProvider.YOUTUBE

        override fun parse(url: String): PreviewRequest? =
            parseYouTubeVideoUrl(url)

        override fun seedMetadata(request: PreviewRequest): ClipboardPreviewMetadata {
            val videoUrl = request as YouTubeVideoUrl
            return ClipboardPreviewMetadata(
                provider = provider,
                sourceUrl = videoUrl.canonicalUrl(),
                sourceId = videoUrl.id
            )
        }

        override fun fetch(request: PreviewRequest): RemotePreviewData? =
            fetchYouTubePreview(request as YouTubeVideoUrl)

        override fun canonicalSourceUrl(request: PreviewRequest): String =
            (request as YouTubeVideoUrl).canonicalUrl()

        override fun ownsMediaHost(host: String): Boolean =
            SupportedYouTubeHosts.contains(host) ||
                host.endsWith(".youtube.com") ||
                host.endsWith(".youtube-nocookie.com") ||
                host.endsWith(".ytimg.com")
    }

    private fun fetchTwitterPreview(statusUrl: TwitterStatusUrl): RemotePreviewData? {
        return requestTwitterPreview(statusUrl)
    }

    private fun parseTwitterApiPreview(response: JsonObject, statusUrl: TwitterStatusUrl): RemotePreviewData? {
        val tweet = response.objectValue("tweet") ?: return null
        return parseTwitterApiStatusPreview(tweet, statusUrl, includeQuote = true)
    }

    private fun parseTwitterApiStatusPreview(
        tweet: JsonObject,
        statusUrl: TwitterStatusUrl,
        includeQuote: Boolean
    ): RemotePreviewData? {
        val rawBodyText = tweet.stringValue("text")?.trim()?.takeIf { it.isNotBlank() }
        val card = tweet.objectValue("card") ?: tweet.objectValue("twitter_card")
        val title = card?.stringValue("title")?.trim()?.takeIf { it.isNotBlank() }
        val description = card?.stringValue("description")?.trim()?.takeIf { it.isNotBlank() }
        val snippet = (rawBodyText ?: title ?: description)?.let { sanitizeClipboardText(it, 160) }

        val media = tweet.objectValue("media")
        val mediaItems = media.twitterMediaItems()
            .ifEmpty {
                listOfNotNull(
                    media?.objectValue("mosaic")?.objectValue("formats")?.stringValue("jpeg"),
                    card?.objectValue("image")?.stringValue("url")
                ).mapIndexed { index, url -> ClipboardLinkPreviewMedia(url = url, sourceIndex = index) }
            }
            .withoutTwitterStatusPageUrls()

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
            selectedImageIndex = 0.takeIf { mediaItems.isNotEmpty() },
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
        val referencedPreviews = if(includeQuote) {
            listOfNotNull(
                tweet.objectValue("quote")?.let { quote ->
                    quote.twitterStatusUrl()?.let { quoteStatusUrl ->
                        parseTwitterApiStatusPreview(
                            tweet = quote,
                            statusUrl = quoteStatusUrl,
                            includeQuote = false
                        )
                    }
                }
            )
        } else {
            emptyList()
        }

        return RemotePreviewData(
            snippet = snippet,
            mediaItems = mediaItems,
            metadata = metadata,
            referencedPreviews = referencedPreviews
        )
    }

    private fun fetchPixivPreview(artworkUrl: PixivArtworkUrl): RemotePreviewData? {
        val response = requestPixivPreview(artworkUrl, pixivSessionId = null) ?: return null
        return parsePixivPreviewData(response.infoResponse, response.pagesResponse, artworkUrl)
    }

    private fun fetchPixivPreview(
        artworkUrl: PixivArtworkUrl,
        pixivSessionId: String?
    ): RemotePreviewData? {
        val response = requestPixivPreview(artworkUrl, pixivSessionId) ?: return null
        return parsePixivPreviewData(response.infoResponse, response.pagesResponse, artworkUrl)
    }

    private fun parsePixivPreviewData(
        response: JsonObject,
        pagesResponse: JsonArray?,
        artworkUrl: PixivArtworkUrl
    ): RemotePreviewData? {
        val body = response.objectValue("body") ?: response
        val imageUrls = pagesResponse?.pixivOriginalImageUrls()
            ?: response.arrayValue("body")?.pixivOriginalImageUrls()
            ?: response.stringArrayValue("image_proxy_urls")
        val mediaItems = imageUrls
            .prioritizeIndex(artworkUrl.pageIndex ?: 0)
            .map { (sourceIndex, url) ->
                ClipboardLinkPreviewMedia(
                    url = url,
                    sourceIndex = sourceIndex,
                    mimeType = url.guessedClipboardMimeType()
                )
            }
        val description = (body.stringValue("description") ?: body.stringValue("illustComment"))
            ?.stripSimpleHtml()
            ?.takeIf { it.isNotBlank() }
        val metadata = ClipboardPreviewMetadata(
            provider = ClipboardPreviewProvider.PIXIV,
            sourceUrl = body.stringValue("url") ?: artworkUrl.canonicalUrl(),
            sourceId = body.stringValue("illust_id") ?: body.stringValue("illustId") ?: body.stringValue("id") ?: artworkUrl.id,
            title = (body.stringValue("title") ?: body.stringValue("illustTitle"))?.trim()?.takeIf { it.isNotBlank() },
            bodyText = description,
            authorName = body.stringValue("author_name") ?: body.stringValue("userName"),
            authorId = body.stringValue("author_id") ?: body.stringValue("userId"),
            createdAt = body.stringValue("create_date") ?: body.stringValue("createDate") ?: body.stringValue("uploadDate"),
            imageCount = imageUrls.size.takeIf { it > 0 },
            selectedImageIndex = (artworkUrl.pageIndex ?: 0).takeIf { imageUrls.isNotEmpty() },
            tags = body.stringArrayValue("tags").ifEmpty { body.pixivTags() },
            stats = ClipboardPreviewStats(
                likeCount = body.longValue("like_count") ?: body.longValue("likeCount"),
                bookmarkCount = body.longValue("bookmark_count") ?: body.longValue("bookmarkCount"),
                viewCount = body.longValue("view_count") ?: body.longValue("viewCount"),
                commentCount = body.longValue("comment_count") ?: body.longValue("commentCount")
            ),
            flags = ClipboardPreviewFlags(
                aiGenerated = body.booleanValue("ai_generated") == true || body.longValue("aiType") == 2L,
                animated = body.booleanValue("is_ugoira") == true || body.longValue("illustType") == 2L,
                restricted = (body.longValue("x_restrict") ?: body.longValue("xRestrict") ?: 0L) > 0L
            )
        ).nullIfEmpty()

        return RemotePreviewData(
            snippet = null,
            mediaItems = mediaItems,
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

        return runPreviewRequestCatching {
            requestJsonObject(requestUrl, MaxPreviewJsonBytes)
        }
    }

    private fun requestTwitterHtmlPreview(statusUrl: TwitterStatusUrl): RemotePreviewData? {
        val html = runPreviewRequestCatching {
            requestText(statusUrl.fixupxUrl(), MaxPreviewJsonBytes)
        } ?: return null

        return parseTwitterHtmlPreview(html, statusUrl)
    }

    private fun <T> runPreviewRequestCatching(block: () -> T): T? =
        try {
            block()
        } catch (e: ClipboardPreviewRateLimitedException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private fun parseTwitterHtmlPreview(
        html: String,
        statusUrl: TwitterStatusUrl
    ): RemotePreviewData? {
        val card = html.htmlPreviewCard()
        val snippet = card.description
            ?.stripSimpleHtml()
            ?.takeIf { it.isNotBlank() }
            ?.let { sanitizeClipboardText(it, 160) }

        val authorHandle = card.authorHandles
            .map { it.removePrefix("@").trim() }
            .firstOrNull { it.isNotBlank() }

        val authorName = card.title
            ?.stripSimpleHtml()
            ?.substringBefore(" (@")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val mediaUrl = card.mediaUrls
            .firstOrNull { it.isNotBlank() && !it.contains("/profile_images/") }

        val metadata = ClipboardPreviewMetadata(
            provider = ClipboardPreviewProvider.TWITTER,
            sourceUrl = card.canonicalUrl ?: statusUrl.canonicalUrl(),
            sourceId = statusUrl.id,
            bodyText = snippet,
            authorName = authorName,
            authorHandle = authorHandle
        ).nullIfEmpty()

        if (snippet == null && mediaUrl == null && metadata == null) return null

        return RemotePreviewData(
            snippet = snippet,
            mediaItems = listOfNotNull(mediaUrl).map {
                ClipboardLinkPreviewMedia(url = it, sourceIndex = 0, mimeType = it.guessedClipboardMimeType())
            }.withoutTwitterStatusPageUrls(),
            metadata = metadata
        )
    }

    private fun List<ClipboardLinkPreviewMedia>.withoutTwitterStatusPageUrls(): List<ClipboardLinkPreviewMedia> =
        filterNot { parseTwitterStatusUrl(it.url) != null }

    private fun requestPixivPreview(
        artworkUrl: PixivArtworkUrl,
        pixivSessionId: String?
    ): PixivPreviewResponse? {
        val infoUrl = "https://www.pixiv.net/ajax/illust/${artworkUrl.id}?lang=${artworkUrl.language}"
        val pagesUrl = "https://www.pixiv.net/ajax/illust/${artworkUrl.id}/pages?lang=${artworkUrl.language}"
        val headers = pixivAjaxHeaders(pixivSessionId)
        val infoResponse = requestJsonObject(infoUrl, MaxPreviewJsonBytes, headers)
        val pagesResponse = runPreviewRequestCatching {
            requestJsonObject(pagesUrl, MaxPreviewJsonBytes, headers).arrayValue("body")
        }
        return PixivPreviewResponse(infoResponse, pagesResponse)
    }

    private fun fetchRedditPreview(
        redditUrl: RedditPostUrl,
        redditAccessToken: String?
    ): RemotePreviewData? {
        redditAccessToken?.takeIf { it.isNotBlank() }?.let { credential ->
            runPreviewRequestCatching {
                requestJsonObject(
                    redditUrl.oauthPostUrl(),
                    MaxPreviewJsonBytes,
                    credential.redditOAuthHeaders()
                )
            }?.let { response ->
                parseRedditApiPreview(response, redditUrl)
            }?.let { return it }
        }

        val resolvedUrl = redditUrl.redirectUrl?.let { redirectUrl ->
            runPreviewRequestCatching {
                requestFinalUrl(redirectUrl)
            }?.let(::parseRedditPostUrl)
                ?.takeIf { it.redirectUrl == null }
        }
        val previewUrl = resolvedUrl ?: redditUrl
        val response = runPreviewRequestCatching {
            requestJsonObject(previewUrl.embedUrl(), MaxPreviewJsonBytes, redditEmbedHeaders())
        } ?: return null

        return parseRedditEmbedPreview(response, previewUrl)
    }

    private fun parseRedditApiPreview(
        response: JsonObject,
        redditUrl: RedditPostUrl
    ): RemotePreviewData? {
        val post = response
            .objectValue("data")
            ?.arrayValue("children")
            ?.firstObject()
            ?.objectValue("data")
            ?: return null

        val title = post.stringValue("title")
            ?.stripSimpleHtml()
            ?.takeIf { it.isNotBlank() }
        val bodyText = post.stringValue("selftext")
            ?.stripSimpleHtml()
            ?.takeIf { it.isNotBlank() }
        val mediaItems = post.redditApiMediaItems()

        val metadata = ClipboardPreviewMetadata(
            provider = ClipboardPreviewProvider.REDDIT,
            sourceUrl = post.stringValue("permalink")?.redditPermalinkUrl() ?: redditUrl.canonicalUrl(),
            sourceId = post.stringValue("id") ?: redditUrl.sourceId(),
            title = title,
            bodyText = bodyText,
            authorHandle = post.stringValue("author"),
            createdAt = post.longValue("created_utc")?.toString(),
            imageCount = mediaItems.size.takeIf { it > 0 },
            selectedImageIndex = 0.takeIf { mediaItems.isNotEmpty() },
            stats = ClipboardPreviewStats(
                likeCount = post.longValue("ups"),
                replyCount = post.longValue("num_comments")
            ),
            flags = ClipboardPreviewFlags(
                restricted = post.booleanValue("over_18") == true
            )
        ).nullIfEmpty()

        if (title == null && bodyText == null && mediaItems.isEmpty() && metadata == null) return null

        return RemotePreviewData(
            snippet = title?.let { sanitizeClipboardText(it, 160) },
            mediaItems = mediaItems,
            metadata = metadata
        )
    }

    private fun parseRedditEmbedPreview(
        response: JsonObject,
        redditUrl: RedditPostUrl
    ): RemotePreviewData? {
        val title = response.stringValue("title")
            ?.stripSimpleHtml()
            ?.takeIf { it.isNotBlank() }
        val snippet = title?.let { sanitizeClipboardText(it, 160) }

        val thumbnailUrl = response.stringValue("thumbnail_url")
            ?.redditApiUrlDecode()
            ?.takeIf { it.isNotBlank() }
        val mediaItems = listOfNotNull(thumbnailUrl)
            .mapIndexed { index, url ->
                ClipboardLinkPreviewMedia(
                    url = url,
                    sourceIndex = index,
                    mimeType = url.guessedClipboardMimeType()
                )
            }

        val metadata = ClipboardPreviewMetadata(
            provider = ClipboardPreviewProvider.REDDIT,
            sourceUrl = redditUrl.canonicalUrl(),
            sourceId = redditUrl.sourceId(),
            title = title,
            authorName = response.stringValue("author_name"),
            imageCount = mediaItems.size.takeIf { it > 0 },
            selectedImageIndex = 0.takeIf { mediaItems.isNotEmpty() }
        ).nullIfEmpty()

        if (snippet == null && title == null && mediaItems.isEmpty() && metadata == null) return null

        return RemotePreviewData(
            snippet = snippet,
            mediaItems = mediaItems,
            metadata = metadata
        )
    }

    private fun fetchYouTubePreview(videoUrl: YouTubeVideoUrl): RemotePreviewData? {
        val response = runPreviewRequestCatching {
            requestJsonObject(videoUrl.oEmbedUrl(), MaxPreviewJsonBytes)
        } ?: return null

        return parseYouTubeOEmbedPreview(response, videoUrl)
    }

    private fun parseYouTubeOEmbedPreview(
        response: JsonObject,
        videoUrl: YouTubeVideoUrl
    ): RemotePreviewData? {
        val title = response.stringValue("title")
            ?.stripSimpleHtml()
            ?.takeIf { it.isNotBlank() }
        val thumbnailUrl = response.stringValue("thumbnail_url")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val mediaItems = listOfNotNull(thumbnailUrl)
            .map {
                ClipboardLinkPreviewMedia(
                    url = it,
                    sourceIndex = 0,
                    mimeType = it.guessedClipboardMimeType()
                )
            }

        if(title == null && mediaItems.isEmpty()) return null

        val metadata = ClipboardPreviewMetadata(
            provider = ClipboardPreviewProvider.YOUTUBE,
            sourceUrl = videoUrl.canonicalUrl(),
            sourceId = videoUrl.id,
            title = title,
            authorName = response.stringValue("author_name")?.trim()?.takeIf { it.isNotBlank() },
            imageCount = 1.takeIf { mediaItems.isNotEmpty() },
            selectedImageIndex = 0.takeIf { mediaItems.isNotEmpty() }
        ).nullIfEmpty()

        return RemotePreviewData(
            snippet = title?.let { sanitizeClipboardText(it, 160) },
            mediaItems = mediaItems,
            metadata = metadata
        )
    }

    fun cachePreviewMedia(
        context: Context,
        mediaUrl: String,
        destinationDir: File,
        provider: ClipboardPreviewProvider? = null,
        thumbnailUrl: String? = null,
        onProgress: (ClipboardPreviewMediaDownloadProgress) -> Unit = {}
    ): ClipboardPreviewMediaCacheResult {
        val fileBaseName = "preview_${mediaUrl.md5Hex()}"
        destinationDir.mkdirs()
        findCachedPreviewFile(destinationDir, fileBaseName)?.let {
            cachePreviewMediaThumbnail(thumbnailUrl, it)
            return ClipboardPreviewMediaCacheResult.Saved(
                fileName = it.name,
                mimeType = it.guessedClipboardMimeType()
            )
        }

        val tempFile = File(context.cacheDir, "${fileBaseName}.tmp")
        try {
            var outputFile: File? = null
            var outputMimeType: String? = null
            withConnection(mediaUrl) { connection ->
                val contentType = connection.contentType.orEmpty().normalizedMimeType()
                val mimeType = contentType.takeIf {
                    it.startsWith("image/") || it.startsWith("video/")
                } ?: mediaUrl.guessedClipboardMimeType()
                if (mimeType == null) {
                    return ClipboardPreviewMediaCacheResult.Failed(
                        "Unsupported media type for $mediaUrl. HTTP Content-Type was '${connection.contentType}'."
                    )
                }

                val extension = mimeType.fileExtensionForMimeType() ?: mediaUrl.fileExtensionHint()
                val fileName = "$fileBaseName.$extension"
                outputMimeType = mimeType
                outputFile = File(destinationDir, fileName)
                if (outputFile!!.exists()) {
                    cachePreviewMediaThumbnail(thumbnailUrl, outputFile!!)
                    return ClipboardPreviewMediaCacheResult.Saved(fileName, mimeType)
                }
                val totalBytes = connection.contentLengthLong.takeIf { it > 0L }

                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyToCapped(
                            output = output,
                            limit = MaxPreviewMediaBytes,
                            totalBytes = totalBytes,
                            onProgress = onProgress
                        )
                    }
                }

                fileName
            }?.let { existingFileName ->
                if (outputFile?.exists() == true) {
                    tempFile.delete()
                    return ClipboardPreviewMediaCacheResult.Saved(existingFileName, outputMimeType)
                }
            }

            val finalFile = outputFile ?: return ClipboardPreviewMediaCacheResult.Failed(
                "No output file was created for $mediaUrl."
            )
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            cachePreviewMediaThumbnail(thumbnailUrl, finalFile)
            if(!ClipboardUtil.thumbnailFor(finalFile).isFile) {
                ClipboardUtil.generateThumbnail(finalFile)
            }
            return ClipboardPreviewMediaCacheResult.Saved(finalFile.name, outputMimeType)
        } catch (e: CancellationException) {
            tempFile.delete()
            throw e
        } catch (e: ClipboardPreviewRateLimitedException) {
            tempFile.delete()
            val rateLimitedProvider = provider ?: providerForUrl(mediaUrl) ?: return ClipboardPreviewMediaCacheResult.Failed(
                "Rate limited while caching preview media for $mediaUrl, but provider could not be determined. Retry after ${e.retryAfterEpochMs}. ${e.message}"
            )
            val detail = "Rate limited by ${rateLimitedProvider.name} while caching preview media for $mediaUrl. Retry after ${e.retryAfterEpochMs}. ${e.message}"
            return ClipboardPreviewMediaCacheResult.RateLimited(
                provider = rateLimitedProvider,
                retryAfterEpochMs = e.retryAfterEpochMs,
                detail = detail
            )
        } catch (e: IllegalStateException) {
            tempFile.delete()
            return ClipboardPreviewMediaCacheResult.SkippedTooLarge(
                "Preview media exceeded ${MaxPreviewMediaBytes} bytes for $mediaUrl: ${e.failureDetail()}"
            )
        } catch (e: Exception) {
            tempFile.delete()
            return ClipboardPreviewMediaCacheResult.Failed(
                "Failed to cache preview media for $mediaUrl: ${e.failureDetail()}"
            )
        }
    }

    private fun cachePreviewMediaThumbnail(thumbnailUrl: String?, mediaFile: File) {
        val thumbFile = ClipboardUtil.thumbnailFor(mediaFile)
        if(thumbnailUrl == null || thumbFile.isFile) return

        val tempFile = File(thumbFile.parentFile, "${thumbFile.name}.tmp")
        try {
            val cached = withConnection(thumbnailUrl) { connection ->
                val contentType = connection.contentType.orEmpty().normalizedMimeType()
                if(!contentType.startsWith("image/")) {
                    false
                } else {
                    connection.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyToCapped(
                                output = output,
                                limit = MaxPreviewMediaBytes,
                                totalBytes = connection.contentLengthLong.takeIf { it > 0L }
                            )
                        }
                    }
                    true
                }
            }
            if(!cached) {
                tempFile.delete()
                return
            }

            if(!tempFile.renameTo(thumbFile)) {
                tempFile.copyTo(thumbFile, overwrite = true)
                tempFile.delete()
            }
        } catch(_: Exception) {
            tempFile.delete()
        }
    }

    private fun requestJsonObject(
        url: String,
        maxBytes: Int,
        requestHeaders: Map<String, String> = emptyMap()
    ): JsonObject =
        withConnection(url, requestHeaders) { connection ->
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

    private fun requestFinalUrl(url: String): String =
        withConnection(url) { connection ->
            connection.url.toString()
        }

    private inline fun <T> withConnection(
        url: String,
        requestHeaders: Map<String, String> = emptyMap(),
        block: (HttpURLConnection) -> T
    ): T {
        val connection = openConnection(url)
        requestHeaders.forEach { (name, value) ->
            connection.setRequestProperty(name, value)
        }
        return try {
            if(connection.responseCode == HttpTooManyRequests) {
                throw connection.rateLimitedException(url)
            }
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
        connection.setRequestProperty("Accept", "application/json,image/*,video/*,*/*")
        if(connection.url.host.endsWith(".pximg.net")) {
            connection.setRequestProperty("Referer", "https://www.pixiv.net/")
        }
        return connection
    }

    private fun PreviewRequest.provider(): ClipboardPreviewProvider = when (this) {
        is TwitterStatusUrl -> TwitterPreviewProvider.provider
        is PixivArtworkUrl -> PixivPreviewProvider.provider
        is RedditPostUrl -> RedditPreviewProvider.provider
        is YouTubeVideoUrl -> YouTubePreviewProvider.provider
    }

    private fun PreviewRequest.seedMetadata(): ClipboardPreviewMetadata =
        previewProvider().seedMetadata(this)

    private fun PreviewRequest.fetchPreview(): RemotePreviewData? =
        previewProvider().fetch(this)

    private fun PreviewRequest.fetchPreview(
        pixivSessionId: String?,
        redditAccessToken: String?
    ): RemotePreviewData? =
        when (this) {
            is PixivArtworkUrl -> fetchPixivPreview(this, pixivSessionId)
            is RedditPostUrl -> fetchRedditPreview(this, redditAccessToken)
            else -> fetchPreview()
        }

    private fun PreviewRequest.prefersImagePreview(): Boolean =
        previewProvider().prefersImagePreview(this)

    private fun PreviewRequest.toCandidate(): ClipboardPreviewCandidate {
        val metadata = seedMetadata()
        return ClipboardPreviewCandidate(
            request = this,
            provider = provider(),
            metadata = metadata,
            archiveKey = metadata.archiveKey(),
            prefersImagePreview = prefersImagePreview()
        )
    }

    private fun PreviewRequest.canonicalSourceUrl(): String =
        previewProvider().canonicalSourceUrl(this)

    private fun PreviewRequest.previewProvider(): ClipboardPreviewProviderAdapter =
        PreviewProviders.first { it.provider == provider() }

    private fun ClipboardLinkPreviewManifest.unavailablePreviewFailure(
        request: PreviewRequest,
        rawText: String
    ): ClipboardPreviewFetchFailure.Unavailable? {
        if(mediaItems.isNotEmpty()) return null
        val unavailableText = listOfNotNull(
            snippet,
            metadata?.title,
            metadata?.bodyText
        ).firstOrNull(::isUnavailablePreviewText) ?: return null
        val provider = request.provider()
        val sourceUrl = metadata?.sourceUrl ?: request.canonicalSourceUrl()
        val detail = "Unavailable ${provider.name} preview for $rawText: $unavailableText"
        return ClipboardPreviewFetchFailure.Unavailable(
            provider = provider,
            sourceUrl = sourceUrl,
            detail = detail
        )
    }

    private fun providerForUrl(url: String): ClipboardPreviewProvider? {
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return null
        return PreviewProviders.firstOrNull { it.ownsMediaHost(host) }?.provider
    }

    private fun HttpURLConnection.rateLimitedException(url: String): ClipboardPreviewRateLimitedException {
        val retryAfterHeader = getHeaderField("Retry-After")
        val retryAfter = retryAfterEpochMs(retryAfterHeader, System.currentTimeMillis())
        val detail = "HTTP 429 Too Many Requests for $url. Retry-After: ${retryAfterHeader ?: "not provided"}."
        return ClipboardPreviewRateLimitedException(
            retryAfterEpochMs = retryAfter,
            message = detail
        )
    }

    private fun extractPreviewRequest(rawText: String): PreviewRequest? {
        return LinkPreviewUrlRegex.findAll(rawText).firstNotNullOfOrNull { match ->
            val url = match.value.trimEnd('.', ',', ';', ':', ')', ']', '}')
            PreviewProviders.firstNotNullOfOrNull { it.parse(url) }
        }
    }

    private fun JsonObject.twitterStatusUrl(): TwitterStatusUrl? {
        stringValue("url")?.let { url ->
            parseTwitterStatusUrl(url)?.let { return it }
        }
        val id = stringValue("id")?.takeIf { it.isNotBlank() } ?: return null
        return TwitterStatusUrl(
            handle = objectValue("author")?.stringValue("screen_name"),
            id = id
        )
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

    private fun parseRedditPostUrl(url: String): RedditPostUrl? {
        val uri = runCatching { URL(url).toURI() }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!SupportedRedditHosts.contains(host)) return null

        val segments = uri.path.split('/').filter { it.isNotBlank() }
        val parsed = when {
            (host == "redd.it" || host == "www.redd.it") && segments.isNotEmpty() ->
                RedditPostUrl(
                    pathSegments = listOf(segments[0]),
                    postId = segments[0],
                    redirectUrl = "https://redd.it/${segments[0]}"
                )
            segments.size >= 4 && segments[0] in setOf("r", "u", "user") && segments[2] == "comments" ->
                RedditPostUrl(
                    pathSegments = segments,
                    postId = segments[3],
                    commentId = segments.getOrNull(5)
                )
            segments.size >= 4 && segments[0] in setOf("r", "u", "user") && segments[2] == "s" ->
                RedditPostUrl(
                    pathSegments = segments,
                    postId = segments[3],
                    redirectUrl = "https://www.reddit.com/${segments.joinToString("/")}"
                )
            segments.size >= 2 && segments[0] == "comments" ->
                RedditPostUrl(
                    pathSegments = segments,
                    postId = segments[1],
                    commentId = segments.getOrNull(3)
                )
            host.endsWith("rxddit.com") && segments.isNotEmpty() ->
                RedditPostUrl(pathSegments = segments, postId = segments[0])
            else -> null
        } ?: return null

        val postId = parsed.postId.takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (postId.length < 2) return null

        val commentId = parsed.commentId
            ?.takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
            ?.takeIf { it.length >= 2 }

        return parsed.copy(
            postId = postId,
            commentId = commentId
        )
    }

    private fun parseYouTubeVideoUrl(url: String): YouTubeVideoUrl? {
        val uri = runCatching { URL(url).toURI() }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!SupportedYouTubeHosts.contains(host)) return null

        val segments = uri.path.split('/').filter { it.isNotBlank() }
        val rawId = when {
            host == "youtu.be" || host == "www.youtu.be" -> segments.firstOrNull()
            segments.size >= 2 && segments[0] in setOf("shorts", "embed", "live", "v") -> segments[1]
            else -> uri.queryParameters()["v"]
        } ?: return null

        val id = rawId.takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (!id.isValidYouTubeVideoId()) return null
        return YouTubeVideoUrl(id)
    }
}

internal fun isUnavailablePreviewText(text: String?): Boolean {
    val normalized = text
        ?.lowercase()
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?: return false
    if(normalized.isBlank()) return false
    return listOf(
        "suspended account",
        "account has been suspended",
        "post is from a suspended",
        "tweet is from a suspended",
        "post is unavailable",
        "tweet is unavailable",
        "this post is unavailable",
        "this tweet is unavailable",
        "this post was deleted",
        "this tweet was deleted",
        "post has been deleted",
        "tweet has been deleted",
        "not found",
        "does not exist",
        "login to view",
        "log in to view",
        "sign in to view",
        "temporarily unavailable"
    ).any(normalized::contains)
}

private fun Throwable.failureDetail(): String =
    listOfNotNull(
        this::class.qualifiedName ?: this::class.simpleName,
        message?.takeIf { it.isNotBlank() }
    ).joinToString(": ").ifBlank { toString() }

internal fun retryAfterEpochMs(value: String?, nowEpochMs: Long): Long {
    val fallback = nowEpochMs + DefaultPreviewRateLimitCooldownMillis
    val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return fallback
    trimmed.toLongOrNull()?.let {
        return nowEpochMs + it.coerceAtLeast(0L) * 1000L
    }
    return runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.parse(trimmed)!!
            .time
            .coerceAtLeast(nowEpochMs)
    }.getOrDefault(fallback)
}

internal fun parseTwitterApiPreviewMediaUrlsForTest(responseText: String): List<String> =
    ClipboardLinkPreviewFetcher.parseTwitterApiPreviewMediaUrlsForTest(responseText)

internal fun parseTwitterApiPreviewForTest(responseText: String): ClipboardLinkPreviewManifest? =
    ClipboardLinkPreviewFetcher.parseTwitterApiPreviewForTest(responseText)

internal fun parsePixivPreviewMediaUrlsForTest(responseText: String, pageIndex: Int? = null): List<String> =
    ClipboardLinkPreviewFetcher.parsePixivPreviewMediaUrlsForTest(responseText, pageIndex)

internal fun parseTwitterHtmlPreviewMediaUrlsForTest(html: String): List<String> =
    ClipboardLinkPreviewFetcher.parseTwitterHtmlPreviewMediaUrlsForTest(html)

internal fun parseRedditEmbedPreviewForTest(responseText: String): ClipboardLinkPreviewManifest? =
    ClipboardLinkPreviewFetcher.parseRedditEmbedPreviewForTest(responseText)

internal fun parseRedditApiPreviewForTest(responseText: String): ClipboardLinkPreviewManifest? =
    ClipboardLinkPreviewFetcher.parseRedditApiPreviewForTest(responseText)

internal fun parseYouTubeOEmbedPreviewForTest(responseText: String): ClipboardLinkPreviewManifest? =
    ClipboardLinkPreviewFetcher.parseYouTubeOEmbedPreviewForTest(responseText)

internal fun unavailablePreviewFailureForTest(
    snippet: String?,
    title: String? = null,
    bodyText: String? = null,
    mediaItems: List<ClipboardLinkPreviewMedia> = emptyList()
): ClipboardPreviewFetchFailure.Unavailable? =
    ClipboardLinkPreviewFetcher.unavailablePreviewFailureForTest(snippet, title, bodyText, mediaItems)

private fun InputStream.readBytesCapped(limit: Int): ByteArray {
    val output = ByteArrayOutputStream(limit.coerceAtMost(8 * 1024))
    copyToCapped(output, limit)
    return output.toByteArray()
}

internal fun InputStream.copyToCapped(
    output: OutputStream,
    limit: Int,
    totalBytes: Long? = null,
    onProgress: (ClipboardPreviewMediaDownloadProgress) -> Unit = {}
) {
    val buffer = ByteArray(8 * 1024)
    var total = 0

    while (true) {
        val read = read(buffer)
        if (read <= 0) break
        total += read
        if (total > limit) throw IllegalStateException("Preview payload too large")
        output.write(buffer, 0, read)
        onProgress(
            ClipboardPreviewMediaDownloadProgress(
                completedBytes = total.toLong(),
                totalBytes = totalBytes
            )
        )
    }
}

private fun InputStream.readStringCapped(limit: Int): String =
    readBytesCapped(limit).toString(Charsets.UTF_8)

private fun String.stripSimpleHtml(): String =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(this)
        }.toString()
    }.getOrElse {
        replace(Regex("<[^>]+>"), " ")
    }
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

private fun JsonObject.pixivTags(): List<String> =
    objectValue("tags")
        ?.arrayValue("tags")
        ?.mapNotNull { (it as? JsonObject)?.stringValue("tag")?.trim()?.takeIf { tag -> tag.isNotBlank() } }
        .orEmpty()

private fun JsonArray.pixivOriginalImageUrls(): List<String> =
    mapNotNull { page ->
        (page as? JsonObject)
            ?.objectValue("urls")
            ?.let { urls ->
                urls.stringValue("original")
                    ?: urls.stringValue("regular")
                    ?: urls.stringValue("small")
            }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

private fun JsonObject.redditApiMediaItems(): List<ClipboardLinkPreviewMedia> {
    val galleryUrls = objectValue("gallery_data")
        ?.arrayValue("items")
        ?.mapNotNull { item ->
            val mediaId = (item as? JsonObject)?.stringValue("media_id") ?: return@mapNotNull null
            objectValue("media_metadata")
                ?.objectValue(mediaId)
                ?.redditMediaMetadataUrl()
        }
        .orEmpty()

    val previewUrls = objectValue("preview")
        ?.arrayValue("images")
        ?.mapNotNull { image ->
            (image as? JsonObject)
                ?.objectValue("source")
                ?.stringValue("url")
        }
        .orEmpty()

    val redditVideo = objectValue("secure_media")
        ?.objectValue("reddit_video")
        ?: objectValue("media")?.objectValue("reddit_video")
        ?: objectValue("preview")?.objectValue("reddit_video_preview")

    val urls = galleryUrls.ifEmpty {
        listOfNotNull(
            redditVideo?.stringValue("fallback_url"),
            stringValue("url_overridden_by_dest")
                ?.takeIf { url -> url.guessedClipboardMimeType()?.startsWith("image/") == true },
            stringValue("url")?.takeIf { url -> url.guessedClipboardMimeType()?.startsWith("image/") == true }
        ) + previewUrls
    }

    return urls
        .map { it.redditApiUrlDecode() }
        .filter { it.isNotBlank() }
        .distinctBy { it.redditPreviewMediaIdentity() }
        .mapIndexed { index, url ->
            ClipboardLinkPreviewMedia(
                url = url,
                sourceIndex = index,
                mimeType = url.guessedClipboardMimeType()
            )
        }
}

private fun String.redditApiUrlDecode(): String =
    replace("&amp;", "&")
        .stripSimpleHtml()

private fun JsonObject.redditMediaMetadataUrl(): String? {
    val preferred = objectValue("s")?.stringValue("u")
        ?: objectValue("s")?.stringValue("gif")
        ?: objectValue("s")?.stringValue("mp4")
    return preferred?.takeIf { it.isNotBlank() }
}

private fun pixivAjaxHeaders(pixivSessionId: String?): Map<String, String> {
    val cookie = pixivSessionId?.pixivSessionCookieHeader() ?: return emptyMap()
    return mapOf("Cookie" to cookie)
}

private fun String.redditOAuthHeaders(): Map<String, String> {
    val credential = trim()
    val token = if(credential.startsWith("Bearer ", ignoreCase = true)) {
        credential.substringAfter(' ').trim()
    } else {
        requestRedditInstalledClientAccessToken(credential)
    }
    return mapOf(
        "Authorization" to "bearer $token",
        "User-Agent" to RedditUserAgent
    )
}

private fun redditEmbedHeaders(): Map<String, String> =
    mapOf("User-Agent" to RedditUserAgent)

private fun requestRedditInstalledClientAccessToken(clientId: String): String {
    val body = "grant_type=${URLEncoder.encode("https://oauth.reddit.com/grants/installed_client", "UTF-8")}" +
        "&device_id=${URLEncoder.encode(RedditInstalledClientDeviceId, "UTF-8")}"
    val auth = Base64.encodeToString("$clientId:".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    val connection = URL("https://www.reddit.com/api/v1/access_token").openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.connectTimeout = PreviewConnectTimeoutMillis
    connection.readTimeout = PreviewReadTimeoutMillis
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setRequestProperty("Authorization", "Basic $auth")
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    connection.setRequestProperty("User-Agent", RedditUserAgent)
    val response = try {
        connection.outputStream.use { output ->
            output.write(body.toByteArray(Charsets.UTF_8))
        }
        connection.inputStream.use { stream ->
            LinkPreviewJson.parseToJsonElement(stream.readStringCapped(MaxPreviewJsonBytes)).jsonObject
        }
    } finally {
        connection.disconnect()
    }
    return response.stringValue("access_token")
        ?.takeIf { it.isNotBlank() }
        ?: error("Reddit OAuth response did not include an access token")
}

private fun String.redditPermalinkUrl(): String =
    if(startsWith("http://") || startsWith("https://")) this else "https://www.reddit.com${this}"

private fun String.pixivSessionCookieHeader(): String? {
    val sessionId = trim()
        .split(';')
        .firstOrNull { it.trim().startsWith("PHPSESSID=") }
        ?.substringAfter('=')
        ?.trim()
        ?: trim()
    return sessionId.takeIf { it.isNotBlank() }?.let { "PHPSESSID=$it" }
}

private fun JsonArray.firstObject(): JsonObject? =
    firstOrNull() as? JsonObject

private fun HtmlPreviewDocument.htmlMetaContent(property: String): String? =
    htmlMetaContents(property).firstOrNull()

private fun HtmlPreviewDocument.htmlMetaContents(property: String): List<String> =
    metaContents[property.lowercase()].orEmpty()

private fun String.htmlMetaAttributes(): Sequence<Map<String, String>> =
    Regex("""<meta\b[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(this)
        .mapNotNull { attributes ->
            HtmlAttributeRegex.findAll(attributes.value)
                .associate { attribute ->
                    attribute.groupValues[1].lowercase() to attribute.groupValues[3]
                }
        }

private fun String.htmlPreviewDocument(): HtmlPreviewDocument {
    val metaContents = htmlMetaAttributes()
        .mapNotNull { attributes ->
            val key = attributes["property"] ?: attributes["name"] ?: return@mapNotNull null
            val value = attributes["content"]
                ?.stripSimpleHtml()
                ?.takeIf { content -> content.isNotBlank() }
                ?: return@mapNotNull null
            key.lowercase() to value
        }
        .groupBy(
            keySelector = { it.first },
            valueTransform = { it.second }
        )
    return HtmlPreviewDocument(
        metaContents = metaContents,
        canonicalUrl = htmlCanonicalUrl()
    )
}

private val HtmlAttributeRegex =
    Regex("""\b([A-Za-z_:.-]+)=(["'])(.*?)\2""", RegexOption.DOT_MATCHES_ALL)

private fun HtmlPreviewDocument.htmlPreviewMediaUrls(): List<String> =
    listOf(
        "og:video:secure_url",
        "og:video",
        "twitter:player:stream",
        "og:image",
        "twitter:image",
        "twitter:image:src"
    )
        .flatMap(::htmlMetaContents)
        .distinct()

private fun String.redditPreviewMediaIdentity(): String =
    runCatching {
        val url = URL(this)
        val host = url.host.lowercase()
        if(host == "i.redd.it" || host == "preview.redd.it") {
            url.path.substringAfterLast('/')
        } else {
            "$host${url.path}"
        }
    }.getOrDefault(this)

private data class HtmlPreviewCard(
    val title: String?,
    val description: String?,
    val canonicalUrl: String?,
    val authorHandles: List<String>,
    val mediaUrls: List<String>
)

private data class HtmlPreviewDocument(
    val metaContents: Map<String, List<String>>,
    val canonicalUrl: String?
)

private fun String.htmlPreviewCard(): HtmlPreviewCard =
    htmlPreviewDocument().let { document ->
        HtmlPreviewCard(
            title = document.htmlMetaContent("og:title"),
            description = document.htmlMetaContent("og:description"),
            canonicalUrl = document.canonicalUrl,
            authorHandles = listOf("twitter:creator", "twitter:site")
                .flatMap(document::htmlMetaContents)
                .distinct(),
            mediaUrls = document.htmlPreviewMediaUrls()
        )
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
    contains(".mp4", ignoreCase = true) -> "mp4"
    contains(".webm", ignoreCase = true) -> "webm"
    contains(".mkv", ignoreCase = true) -> "mkv"
    contains(".3gp", ignoreCase = true) -> "3gp"
    contains(".mov", ignoreCase = true) -> "mov"
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
    "video/mp4" -> "mp4"
    "video/webm" -> "webm"
    "video/x-matroska" -> "mkv"
    "video/3gpp" -> "3gp"
    "video/quicktime" -> "mov"
    else -> null
}

private fun String.isValidYouTubeVideoId(): Boolean =
    length >= 6 && all { it.isLetterOrDigit() || it == '_' || it == '-' }

private fun findCachedPreviewFile(directory: File, fileBaseName: String): File? =
    directory.listFiles()
        ?.firstOrNull { file ->
            file.isFile &&
                file.name.startsWith("$fileBaseName.") &&
                !file.name.contains(".thumb.")
        }

private fun String.md5Hex(): String =
    MessageDigest.getInstance("MD5").digest(toByteArray()).joinToString("") { "%02x".format(it) }

internal sealed interface PreviewRequest

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
    fun pasteUrl(targetDomain: String): String = "https://$targetDomain/$language/artworks/$id"
}

private data class PixivPreviewResponse(
    val infoResponse: JsonObject,
    val pagesResponse: JsonArray?
)

private data class RedditPostUrl(
    val pathSegments: List<String>,
    val postId: String,
    val commentId: String? = null,
    val redirectUrl: String? = null
) : PreviewRequest {
    fun canonicalUrl(): String = "https://www.reddit.com/${pathSegments.joinToString("/")}"
    fun oauthPostUrl(): String = "https://oauth.reddit.com/by_id/t3_$postId?raw_json=1"
    fun embedUrl(): String =
        "https://www.reddit.com/oembed?url=${URLEncoder.encode(canonicalUrl(), "UTF-8")}"
    fun sourceId(): String = listOfNotNull(postId, commentId).joinToString(":")
}

private data class YouTubeVideoUrl(
    val id: String
) : PreviewRequest {
    fun canonicalUrl(): String = "https://www.youtube.com/watch?v=$id"
    fun oEmbedUrl(): String =
        "https://www.youtube.com/oembed?url=${URLEncoder.encode(canonicalUrl(), "UTF-8")}&format=json"
}

private data class RemotePreviewData(
    val snippet: String?,
    val mediaItems: List<ClipboardLinkPreviewMedia>,
    val metadata: ClipboardPreviewMetadata?,
    val referencedPreviews: List<RemotePreviewData> = emptyList()
)

private fun RemotePreviewData.toManifest(): ClipboardLinkPreviewManifest =
    ClipboardLinkPreviewManifest(
        snippet = snippet,
        mediaItems = mediaItems,
        metadata = metadata,
        referencedManifests = referencedPreviews.map { it.toManifest() }
    )

private fun JsonObject?.twitterMediaItems(): List<ClipboardLinkPreviewMedia> {
    if (this == null) return emptyList()

    val photos = arrayValue("photos")
        ?.mapIndexedNotNull { index, element ->
            val url = (element as? JsonObject)
                ?.stringValue("url")
                ?.toOriginalSizedImageUrl()
                ?: return@mapIndexedNotNull null
            ClipboardLinkPreviewMedia(
                url = url,
                sourceIndex = index,
                mimeType = url.guessedClipboardMimeType()
            )
        }
        .orEmpty()

    val videos = arrayValue("videos")
        ?.mapIndexedNotNull { index, element ->
            val video = element as? JsonObject ?: return@mapIndexedNotNull null
            val thumbnailUrl = video.stringValue("thumbnail_url")
            val url = video.stringValue("url")
                ?: video.stringValue("download_url")
                ?: video.arrayValue("variants")?.firstNotNullOfOrNull { variant ->
                    (variant as? JsonObject)?.stringValue("url")
                }
                ?: video.arrayValue("variants")?.firstObject()?.stringValue("url")
                ?: video.objectValue("variants")?.stringValue("url")
                ?: thumbnailUrl
                ?: return@mapIndexedNotNull null
            ClipboardLinkPreviewMedia(
                url = url,
                sourceIndex = photos.size + index,
                mimeType = url.guessedClipboardMimeType(),
                thumbnailUrl = thumbnailUrl?.takeIf { it != url }
            )
        }
        .orEmpty()

    return photos + videos
}

private fun List<String>.prioritizeIndex(index: Int): List<Pair<Int, String>> {
    if (isEmpty()) return emptyList()
    val safeIndex = index.takeIf { it in indices }
    return buildList {
        safeIndex?.let { add(it to this@prioritizeIndex[it]) }
        this@prioritizeIndex.forEachIndexed { sourceIndex, url ->
            if (sourceIndex != safeIndex) add(sourceIndex to url)
        }
    }
}

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
