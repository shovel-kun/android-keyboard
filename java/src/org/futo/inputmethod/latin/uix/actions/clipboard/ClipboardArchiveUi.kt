package org.futo.inputmethod.latin.uix.actions.clipboard

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import org.futo.inputmethod.latin.R

internal enum class ClipboardArchiveProviderFilter {
    All,
    Pixiv,
    Twitter
}

internal enum class ClipboardArchiveStatusFilter {
    All,
    Complete,
    Partial,
    FailedInProgress
}

internal enum class ClipboardArchiveDisplayStatus {
    Complete,
    Saving,
    Waiting,
    Partial,
    Failed,
    Retry
}

internal enum class ClipboardArchiveDownloadRowStatus {
    Active,
    Waiting,
    Failed
}

internal data class ClipboardArchiveGalleryItem(
    val media: ClipboardArchiveMedia,
    val file: File?,
    val position: Int,
    val totalCount: Int,
    val displayStatus: ClipboardArchiveDisplayStatus
) {
    val isShareable: Boolean get() = file?.isFile == true
    val hasFailureDetails: Boolean get() = media.failureDetail?.isNotBlank() == true
}

internal data class ClipboardArchiveDownloadListItem(
    val archiveKey: String,
    val sourceUrl: String,
    val sourceIndex: Int,
    val mediaCount: Int,
    val provider: ClipboardPreviewProvider,
    val providerLabel: String,
    val title: String,
    val subtitle: String?,
    val status: ClipboardArchiveDownloadRowStatus,
    val failureSummaryLabelRes: Int?,
    val progress: ClipboardArchiveDownloadProgress?,
    val lastAttemptAtEpochMs: Long?,
    val retryAvailableAtEpochMs: Long?,
    val canStop: Boolean,
    val canRetry: Boolean
)

internal data class ClipboardArchiveDownloadSummary(
    val activeCount: Int,
    val waitingCount: Int,
    val retryCount: Int
)

internal data class ClipboardArchiveDownloadGroups(
    val active: List<ClipboardArchiveDownloadListItem>,
    val waiting: List<ClipboardArchiveDownloadListItem>,
    val needsAttention: List<ClipboardArchiveDownloadListItem>
)

internal data class ClipboardArchiveDownloadPresentation(
    val summary: ClipboardArchiveDownloadSummary,
    val groups: ClipboardArchiveDownloadGroups,
    val retryableItems: List<ClipboardArchiveDownloadListItem>,
    val activeItems: List<ClipboardArchiveDownloadListItem>
)

internal fun archiveDownloadPresentation(items: List<ClipboardArchiveDownloadListItem>): ClipboardArchiveDownloadPresentation {
    val active = mutableListOf<ClipboardArchiveDownloadListItem>()
    val waiting = mutableListOf<ClipboardArchiveDownloadListItem>()
    val needsAttention = mutableListOf<ClipboardArchiveDownloadListItem>()
    val retryable = mutableListOf<ClipboardArchiveDownloadListItem>()
    val stoppable = mutableListOf<ClipboardArchiveDownloadListItem>()

    items.forEach { item ->
        when(item.status) {
            ClipboardArchiveDownloadRowStatus.Active -> active.add(item)
            ClipboardArchiveDownloadRowStatus.Waiting -> waiting.add(item)
            ClipboardArchiveDownloadRowStatus.Failed -> needsAttention.add(item)
        }
        if(item.canRetry) retryable.add(item)
        if(item.canStop) stoppable.add(item)
    }

    return ClipboardArchiveDownloadPresentation(
        summary = ClipboardArchiveDownloadSummary(
            activeCount = active.size,
            waitingCount = waiting.size,
            retryCount = needsAttention.size
        ),
        groups = ClipboardArchiveDownloadGroups(
            active = active,
            waiting = waiting,
            needsAttention = needsAttention
        ),
        retryableItems = retryable,
        activeItems = stoppable
    )
}

