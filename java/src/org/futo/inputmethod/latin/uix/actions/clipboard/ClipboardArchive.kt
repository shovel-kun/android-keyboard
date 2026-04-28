package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

const val ClipboardArchiveFileName = "clipboard_archives.json"
const val ClipboardArchiveFilesDirectoryName = "clipboardarchivefiles"
const val ClipboardBackupArchiveFilesDirectoryName = ClipboardArchiveFilesDirectoryName

val Context.clipboardArchiveFile get() = File(filesDir, ClipboardArchiveFileName)
val Context.clipboardArchiveDir get() = File(filesDir, ClipboardArchiveFilesDirectoryName)

private val ClipboardArchiveJson = Json {
    ignoreUnknownKeys = true
}

@Serializable
enum class ClipboardArchiveMediaStatus {
    Pending,
    Saved,
    Failed,
    SkippedTooLarge,
    Missing
}

@Serializable
enum class ClipboardLinkArchiveStatus {
    Pending,
    InProgress,
    Complete,
    Partial,
    Failed
}

@Serializable
data class ClipboardArchiveMedia(
    val sourceUrl: String,
    val sourceIndex: Int,
    val mimeType: String? = null,
    val fileName: String? = null,
    val status: ClipboardArchiveMediaStatus = ClipboardArchiveMediaStatus.Pending,
    val lastAttemptAtEpochMs: Long? = null
)

@Serializable
data class ClipboardLinkArchive(
    val key: String,
    val provider: ClipboardPreviewProvider,
    val sourceUrl: String,
    val sourceId: String? = null,
    val metadata: ClipboardPreviewMetadata? = null,
    val media: List<ClipboardArchiveMedia> = emptyList(),
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
) {
    val status: ClipboardLinkArchiveStatus
        get() = archiveStatusFor(media)
}

fun ClipboardPreviewMetadata.archiveKey(): String? {
    val source = sourceId ?: sourceUrl ?: return null
    return "${provider.name.lowercase()}:$source"
}

fun ClipboardLinkPreviewManifest.archiveKey(): String? =
    metadata?.archiveKey()

fun encodeClipboardArchives(archives: Collection<ClipboardLinkArchive>): String =
    ClipboardArchiveJson.encodeToString(archives.sortedBy { it.key })

fun decodeClipboardArchives(text: String): List<ClipboardLinkArchive> =
    ClipboardArchiveJson.decodeFromString<List<ClipboardLinkArchive>>(text)

fun File.decodeClipboardArchives(): List<ClipboardLinkArchive> =
    decodeClipboardArchives(readText())

fun ClipboardLinkArchive.savedPreviewMedia(): List<ClipboardPreviewMedia> =
    media
        .filter { it.status == ClipboardArchiveMediaStatus.Saved && it.fileName != null }
        .sortedBy { it.sourceIndex }
        .map {
            ClipboardPreviewMedia(
                fileName = it.fileName!!,
                sourceUrl = it.sourceUrl,
                sourceIndex = it.sourceIndex,
                mimeType = it.mimeType
            )
        }

fun ClipboardLinkArchive.hasRetryableMedia(): Boolean =
    media.any { it.status.isRetryableArchiveMediaStatus() }

fun ClipboardArchiveMediaStatus.isRetryableArchiveMediaStatus(): Boolean = when (this) {
    ClipboardArchiveMediaStatus.Pending,
    ClipboardArchiveMediaStatus.Failed,
    ClipboardArchiveMediaStatus.Missing -> true
    ClipboardArchiveMediaStatus.Saved,
    ClipboardArchiveMediaStatus.SkippedTooLarge -> false
}

fun ClipboardLinkArchive.retryableMedia(): List<ClipboardArchiveMedia> =
    media.filter { it.status.isRetryableArchiveMediaStatus() }

fun referencedClipboardArchiveFileNames(archives: Collection<ClipboardLinkArchive>): Set<String> =
    archives
        .flatMap { archive -> archive.media.mapNotNull { it.fileName } }
        .flatMap { listOf(it, ClipboardUtil.thumbnailForName(it)) }
        .toSet()

