package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

const val ClipboardArchiveFileName = "clipboard_archives.json"
const val ClipboardArchiveTombstonesFileName = "clipboard_archive_tombstones.json"
const val ClipboardArchiveMetadataDirectoryName = "clipboardarchives"
const val ClipboardArchiveFilesDirectoryName = "clipboardarchivefiles"
const val ClipboardBackupArchiveFilesDirectoryName = ClipboardArchiveFilesDirectoryName

val Context.clipboardArchiveFile get() = File(filesDir, ClipboardArchiveFileName)
val Context.clipboardArchiveTombstonesFile get() = File(filesDir, ClipboardArchiveTombstonesFileName)
val Context.clipboardArchiveMetadataDir get() = File(filesDir, ClipboardArchiveMetadataDirectoryName)
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
    val lastAttemptAtEpochMs: Long? = null,
    val failureDetail: String? = null
)

@Serializable
data class ClipboardLinkArchive(
    val key: String,
    val provider: ClipboardPreviewProvider,
    val sourceUrl: String,
    val sourceId: String? = null,
    val metadata: ClipboardPreviewMetadata? = null,
    val media: List<ClipboardArchiveMedia> = emptyList(),
    val providerManifestAvailable: Boolean = true,
    val deletedMediaKeys: Set<String> = emptySet(),
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
) {
    val status: ClipboardLinkArchiveStatus
        get() = archiveStatusFor(this)
}

@Serializable
data class ClipboardArchiveTombstone(
    val key: String,
    val deletedAtEpochMs: Long,
    val reason: String? = null
)

sealed interface ClipboardArchiveEvent {
    data class ManifestSeen(
        val manifest: ClipboardLinkPreviewManifest,
        val now: Long
    ) : ClipboardArchiveEvent

    data class MediaDownloadSaved(
        val sourceUrl: String,
        val fileName: String,
        val mimeType: String?,
        val now: Long
    ) : ClipboardArchiveEvent

    data class MediaDownloadFailed(
        val sourceUrl: String,
        val now: Long,
        val failureDetail: String?
    ) : ClipboardArchiveEvent

    data class MediaSkippedTooLarge(
        val sourceUrl: String,
        val now: Long,
        val failureDetail: String?
    ) : ClipboardArchiveEvent

    data class DiskReconciled(
        val existingFileNames: Set<String>,
        val now: Long
    ) : ClipboardArchiveEvent

    data class ImportedArchive(
        val incomingArchive: ClipboardLinkArchive
    ) : ClipboardArchiveEvent
}

fun ClipboardPreviewMetadata.archiveKey(): String? {
    val source = sourceId ?: sourceUrl ?: return null
    return "${provider.name.lowercase()}:$source"
}

fun ClipboardLinkPreviewManifest.archiveKey(): String? =
    metadata?.archiveKey()

fun encodeClipboardArchives(archives: Collection<ClipboardLinkArchive>): String =
    ClipboardArchiveJson.encodeToString(archives.sortedBy { it.key })

fun encodeClipboardArchiveTombstones(tombstones: Collection<ClipboardArchiveTombstone>): String =
    ClipboardArchiveJson.encodeToString(tombstones.sortedBy { it.key })

fun decodeClipboardArchiveTombstones(text: String): List<ClipboardArchiveTombstone> =
    ClipboardArchiveJson.decodeFromString<List<ClipboardArchiveTombstone>>(text)
        .distinctBy { it.key }
        .sortedBy { it.key }

fun File.decodeClipboardArchiveTombstones(): List<ClipboardArchiveTombstone> =
    decodeClipboardArchiveTombstones(readText())

fun decodeClipboardArchives(text: String): List<ClipboardLinkArchive> =
    ClipboardArchiveJson.decodeFromString<List<ClipboardLinkArchive>>(text)
        .map { it.withNormalizedArchiveMedia() }

fun File.decodeClipboardArchives(): List<ClipboardLinkArchive> =
    decodeClipboardArchives(readText())

fun decodeLegacyClipboardArchives(text: String): List<ClipboardLinkArchive> =
    decodeClipboardArchives(text).map { it.withLegacyRetryablePlaceholderIfEmpty() }

fun File.decodeLegacyClipboardArchives(): List<ClipboardLinkArchive> =
    decodeLegacyClipboardArchives(readText())

fun encodeClipboardArchive(archive: ClipboardLinkArchive): String =
    ClipboardArchiveJson.encodeToString(archive)