internal fun sortedClipboardArchives(archives: Collection<ClipboardLinkArchive>): List<ClipboardLinkArchive> =
    archives.sortedWith(
        compareByDescending<ClipboardLinkArchive> { it.updatedAtEpochMs }
            .thenByDescending { it.createdAtEpochMs }
            .thenBy { it.key }
    )

internal fun ClipboardLinkArchive.savedMediaCount(): Int =
    media.count { it.status == ClipboardArchiveMediaStatus.Saved && it.fileName != null }

internal fun ClipboardLinkArchive.expectedMediaCount(): Int = media.size

internal fun ClipboardArchiveDownloadProgress.progressPercent(): Int? =
    progressFraction?.let { (it * 100f).roundToInt().coerceIn(0, 100) }

internal fun archiveMediaShareMimeType(
    media: ClipboardArchiveMedia,
    targetFile: File
): String =
    when {
        targetFile.isClipboardVideoFile() -> targetFile.guessedClipboardMimeType() ?: "video/*"
        targetFile.isClipboardImageFile() -> targetFile.guessedClipboardMimeType()
            ?: media.mimeType?.takeIf { it.startsWith("image/") }
            ?: "image/*"
        else -> media.mimeType ?: targetFile.guessedClipboardMimeType() ?: "application/octet-stream"
    }

internal fun archiveDownloadItems(
    archives: Collection<ClipboardLinkArchive>,
    progressByArchiveKey: Map<String, ClipboardArchiveDownloadProgress>,
    loadingArchiveKeys: Set<String>,
    cooldownsByProvider: Map<ClipboardPreviewProvider, ClipboardPreviewProviderCooldown> = emptyMap(),
    archiveDir: File? = null,
    existingArchiveFileNames: Set<String>? = null
): List<ClipboardArchiveDownloadListItem> =
    archives.flatMap { rawArchive ->
        val archive = when {
            existingArchiveFileNames != null -> rawArchive.withMissingArchiveFilesMarked(
                existingArchiveFileNames,
                now = rawArchive.updatedAtEpochMs
            )
            archiveDir != null -> rawArchive.withMissingArchiveFilesMarked(archiveDir, now = rawArchive.updatedAtEpochMs)
            else -> rawArchive
        }
        val progress = progressByArchiveKey[archive.key]
        val archiveLoading = archive.key in loadingArchiveKeys
        val cooldown = cooldownsByProvider[archive.provider]
        archive.media
            .filter {
                it.status.isRetryableArchiveMediaStatus() ||
                    (archiveLoading && progress?.sourceUrl == it.sourceUrl)
            }
            .map { media ->
                val active = archiveLoading && progress?.sourceUrl == media.sourceUrl
                ClipboardArchiveDownloadListItem(
                    archiveKey = archive.key,
                    sourceUrl = media.sourceUrl,
                    sourceIndex = media.sourceIndex,
                    mediaCount = archive.media.size,
                    provider = archive.provider,
                    providerLabel = archive.providerLabel(),
                    title = archive.displayTitle(),
                    subtitle = archive.displaySubtitle() ?: archive.sourceUrl,
                    status = when {
                        active -> ClipboardArchiveDownloadRowStatus.Active
                        media.status == ClipboardArchiveMediaStatus.Pending -> ClipboardArchiveDownloadRowStatus.Waiting
                        else -> ClipboardArchiveDownloadRowStatus.Failed
                    },
                    failureSummaryLabelRes = cooldown?.let {
                        R.string.clipboard_history_archive_failure_rate_limited
                    } ?: media.failureSummaryLabelRes(),
                    progress = progress?.takeIf { active },
                    lastAttemptAtEpochMs = media.lastAttemptAtEpochMs,
                    retryAvailableAtEpochMs = cooldown?.retryAfterEpochMs,
                    canStop = active,
                    canRetry = !archiveLoading && cooldown == null && media.status.isRetryableArchiveMediaStatus()
                )
            }
    }.sortedWith(
        compareBy<ClipboardArchiveDownloadListItem> {
            when (it.status) {
                ClipboardArchiveDownloadRowStatus.Active -> 0
                ClipboardArchiveDownloadRowStatus.Waiting -> 1
                ClipboardArchiveDownloadRowStatus.Failed -> 2
            }
        }.thenByDescending { it.lastAttemptAtEpochMs ?: 0L }
            .thenBy { it.providerLabel }
            .thenBy { it.title }
            .thenBy { it.sourceIndex }
    )

