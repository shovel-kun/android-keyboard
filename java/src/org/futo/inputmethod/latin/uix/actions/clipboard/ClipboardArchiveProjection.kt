package org.futo.inputmethod.latin.uix.actions.clipboard

import java.io.File

internal data class ClipboardArchiveUiSnapshot(
    val archives: List<ClipboardLinkArchive>,
    val previewFilesByArchiveKey: Map<String, List<File>>,
    val downloadActionCount: Int,
    val imageTagEligibleCount: Int
)

internal data class ClipboardArchiveActivitySnapshot(
    val loadingArchiveKeys: Set<String>,
    val progressByArchiveKey: Map<String, ClipboardArchiveDownloadProgress>,
    val imageTaggingState: ClipboardImageTaggingState
)

internal fun clipboardArchiveUiSnapshot(
    archives: Collection<ClipboardLinkArchive>,
    clipboardDir: File,
    storageFileNames: Set<String>,
    imageTagEligibleCount: Int = 0
): ClipboardArchiveUiSnapshot {
    val immutableFileNames = storageFileNames.toSet()
    val currentArchives = archives.map {
        it.withMissingArchiveFilesMarked(immutableFileNames, now = it.updatedAtEpochMs)
    }
    return ClipboardArchiveUiSnapshot(
        archives = currentArchives,
        previewFilesByArchiveKey = currentArchives.associate { archive ->
            archive.key to archive.savedPreviewMedia().mapNotNull { media ->
                media.fileName
                    ?.takeIf { it in immutableFileNames }
                    ?.let { File(clipboardDir, it) }
            }
        },
        downloadActionCount = archiveDownloadActionCount(currentArchives, immutableFileNames),
        imageTagEligibleCount = imageTagEligibleCount
    )
}