fun decodeClipboardArchive(text: String): ClipboardLinkArchive =
    ClipboardArchiveJson.decodeFromString<ClipboardLinkArchive>(text).withNormalizedArchiveMedia()

fun File.decodeClipboardArchive(): ClipboardLinkArchive =
    decodeClipboardArchive(readText())

fun clipboardArchiveMetadataFileName(key: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "$digest.json"
}

fun File.clipboardArchiveMetadataFile(key: String): File =
    File(this, clipboardArchiveMetadataFileName(key))

fun loadClipboardArchivesFromMetadataDir(metadataDir: File): List<ClipboardLinkArchive> =
    metadataDir.listFiles()
        ?.filter { it.isFile && it.extension == "json" }
        ?.mapNotNull { file -> file.decodeClipboardArchiveOrBackup() }
        ?.sortedBy { it.key }
        .orEmpty()

private fun File.decodeClipboardArchiveOrBackup(): ClipboardLinkArchive? =
    runCatching { decodeClipboardArchive() }.getOrElse {
        val backupFile = File(parentFile, "$name.bak")
        if (backupFile.isFile) runCatching { backupFile.decodeClipboardArchive() }.getOrNull() else null
    }

fun mergeStoredClipboardArchives(
    legacyArchives: List<ClipboardLinkArchive>,
    metadataArchives: List<ClipboardLinkArchive>
): List<ClipboardLinkArchive> =
    when {
        metadataArchives.isEmpty() -> legacyArchives.map { it.withLegacyRetryablePlaceholderIfEmpty() }.sortedBy { it.key }
        legacyArchives.isEmpty() -> metadataArchives.sortedBy { it.key }
        else -> mergeClipboardArchives(
            currentArchives = legacyArchives.map { it.withLegacyRetryablePlaceholderIfEmpty() },
            importedArchives = metadataArchives
        )
    }

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

fun ClipboardLinkArchive.hasAutoDownloadableMedia(): Boolean =
    media.any { it.isAutoDownloadableArchiveMedia(this) }

fun ClipboardArchiveMediaStatus.isRetryableArchiveMediaStatus(): Boolean = when (this) {
    ClipboardArchiveMediaStatus.Pending,
    ClipboardArchiveMediaStatus.Failed,
    ClipboardArchiveMediaStatus.Missing,
    ClipboardArchiveMediaStatus.SkippedTooLarge -> true
    ClipboardArchiveMediaStatus.Saved -> false
}

fun ClipboardLinkArchive.retryableMedia(): List<ClipboardArchiveMedia> =
    media.filter { it.status.isRetryableArchiveMediaStatus() }

fun ClipboardLinkArchive.autoDownloadableMedia(): List<ClipboardArchiveMedia> =
    media.filter { it.isAutoDownloadableArchiveMedia(this) }

private fun ClipboardArchiveMedia.isAutoDownloadableArchiveMedia(archive: ClipboardLinkArchive): Boolean = when (status) {
    ClipboardArchiveMediaStatus.Pending -> true
    ClipboardArchiveMediaStatus.Missing ->
        archive.providerManifestAvailable && !ClipboardLinkPreviewFetcher.supportsPreview(sourceUrl)
    ClipboardArchiveMediaStatus.Failed,
    ClipboardArchiveMediaStatus.Saved,
    ClipboardArchiveMediaStatus.SkippedTooLarge -> false
}

fun ClipboardArchiveMedia.archiveMediaKey(): String =
    "index:$sourceIndex"

private fun ClipboardArchiveMedia.legacyArchiveMediaKey(): String =
    "$sourceIndex:$sourceUrl"

fun ClipboardLinkArchive.withNormalizedArchiveMedia(): ClipboardLinkArchive =
    copy(
        media = normalizeArchiveMedia(media)
            .filter { it.archiveMediaKey() !in deletedMediaKeys && it.legacyArchiveMediaKey() !in deletedMediaKeys }
            .filterNot(::isInvalidArchiveMedia)
    )

private fun ClipboardLinkArchive.isInvalidArchiveMedia(media: ClipboardArchiveMedia): Boolean =
    provider == ClipboardPreviewProvider.TWITTER &&
        media.status != ClipboardArchiveMediaStatus.Saved &&
        ClipboardLinkPreviewFetcher.supportsPreview(media.sourceUrl)

