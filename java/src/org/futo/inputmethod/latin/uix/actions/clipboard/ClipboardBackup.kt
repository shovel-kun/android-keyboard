package org.futo.inputmethod.latin.uix.actions.clipboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.zip.ZipInputStream

private val ClipboardJson = Json {
    ignoreUnknownKeys = true
}

const val ClipboardBackupCurrentVersion = 1
const val ClipboardBackupManifestFileName = "manifest.json"
const val ClipboardBackupFilesDirectoryName = "clipboardfiles"

internal const val ClipboardBackupManifestMaxBytes = 64L * 1024L
internal const val ClipboardBackupEntriesMaxBytes = 32L * 1024L * 1024L
internal const val ClipboardBackupArchivesMaxBytes = 32L * 1024L * 1024L
// Archive imports allow a little headroom above the current 50 MiB stored-media cap.
internal const val ClipboardBackupMediaMaxBytes = 52L * 1024L * 1024L
internal const val ClipboardBackupMaxEntryCount = 10_000
internal const val ClipboardBackupTotalExpandedMaxBytes = 1024L * 1024L * 1024L

internal data class ClipboardBackupExtractionLimits(
    val manifestMaxBytes: Long = ClipboardBackupManifestMaxBytes,
    val entriesMaxBytes: Long = ClipboardBackupEntriesMaxBytes,
    val archivesMaxBytes: Long = ClipboardBackupArchivesMaxBytes,
    val mediaMaxBytes: Long = ClipboardBackupMediaMaxBytes,
    val maxEntryCount: Int = ClipboardBackupMaxEntryCount,
    val totalExpandedMaxBytes: Long = ClipboardBackupTotalExpandedMaxBytes
)

internal data class ExtractedClipboardBackup(
    val manifest: ClipboardBackupManifest,
    val entries: List<ClipboardEntry>,
    val filesDir: File,
    val archives: List<ClipboardLinkArchive>,
    val archiveFilesDir: File
)

@Serializable
data class ClipboardBackupManifest(
    val version: Int,
    val createdAtEpochMs: Long
)

enum class ClipboardImportMode {
    Merge,
    Replace
}

data class ClipboardBackupMetadata(
    val dateExported: Date,
    val isNewer: Boolean
)

fun decodeClipboardEntries(text: String): List<ClipboardEntry> =
    ClipboardJson.decodeFromString<List<ClipboardEntry>>(text).map { it.withNormalizedPreviewMedia() }

fun encodeClipboardEntries(entries: List<ClipboardEntry>): String =
    ClipboardJson.encodeToString(entries.map { it.withCurrentPreviewMediaEncoding() })

fun File.decodeClipboardEntries(): List<ClipboardEntry> =
    decodeClipboardEntries(readText())

private fun ClipboardEntry.withNormalizedPreviewMedia(): ClipboardEntry =
    when {
        previewMediaFiles.isNotEmpty() -> this
        previewImageFile != null -> copy(previewMediaFiles = previewMedia())
        else -> this
    }

private fun ClipboardEntry.withCurrentPreviewMediaEncoding(): ClipboardEntry =
    withNormalizedPreviewMedia().copy(previewImageFile = null)

fun clipboardBackupMetadata(manifest: ClipboardBackupManifest): ClipboardBackupMetadata =
    ClipboardBackupMetadata(
        dateExported = Date(manifest.createdAtEpochMs),
        isNewer = manifest.version > ClipboardBackupCurrentVersion
    )