internal fun archiveDownloadActionCount(
    archives: Collection<ClipboardLinkArchive>,
    existingArchiveFileNames: Set<String>? = null
): Int =
    archives.sumOf { rawArchive ->
        val archive = existingArchiveFileNames
            ?.let { rawArchive.withMissingArchiveFilesMarked(it, now = rawArchive.updatedAtEpochMs) }
            ?: rawArchive
        archive.media.count { it.status.isRetryableArchiveMediaStatus() }
    }

internal fun ClipboardLinkArchive.failureSummaryLabelRes(): Int? =
    media.firstNotNullOfOrNull { it.failureSummaryLabelRes() }

internal fun ClipboardArchiveMedia.failureSummaryLabelRes(): Int? =
    when (status) {
        ClipboardArchiveMediaStatus.Failed -> if(failureDetail.isRateLimitFailureDetail()) {
            R.string.clipboard_history_archive_failure_rate_limited
        } else {
            R.string.clipboard_history_archive_failure_download_failed
        }
        ClipboardArchiveMediaStatus.Missing -> R.string.clipboard_history_archive_failure_file_missing
        ClipboardArchiveMediaStatus.SkippedTooLarge -> R.string.clipboard_history_archive_failure_too_large
        ClipboardArchiveMediaStatus.Saved,
        ClipboardArchiveMediaStatus.Pending -> null
    }

private fun String?.isRateLimitFailureDetail(): Boolean =
    this?.contains("Rate limited", ignoreCase = true) == true ||
        this?.contains("HTTP 429", ignoreCase = true) == true

internal fun ClipboardLinkArchive.providerLabel(): String = when (provider) {
    ClipboardPreviewProvider.PIXIV -> "Pixiv"
    ClipboardPreviewProvider.TWITTER -> "Twitter/X"
}

internal fun ClipboardLinkArchive.displayTitle(): String =
    metadata?.title
        ?: metadata?.bodyText?.takeIf { it.isNotBlank() }
        ?: sourceUrl

internal fun ClipboardLinkArchive.displaySubtitle(): String? =
    listOfNotNull(
        metadata?.authorName,
        metadata?.authorHandle?.let { "@${it.removePrefix("@")}" }
    ).joinToString(" ").takeIf { it.isNotBlank() }
        ?: metadata?.sourceId

internal fun ClipboardLinkArchive.displayStatus(loading: Boolean = false): ClipboardArchiveDisplayStatus {
    if(loading) return ClipboardArchiveDisplayStatus.Saving
    if(status == ClipboardLinkArchiveStatus.Complete) return ClipboardArchiveDisplayStatus.Complete
    if(media.any {
        it.status == ClipboardArchiveMediaStatus.Failed ||
            it.status == ClipboardArchiveMediaStatus.Missing ||
            it.status == ClipboardArchiveMediaStatus.SkippedTooLarge
    }) {
        return ClipboardArchiveDisplayStatus.Retry
    }
    if(media.any { it.status == ClipboardArchiveMediaStatus.Pending }) {
        return ClipboardArchiveDisplayStatus.Waiting
    }
    if(status == ClipboardLinkArchiveStatus.Partial) return ClipboardArchiveDisplayStatus.Partial
    return ClipboardArchiveDisplayStatus.Failed
}

internal data class ClipboardArchiveDetailRow(
    val label: String,
    val value: String
)

internal data class ClipboardArchiveDetailSection(
    val title: String,
    val rows: List<ClipboardArchiveDetailRow>
)