fun ClipboardLinkArchive.withLegacyRetryablePlaceholderIfEmpty(): ClipboardLinkArchive =
    if(media.isNotEmpty() || provider != ClipboardPreviewProvider.PIXIV) {
        this
    } else {
        copy(
            media = listOf(
                ClipboardArchiveMedia(
                    sourceUrl = sourceUrl,
                    sourceIndex = 0,
                    status = ClipboardArchiveMediaStatus.Missing,
                    lastAttemptAtEpochMs = updatedAtEpochMs
                )
            ),
            providerManifestAvailable = false
        )
    }

fun ClipboardLinkArchive.withMissingArchiveFilesMarked(
    clipboardDir: File,
    now: Long = System.currentTimeMillis()
): ClipboardLinkArchive {
    return withMissingArchiveFilesMarked(existingClipboardMediaFileNames(clipboardDir), now)
}

fun ClipboardLinkArchive.withMissingArchiveFilesMarked(
    existingFileNames: Set<String>,
    now: Long = System.currentTimeMillis()
): ClipboardLinkArchive {
    return reduceArchive(
        archive = this,
        event = ClipboardArchiveEvent.DiskReconciled(existingFileNames, now)
    ) ?: this
}

fun referencedClipboardArchiveFileNames(archives: Collection<ClipboardLinkArchive>): Set<String> =
    archives
        .flatMap { archive -> archive.media.mapNotNull { it.fileName } }
        .flatMap { listOf(it, ClipboardUtil.thumbnailForName(it)) }
        .toSet()

fun existingClipboardMediaFileNames(clipboardDir: File, legacyArchiveDir: File? = null): Set<String> =
    listOfNotNull(clipboardDir, legacyArchiveDir)
        .flatMap { dir ->
            dir.listFiles()
                ?.filter { it.isFile }
                ?.map { it.name }
                .orEmpty()
        }
        .toSet()

fun clipboardMediaFile(clipboardDir: File, fileName: String): File? =
    File(clipboardDir, fileName).takeIf { it.isFile }

fun legacyAwareClipboardMediaFile(
    clipboardDir: File,
    legacyArchiveDir: File,
    fileName: String
): File? =
    listOf(File(clipboardDir, fileName), File(legacyArchiveDir, fileName))
        .firstOrNull { it.isFile }

fun migrateLegacyArchiveMediaFiles(
    legacyArchiveDir: File,
    clipboardDir: File,
    referencedFileNames: Set<String>
) {
    clipboardDir.mkdirs()
    legacyArchiveDir.listFiles()?.forEach { legacyFile ->
        if(!legacyFile.isFile) return@forEach

        val destination = File(clipboardDir, legacyFile.name)
        if(!destination.exists()) {
            legacyFile.copyTo(destination, overwrite = false)
        }
        legacyFile.delete()
    }
    legacyArchiveDir.delete()
}

fun reconcileClipboardArchivesWithStorage(
    archives: Collection<ClipboardLinkArchive>,
    clipboardDir: File,
    legacyArchiveDir: File? = null,
    now: Long = System.currentTimeMillis()
): List<ClipboardLinkArchive> {
    val existingFileNames = existingClipboardMediaFileNames(clipboardDir, legacyArchiveDir)
    return archives.mapNotNull {
        reduceArchive(it, ClipboardArchiveEvent.DiskReconciled(existingFileNames, now))
    }
}

fun mergeClipboardArchives(
    currentArchives: Collection<ClipboardLinkArchive>,
    importedArchives: Collection<ClipboardLinkArchive>
): List<ClipboardLinkArchive> {
    val merged = currentArchives
        .associateBy { it.key }
        .toMutableMap()
    importedArchives.forEach { incoming ->
        reduceArchive(
            archive = merged[incoming.key],
            event = ClipboardArchiveEvent.ImportedArchive(incoming)
        )?.let { merged[incoming.key] = it }
    }
    return merged.values.sortedBy { it.key }
}

fun filterDeletedClipboardArchives(
    archives: Collection<ClipboardLinkArchive>,
    deletedArchiveKeys: Set<String>
): List<ClipboardLinkArchive> =
    archives.filter { it.key !in deletedArchiveKeys }.sortedBy { it.key }

fun mergeArchiveTombstones(
    existing: Collection<ClipboardArchiveTombstone>,
    migratedKeys: Collection<String>,
    now: Long = System.currentTimeMillis()
): List<ClipboardArchiveTombstone> {
    val tombstones = existing.associateBy { it.key }.toMutableMap()
    migratedKeys.forEach { key ->
        tombstones.putIfAbsent(
            key,
            ClipboardArchiveTombstone(
                key = key,
                deletedAtEpochMs = now,
                reason = "migrated"
            )
        )
    }
    return tombstones.values.sortedBy { it.key }
}