internal fun extractClipboardBackup(
    inputStream: InputStream,
    tempRoot: File,
    limits: ClipboardBackupExtractionLimits = ClipboardBackupExtractionLimits()
): ExtractedClipboardBackup = ZipInputStream(inputStream).use { zipIn ->
    val tempFilesDir = File(tempRoot, ClipboardBackupFilesDirectoryName).apply { mkdirs() }
    val tempArchiveFilesDir = File(tempRoot, ClipboardBackupArchiveFilesDirectoryName).apply { mkdirs() }
    var manifest: ClipboardBackupManifest? = null
    var entries: List<ClipboardEntry>? = null
    var archives: List<ClipboardLinkArchive>? = null
    var entryCount = 0
    var totalExpandedBytes = 0L
    val singletonEntries = mutableSetOf<String>()

    fun copyCurrentEntry(output: OutputStream, entryLimit: Long) {
        val buffer = ByteArray(8 * 1024)
        var entryBytes = 0L
        while(true) {
            val read = zipIn.read(buffer)
            if(read <= 0) break
            entryBytes += read
            totalExpandedBytes += read
            require(entryBytes <= entryLimit) { "Clipboard backup entry exceeds size limit" }
            require(totalExpandedBytes <= limits.totalExpandedMaxBytes) {
                "Clipboard backup exceeds total expanded size limit"
            }
            output.write(buffer, 0, read)
        }
    }

    var zipEntry = zipIn.nextEntry
    while(zipEntry != null) {
        entryCount += 1
        require(entryCount <= limits.maxEntryCount) { "Clipboard backup has too many entries" }
        require(!zipEntry.isDirectory) { "Unsupported clipboard backup path: ${zipEntry.name}" }

        when {
            zipEntry.name == ClipboardBackupManifestFileName -> {
                require(singletonEntries.add(zipEntry.name)) {
                    "Duplicate clipboard backup entry: ${zipEntry.name}"
                }
                val output = ByteArrayOutputStream()
                copyCurrentEntry(output, limits.manifestMaxBytes)
                manifest = ClipboardJson.decodeFromString(output.toString(Charsets.UTF_8.name()))
            }

            zipEntry.name == ClipboardFileName -> {
                require(singletonEntries.add(zipEntry.name)) {
                    "Duplicate clipboard backup entry: ${zipEntry.name}"
                }
                val output = ByteArrayOutputStream()
                copyCurrentEntry(output, limits.entriesMaxBytes)
                entries = decodeClipboardEntries(output.toString(Charsets.UTF_8.name()))
            }

            zipEntry.name == ClipboardArchiveFileName -> {
                require(singletonEntries.add(zipEntry.name)) {
                    "Duplicate clipboard backup entry: ${zipEntry.name}"
                }
                val output = ByteArrayOutputStream()
                copyCurrentEntry(output, limits.archivesMaxBytes)
                archives = decodeLegacyClipboardArchives(output.toString(Charsets.UTF_8.name()))
            }

            zipEntry.name.startsWith("$ClipboardBackupFilesDirectoryName/") -> {
                val relativePath = zipEntry.name.removePrefix("$ClipboardBackupFilesDirectoryName/")
                require(relativePath.isNotBlank() && !relativePath.contains('/')) {
                    "Unsupported clipboard backup file path: ${zipEntry.name}"
                }
                File(tempFilesDir, relativePath).outputStream().use {
                    copyCurrentEntry(it, limits.mediaMaxBytes)
                }
            }

            zipEntry.name.startsWith("$ClipboardBackupArchiveFilesDirectoryName/") -> {
                val relativePath = zipEntry.name.removePrefix("$ClipboardBackupArchiveFilesDirectoryName/")
                require(relativePath.isNotBlank() && !relativePath.contains('/')) {
                    "Unsupported clipboard archive backup file path: ${zipEntry.name}"
                }
                File(tempArchiveFilesDir, relativePath).outputStream().use {
                    copyCurrentEntry(it, limits.mediaMaxBytes)
                }
            }

            else -> throw IllegalArgumentException("Unsupported clipboard backup path: ${zipEntry.name}")
        }

        zipIn.closeEntry()
        zipEntry = zipIn.nextEntry
    }

    val extractedManifest = requireNotNull(manifest) { "Clipboard backup manifest missing" }
    require(extractedManifest.version >= 1) { "Invalid clipboard backup version" }
    require(extractedManifest.createdAtEpochMs >= 0L) { "Invalid clipboard backup creation time" }

    ExtractedClipboardBackup(
        manifest = extractedManifest,
        entries = entries.orEmpty(),
        filesDir = tempFilesDir,
        archives = archives.orEmpty(),
        archiveFilesDir = tempArchiveFilesDir
    )
}