fun reconcileClipboardArchivesWithStorage(
    archives: Collection<ClipboardLinkArchive>,
    archiveDir: File,
    now: Long = System.currentTimeMillis()
): List<ClipboardLinkArchive> =
    archives.map { archive ->
        val reconciledMedia = archive.media.map { media ->
            if(media.status == ClipboardArchiveMediaStatus.Saved && media.fileName != null &&
                File(archiveDir, media.fileName).isFile != true
            ) {
                media.copy(
                    status = ClipboardArchiveMediaStatus.Missing,
                    lastAttemptAtEpochMs = now
                )
            } else {
                media
            }
        }
        if(reconciledMedia == archive.media) archive else archive.copy(
            media = reconciledMedia,
            updatedAtEpochMs = now
        )
    }

fun mergeClipboardArchives(
    currentArchives: Collection<ClipboardLinkArchive>,
    importedArchives: Collection<ClipboardLinkArchive>
): List<ClipboardLinkArchive> {
    val merged = currentArchives.associateBy { it.key }.toMutableMap()
    importedArchives.forEach { incoming ->
        merged[incoming.key] = merged[incoming.key]?.let { existing ->
            mergeClipboardArchive(existing, incoming)
        } ?: incoming
    }
    return merged.values.sortedBy { it.key }
}

fun newArchiveFromManifest(
    manifest: ClipboardLinkPreviewManifest,
    now: Long = System.currentTimeMillis()
): ClipboardLinkArchive? {
    val metadata = manifest.metadata ?: return null
    val key = manifest.archiveKey() ?: return null
    val sourceUrl = metadata.sourceUrl ?: return null
    return ClipboardLinkArchive(
        key = key,
        provider = metadata.provider,
        sourceUrl = sourceUrl,
        sourceId = metadata.sourceId,
        metadata = metadata,
        media = manifest.mediaItems.map {
            ClipboardArchiveMedia(
                sourceUrl = it.url,
                sourceIndex = it.sourceIndex,
                mimeType = it.mimeType
            )
        },
        createdAtEpochMs = now,
        updatedAtEpochMs = now
    )
}

fun mergeArchiveWithManifest(
    archive: ClipboardLinkArchive,
    manifest: ClipboardLinkPreviewManifest,
    now: Long = System.currentTimeMillis()
): ClipboardLinkArchive {
    val incomingMediaByUrl = manifest.mediaItems.associateBy { it.url }
    val existingByUrl = archive.media.associateBy { it.sourceUrl }
    val media = manifest.mediaItems.map { incoming ->
        existingByUrl[incoming.url]?.copy(
            sourceIndex = incoming.sourceIndex,
            mimeType = incoming.mimeType ?: existingByUrl[incoming.url]?.mimeType
        ) ?: ClipboardArchiveMedia(
            sourceUrl = incoming.url,
            sourceIndex = incoming.sourceIndex,
            mimeType = incoming.mimeType
        )
    } + archive.media.filter { it.sourceUrl !in incomingMediaByUrl }

    return archive.copy(
        sourceUrl = manifest.metadata?.sourceUrl ?: archive.sourceUrl,
        sourceId = manifest.metadata?.sourceId ?: archive.sourceId,
        metadata = mergePreviewMetadataForArchive(archive.metadata, manifest.metadata),
        media = media.sortedBy { it.sourceIndex },
        updatedAtEpochMs = now
    )
}

private fun archiveStatusFor(media: List<ClipboardArchiveMedia>): ClipboardLinkArchiveStatus {
    if(media.isEmpty()) return ClipboardLinkArchiveStatus.Failed
    if(media.all { it.status == ClipboardArchiveMediaStatus.Pending }) return ClipboardLinkArchiveStatus.Pending
    if(media.any { it.status == ClipboardArchiveMediaStatus.Pending }) return ClipboardLinkArchiveStatus.InProgress
    if(media.all { it.status == ClipboardArchiveMediaStatus.Saved }) return ClipboardLinkArchiveStatus.Complete
    if(media.any { it.status == ClipboardArchiveMediaStatus.Saved }) return ClipboardLinkArchiveStatus.Partial
    return ClipboardLinkArchiveStatus.Failed
}