fun archiveTombstonesForEntries(
    existing: Collection<ClipboardArchiveTombstone>,
    entries: Collection<ClipboardEntry>,
    now: Long = System.currentTimeMillis()
): List<ClipboardArchiveTombstone> =
    mergeArchiveTombstones(
        existing = existing,
        migratedKeys = entries.flatMap { it.deletedArchiveKeys },
        now = now
    )

fun clearEntryArchiveTombstones(entries: Collection<ClipboardEntry>): List<ClipboardEntry> =
    entries.map { it.copy(deletedArchiveKeys = emptySet()) }

fun archiveTombstoneKeys(tombstones: Collection<ClipboardArchiveTombstone>): Set<String> =
    tombstones.map { it.key }.toSet()

fun tombstonesRetainedAfterArchiveImport(
    tombstones: Collection<ClipboardArchiveTombstone>,
    importedArchives: Collection<ClipboardLinkArchive>
): List<ClipboardArchiveTombstone> {
    val restoredArchiveKeys = importedArchives.map { it.key }.toSet()
    return tombstones.filter { it.key !in restoredArchiveKeys }.sortedBy { it.key }
}

fun newArchiveFromManifest(
    manifest: ClipboardLinkPreviewManifest,
    now: Long = System.currentTimeMillis()
): ClipboardLinkArchive? =
    reduceArchive(null, ClipboardArchiveEvent.ManifestSeen(manifest, now))

fun mergeArchiveWithManifest(
    archive: ClipboardLinkArchive,
    manifest: ClipboardLinkPreviewManifest,
    now: Long = System.currentTimeMillis()
): ClipboardLinkArchive =
    reduceArchive(archive, ClipboardArchiveEvent.ManifestSeen(manifest, now)) ?: archive

internal fun newFallbackArchiveFromEntry(
    entry: ClipboardEntry,
    metadata: ClipboardPreviewMetadata,
    savedMedia: List<ClipboardArchiveMedia>,
    now: Long = System.currentTimeMillis()
): ClipboardLinkArchive? {
    val key = metadata.archiveKey() ?: return null
    val sourceUrl = metadata.sourceUrl ?: entry.text ?: return null
    val expectedCount = metadata.imageCount?.takeIf { it > savedMedia.size }
        ?: if(savedMedia.isEmpty() && metadata.provider == ClipboardPreviewProvider.PIXIV) 1 else savedMedia.size
    val savedIndexes = savedMedia.map { it.sourceIndex }.toSet()
    val placeholders = if(expectedCount > savedMedia.size) {
        (0 until expectedCount).filter { it !in savedIndexes }.map { index ->
            ClipboardArchiveMedia(
                sourceUrl = if(savedMedia.isEmpty() && expectedCount == 1) {
                    sourceUrl
                } else {
                    "$sourceUrl#missing-media-$index"
                },
                sourceIndex = index,
                status = ClipboardArchiveMediaStatus.Missing,
                lastAttemptAtEpochMs = now
            )
        }
    } else {
        emptyList()
    }

    return ClipboardLinkArchive(
        key = key,
        provider = metadata.provider,
        sourceUrl = sourceUrl,
        sourceId = metadata.sourceId,
        metadata = metadata,
        media = normalizeArchiveMedia(savedMedia + placeholders),
        providerManifestAvailable = false,
        deletedMediaKeys = emptySet(),
        createdAtEpochMs = now,
        updatedAtEpochMs = now
    )
}

private fun archiveStatusFor(archive: ClipboardLinkArchive): ClipboardLinkArchiveStatus {
    val media = archive.media
    if(media.isEmpty() && !archive.providerManifestAvailable) return ClipboardLinkArchiveStatus.Failed
    return archiveStatusFor(media)
}

private fun archiveStatusFor(media: List<ClipboardArchiveMedia>): ClipboardLinkArchiveStatus {
    if(media.isEmpty()) return ClipboardLinkArchiveStatus.Complete
    if(media.all { it.status == ClipboardArchiveMediaStatus.Pending }) return ClipboardLinkArchiveStatus.Pending
    if(media.any { it.status == ClipboardArchiveMediaStatus.Pending }) return ClipboardLinkArchiveStatus.InProgress
    if(media.all { it.status == ClipboardArchiveMediaStatus.Saved }) return ClipboardLinkArchiveStatus.Complete
    if(media.any { it.status == ClipboardArchiveMediaStatus.Saved }) return ClipboardLinkArchiveStatus.Partial
    return ClipboardLinkArchiveStatus.Failed
}