fun referencedClipboardFileNames(entries: List<ClipboardEntry>): Set<String> =
    entries
        .flatMap { listOfNotNull(it.backingFile) + it.previewMediaFileNames() }
        .flatMap { listOf(it, ClipboardUtil.thumbnailForName(it)) }
        .toSet()

fun clipboardArchivesFromPreviewEntries(
    entries: Collection<ClipboardEntry>,
    now: Long = System.currentTimeMillis()
): List<ClipboardLinkArchive> {
    val recoveredArchives = entries.mapNotNull { entry ->
        val metadata = entry.archiveBackfillMetadata() ?: return@mapNotNull null
        val fallbackSourceUrl = metadata.sourceUrl ?: entry.text ?: return@mapNotNull null
        val savedMedia = entry.previewMedia().map { media ->
            ClipboardArchiveMedia(
                sourceUrl = media.sourceUrl ?: "$fallbackSourceUrl#legacy-media-${media.sourceIndex}",
                sourceIndex = media.sourceIndex,
                mimeType = media.mimeType ?: media.fileName.guessedClipboardMimeType(),
                fileName = media.fileName,
                status = ClipboardArchiveMediaStatus.Saved,
                lastAttemptAtEpochMs = now
            )
        }

        newFallbackArchiveFromEntry(
            entry = entry,
            metadata = metadata,
            savedMedia = savedMedia,
            now = now
        )
    }

    return mergeClipboardArchives(
        currentArchives = emptyList(),
        importedArchives = recoveredArchives
    )
}

fun clipboardArchivesFromLocalPreviewEntries(
    entries: Collection<ClipboardEntry>,
    clipboardDir: File,
    existingArchiveKeys: Set<String>,
    deletedArchiveKeys: Set<String>,
    now: Long = System.currentTimeMillis()
): List<ClipboardLinkArchive> {
    val recoveredArchives = entries.mapNotNull { entry ->
        val metadata = entry.archiveBackfillMetadata() ?: return@mapNotNull null
        val archiveKey = metadata.archiveKey() ?: return@mapNotNull null
        if(archiveKey in existingArchiveKeys || archiveKey in deletedArchiveKeys) return@mapNotNull null
        val fallbackSourceUrl = metadata.sourceUrl ?: entry.text ?: return@mapNotNull null
        val savedMedia = entry.previewMedia().mapNotNull { media ->
            val file = File(clipboardDir, media.fileName)
            if(!file.isFile) return@mapNotNull null

            ClipboardArchiveMedia(
                sourceUrl = media.sourceUrl ?: "$fallbackSourceUrl#legacy-media-${media.sourceIndex}",
                sourceIndex = media.sourceIndex,
                mimeType = media.mimeType ?: media.fileName.guessedClipboardMimeType(),
                fileName = media.fileName,
                status = ClipboardArchiveMediaStatus.Saved,
                lastAttemptAtEpochMs = now
            )
        }
        if(savedMedia.isEmpty()) return@mapNotNull null

        newFallbackArchiveFromEntry(
            entry = entry,
            metadata = metadata,
            savedMedia = savedMedia,
            now = now
        )
    }

    return mergeClipboardArchives(
        currentArchives = emptyList(),
        importedArchives = recoveredArchives
    )
}

fun reconcileClipboardEntriesWithStorage(
    entries: List<ClipboardEntry>,
    clipboardDir: File
): List<ClipboardEntry> {
    val deduplicated = LinkedHashSet<ClipboardEntry>()

    return entries.mapNotNull { entry ->
        if(entry.backingFile != null && File(clipboardDir, entry.backingFile).isFile != true) {
            null
        } else if(entry.previewMedia().any { !previewMediaFileExists(clipboardDir, it.fileName) }) {
            val retainedPreviewMedia = entry.previewMedia()
                .filter { previewMediaFileExists(clipboardDir, it.fileName) }
            entry.copy(
                previewImageFile = null,
                previewMediaFiles = retainedPreviewMedia,
                previewFetchStatus = if(
                    entry.previewFetchStatus == ClipboardPreviewFetchStatus.Success &&
                    entry.previewText == null &&
                    retainedPreviewMedia.isEmpty()
                ) {
                    ClipboardPreviewFetchStatus.NeverAttempted
                } else {
                    entry.previewFetchStatus
                }
            )
        } else {
            entry
        }
    }.filter { deduplicated.add(it) }
}