internal fun ClipboardLinkArchive.archiveMetadataDetailSections(): List<ClipboardArchiveDetailSection> = buildList {
    add(
        ClipboardArchiveDetailSection(
            title = "Source",
            rows = listOfNotNull(
                ClipboardArchiveDetailRow("Provider", providerLabel()),
                ClipboardArchiveDetailRow("Archive status", status.detailsLabel()),
                ClipboardArchiveDetailRow("Saved media", "${savedMediaCount()}/${expectedMediaCount()}"),
                ClipboardArchiveDetailRow("Source URL", sourceUrl),
                sourceId?.let { ClipboardArchiveDetailRow("Source ID", it) }
            )
        )
    )

    metadata?.let { metadata ->
        val contentRows = listOfNotNull(
            metadata.title?.let { ClipboardArchiveDetailRow("Title", it) },
            metadata.bodyText?.let { ClipboardArchiveDetailRow("Body", it) },
            metadata.createdAt?.let { ClipboardArchiveDetailRow("Created at", it) },
            metadata.imageCount?.let { ClipboardArchiveDetailRow("Image count", it.toString()) },
            metadata.selectedImageIndex?.let { ClipboardArchiveDetailRow("Selected image index", it.toString()) },
            metadata.tags.takeIf { it.isNotEmpty() }?.let {
                ClipboardArchiveDetailRow("Tags", it.joinToString(", "))
            },
            metadata.stats?.detailsText()?.let { ClipboardArchiveDetailRow("Stats", it) },
            metadata.flags.detailsText()?.let { ClipboardArchiveDetailRow("Flags", it) }
        )
        if(contentRows.isNotEmpty()) {
            add(ClipboardArchiveDetailSection("Content", contentRows))
        }

        val authorRows = listOfNotNull(
            metadata.authorName?.let { ClipboardArchiveDetailRow("Name", it) },
            metadata.authorHandle?.let { ClipboardArchiveDetailRow("Handle", it) },
            metadata.authorId?.let { ClipboardArchiveDetailRow("ID", it) },
            metadata.sourceUrl?.let { ClipboardArchiveDetailRow("Metadata source URL", it) },
            metadata.sourceId?.let { ClipboardArchiveDetailRow("Metadata source ID", it) }
        )
        if(authorRows.isNotEmpty()) {
            add(ClipboardArchiveDetailSection("Author", authorRows))
        }
    }

    add(
        ClipboardArchiveDetailSection(
            title = "Archive",
            rows = listOf(
                ClipboardArchiveDetailRow("Archive key", key),
                ClipboardArchiveDetailRow("Archive created", createdAtEpochMs.archiveReadableDateTime()),
                ClipboardArchiveDetailRow("Archive updated", updatedAtEpochMs.archiveReadableDateTime())
            )
        )
    )

    add(
        ClipboardArchiveDetailSection(
            title = "Media",
            rows = mediaDetailsRows()
        )
    )
}

internal fun ClipboardLinkArchive.archiveMetadataDetailsText(): String =
    archiveMetadataDetailSections().joinToString("\n\n") { section ->
        buildString {
            appendLine(section.title)
            section.rows.forEach { row ->
                appendLine("${row.label}: ${row.value}")
            }
        }.trimEnd()
    }

private fun ClipboardLinkArchive.mediaDetailsRows(): List<ClipboardArchiveDetailRow> {
    if(media.isEmpty()) return listOf(ClipboardArchiveDetailRow("Media", "No media"))
    return media.sortedBy { it.sourceIndex }.flatMap { media ->
        listOfNotNull(
            ClipboardArchiveDetailRow("Media ${media.sourceIndex + 1}", media.status.detailsLabel()),
            ClipboardArchiveDetailRow("Source URL", media.sourceUrl),
            media.mimeType?.let { ClipboardArchiveDetailRow("MIME type", it) },
            media.fileName?.let { ClipboardArchiveDetailRow("File name", it) },
            media.lastAttemptAtEpochMs?.let {
                ClipboardArchiveDetailRow("Last attempted", it.archiveReadableDateTime())
            },
            media.failureSummaryText()?.let { ClipboardArchiveDetailRow("Failure", it) }
        )
    }
}