fun reduceArchive(
    archive: ClipboardLinkArchive?,
    event: ClipboardArchiveEvent
): ClipboardLinkArchive? = when (event) {
    is ClipboardArchiveEvent.ManifestSeen -> reduceManifestSeen(archive, event.manifest, event.now)
    is ClipboardArchiveEvent.MediaDownloadSaved -> archive?.withUpdatedMedia(event.sourceUrl, event.now) {
        it.copy(
            fileName = event.fileName,
            mimeType = event.mimeType ?: it.mimeType,
            status = ClipboardArchiveMediaStatus.Saved,
            lastAttemptAtEpochMs = event.now,
            failureDetail = null
        )
    }
    is ClipboardArchiveEvent.MediaDownloadFailed -> archive?.withUpdatedMedia(event.sourceUrl, event.now) {
        if(it.status == ClipboardArchiveMediaStatus.Saved) it else it.copy(
            status = ClipboardArchiveMediaStatus.Failed,
            lastAttemptAtEpochMs = event.now,
            failureDetail = event.failureDetail
        )
    }
    is ClipboardArchiveEvent.MediaSkippedTooLarge -> archive?.withUpdatedMedia(event.sourceUrl, event.now) {
        if(it.status == ClipboardArchiveMediaStatus.Saved) it else it.copy(
            status = ClipboardArchiveMediaStatus.SkippedTooLarge,
            lastAttemptAtEpochMs = event.now,
            failureDetail = event.failureDetail
        )
    }
    is ClipboardArchiveEvent.DiskReconciled -> archive?.let {
        val reconciledMedia = it.media.map { media ->
            when {
                media.status == ClipboardArchiveMediaStatus.Saved &&
                    media.fileName?.let { fileName -> fileName !in event.existingFileNames } != false ->
                    media.copy(
                        status = ClipboardArchiveMediaStatus.Missing,
                        lastAttemptAtEpochMs = event.now,
                        failureDetail = "Saved archive file is missing from disk: ${media.fileName}"
                    )
                media.status == ClipboardArchiveMediaStatus.Missing &&
                    media.fileName?.let { fileName -> fileName in event.existingFileNames } == true ->
                    media.copy(
                        status = ClipboardArchiveMediaStatus.Saved,
                        lastAttemptAtEpochMs = event.now,
                        failureDetail = null
                    )
                else -> media
            }
        }
        it.copy(
            media = normalizeArchiveMedia(reconciledMedia),
            updatedAtEpochMs = if(reconciledMedia == it.media) it.updatedAtEpochMs else event.now
        )
    }
    is ClipboardArchiveEvent.ImportedArchive -> reduceImportedArchive(archive, event.incomingArchive)
}

private fun reduceManifestSeen(
    archive: ClipboardLinkArchive?,
    manifest: ClipboardLinkPreviewManifest,
    now: Long
): ClipboardLinkArchive? {
    val metadata = manifest.metadata ?: return archive
    val key = manifest.archiveKey() ?: return archive
    val sourceUrl = metadata.sourceUrl ?: archive?.sourceUrl ?: return archive
    val unavailableManifest = manifest.isUnavailableManifest()
    if(unavailableManifest && archive == null) return null
    val existingByUrl = archive?.media.orEmpty().associateBy { it.sourceUrl }
    val existingByIndex = normalizeArchiveMedia(archive?.media.orEmpty()).associateBy { it.sourceIndex }
    val incomingSourceIndexes = manifest.mediaItems.map { it.sourceIndex }.toSet()
    val deletedMediaKeys = archive?.deletedMediaKeys.orEmpty()
    val manifestMedia = manifest.mediaItems.mapNotNull { incoming ->
        val incomingMediaKey = ClipboardArchiveMedia(
            sourceUrl = incoming.url,
            sourceIndex = incoming.sourceIndex
        ).archiveMediaKey()
        val incomingLegacyMediaKey = ClipboardArchiveMedia(
            sourceUrl = incoming.url,
            sourceIndex = incoming.sourceIndex
        ).legacyArchiveMediaKey()
        if(incomingMediaKey in deletedMediaKeys || incomingLegacyMediaKey in deletedMediaKeys) return@mapNotNull null

        val existing = existingByUrl[incoming.url] ?: existingByIndex[incoming.sourceIndex]
        existing?.copy(
            sourceUrl = incoming.url,
            sourceIndex = incoming.sourceIndex,
            mimeType = incoming.mimeType ?: existing.mimeType
        ) ?: ClipboardArchiveMedia(
            sourceUrl = incoming.url,
            sourceIndex = incoming.sourceIndex,
            mimeType = incoming.mimeType
        )
    }
    val retainedMedia = if(manifest.mediaItems.isEmpty()) {
        archive?.media.orEmpty().filter { it.status == ClipboardArchiveMediaStatus.Saved }
    } else {
        archive?.media.orEmpty().filter { it.sourceIndex !in incomingSourceIndexes }
    }

    return ClipboardLinkArchive(
        key = archive?.key ?: key,
        provider = archive?.provider ?: metadata.provider,
        sourceUrl = metadata.sourceUrl ?: archive?.sourceUrl ?: sourceUrl,
        sourceId = metadata.sourceId ?: archive?.sourceId,
        metadata = mergePreviewMetadataForArchive(archive?.metadata, manifest.metadata),
        media = normalizeArchiveMedia(manifestMedia + retainedMedia),
        providerManifestAvailable = if(unavailableManifest) {
            archive?.providerManifestAvailable ?: false
        } else {
            true
        },
        deletedMediaKeys = deletedMediaKeys,
        createdAtEpochMs = archive?.createdAtEpochMs ?: now,
        updatedAtEpochMs = now
    )
}

