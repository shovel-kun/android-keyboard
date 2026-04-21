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
import java.io.File

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
    PIXIV
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
    val previewMetadata: ClipboardPreviewMetadata? = null,
    val previewFetchStatus: ClipboardPreviewFetchStatus = ClipboardPreviewFetchStatus.NeverAttempted,
    val previewFetchLastAttemptAt: Long? = null,
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

fun ClipboardEntry.getFile(context: Context): File? =
    backingFile?.let { File(context.clipboardDir, it) }

fun ClipboardEntry.getPreviewFile(context: Context): File? =
    previewImageFile?.let { File(context.clipboardDir, it) }

fun ClipboardEntry.hasRenderablePreview(): Boolean =
    previewText != null || previewImageFile != null

fun ClipboardEntry.hasRetainedPreviewState(): Boolean =
    hasRenderablePreview() ||
        previewMetadata != null ||
        previewFetchStatus != ClipboardPreviewFetchStatus.NeverAttempted ||
        previewFetchLastAttemptAt != null

fun ClipboardEntry.canAutoFetchPreview(): Boolean =
    text != null &&
        !hasRenderablePreview() &&
        previewFetchStatus == ClipboardPreviewFetchStatus.NeverAttempted

fun ClipboardEntry.shouldShowManualPreviewRetry(): Boolean =
    text != null &&
        !hasRenderablePreview() &&
        previewFetchStatus == ClipboardPreviewFetchStatus.Failed

fun ClipboardEntry.selectionKey(): String =
    text ?: backingFile ?: timestamp.toString()

fun ClipboardEntry.matchesQuery(query: String): Boolean {
    if(query.isBlank()) return true

    val normalizedQuery = query.trim().lowercase()
    val haystacks = buildList {
        text?.let { add(it.lowercase()) }
        previewText?.let { add(it.lowercase()) }
        previewMetadata?.title?.let { add(it.lowercase()) }
        previewMetadata?.bodyText?.let { add(it.lowercase()) }
        previewMetadata?.authorName?.let { add(it.lowercase()) }
        previewMetadata?.authorHandle?.let { add(it.lowercase()) }
        previewMetadata?.tags?.forEach { add(it.lowercase()) }
        mimeTypes.forEach { add(it.lowercase()) }
        addAll(searchTokens())
    }

    return haystacks.any { it.contains(normalizedQuery) }
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
    when {
        backingFile != null -> addAll(setOf("image", "images", "photo", "picture"))
        text != null -> addAll(setOf("text", "link"))
    }

    if(pinned) {
        addAll(setOf("pinned", "pin"))
    }
}
