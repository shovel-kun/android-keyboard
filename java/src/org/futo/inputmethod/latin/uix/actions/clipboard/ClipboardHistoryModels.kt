package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.Context
import android.net.Uri
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.futo.inputmethod.latin.R
import java.io.File
import java.util.Locale

object UriSerializer : KSerializer<Uri> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Uri", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uri) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Uri {
        return Uri.parse(decoder.decodeString())
    }
}

@Serializable
enum class ClipboardPreviewProvider {
    TWITTER,
    PIXIV,
    FANBOX,
    REDDIT,
    YOUTUBE,
    MASTODON
}

@Serializable
data class ClipboardPreviewStats(
    val likeCount: Long? = null,
    val bookmarkCount: Long? = null,
    val viewCount: Long? = null,
    val replyCount: Long? = null,
    val repostCount: Long? = null,
    val quoteCount: Long? = null,
    val commentCount: Long? = null
)

@Serializable
data class ClipboardPreviewFlags(
    val aiGenerated: Boolean = false,
    val animated: Boolean = false,
    val restricted: Boolean = false,
    val noteTweet: Boolean = false
)

@Serializable
data class ClipboardPreviewMetadata(
    val provider: ClipboardPreviewProvider,
    val sourceUrl: String? = null,
    val sourceId: String? = null,
    val title: String? = null,
    val bodyText: String? = null,
    val authorName: String? = null,
    val authorHandle: String? = null,
    val authorId: String? = null,
    val createdAt: String? = null,
    val imageCount: Int? = null,
    val selectedImageIndex: Int? = null,
    val tags: List<String> = emptyList(),
    val stats: ClipboardPreviewStats? = null,
    val flags: ClipboardPreviewFlags = ClipboardPreviewFlags()
)

@Serializable
enum class ClipboardPreviewFetchStatus {
    NeverAttempted,
    Success,
    Failed
}

@Serializable
data class ClipboardPreviewMedia(
    val fileName: String,
    val sourceUrl: String? = null,
    val sourceIndex: Int = 0,
    val mimeType: String? = null
)

@Serializable
data class ClipboardEntry(
    val timestamp: Long,
    val pinned: Boolean,
    val text: String?,
    @Serializable(with = UriSerializer::class)
    val uri: Uri?,
    val mimeTypes: List<String>,
    val backingFile: String? = null,
    val sizeMb: Float? = null,
    val previewText: String? = null,
    val previewImageFile: String? = null,
    val previewMediaFiles: List<ClipboardPreviewMedia> = emptyList(),
    val previewMetadata: ClipboardPreviewMetadata? = null,
    val previewFetchStatus: ClipboardPreviewFetchStatus = ClipboardPreviewFetchStatus.NeverAttempted,
    val previewFetchLastAttemptAt: Long? = null,
    val previewFetchFailureDetail: String? = null,
    val deletedArchiveKeys: Set<String> = emptySet()
)

const val ClipboardFileName = "clipboard.json"

val Context.clipboardFile get() = File(filesDir, ClipboardFileName)
val Context.clipboardDir get() = File(filesDir, "clipboardfiles")

val DefaultClipboardEntry = ClipboardEntry(
    timestamp = 0L,
    pinned = true,
    text = "Clipboard entries will appear here",
    uri = null,
    mimeTypes = listOf()
)

private val ClipboardVideoExtensions = setOf("mp4", "webm", "mkv", "m4v", "3gp", "mov")
private val ClipboardGifExtensions = setOf("gif")
private val ClipboardImageExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp", "avif") + ClipboardGifExtensions

private fun String.fileExtensionLowercase(): String =
    substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase(Locale.ROOT)

fun String.guessedClipboardMimeType(): String? = when (fileExtensionLowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "avif" -> "image/avif"
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "3gp" -> "video/3gpp"
    "mov" -> "video/quicktime"
    else -> null
}

fun String.isClipboardVideoFileName(): Boolean =
    fileExtensionLowercase() in ClipboardVideoExtensions

fun String.isClipboardGifFileName(): Boolean =
    fileExtensionLowercase() in ClipboardGifExtensions

fun String.isClipboardImageFileName(): Boolean =
    fileExtensionLowercase() in ClipboardImageExtensions

fun File.guessedClipboardMimeType(): String? =
    name.guessedClipboardMimeType()

fun File.isClipboardVideoFile(): Boolean =
    name.isClipboardVideoFileName()

fun File.isClipboardGifFile(): Boolean =
    name.isClipboardGifFileName()

fun File.isClipboardImageFile(): Boolean =
    name.isClipboardImageFileName()

fun ClipboardEntry.getFile(context: Context): File? =
    getFile(context.clipboardDir)

fun ClipboardEntry.getFile(clipboardDir: File): File? =
    backingFile?.let { File(clipboardDir, it) }

fun ClipboardEntry.getPreviewFile(context: Context): File? =
    getPreviewFile(context.clipboardDir)

fun ClipboardEntry.getPreviewFile(clipboardDir: File): File? =
    previewMediaFileNames().firstOrNull()?.let { previewMediaFile(clipboardDir, it) }

fun ClipboardEntry.getPreviewFiles(context: Context): List<File> =
    getPreviewFiles(context.clipboardDir)

fun ClipboardEntry.getPreviewFiles(clipboardDir: File): List<File> =
    previewMediaFileNames().map { previewMediaFile(clipboardDir, it) }

private fun previewMediaFile(clipboardDir: File, fileName: String): File {
    return File(clipboardDir, fileName)
}