internal fun Long.archiveReadableDateTime(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(this))
}

internal fun ClipboardArchiveMedia.failureDetailsText(): String = buildList {
    add("Failure")
    failureSummaryText()?.let { add("Summary: $it") }
    add("Source URL: $sourceUrl")
    add("Source index: ${sourceIndex + 1}")
    mimeType?.let { add("MIME type: $it") }
    fileName?.let { add("File name: $it") }
    lastAttemptAtEpochMs?.let { add("Last attempted: ${it.archiveReadableDateTime()}") }
    failureDetail?.takeIf { it.isNotBlank() }?.let {
        add("")
        add("Raw detail")
        add(it)
    }
}.joinToString("\n")

internal fun ClipboardLinkArchiveStatus.detailsLabel(): String = when (this) {
    ClipboardLinkArchiveStatus.Pending -> "Pending"
    ClipboardLinkArchiveStatus.InProgress -> "In progress"
    ClipboardLinkArchiveStatus.Complete -> "Complete"
    ClipboardLinkArchiveStatus.Partial -> "Partial"
    ClipboardLinkArchiveStatus.Failed -> "Failed"
}

internal fun ClipboardArchiveMediaStatus.detailsLabel(): String = when (this) {
    ClipboardArchiveMediaStatus.Pending -> "Pending"
    ClipboardArchiveMediaStatus.Saved -> "Saved"
    ClipboardArchiveMediaStatus.Failed -> "Failed"
    ClipboardArchiveMediaStatus.SkippedTooLarge -> "Too large"
    ClipboardArchiveMediaStatus.Missing -> "Missing"
}

internal fun ClipboardArchiveMedia.failureSummaryText(): String? = when (status) {
    ClipboardArchiveMediaStatus.Failed -> if(failureDetail.isRateLimitFailureDetail()) {
        "Rate limited"
    } else {
        "Download failed"
    }
    ClipboardArchiveMediaStatus.Missing -> "File missing"
    ClipboardArchiveMediaStatus.SkippedTooLarge -> "Too large"
    ClipboardArchiveMediaStatus.Saved,
    ClipboardArchiveMediaStatus.Pending -> null
}

private fun ClipboardPreviewStats.detailsText(): String? = listOfNotNull(
    likeCount?.let { "likes=$it" },
    bookmarkCount?.let { "bookmarks=$it" },
    viewCount?.let { "views=$it" },
    replyCount?.let { "replies=$it" },
    repostCount?.let { "reposts=$it" },
    quoteCount?.let { "quotes=$it" },
    commentCount?.let { "comments=$it" }
).joinToString(", ").takeIf { it.isNotBlank() }

private fun ClipboardPreviewFlags.detailsText(): String? = listOfNotNull(
    "aiGenerated".takeIf { aiGenerated },
    "animated".takeIf { animated },
    "restricted".takeIf { restricted },
    "noteTweet".takeIf { noteTweet }
).joinToString(", ").takeIf { it.isNotBlank() }

internal fun ClipboardLinkArchive.matchesArchiveQuery(query: String): Boolean {
    if(query.isBlank()) return true

    val normalized = query.trim().lowercase()
    val metadata = metadata
    val haystacks = buildList {
        add(providerLabel())
        add(sourceUrl)
        sourceId?.let(::add)
        metadata?.sourceUrl?.let(::add)
        metadata?.sourceId?.let(::add)
        metadata?.title?.let(::add)
        metadata?.bodyText?.let(::add)
        metadata?.authorName?.let(::add)
        metadata?.authorHandle?.let(::add)
        metadata?.authorId?.let(::add)
        metadata?.tags?.forEach(::add)
    }

    return haystacks.any { it.lowercase().contains(normalized) }
}