private fun ClipboardLinkPreviewManifest.isUnavailableManifest(): Boolean =
    mediaItems.isEmpty() &&
        listOfNotNull(snippet, metadata?.title, metadata?.bodyText)
            .any(::isUnavailablePreviewText)

private fun reduceImportedArchive(
    archive: ClipboardLinkArchive?,
    incoming: ClipboardLinkArchive
): ClipboardLinkArchive {
    val normalizedIncoming = incoming.copy(media = normalizeArchiveMedia(incoming.media))
    val existing = archive ?: return normalizedIncoming.withLegacyRetryablePlaceholderIfEmpty().withNormalizedArchiveMedia()
    val deletedMediaKeys = existing.deletedMediaKeys + incoming.deletedMediaKeys
    return existing.copy(
        sourceUrl = existing.sourceUrl.ifBlank { incoming.sourceUrl },
        sourceId = existing.sourceId ?: incoming.sourceId,
        metadata = mergePreviewMetadataForArchive(existing.metadata, incoming.metadata),
        media = normalizeArchiveMedia(existing.media + incoming.media)
            .filter { it.archiveMediaKey() !in deletedMediaKeys },
        providerManifestAvailable = existing.providerManifestAvailable || incoming.providerManifestAvailable,
        deletedMediaKeys = deletedMediaKeys,
        createdAtEpochMs = minOf(existing.createdAtEpochMs, incoming.createdAtEpochMs),
        updatedAtEpochMs = maxOf(existing.updatedAtEpochMs, incoming.updatedAtEpochMs)
    ).withNormalizedArchiveMedia()
}

private fun ClipboardLinkArchive.withUpdatedMedia(
    sourceUrl: String,
    now: Long,
    transform: (ClipboardArchiveMedia) -> ClipboardArchiveMedia
): ClipboardLinkArchive {
    var updatedAny = false
    val updatedMedia = media.map {
        if(it.sourceUrl == sourceUrl) {
            updatedAny = true
            transform(it)
        } else {
            it
        }
    }
    return copy(
        media = normalizeArchiveMedia(updatedMedia),
        updatedAtEpochMs = if(updatedAny) now else updatedAtEpochMs
    )
}

private fun normalizeArchiveMedia(media: List<ClipboardArchiveMedia>): List<ClipboardArchiveMedia> =
    media
        .groupBy { it.sourceIndex }
        .mapNotNull { (_, items) ->
            items.reduceOrNull(::richerArchiveMedia)?.let {
                if(it.status == ClipboardArchiveMediaStatus.Saved && it.fileName == null) {
                    it.copy(status = ClipboardArchiveMediaStatus.Missing)
                } else {
                    it
                }
            }
        }
        .sortedBy { it.sourceIndex }

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
        failureDetail = winner.failureDetail ?: loser.failureDetail,
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
            title = incoming.title.takeUnless(::isUnavailablePreviewText) ?: existing.title,
            bodyText = incoming.bodyText.takeUnless(::isUnavailablePreviewText) ?: existing.bodyText,
            authorName = incoming.authorName.takeUnless(::isUnavailablePreviewText) ?: existing.authorName,
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