private fun previewMediaFileExists(clipboardDir: File, fileName: String): Boolean =
    File(clipboardDir, fileName).isFile == true

fun mergeClipboardEntries(
    currentEntries: List<ClipboardEntry>,
    importedEntries: List<ClipboardEntry>
): List<ClipboardEntry> {
    val merged = deduplicateClipboardEntries(currentEntries).toMutableList()
    val mergedIndex = clipboardEntryIndex(merged)
    val normalizedImported = deduplicateClipboardEntries(importedEntries)
    normalizedImported.forEach { entry ->
        mergeClipboardEntryInto(
            target = merged,
            indexByKey = mergedIndex,
            incoming = entry,
            preferIncomingOnTie = false
        )
    }
    return merged
}

fun deduplicateClipboardEntries(entries: List<ClipboardEntry>): List<ClipboardEntry> {
    val deduplicated = mutableListOf<ClipboardEntry>()
    val indexByKey = mutableMapOf<ClipboardEntryDedupKey, Int>()
    entries.forEach { entry ->
        mergeClipboardEntryInto(
            target = deduplicated,
            indexByKey = indexByKey,
            incoming = entry,
            preferIncomingOnTie = true
        )
    }
    return deduplicated
}

private fun mergeClipboardEntryInto(
    target: MutableList<ClipboardEntry>,
    indexByKey: MutableMap<ClipboardEntryDedupKey, Int>,
    incoming: ClipboardEntry,
    preferIncomingOnTie: Boolean
) {
    val key = incoming.dedupKey()
    val duplicateIndex = key?.let(indexByKey::get)
    if(duplicateIndex == null) {
        target.add(incoming)
        key?.let { indexByKey[it] = target.lastIndex }
        return
    }

    val merged = mergeDuplicateEntries(
        existing = target[duplicateIndex],
        incoming = incoming,
        preferIncomingOnTie = preferIncomingOnTie
    )
    target[duplicateIndex] = merged
}

private sealed interface ClipboardEntryDedupKey {
    data class Text(val value: String) : ClipboardEntryDedupKey
    data class BackingFile(val value: String) : ClipboardEntryDedupKey
}

private fun ClipboardEntry.dedupKey(): ClipboardEntryDedupKey? = when {
    text != null -> ClipboardEntryDedupKey.Text(text)
    backingFile != null -> ClipboardEntryDedupKey.BackingFile(backingFile)
    else -> null
}

private fun clipboardEntryIndex(entries: List<ClipboardEntry>): MutableMap<ClipboardEntryDedupKey, Int> =
    entries.mapIndexedNotNull { index, entry ->
        entry.dedupKey()?.let { it to index }
    }.toMap().toMutableMap()

private fun mergeDuplicateEntries(
    existing: ClipboardEntry,
    incoming: ClipboardEntry,
    preferIncomingOnTie: Boolean
): ClipboardEntry {
    val incomingWins = shouldPreferIncomingEntry(existing, incoming, preferIncomingOnTie)
    val winner = if(incomingWins) incoming else existing
    val loser = if(incomingWins) existing else incoming

    return winner.copy(
        pinned = winner.pinned || loser.pinned,
        uri = winner.uri ?: loser.uri,
        mimeTypes = richerMimeTypes(winner.mimeTypes, loser.mimeTypes),
        sizeMb = winner.sizeMb ?: loser.sizeMb,
        previewText = richerPreviewText(winner.previewText, loser.previewText),
        previewImageFile = winner.previewImageFile ?: loser.previewImageFile,
        previewMediaFiles = richerPreviewMedia(winner.previewMedia(), loser.previewMedia()),
        previewMetadata = mergePreviewMetadata(winner.previewMetadata, loser.previewMetadata),
        previewFetchStatus = richerPreviewFetchStatus(
            winner.previewFetchStatus,
            loser.previewFetchStatus
        ),
        previewFetchLastAttemptAt = maxOfNullable(
            winner.previewFetchLastAttemptAt,
            loser.previewFetchLastAttemptAt
        ),
        deletedArchiveKeys = mergeDeletedArchiveKeys(winner, loser)
    )
}

