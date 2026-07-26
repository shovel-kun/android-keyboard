package org.futo.inputmethod.latin.uix.actions.clipboard

import java.io.File

internal data class ClipboardArchiveUiSnapshot(
    val archives: List<ClipboardLinkArchive>,
    val previewFilesByArchiveKey: Map<String, List<File>>,
    val galleryItemsByArchiveKey: Map<String, List<ClipboardArchiveGalleryItem>>,
    val downloadItems: List<ClipboardArchiveDownloadListItem>,
    val downloadActionCount: Int,
    val loadingArchiveKeys: Set<String>,
    val progressByArchiveKey: Map<String, ClipboardArchiveDownloadProgress>,
    val imageTaggingState: ClipboardImageTaggingState,
    val imageTagEligibleCount: Int
)

internal fun clipboardArchiveUiSnapshot(
    archives: Collection<ClipboardLinkArchive>,
    clipboardDir: File,
    storageFileNames: Set<String>,
    downloadState: ClipboardArchiveDownloadStateSnapshot,
    loadingArchiveKeys: Set<String>,
    imageTaggingState: ClipboardImageTaggingState = ClipboardImageTaggingState(),
    imageTagEligibleCount: Int = 0
): ClipboardArchiveUiSnapshot {
    val immutableFileNames = storageFileNames.toSet()
    val currentArchives = archives.map {
        it.withMissingArchiveFilesMarked(immutableFileNames, now = it.updatedAtEpochMs)
    }
    val immutableLoadingKeys = loadingArchiveKeys.toSet()
    val downloadItems = archiveDownloadItems(
        archives = currentArchives,
        progressByArchiveKey = downloadState.progressByArchiveKey,
        loadingArchiveKeys = immutableLoadingKeys,
        queuedSourceUrlsByArchiveKey = downloadState.queuedSourceUrlsByArchiveKey,
        cooldownsByProvider = downloadState.cooldownsByProvider,
        existingArchiveFileNames = immutableFileNames
    )

    return ClipboardArchiveUiSnapshot(
        archives = currentArchives,
        previewFilesByArchiveKey = currentArchives.associate { archive ->
            archive.key to archive.savedPreviewMedia().mapNotNull { media ->
                media.fileName
                    ?.takeIf { it in immutableFileNames }
                    ?.let { File(clipboardDir, it) }
            }
        },
        galleryItemsByArchiveKey = currentArchives.associate { archive ->
            archive.key to archive.galleryItems(clipboardDir, immutableFileNames)
        },
        downloadItems = downloadItems,
        downloadActionCount = archiveDownloadActionCount(currentArchives, immutableFileNames),
        loadingArchiveKeys = immutableLoadingKeys,
        progressByArchiveKey = downloadState.progressByArchiveKey.toMap(),
        imageTaggingState = imageTaggingState,
        imageTagEligibleCount = imageTagEligibleCount
    )
}