private fun mergeClipboardArchive(
    existing: ClipboardLinkArchive,
    incoming: ClipboardLinkArchive
): ClipboardLinkArchive {
    val existingByUrl = existing.media.associateBy { it.sourceUrl }
    val incomingByUrl = incoming.media.associateBy { it.sourceUrl }
    val allUrls = (existingByUrl.keys + incomingByUrl.keys)
    val mergedMedia = allUrls.mapNotNull { url ->
        val left = existingByUrl[url]
        val right = incomingByUrl[url]
        when {
            left == null -> right
            right == null -> left
            else -> richerArchiveMedia(left, right)
        }
    }.sortedBy { it.sourceIndex }

    return existing.copy(
        sourceUrl = existing.sourceUrl.ifBlank { incoming.sourceUrl },
        sourceId = existing.sourceId ?: incoming.sourceId,
        metadata = mergePreviewMetadataForArchive(existing.metadata, incoming.metadata),
        media = mergedMedia,
        createdAtEpochMs = minOf(existing.createdAtEpochMs, incoming.createdAtEpochMs),
        updatedAtEpochMs = maxOf(existing.updatedAtEpochMs, incoming.updatedAtEpochMs)
    )
}

private fun richerArchiveMedia(
    existing: ClipboardArchiveMedia,
    incoming: ClipboardArchiveMedia
): ClipboardArchiveMedia {
    val incomingWins = archiveMediaStatusScore(incoming.status) > archiveMediaStatusScore(existing.status)
    val winner = if(incomingWins) incoming else existing
    val loser = if(incomingWins) existing else incoming
    return winner.copy(
        mimeType = winner.mimeType ?: loser.mimeType,
        fileName = winner.fileName ?: loser.fileName,
        lastAttemptAtEpochMs = maxOfNullableForArchive(
            winner.lastAttemptAtEpochMs,
            loser.lastAttemptAtEpochMs
        )
    )
}

private fun archiveMediaStatusScore(status: ClipboardArchiveMediaStatus): Int = when (status) {
    ClipboardArchiveMediaStatus.Pending -> 0
    ClipboardArchiveMediaStatus.Failed -> 1
    ClipboardArchiveMediaStatus.Missing -> 1
    ClipboardArchiveMediaStatus.SkippedTooLarge -> 2
    ClipboardArchiveMediaStatus.Saved -> 3
}

private fun mergePreviewMetadataForArchive(
    existing: ClipboardPreviewMetadata?,
    incoming: ClipboardPreviewMetadata?
): ClipboardPreviewMetadata? =
    when {
        existing == null -> incoming
        incoming == null -> existing
        else -> incoming.copy(
            sourceUrl = incoming.sourceUrl ?: existing.sourceUrl,
            sourceId = incoming.sourceId ?: existing.sourceId,
            title = incoming.title ?: existing.title,
            bodyText = incoming.bodyText ?: existing.bodyText,
            authorName = incoming.authorName ?: existing.authorName,
            authorHandle = incoming.authorHandle ?: existing.authorHandle,
            authorId = incoming.authorId ?: existing.authorId,
            createdAt = incoming.createdAt ?: existing.createdAt,
            imageCount = incoming.imageCount ?: existing.imageCount,
            selectedImageIndex = incoming.selectedImageIndex ?: existing.selectedImageIndex,
            tags = incoming.tags.ifEmpty { existing.tags },
            stats = incoming.stats ?: existing.stats,
            flags = ClipboardPreviewFlags(
                aiGenerated = incoming.flags.aiGenerated || existing.flags.aiGenerated,
                animated = incoming.flags.animated || existing.flags.animated,
                restricted = incoming.flags.restricted || existing.flags.restricted,
                noteTweet = incoming.flags.noteTweet || existing.flags.noteTweet
            )
        )
    }

private fun maxOfNullableForArchive(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}