private fun mergeDeletedArchiveKeys(winner: ClipboardEntry, loser: ClipboardEntry): Set<String> {
    return winner.deletedArchiveKeys + loser.deletedArchiveKeys
}

private fun shouldPreferIncomingEntry(
    existing: ClipboardEntry,
    incoming: ClipboardEntry,
    preferIncomingOnTie: Boolean
): Boolean {
    comparePreviewFieldRichness(incoming, existing).takeIf { it != 0 }?.let { return it > 0 }
    comparePreviewMetadata(incoming.previewMetadata, existing.previewMetadata)
        .takeIf { it != 0 }
        ?.let { return it > 0 }
    comparePreviewFetchStatus(incoming.previewFetchStatus, existing.previewFetchStatus)
        .takeIf { it != 0 }
        ?.let { return it > 0 }
    compareNullableLong(incoming.previewFetchLastAttemptAt, existing.previewFetchLastAttemptAt)
        .takeIf { it != 0 }
        ?.let { return it > 0 }
    compareNullableFloat(incoming.sizeMb, existing.sizeMb)
        .takeIf { it != 0 }
        ?.let { return it > 0 }

    val mimeTypeCompare = incoming.mimeTypes.size.compareTo(existing.mimeTypes.size)
    if(mimeTypeCompare != 0) return mimeTypeCompare > 0

    val uriCompare = incoming.uri.presentScore().compareTo(existing.uri.presentScore())
    if(uriCompare != 0) return uriCompare > 0

    val timestampCompare = incoming.timestamp.compareTo(existing.timestamp)
    if(timestampCompare != 0) return timestampCompare > 0

    return preferIncomingOnTie
}

private fun comparePreviewFieldRichness(a: ClipboardEntry, b: ClipboardEntry): Int =
    previewFieldScore(a).compareTo(previewFieldScore(b))

private fun previewFieldScore(entry: ClipboardEntry): Int =
    entry.previewMedia().size * 1000 +
        if(entry.previewText.isNullOrBlank()) 0 else 100 + entry.previewText.length.coerceAtMost(200)

private fun richerPreviewMedia(
    primary: List<ClipboardPreviewMedia>,
    secondary: List<ClipboardPreviewMedia>
): List<ClipboardPreviewMedia> =
    if(primary.size >= secondary.size) primary else secondary

private fun comparePreviewMetadata(
    a: ClipboardPreviewMetadata?,
    b: ClipboardPreviewMetadata?
): Int = previewMetadataScore(a).compareTo(previewMetadataScore(b))

private fun previewMetadataScore(metadata: ClipboardPreviewMetadata?): Int {
    if(metadata == null) return 0

    var score = 0
    score += metadata.sourceUrl.presentScore()
    score += metadata.sourceId.presentScore()
    score += metadata.title.presentScore()
    score += metadata.bodyText.presentScore()
    score += metadata.authorName.presentScore()
    score += metadata.authorHandle.presentScore()
    score += metadata.authorId.presentScore()
    score += metadata.createdAt.presentScore()
    score += if(metadata.imageCount != null) 1 else 0
    score += if(metadata.selectedImageIndex != null) 1 else 0
    score += metadata.tags.size
    score += previewStatsScore(metadata.stats)
    score += previewFlagsScore(metadata.flags)
    return score
}

private fun previewStatsScore(stats: ClipboardPreviewStats?): Int {
    if(stats == null) return 0

    return listOf(
        stats.likeCount,
        stats.bookmarkCount,
        stats.viewCount,
        stats.replyCount,
        stats.repostCount,
        stats.quoteCount,
        stats.commentCount
    ).count { it != null }
}

private fun previewFlagsScore(flags: ClipboardPreviewFlags): Int =
    listOf(
        flags.aiGenerated,
        flags.animated,
        flags.restricted,
        flags.noteTweet
    ).count { it }

