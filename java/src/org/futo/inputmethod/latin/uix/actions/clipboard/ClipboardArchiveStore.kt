package org.futo.inputmethod.latin.uix.actions.clipboard

import java.io.File

internal data class ClipboardArchiveCorruptRecord(
    val primaryFile: File,
    val backupFile: File?,
    val recoveredFromBackup: Boolean
)

internal data class ClipboardArchiveStoreLoadResult(
    val archives: List<ClipboardLinkArchive>,
    val tombstones: List<ClipboardArchiveTombstone>,
    val corruptRecords: List<ClipboardArchiveCorruptRecord>
)

internal data class ClipboardArchiveStoreState(
    val entries: List<ClipboardEntry>,
    val archives: List<ClipboardLinkArchive>,
    val tombstones: List<ClipboardArchiveTombstone>
)

/** Owns the filesystem boundary for clipboard entries, archive metadata, tombstones, and media. */
internal class ClipboardArchiveStore(
    private val filesDir: File,
    private val promotionStep: (Int) -> Unit = {},
    private val move: (File, File) -> Boolean = { source, destination -> source.renameTo(destination) }
) {
    private val clipboardFile = File(filesDir, ClipboardFileName)
    private val metadataDir = File(filesDir, ClipboardArchiveMetadataDirectoryName)
    private val tombstonesFile = File(filesDir, ClipboardArchiveTombstonesFileName)
    private val mediaDir = File(filesDir, ClipboardBackupFilesDirectoryName)
    private val legacyArchiveFile = File(filesDir, ClipboardArchiveFileName)
    private val legacyArchiveMediaDir = File(filesDir, ClipboardArchiveFilesDirectoryName)
    private val previousDir = File(filesDir, ".clipboard-store-previous")
    private val promotionMarker = File(previousDir, "promotion-in-progress")
    private var recoveryFileNames = emptySet<String>()

    init {
        recoverInterruptedPromotion()
    }

    fun load(): ClipboardArchiveStoreLoadResult {
        val corrupt = mutableListOf<ClipboardArchiveCorruptRecord>()
        val metadataArchives = metadataDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.mapNotNull { primary ->
                val backup = File(metadataDir, "${primary.name}.bak")
                runCatching { primary.decodeClipboardArchive() }.getOrElse {
                    val recovered = if(backup.isFile) {
                        runCatching { backup.decodeClipboardArchive() }.getOrNull()
                    } else {
                        null
                    }
                    corrupt += ClipboardArchiveCorruptRecord(
                        primaryFile = primary,
                        backupFile = backup.takeIf(File::isFile),
                        recoveredFromBackup = recovered != null
                    )
                    recovered
                }
            }
            ?.sortedBy { it.key }
            .orEmpty()
        recoveryFileNames = corrupt
            .filterNot { it.recoveredFromBackup }
            .flatMap { listOfNotNull(it.primaryFile.name, it.backupFile?.name) }
            .toSet()

        val legacyArchives = loadWithBackup(legacyArchiveFile) { it.decodeLegacyClipboardArchives() }
            .orEmpty()
        val tombstones = loadWithBackup(tombstonesFile) { it.decodeClipboardArchiveTombstones() }
            .orEmpty()
        return ClipboardArchiveStoreLoadResult(
            archives = mergeStoredClipboardArchives(legacyArchives, metadataArchives),
            tombstones = tombstones,
            corruptRecords = corrupt
        )
    }

    fun saveArchive(archive: ClipboardLinkArchive) {
        metadataDir.mkdirs()
        val target = metadataDir.clipboardArchiveMetadataFile(archive.key)
        val backup = File(metadataDir, "${target.name}.bak")
        val swap = File(metadataDir, "${target.name}.swap")
        swap.writeText(encodeClipboardArchive(archive))
        require(swap.decodeClipboardArchive() == archive) { "Saved archive data does not match expected data" }
        replaceFileWithBackup(swap, target, backup)
        require(target.decodeClipboardArchive() == archive) { "Saved archive data does not match expected data" }
        deleteLegacyAggregateFiles()
    }

    fun saveTombstones(tombstones: Collection<ClipboardArchiveTombstone>) {
        val expected = tombstones.sortedBy { it.key }
        val backup = File(filesDir, "$ClipboardArchiveTombstonesFileName.bak")
        val swap = File(filesDir, "$ClipboardArchiveTombstonesFileName.swap")
        swap.writeText(encodeClipboardArchiveTombstones(expected))
        require(swap.decodeClipboardArchiveTombstones() == expected) { "Saved archive tombstones do not match expected data" }
        replaceFileWithBackup(swap, tombstonesFile, backup)
        require(tombstonesFile.decodeClipboardArchiveTombstones() == expected) { "Saved archive tombstones do not match expected data" }
    }

    fun replaceArchiveMetadata(archives: Collection<ClipboardLinkArchive>) {
        archives.forEach(::saveArchive)
        deleteStaleArchiveMetadata(archives.map { it.key }.toSet())
    }

    fun deleteArchiveMetadata(key: String) {
        val file = metadataDir.clipboardArchiveMetadataFile(key)
        listOf(file, File(metadataDir, "${file.name}.bak"), File(metadataDir, "${file.name}.swap"))
            .forEach(File::delete)
    }

    fun deleteStaleArchiveMetadata(expectedKeys: Set<String>) {
        val expectedNames = expectedKeys
            .map(::clipboardArchiveMetadataFileName)
            .flatMap { listOf(it, "$it.bak", "$it.swap") }
            .toSet() + recoveryFileNames
        metadataDir.listFiles()?.forEach { file ->
            if(file.name !in expectedNames) file.delete()
        }
        deleteLegacyAggregateFiles()
    }

    fun stageAndPromote(
        state: ClipboardArchiveStoreState,
        importedMediaDirs: List<File>,
        preserveExistingMedia: Boolean
    ): ClipboardArchiveStoreState {
        val stageDir = File(filesDir, ".clipboard-store-stage-${System.nanoTime()}")
        val stagedMediaDir = File(stageDir, ClipboardBackupFilesDirectoryName)
        val stagedMetadataDir = File(stageDir, ClipboardArchiveMetadataDirectoryName)
        try {
            stagedMediaDir.mkdirs()
            if(preserveExistingMedia) {
                copyFiles(mediaDir, stagedMediaDir, overwrite = true)
                copyFiles(legacyArchiveMediaDir, stagedMediaDir, overwrite = false)
            }
            importedMediaDirs.forEach { copyFiles(it, stagedMediaDir, overwrite = false) }

            val reconciledArchives = reconcileClipboardArchivesWithStorage(
                archives = state.archives,
                clipboardDir = stagedMediaDir,
                legacyArchiveDir = File(stageDir, ClipboardArchiveFilesDirectoryName)
            )
            val reconciledEntries = reconcileClipboardEntriesWithStorage(state.entries, stagedMediaDir)
            val installed = state.copy(entries = reconciledEntries, archives = reconciledArchives)

            File(stageDir, ClipboardFileName).writeText(encodeClipboardEntries(installed.entries))
            File(stageDir, ClipboardArchiveTombstonesFileName)
                .writeText(encodeClipboardArchiveTombstones(installed.tombstones))
            stagedMetadataDir.mkdirs()
            installed.archives.forEach { archive ->
                stagedMetadataDir.clipboardArchiveMetadataFile(archive.key)
                    .writeText(encodeClipboardArchive(archive))
            }
            validateStage(stageDir, installed)
            promote(stageDir)
            recoveryFileNames = emptySet()
            return installed
        } catch(e: Exception) {
            stageDir.deleteRecursively()
            throw e
        }
    }

    private fun promote(stageDir: File) {
        val names = listOf(
            ClipboardFileName,
            ClipboardBackupFilesDirectoryName,
            ClipboardArchiveMetadataDirectoryName,
            ClipboardArchiveTombstonesFileName
        )
        val movedCurrent = mutableListOf<String>()
        val installed = mutableListOf<String>()
        previousDir.deleteRecursively()
        previousDir.mkdirs()
        promotionMarker.writeText(
            names.filter { File(filesDir, it).exists() }.joinToString("\n")
        )
        try {
            names.forEachIndexed { index, name ->
                val current = File(filesDir, name)
                if(current.exists()) {
                    require(move(current, File(previousDir, name))) { "Could not preserve current clipboard state" }
                    movedCurrent += name
                }
                promotionStep(index * 2)
                val staged = File(stageDir, name)
                require(move(staged, current)) { "Could not install staged clipboard state" }
                installed += name
                promotionStep(index * 2 + 1)
            }
            deleteLegacyAggregateFiles()
            legacyArchiveMediaDir.deleteRecursively()
            deleteSupersededSwapFiles()
            promotionMarker.delete()
            previousDir.deleteRecursively()
        } catch(e: Exception) {
            installed.asReversed().forEach { File(filesDir, it).deleteRecursively() }
            var rollbackFailure: IllegalStateException? = null
            movedCurrent.asReversed().forEach { name ->
                val previous = File(previousDir, name)
                if(previous.exists() && !move(previous, File(filesDir, name))) {
                    val failure = IllegalStateException("Could not restore previous clipboard path: $name", e)
                    if(rollbackFailure == null) rollbackFailure = failure else rollbackFailure?.addSuppressed(failure)
                }
            }
            rollbackFailure?.let { throw it }
            promotionMarker.delete()
            previousDir.deleteRecursively()
            throw e
        } finally {
            stageDir.deleteRecursively()
        }
    }

    private fun recoverInterruptedPromotion() {
        if(!promotionMarker.isFile) return
        val previousNames = promotionMarker.readLines().toSet()
        listOf(
            ClipboardFileName,
            ClipboardBackupFilesDirectoryName,
            ClipboardArchiveMetadataDirectoryName,
            ClipboardArchiveTombstonesFileName
        ).forEach { name ->
            val current = File(filesDir, name)
            val previous = File(previousDir, name)
            when {
                previous.exists() -> {
                    current.deleteRecursively()
                    require(move(previous, current)) { "Could not recover previous clipboard state" }
                }
                name !in previousNames -> current.deleteRecursively()
            }
        }
        promotionMarker.delete()
        previousDir.deleteRecursively()
    }

    private fun validateStage(stageDir: File, expected: ClipboardArchiveStoreState) {
        require(File(stageDir, ClipboardFileName).decodeClipboardEntries() == expected.entries)
        require(
            File(stageDir, ClipboardArchiveTombstonesFileName).decodeClipboardArchiveTombstones() ==
                expected.tombstones.sortedBy { it.key }
        )
        require(
            loadClipboardArchivesFromMetadataDir(File(stageDir, ClipboardArchiveMetadataDirectoryName)) ==
                expected.archives.sortedBy { it.key }
        )
    }

    private fun deleteLegacyAggregateFiles() {
        listOf(
            legacyArchiveFile,
            File(filesDir, "$ClipboardArchiveFileName.bak"),
            File(filesDir, "$ClipboardArchiveFileName.swap")
        ).forEach(File::delete)
    }

    private fun deleteSupersededSwapFiles() {
        listOf(
            File(filesDir, "$ClipboardFileName.bak"),
            File(filesDir, "$ClipboardFileName.swap"),
            File(filesDir, "$ClipboardArchiveTombstonesFileName.bak"),
            File(filesDir, "$ClipboardArchiveTombstonesFileName.swap")
        ).forEach(File::delete)
    }

    private fun <T> loadWithBackup(primary: File, decode: (File) -> T): T? {
        val backup = File(filesDir, "${primary.name}.bak")
        return when {
            primary.isFile -> runCatching { decode(primary) }.getOrElse {
                if(backup.isFile) decode(backup) else throw it
            }
            backup.isFile -> decode(backup)
            else -> null
        }
    }

    private fun copyFiles(source: File, destination: File, overwrite: Boolean) {
        source.listFiles()?.filter(File::isFile)?.forEach { file ->
            val target = File(destination, file.name)
            if(overwrite || !target.exists()) file.copyTo(target, overwrite = overwrite)
        }
    }
}