internal fun ClipboardLinkArchive.matchesProviderFilter(filter: ClipboardArchiveProviderFilter): Boolean =
    when (filter) {
        ClipboardArchiveProviderFilter.All -> true
        ClipboardArchiveProviderFilter.Pixiv -> provider == ClipboardPreviewProvider.PIXIV
        ClipboardArchiveProviderFilter.Twitter -> provider == ClipboardPreviewProvider.TWITTER
    }

internal fun ClipboardLinkArchive.matchesStatusFilter(filter: ClipboardArchiveStatusFilter): Boolean =
    when (filter) {
        ClipboardArchiveStatusFilter.All -> true
        ClipboardArchiveStatusFilter.Complete -> status == ClipboardLinkArchiveStatus.Complete
        ClipboardArchiveStatusFilter.Partial -> status == ClipboardLinkArchiveStatus.Partial
        ClipboardArchiveStatusFilter.FailedInProgress -> hasRetryableMedia() ||
            status in setOf(
                ClipboardLinkArchiveStatus.Pending,
                ClipboardLinkArchiveStatus.InProgress,
                ClipboardLinkArchiveStatus.Failed
            )
    }

internal fun ClipboardLinkArchive.galleryItems(archiveDir: File): List<ClipboardArchiveGalleryItem> {
    val archive = withMissingArchiveFilesMarked(archiveDir, now = updatedAtEpochMs)
    return archive.galleryItemsFromNormalizedArchive { File(archiveDir, it).takeIf { file -> file.isFile } }
}

internal fun ClipboardLinkArchive.galleryItems(
    archiveDir: File,
    clipboardDir: File
): List<ClipboardArchiveGalleryItem> {
    val archive = withMissingArchiveFilesMarked(archiveDir, clipboardDir, now = updatedAtEpochMs)
    return archive.galleryItemsFromNormalizedArchive { archiveMediaFile(archiveDir, clipboardDir, it) }
}

internal fun ClipboardLinkArchive.galleryItems(
    archiveDir: File,
    existingArchiveFileNames: Set<String>
): List<ClipboardArchiveGalleryItem> {
    val archive = withMissingArchiveFilesMarked(existingArchiveFileNames, now = updatedAtEpochMs)
    return archive.galleryItemsFromNormalizedArchive { File(archiveDir, it).takeIf { file -> file.isFile } }
}

internal fun ClipboardLinkArchive.galleryItems(
    archiveDir: File,
    clipboardDir: File,
    existingArchiveFileNames: Set<String>
): List<ClipboardArchiveGalleryItem> {
    val archive = withMissingArchiveFilesMarked(existingArchiveFileNames, now = updatedAtEpochMs)
    return archive.galleryItemsFromNormalizedArchive { archiveMediaFile(archiveDir, clipboardDir, it) }
}

private fun ClipboardLinkArchive.galleryItemsFromNormalizedArchive(
    mediaFile: (String) -> File?
): List<ClipboardArchiveGalleryItem> {
    val sortedMedia = media.sortedBy { it.sourceIndex }
    return sortedMedia.mapIndexed { index, item ->
        val file = item.fileName
            ?.takeIf { item.status == ClipboardArchiveMediaStatus.Saved }
            ?.let(mediaFile)

        ClipboardArchiveGalleryItem(
            media = item,
            file = file,
            position = index + 1,
            totalCount = sortedMedia.size,
            displayStatus = item.displayStatus(file)
        )
    }
}

private fun ClipboardArchiveMedia.displayStatus(file: File?): ClipboardArchiveDisplayStatus =
    when (status) {
        ClipboardArchiveMediaStatus.Saved -> {
            if(file?.isFile == true) ClipboardArchiveDisplayStatus.Complete else ClipboardArchiveDisplayStatus.Failed
        }
        ClipboardArchiveMediaStatus.Pending -> ClipboardArchiveDisplayStatus.Waiting
        ClipboardArchiveMediaStatus.Failed,
        ClipboardArchiveMediaStatus.Missing,
        ClipboardArchiveMediaStatus.SkippedTooLarge -> ClipboardArchiveDisplayStatus.Retry
    }