private fun comparePreviewFetchStatus(
    a: ClipboardPreviewFetchStatus,
    b: ClipboardPreviewFetchStatus
): Int = previewFetchStatusScore(a).compareTo(previewFetchStatusScore(b))

private fun previewFetchStatusScore(status: ClipboardPreviewFetchStatus): Int = when (status) {
    ClipboardPreviewFetchStatus.NeverAttempted -> 0
    ClipboardPreviewFetchStatus.Failed -> 1
    ClipboardPreviewFetchStatus.Success -> 2
}

private fun richerPreviewText(primary: String?, secondary: String?): String? =
    when {
        primary.isNullOrBlank() -> secondary
        secondary.isNullOrBlank() -> primary
        secondary.length > primary.length -> secondary
        else -> primary
    }

private fun mergePreviewMetadata(
    primary: ClipboardPreviewMetadata?,
    secondary: ClipboardPreviewMetadata?
): ClipboardPreviewMetadata? {
    if(primary == null) return secondary
    if(secondary == null) return primary

    val primaryWins = comparePreviewMetadata(primary, secondary) >= 0
    val winner = if(primaryWins) primary else secondary
    val loser = if(primaryWins) secondary else primary

    return ClipboardPreviewMetadata(
        provider = winner.provider,
        sourceUrl = winner.sourceUrl ?: loser.sourceUrl,
        sourceId = winner.sourceId ?: loser.sourceId,
        title = winner.title ?: loser.title,
        bodyText = richerPreviewText(winner.bodyText, loser.bodyText),
        authorName = winner.authorName ?: loser.authorName,
        authorHandle = winner.authorHandle ?: loser.authorHandle,
        authorId = winner.authorId ?: loser.authorId,
        createdAt = winner.createdAt ?: loser.createdAt,
        imageCount = winner.imageCount ?: loser.imageCount,
        selectedImageIndex = winner.selectedImageIndex ?: loser.selectedImageIndex,
        tags = if(winner.tags.size >= loser.tags.size) winner.tags else loser.tags,
        stats = mergePreviewStats(winner.stats, loser.stats),
        flags = ClipboardPreviewFlags(
            aiGenerated = winner.flags.aiGenerated || loser.flags.aiGenerated,
            animated = winner.flags.animated || loser.flags.animated,
            restricted = winner.flags.restricted || loser.flags.restricted,
            noteTweet = winner.flags.noteTweet || loser.flags.noteTweet
        )
    )
}

private fun mergePreviewStats(
    primary: ClipboardPreviewStats?,
    secondary: ClipboardPreviewStats?
): ClipboardPreviewStats? {
    if(primary == null) return secondary
    if(secondary == null) return primary

    return ClipboardPreviewStats(
        likeCount = primary.likeCount ?: secondary.likeCount,
        bookmarkCount = primary.bookmarkCount ?: secondary.bookmarkCount,
        viewCount = primary.viewCount ?: secondary.viewCount,
        replyCount = primary.replyCount ?: secondary.replyCount,
        repostCount = primary.repostCount ?: secondary.repostCount,
        quoteCount = primary.quoteCount ?: secondary.quoteCount,
        commentCount = primary.commentCount ?: secondary.commentCount
    )
}

private fun richerPreviewFetchStatus(
    primary: ClipboardPreviewFetchStatus,
    secondary: ClipboardPreviewFetchStatus
): ClipboardPreviewFetchStatus =
    if(comparePreviewFetchStatus(primary, secondary) >= 0) primary else secondary

private fun richerMimeTypes(primary: List<String>, secondary: List<String>): List<String> =
    if(primary.size >= secondary.size) primary else secondary

private fun compareNullableLong(a: Long?, b: Long?): Int = when {
    a == null && b == null -> 0
    a == null -> -1
    b == null -> 1
    else -> a.compareTo(b)
}

private fun compareNullableFloat(a: Float?, b: Float?): Int = when {
    a == null && b == null -> 0
    a == null -> -1
    b == null -> 1
    else -> a.compareTo(b)
}

private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}

private fun String?.presentScore(): Int =
    if(this.isNullOrBlank()) 0 else 1

private fun Any?.presentScore(): Int =
    if(this == null) 0 else 1