fun ClipboardEntry.previewMedia(): List<ClipboardPreviewMedia> =
    when {
        previewMediaFiles.isNotEmpty() -> previewMediaFiles
        previewImageFile != null -> listOf(ClipboardPreviewMedia(fileName = previewImageFile))
        else -> emptyList()
    }

fun ClipboardEntry.previewMediaFileNames(): List<String> =
    previewMedia().map { it.fileName }

fun ClipboardEntry.hasRenderablePreview(): Boolean =
    previewText != null || previewMedia().isNotEmpty()

fun ClipboardEntry.hasRetainedPreviewState(): Boolean =
    hasRenderablePreview() ||
        previewMetadata != null ||
        previewFetchStatus != ClipboardPreviewFetchStatus.NeverAttempted ||
        previewFetchLastAttemptAt != null

fun ClipboardEntry.canAutoFetchPreview(): Boolean =
    text != null &&
        !hasRenderablePreview() &&
        previewFetchStatus == ClipboardPreviewFetchStatus.NeverAttempted &&
        ClipboardLinkPreviewFetcher.previewCandidateFor(text) != null

fun ClipboardEntry.shouldShowManualPreviewRetry(): Boolean =
    text != null &&
        !hasRenderablePreview() &&
        previewFetchStatus == ClipboardPreviewFetchStatus.Failed

fun ClipboardEntry.selectionKey(): String =
    text ?: backingFile ?: timestamp.toString()

// History loading and imports deduplicate by text or backing file, not timestamp.
fun ClipboardEntry.lazyListKey(): String = when {
    text != null -> if(text.length <= 512) "text:$text" else "textHash:${text.toFNV1aHash()}"
    backingFile != null -> "file:$backingFile"
    else -> "timestamp:$timestamp"
}

fun ClipboardEntry.matchesQuery(query: String): Boolean =
    matchesNormalizedQuery(query.trim().lowercase())

internal fun ClipboardEntry.matchesNormalizedQuery(query: String): Boolean {
    if(query.isEmpty()) return true
    fun String?.matches() = this?.contains(query, ignoreCase = true) == true

    return text.matches() || previewText.matches() ||
        previewMetadata?.title.matches() || previewMetadata?.bodyText.matches() ||
        previewMetadata?.authorName.matches() || previewMetadata?.authorHandle.matches() ||
        previewMetadata?.tags.orEmpty().any { it.matches() } ||
        mimeTypes.any { it.matches() } || searchTokens().any { it.matches() }
}

fun sortedClipboardEntries(
    entries: List<ClipboardEntry>,
    showPinnedOnTop: Boolean
): List<ClipboardEntry> {
    val sorted = if(showPinnedOnTop) {
        entries.sortedBy { it.pinned }
    } else {
        entries
    }
    return sorted.asReversed()
}

internal fun sanitizeClipboardText(text: String, maxLength: Int = 64): String {
    var result = text.replace("\n", " ")
    if(result.length > maxLength) {
        result = result.substring(0, maxLength) + "..."
    }
    return result
}

internal fun wrapDisplayTextAnywhere(text: String): String =
    buildString(text.length * 2) {
        text.forEachIndexed { index, char ->
            append(char)
            if(index != text.lastIndex) append('\u200B')
        }
    }

fun String.toFNV1aHash(): Long {
    val fnvPrime: Long = 1099511628211L
    var hash: Long = -3750763034362895579L

    for (byte in this.toByteArray()) {
        hash = hash xor byte.toLong()
        hash *= fnvPrime
    }

    return hash
}

private fun ClipboardEntry.searchTokens(): Set<String> = buildSet {
    if(text != null) addAll(setOf("text", "link"))
    if(matchesMediaFilter(ClipboardMediaFilter.Images)) {
        addAll(setOf("media", "image", "images", "photo", "picture"))
    }
    if(matchesMediaFilter(ClipboardMediaFilter.Videos)) {
        addAll(setOf("media", "video", "videos", "clip"))
    }
    if(matchesMediaFilter(ClipboardMediaFilter.Gifs)) {
        addAll(setOf("gif", "gifs"))
    }

    if(pinned) {
        addAll(setOf("pinned", "pin"))
    }
}

internal enum class ClipboardMediaFilter(val labelRes: Int) {
    All(R.string.clipboard_history_filter_all),
    Images(R.string.clipboard_history_filter_images),
    Videos(R.string.clipboard_history_filter_videos),
    Gifs(R.string.clipboard_history_filter_gifs);

    fun matches(mimeType: String?, fileName: String?): Boolean {
        if(this == All) return true
        val declaredType = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val guessedType = fileName?.guessedClipboardMimeType()
        val type = when (declaredType) {
            null, "", "*/*", "application/octet-stream" -> guessedType
            "image/*" -> guessedType?.takeIf { it.startsWith("image/") } ?: declaredType
            else -> declaredType
        }
        return when (this) {
            All -> true
            Images -> type?.startsWith("image/") == true
            Videos -> type?.startsWith("video/") == true
            Gifs -> type == "image/gif"
        }
    }
}

internal fun ClipboardEntry.matchesMediaFilter(filter: ClipboardMediaFilter): Boolean {
    if(filter == ClipboardMediaFilter.All) return true

    val fileMatches = if(backingFile != null || uri != null) {
        if(mimeTypes.isEmpty()) filter.matches(null, backingFile)
        else mimeTypes.any { filter.matches(it, backingFile) }
    } else {
        false
    }
    return fileMatches || previewMedia().any { filter.matches(it.mimeType, it.fileName) }
}

internal fun ClipboardLinkArchive.matchesMediaFilter(filter: ClipboardMediaFilter): Boolean =
    filter == ClipboardMediaFilter.All ||
        media.any { filter.matches(it.mimeType, it.fileName ?: it.sourceUrl) }
