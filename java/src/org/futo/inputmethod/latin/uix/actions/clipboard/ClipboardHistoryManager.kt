package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.uix.PersistentActionState
import org.futo.inputmethod.latin.uix.QuickClip
import org.futo.inputmethod.latin.uix.actions.BugInfo
import org.futo.inputmethod.latin.uix.actions.BugViewerState
import org.futo.inputmethod.latin.uix.actions.throwIfDebug
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.getUnlockedSetting
import org.futo.inputmethod.latin.uix.isDirectBootUnlocked
import org.futo.inputmethod.latin.uix.setSetting
import android.webkit.MimeTypeMap
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
private val ClipboardIOContext = Dispatchers.IO.limitedParallelism(1)
@OptIn(ExperimentalCoroutinesApi::class)
private val ClipboardPreviewFetchContext = Dispatchers.IO.limitedParallelism(3)
private const val ClipboardStoredMediaMaxBytes = 50L * 1024L * 1024L
private const val ClipboardStartupPreviewFetchLimit = 8

private data class ClipboardPreviewFetchRequest(
    val text: String,
    val candidate: ClipboardPreviewCandidate,
    val maxAttempts: Int,
    val manualRetry: Boolean
)

internal data class ClipboardArchiveBackfillRequest(
    val entry: ClipboardEntry,
    val archiveKey: String
)

internal data class ClipboardArchiveDownloadProgress(
    val archiveKey: String,
    val sourceUrl: String,
    val sourceIndex: Int,
    val completedBytes: Long,
    val totalBytes: Long?,
    val savedCount: Int,
    val expectedCount: Int
) {
    val progressFraction: Float?
        get() = totalBytes
            ?.takeIf { it > 0L }
            ?.let { (completedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
}

private data class ClipboardArchiveMediaDownloadTarget(
    val archive: ClipboardLinkArchive,
    val media: ClipboardArchiveMedia
)

private data class ClipboardArchiveProgressSnapshot(
    val archiveKey: String,
    val sourceUrl: String,
    val sourceIndex: Int,
    val completedBytes: Long,
    val totalBytes: Long?,
    val savedCount: Int,
    val expectedCount: Int
) {
    fun toProgress(): ClipboardArchiveDownloadProgress = ClipboardArchiveDownloadProgress(
        archiveKey = archiveKey,
        sourceUrl = sourceUrl,
        sourceIndex = sourceIndex,
        completedBytes = completedBytes,
        totalBytes = totalBytes,
        savedCount = savedCount,
        expectedCount = expectedCount
    )
}

private data class ClipboardStorageSnapshot(
    val fileNames: Set<String>
)

internal fun describeClipboardStorageFile(role: String, file: File): String {
    val exists = file.exists()
    val decodeSuccess = exists && runCatching {
        decodeClipboardEntries(file.readText())
    }.isSuccess

    return "role=$role, name=${file.name}, exists=$exists, byteSize=${if(exists) file.length() else 0L}, decodeSuccess=$decodeSuccess"
}

internal data class ClipboardPreviewProviderCooldown(
    val provider: ClipboardPreviewProvider,
    val retryAfterEpochMs: Long,
    val detail: String
)

internal fun replaceFileWithBackup(
    swapFile: File,
    targetFile: File,
    backupFile: File
) {
    if(backupFile.exists() && !backupFile.delete()) {
        throw Exception("Failed to delete stale backup file")
    }
    if(targetFile.exists() && !targetFile.renameTo(backupFile)) {
        throw Exception("Failed to move current file to backup")
    }
    if(!swapFile.renameTo(targetFile)) {
        throw Exception("Failed to swap new file")
    }
}

internal fun ClipboardEntry.archiveBackfillMetadata(): ClipboardPreviewMetadata? {
    val text = text ?: return null
    val candidate = ClipboardLinkPreviewFetcher.previewCandidateFor(text) ?: return null
    return previewMetadata?.takeIf { it.provider == candidate.provider }?.let { legacy ->
        candidate.metadata.copy(
            sourceUrl = legacy.sourceUrl ?: candidate.metadata.sourceUrl,
            sourceId = legacy.sourceId ?: candidate.metadata.sourceId,
            title = legacy.title,
            bodyText = legacy.bodyText ?: previewText,
            authorName = legacy.authorName,
            authorHandle = legacy.authorHandle ?: candidate.metadata.authorHandle,
            authorId = legacy.authorId,
            createdAt = legacy.createdAt,
            imageCount = legacy.imageCount,
            selectedImageIndex = legacy.selectedImageIndex ?: candidate.metadata.selectedImageIndex,
            tags = legacy.tags,
            stats = legacy.stats,
            flags = legacy.flags
        )
    } ?: candidate.metadata.copy(bodyText = previewText)
}

internal fun ClipboardEntry.isEligibleForArchiveBackfill(
    existingArchiveKeys: Set<String>,
    deletedArchiveKeys: Set<String> = emptySet()
): Boolean {
    val archiveKey = archiveBackfillKey() ?: return false
    if(archiveKey in deletedArchiveKeys) return false
    return archiveKey !in existingArchiveKeys
}

internal fun ClipboardEntry.archiveBackfillKey(): String? {
    if(!hasRenderablePreview() && previewMetadata == null) return null
    val metadata = archiveBackfillMetadata() ?: return null
    return metadata.archiveKey()
}

internal fun archiveBackfillRequests(
    entries: List<ClipboardEntry>,
    existingArchiveKeys: Set<String>,
    attemptedArchiveKeys: Set<String> = emptySet(),
    deletedArchiveKeys: Set<String> = emptySet()
): List<ClipboardArchiveBackfillRequest> {
    val scheduledArchiveKeys = (existingArchiveKeys + attemptedArchiveKeys).toMutableSet()
    return entries.mapNotNull { entry ->
        val archiveKey = entry.archiveBackfillKey() ?: return@mapNotNull null
        if(archiveKey in deletedArchiveKeys) return@mapNotNull null
        if(!scheduledArchiveKeys.add(archiveKey)) return@mapNotNull null
        ClipboardArchiveBackfillRequest(entry = entry, archiveKey = archiveKey)
    }
}

internal fun startupPreviewFetchTexts(
    entries: List<ClipboardEntry>,
    limit: Int?
): List<String> =
    startupPreviewFetchTextSequence(entries)
        .let { texts -> limit?.let(texts::take) ?: texts }
        .toList()

private fun startupPreviewFetchTextSequence(entries: List<ClipboardEntry>): Sequence<String> =
    entries.asReversed().asSequence().mapNotNull { entry ->
        entry.text?.takeIf { entry.canAutoFetchPreview() }
    }

internal fun ClipboardEntry.matchesDeletedArchiveKey(archiveKey: String): Boolean =
    previewMetadata?.archiveKey() == archiveKey ||
        archiveBackfillMetadata()?.archiveKey() == archiveKey

private fun Context.pixivSessionIdForClipboardPreviews(): String? =
    getSetting(ClipboardPixivSessionId).trim().takeIf { it.isNotBlank() }

private fun Context.redditAccessTokenForClipboardPreviews(): String? =
    getSetting(ClipboardRedditAccessToken).trim().takeIf { it.isNotBlank() }

internal fun deletedArchiveKeysAfterTextImport(
    text: String,
    deletedArchiveKeys: Set<String>
): Set<String> {
    val archiveKey = ClipboardLinkPreviewFetcher.previewCandidateFor(text)?.archiveKey ?: return deletedArchiveKeys
    return deletedArchiveKeys - archiveKey
}

internal fun deletedArchiveKeysAfterPreviewManifest(
    manifest: ClipboardLinkPreviewManifest,
    deletedArchiveKeys: Set<String>
): Set<String> {
    val archiveKey = manifest.archiveKey() ?: return deletedArchiveKeys
    return deletedArchiveKeys - archiveKey
}

internal fun shouldRunArchiveBackfill(
    completedVersion: Int,
    currentVersion: Int,
    incognito: Boolean,
    previewsEnabled: Boolean,
    forceCompletedVersion: Boolean = false
): Boolean =
    !incognito && previewsEnabled && (forceCompletedVersion || completedVersion < currentVersion)

internal fun copyLegacyPreviewMediaToArchive(
    entry: ClipboardEntry,
    metadata: ClipboardPreviewMetadata,
    clipboardDir: File,
    legacyArchiveDir: File,
    now: Long = System.currentTimeMillis()
): List<ClipboardArchiveMedia> {
    val fallbackSourceUrl = metadata.sourceUrl ?: entry.text ?: return emptyList()

    return entry.previewMedia().mapNotNull { media ->
        val mediaFile = legacyAwareClipboardMediaFile(
            clipboardDir = clipboardDir,
            legacyArchiveDir = legacyArchiveDir,
            fileName = media.fileName
        ) ?: return@mapNotNull null

        ClipboardArchiveMedia(
            sourceUrl = media.sourceUrl ?: "$fallbackSourceUrl#legacy-media-${media.sourceIndex}",
            sourceIndex = media.sourceIndex,
            mimeType = media.mimeType ?: mediaFile.guessedClipboardMimeType(),
            fileName = mediaFile.name,
            status = ClipboardArchiveMediaStatus.Saved,
            lastAttemptAtEpochMs = now
        )
    }
}

internal fun retainedPreviewMediaAfterArchiveDelete(
    entry: ClipboardEntry,
    archivedFileNames: Set<String>,
    existingMediaNames: Set<String>
): List<ClipboardPreviewMedia> =
    entry.previewMedia().filter {
        it.fileName !in archivedFileNames ||
            it.fileName in existingMediaNames
    }

internal fun retainedPreviewTextAfterArchiveDelete(
    entry: ClipboardEntry,
    retainedPreviewMedia: List<ClipboardPreviewMedia>
): String? =
    entry.previewText?.takeIf { retainedPreviewMedia.isNotEmpty() }

internal fun orphanedSharedArchiveFileNamesAfterArchiveDelete(
    archivedFileNames: Set<String>,
    entries: List<ClipboardEntry>
): Set<String> {
    val retainedClipFiles = referencedClipboardFileNames(entries)
    return archivedFileNames
        .flatMap { listOf(it, ClipboardUtil.thumbnailForName(it)) }
        .filter { it !in retainedClipFiles }
        .toSet()
}

internal fun upsertClipboardMediaEntry(
    entries: MutableList<ClipboardEntry>,
    entry: ClipboardEntry
) {
    val backingFile = entry.backingFile ?: return
    val wasPinned = entries.any { it.backingFile == backingFile && it.pinned }
    entries.removeAll { it.backingFile == backingFile }
    entries.add(entry.copy(pinned = entry.pinned || wasPinned))
}

class ClipboardHistoryManager private constructor(
    val context: Context
) : PersistentActionState {
    // Process-lifetime scope, owned by this (singleton) manager rather than the IME
    // service's lifecycleScope, so in-flight work and the loaded state survive the
    // service being destroyed and recreated on an input-method switch.
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val archiveDownloadCoordinator = ClipboardArchiveDownloadCoordinator(coroutineScope)

    // Serializes clipboard loads so the initial load and an unlock-triggered load
    // cannot interleave their clear/repopulate of the in-memory lists.
    private val loadMutex = Mutex()

    var clipboardIOFailureReason = ""
    val clipboardIOFailure = mutableStateOf(false)
    val previewLoadingByText = mutableStateMapOf<String, Boolean>()
    private val clipboardStorageSnapshot = mutableStateOf(ClipboardStorageSnapshot(emptySet()))
    val archiveBackfillInProgress = mutableStateOf(false)
    val archiveBackfillRemainingCount = mutableStateOf(0)

    companion object {
        val onClipboardImportedFlow = MutableSharedFlow<File>()

        @Volatile
        private var instance: ClipboardHistoryManager? = null

        /**
         * Returns the process-wide ClipboardHistoryManager, creating it on first use.
         * Holding a single instance built from the application context (with its own
         * process-lifetime scope) avoids reloading and reconciling the entire clipboard
         * every time the IME service is destroyed and recreated, e.g. on an input-method
         * switch. Safe to call from the keyboard service or the settings activity.
         */
        fun getInstance(context: Context): ClipboardHistoryManager =
            instance ?: synchronized(this) {
                instance ?: ClipboardHistoryManager(context.applicationContext).also {
                    instance = it
                }
            }
    }

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipboardHistory = mutableStateListOf<ClipboardEntry>()
    val linkArchives = mutableStateMapOf<String, ClipboardLinkArchive>()

    private val clipboardFile = context.clipboardFile
    private val clipboardFileBak = File(context.filesDir, "$ClipboardFileName.bak")
    private val clipboardFileSwap = File(context.filesDir, "$ClipboardFileName.swap")
    private val archiveStore = ClipboardArchiveStore(context.filesDir)
    private val archiveSaveLock = Any()

    private var scheduledPreviewSaveJob: Job? = null
    private var saveClipboardLoadJob: Job? = null
    private var clipboardLoaded = false
    private val archiveBackfillAttemptedKeys = mutableSetOf<String>()
    private val deletedArchiveKeys = mutableSetOf<String>()
    private val archiveTombstonesByKey = mutableMapOf<String, ClipboardArchiveTombstone>()
    private var archiveBackfillCompletionPending = false
    private var archiveBackfillBlockedByCooldown = false
    private val pendingArchiveSavesByKey = mutableMapOf<String, ClipboardLinkArchive>()
    private var scheduledArchiveSaveJob: Job? = null

    private val screenshotHelper = ScreenshotHelper(
        context = context,
        lifecycleScope = coroutineScope,
        listener = object : ScreenshotListener {
            override fun onScreenshotAdded(mime: String, uri: Uri) {
                importScreenshotEntry(mime, uri)
            }
        }
    )

    override suspend fun onDeviceUnlocked() {
        // The singleton's init already loads on construction; only load here if that
        // hasn't happened yet (e.g. the manager was built while still device-locked).
        if(clipboardLoaded) return
        loadClipboard()
    }

    private val primaryClipChangedListener = object : ClipboardManager.OnPrimaryClipChangedListener {
        override fun onPrimaryClipChanged() {
            if(!shouldImportClipboardChanges()) return

            val clip = try {
                clipboardManager.primaryClip
            } catch(_: Exception) {
                null
            }

            val uri = clip?.getItemAt(0)?.uri
            val mimeTypes = List(clip?.description?.mimeTypeCount ?: 0) {
                clip?.description?.getMimeType(it)
            }.filterNotNull()

            var textChrSeq = if(uri == null || mimeTypes.any { it.startsWith("text/") }) {
                clip?.getItemAt(0)?.coerceToText(context)
            } else {
                null
            }

            if(textChrSeq != null && textChrSeq.length > 500_000) {
                textChrSeq = null
            }

            val text = textChrSeq?.toString()
            if(text == null && uri == null) return

            val timestamp = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                clip?.description?.timestamp
            } else {
                null
            } ?: System.currentTimeMillis()

            val canSaveSensitive = context.getSetting(ClipboardHistorySaveSensitive)
            val isSensitive = clip?.description?.extras?.getBoolean(
                ClipDescription.EXTRA_IS_SENSITIVE,
                false
            ) == true
            if(isSensitive && !canSaveSensitive) return

            when {
                text != null -> importTextEntry(timestamp, text, mimeTypes)
                uri != null -> importMediaEntry(timestamp, uri, mimeTypes)
            }
        }
    }

    init {
        coroutineScope.launch {
            loadClipboard()

            withContext(Dispatchers.Main) {
                clipboardManager.addPrimaryClipChangedListener(primaryClipChangedListener)
            }

            onClipboardImportedFlow.collectLatest {
                coroutineScope.ensureActive()
                onClipboardImported(it)
            }
        }
    }

    private fun shouldImportClipboardChanges(): Boolean =
        context.getSettingBlocking(ClipboardHistoryEnabled) &&
            !context.getSettingBlocking(ClipboardIncognitoMode)

    private fun importTextEntry(timestamp: Long, rawText: String, mimeTypes: List<String>) {
        val text = ClipboardLinkPreviewFetcher.normalizedTextForClipboardImport(rawText)
        val existingEntries = clipboardHistory.filter { it.text == text }
        val preservedEntry = existingEntries.lastOrNull { it.hasRetainedPreviewState() }
            ?: existingEntries.lastOrNull()
        val isAlreadyPinned = existingEntries.any { it.pinned }
        reviveDeletedArchiveForImportedText(text)

        clipboardHistory.removeAll { it.text == text }
        val newEntry = ClipboardEntry(
            timestamp = timestamp,
            pinned = isAlreadyPinned,
            text = text,
            uri = null,
            mimeTypes = mimeTypes,
            previewText = preservedEntry?.previewText,
            previewImageFile = null,
            previewMediaFiles = preservedEntry?.previewMedia().orEmpty(),
            previewMetadata = preservedEntry?.previewMetadata,
            previewFetchStatus = preservedEntry?.previewFetchStatus
                ?: ClipboardPreviewFetchStatus.NeverAttempted,
            previewFetchLastAttemptAt = preservedEntry?.previewFetchLastAttemptAt
        )
        clipboardHistory.add(newEntry)

        if(newEntry.canAutoFetchPreview() && canRunAutomaticClipboardNetworkDownloads()) {
            fetchPreviewForEntry(text)
        }

        saveClipboard(reconcileBeforeSave = false)
    }

    private fun reviveDeletedArchiveForImportedText(text: String) {
        val archiveKey = ClipboardLinkPreviewFetcher.previewCandidateFor(text)?.archiveKey ?: return
        reviveDeletedArchiveKeys(deletedArchiveKeys - archiveKey)
    }

    private fun reviveDeletedArchiveForPreviewManifest(manifest: ClipboardLinkPreviewManifest) {
        reviveDeletedArchiveKeys(deletedArchiveKeysAfterPreviewManifest(manifest, deletedArchiveKeys))
    }

    private fun reviveDeletedArchiveKeys(updatedKeys: Set<String>) {
        if(updatedKeys == deletedArchiveKeys) return

        val revivedKeys = deletedArchiveKeys - updatedKeys
        deletedArchiveKeys.clear()
        deletedArchiveKeys.addAll(updatedKeys)
        revivedKeys.forEach(archiveTombstonesByKey::remove)
        saveArchiveTombstones(archiveTombstonesByKey.values)
    }

    private fun importScreenshotEntry(mime: String, uri: Uri) {
        if(!shouldObserveScreenshots(
                historyEnabled = context.getSettingBlocking(ClipboardHistoryEnabled),
                incognitoMode = context.getSettingBlocking(ClipboardIncognitoMode),
                saveScreenshots = context.getSettingBlocking(ClipboardSaveScreenshots),
                hasPermission = true
            )
        ) {
            return
        }

        importMediaEntry(
            timestamp = System.currentTimeMillis(),
            uri = uri,
            mimeTypes = listOf(mime),
            imagesOnly = true
        )
    }

    private fun importMediaEntry(
        timestamp: Long,
        uri: Uri,
        mimeTypes: List<String>,
        imagesOnly: Boolean = false
    ) {
        try {
            val targetMime = mimeTypes.firstOrNull {
                it.startsWith("image/") || (!imagesOnly && it.startsWith("video/"))
            }
                ?: return

            val resolver = context.contentResolver
            val stream = resolver.openInputStream(uri) ?: return
            val md = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8 * 1024)
            var totalBytes = 0L
            var bytesRead: Int

            val tempFile = File(context.cacheDir, "temp_media")
            tempFile.outputStream().use { out ->
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    totalBytes += bytesRead
                    if(totalBytes > ClipboardStoredMediaMaxBytes) {
                        tempFile.delete()
                        return
                    }
                    md.update(buffer, 0, bytesRead)
                    out.write(buffer, 0, bytesRead)
                }
            }
            stream.close()

            val md5Hex = md.digest().joinToString("") { "%02x".format(it) }
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(targetMime)
                ?: uri.lastPathSegment?.substringAfterLast('.', "")
                    ?.substringBefore('?')
                    ?.takeIf { it.isNotBlank() }
                ?: "bin"

            context.clipboardDir.mkdirs()
            val finalFile = File(context.clipboardDir, "$md5Hex.$extension")
            if(!finalFile.exists()) {
                tempFile.renameTo(finalFile)
                ClipboardUtil.generateThumbnail(finalFile, targetMime)
            } else {
                tempFile.delete()
            }
            noteClipboardMediaFileSaved(finalFile.name)

            upsertClipboardMediaEntry(
                clipboardHistory,
                ClipboardEntry(
                    timestamp = timestamp,
                    pinned = false,
                    text = null,
                    uri = null,
                    backingFile = finalFile.name,
                    sizeMb = totalBytes / (1024f * 1024f),
                    mimeTypes = listOf(targetMime)
                )
            )
        } catch(e: Exception) {
            throwIfDebug(e)
        } finally {
            saveClipboard(reconcileBeforeSave = true)
        }
    }

    private suspend fun onClipboardImported(file: File) {
        if(file != clipboardFile && file.name != clipboardFile.name) return

        loadClipboard()
        reconcileClipboardStorage()
        refreshMissingLinkPreviews(forceArchiveBackfill = true)
    }

    suspend fun reconcileClipboardStorage() = withContext(ClipboardIOContext) {
        // Enumerate the clipboard media directory once on a background thread rather
        // than issuing a File.isFile stat per entry/preview-media on the UI thread.
        // Both checks below only ever target context.clipboardDir, so membership in
        // this set is equivalent to the previous per-file .isFile checks.
        val existingMediaNames = existingClipboardMediaFileNames(context.clipboardDir)

        withContext(Dispatchers.Main) {
            val deduplicated = deduplicateClipboardEntries(clipboardHistory)
            if(deduplicated.size < clipboardHistory.size || deduplicated != clipboardHistory.toList()) {
                clipboardHistory.clear()
                clipboardHistory.addAll(deduplicated)
            }

            clipboardHistory.removeAll {
                it.backingFile != null && it.backingFile !in existingMediaNames
            }

            for(i in clipboardHistory.indices) {
                val entry = clipboardHistory[i]
                val retainedPreviewMedia = entry.previewMedia()
                    .filter { it.fileName in existingMediaNames }
                if(retainedPreviewMedia.size != entry.previewMedia().size) {
                    clipboardHistory[i] = entry.copy(
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
                }
            }
        }

        reconcileArchiveStorage()
    }

    internal fun saveClipboard(
        exiting: Boolean = false,
        reconcileBeforeSave: Boolean = true
    ): Job? {
        if(!context.isDirectBootUnlocked) return null
        if(!clipboardLoaded) {
            if(saveClipboardLoadJob?.isActive == true) return null

            val currentEntries = clipboardHistory.toList()
            saveClipboardLoadJob = coroutineScope.launch {
                loadClipboard()

                if(clipboardLoaded) {
                    clipboardHistory.addAll(currentEntries)
                    saveClipboard(
                        exiting = exiting,
                        reconcileBeforeSave = reconcileBeforeSave
                    )
                } else {
                    clipboardIOFailure.value = true
                }
            }

            return saveClipboardLoadJob
        }

        return coroutineScope.launch(context = ClipboardIOContext) {
            try {
                if(reconcileBeforeSave) reconcileClipboardStorage()

                val list = withContext(Dispatchers.Main) {
                    clipboardHistory.map { it.copy(deletedArchiveKeys = emptySet()) }
                }
                val encoded = encodeClipboardEntries(list)
                val normalizedList = decodeClipboardEntries(encoded)
                clipboardFileSwap.writeText(encoded)

                val decodedData = decodeFile(clipboardFileSwap)
                if(decodedData != normalizedList) {
                    throw Exception("Saved file data does not match expected data. Decoded: $decodedData, expected: $normalizedList")
                }

                replaceFileWithBackup(
                    swapFile = clipboardFileSwap,
                    targetFile = clipboardFile,
                    backupFile = clipboardFileBak
                )

                if(decodeFile(clipboardFile) != normalizedList) {
                    throw Exception("Saved file data does not match expected data")
                }

                clipboardIOFailure.value = false
            } catch (e: Exception) {
                clipboardIOFailure.value = true
                clipboardIOFailureReason = e.toString()
                reportError("saveClipboard", e)
            }
        }
    }

    fun deleteClipboard() {
        listOf(clipboardFile, clipboardFileSwap, clipboardFileBak).forEach {
            if(it.exists()) it.delete()
        }
    }

    fun refreshMissingLinkPreviews(
        forceArchiveBackfill: Boolean = false,
        boundedPreviewFetches: Boolean = false
    ) {
        if(context.getSetting(ClipboardIncognitoMode)) return
        if(!currentPreviewState().shouldArchivePreviews) return
        if(!canRunAutomaticClipboardNetworkDownloads()) return

        val previewFetchLimit = ClipboardStartupPreviewFetchLimit.takeIf { boundedPreviewFetches }
        var scheduledPreviewFetches = 0
        for(text in startupPreviewFetchTextSequence(clipboardHistory.toList())) {
            if(previewFetchLimit != null && scheduledPreviewFetches >= previewFetchLimit) break
            if(fetchPreviewForEntry(text)) {
                scheduledPreviewFetches += 1
            }
        }
        runLegacyArchiveBackfillIfNeeded(forceCompletedVersion = forceArchiveBackfill)
        resumeProviderArchiveDownloads()
    }

    fun retryPreviewForEntry(entry: ClipboardEntry) {
        val text = entry.text ?: return
        fetchPreviewForEntry(text, manualRetry = true)
    }

    fun canRetryPreview(entry: ClipboardEntry): Boolean =
        entry.shouldShowManualPreviewRetry() &&
            entry.previewProviderCooldown() == null

    fun isPreviewRetryBlockedByCooldown(entry: ClipboardEntry): Boolean =
        entry.shouldShowManualPreviewRetry() &&
            entry.previewProviderCooldown() != null

    fun expectedPreviewMediaCount(entry: ClipboardEntry): Int? =
        archiveForEntry(entry)
            ?.let { currentArchive(it, currentArchiveFileNames()) }
            ?.media
            ?.size
            ?.takeIf { it > entry.previewMedia().size }

    private fun runLegacyArchiveBackfillIfNeeded(forceCompletedVersion: Boolean = false) {
        if(archiveBackfillInProgress.value) return
        if(!canRunAutomaticClipboardNetworkDownloads()) return
        val incognito = context.getSetting(ClipboardIncognitoMode)
        val previewsEnabled = currentPreviewState().shouldArchivePreviews
        if(!shouldRunArchiveBackfill(
            completedVersion = context.getSetting(ClipboardArchiveBackfillCompletedVersion),
            currentVersion = ClipboardArchiveBackfillVersion,
            incognito = incognito,
            previewsEnabled = previewsEnabled,
            forceCompletedVersion = forceCompletedVersion
        )) {
            return
        }

        val requests = archiveBackfillRequests(
            entries = clipboardHistory.toList(),
            existingArchiveKeys = linkArchives.keys,
            attemptedArchiveKeys = archiveBackfillAttemptedKeys,
            deletedArchiveKeys = deletedArchiveKeys
        )
        val shouldCompleteMigration = !forceCompletedVersion &&
            context.getSetting(ClipboardArchiveBackfillCompletedVersion) < ClipboardArchiveBackfillVersion
        if(requests.isEmpty()) {
            if(shouldCompleteMigration) {
                markLegacyArchiveBackfillComplete()
            }
            return
        }

        archiveBackfillCompletionPending = false
        archiveBackfillBlockedByCooldown = false
        var scheduledAny = false
        requests.forEach { request ->
            if(fetchArchiveForEntry(request)) {
                scheduledAny = true
            } else if(request.entry.archiveBackfillMetadata()?.provider?.let { providerCooldown(it) } != null) {
                archiveBackfillBlockedByCooldown = true
            } else if(request.entry.text?.let { previewLoadingByText[it] == true } == true ||
                previewLoadingByText[request.archiveKey] == true
            ) {
                archiveBackfillBlockedByCooldown = true
            }
        }
        if(scheduledAny) {
            archiveBackfillCompletionPending = shouldCompleteMigration
        } else if(!archiveBackfillBlockedByCooldown) {
            if(shouldCompleteMigration) {
                markLegacyArchiveBackfillComplete()
            }
        }
    }

    private fun fetchArchiveForEntry(request: ClipboardArchiveBackfillRequest): Boolean {
        if(context.getSetting(ClipboardIncognitoMode)) return false
        if(!currentPreviewState().shouldArchivePreviews) return false
        if(!canRunAutomaticClipboardNetworkDownloads()) return false
        val text = request.entry.text ?: return false
        val archiveKey = request.archiveKey
        val candidate = ClipboardLinkPreviewFetcher.previewCandidateFor(text) ?: return false
        val metadata = request.entry.previewMetadata?.takeIf { it.provider == candidate.provider }?.let { legacy ->
            candidate.metadata.copy(
                sourceUrl = legacy.sourceUrl ?: candidate.metadata.sourceUrl,
                sourceId = legacy.sourceId ?: candidate.metadata.sourceId,
                title = legacy.title,
                bodyText = legacy.bodyText ?: request.entry.previewText,
                authorName = legacy.authorName,
                authorHandle = legacy.authorHandle ?: candidate.metadata.authorHandle,
                authorId = legacy.authorId,
                createdAt = legacy.createdAt,
                imageCount = legacy.imageCount,
                selectedImageIndex = legacy.selectedImageIndex ?: candidate.metadata.selectedImageIndex,
                tags = legacy.tags,
                stats = legacy.stats,
                flags = legacy.flags
            )
        } ?: candidate.metadata.copy(bodyText = request.entry.previewText)
        if(providerCooldown(metadata.provider) != null) return false
        if(previewLoadingByText[text] == true) return false
        if(previewLoadingByText[archiveKey] == true) return false
        if(!archiveBackfillAttemptedKeys.add(archiveKey)) return false

        if(metadata.archiveKey() != archiveKey) return false
        if(archiveKey in linkArchives) return false

        beginArchiveBackfillWork()
        coroutineScope.launch {
            previewLoadingByText[text] = true
            try {
                val manifestResult = withContext(ClipboardPreviewFetchContext) {
                    ClipboardLinkPreviewFetcher.fetchManifestResult(
                        candidate,
                        context.pixivSessionIdForClipboardPreviews(),
                        context.redditAccessTokenForClipboardPreviews()
                    )
                }
                setProviderCooldown(manifestResult.failure)
                if(manifestResult.failure is ClipboardPreviewFetchFailure.RateLimited) {
                    archiveBackfillAttemptedKeys.remove(archiveKey)
                    archiveBackfillBlockedByCooldown = true
                    return@launch
                }
                val manifest = manifestResult.manifest
                val attemptedAt = System.currentTimeMillis()
                val archive = if(manifest != null &&
                    (manifest.snippet != null || manifest.mediaItems.isNotEmpty() || manifest.metadata != null)
                ) {
                    createOrUpdateArchive(manifest, attemptedAt)
                } else {
                    createFallbackArchiveFromEntry(request.entry, attemptedAt)
                }

                archive
                    ?.takeIf { it.providerManifestAvailable && it.hasAutoDownloadableMedia() }
                    ?.let {
                    downloadArchiveMediaWithLoading(text, it.key)
                }
            } finally {
                previewLoadingByText.remove(text)
                finishArchiveBackfillWork()
            }
        }
        return true
    }

    private fun resumeProviderArchiveDownloads() {
        if(!canRunAutomaticClipboardNetworkDownloads()) return
        coroutineScope.launch {
            val existingArchiveFileNames = refreshArchiveFileNames()
            val archiveKeys = providerArchiveDownloadResumeKeys(
                archives = linkArchives.values.toList(),
                existingArchiveFileNames = existingArchiveFileNames,
                isRetryBlocked = ::isArchiveRetryBlockedByCooldown
            )
            archiveKeys.forEach { archiveKey ->
                linkArchives[archiveKey]?.let {
                    updateArchiveWithCurrentStorageState(it, existingArchiveFileNames)
                }
                startArchiveDownload(text = null, archiveKey = archiveKey)
            }
        }
    }

    private fun beginArchiveBackfillWork() {
        archiveBackfillRemainingCount.value += 1
        archiveBackfillInProgress.value = true
    }

    private fun finishArchiveBackfillWork() {
        archiveBackfillRemainingCount.value = (archiveBackfillRemainingCount.value - 1).coerceAtLeast(0)
        archiveBackfillInProgress.value = archiveBackfillRemainingCount.value > 0
        if(archiveBackfillCompletionPending &&
            archiveBackfillRemainingCount.value == 0 &&
            !archiveBackfillBlockedByCooldown
        ) {
            markLegacyArchiveBackfillComplete()
        }
    }

    private fun markLegacyArchiveBackfillComplete() {
        archiveBackfillCompletionPending = false
        coroutineScope.launch {
            context.setSetting(ClipboardArchiveBackfillCompletedVersion, ClipboardArchiveBackfillVersion)
        }
    }

    internal fun archiveUiSnapshot(): ClipboardArchiveUiSnapshot = clipboardArchiveUiSnapshot(
        archives = linkArchives.values.toList(),
        clipboardDir = context.clipboardDir,
        storageFileNames = currentArchiveFileNames(),
        downloadState = archiveDownloadCoordinator.snapshot(),
        loadingArchiveKeys = previewLoadingByText.filterValues { it }.keys
    )

    internal fun providerCooldown(provider: ClipboardPreviewProvider): ClipboardPreviewProviderCooldown? =
        archiveDownloadCoordinator.providerCooldown(provider)

    private fun ClipboardEntry.previewProviderCooldown(): ClipboardPreviewProviderCooldown? =
        text
            ?.let { ClipboardLinkPreviewFetcher.previewCandidateFor(it)?.provider }
            ?.let { providerCooldown(it) }

    internal fun isArchiveRetryBlockedByCooldown(archive: ClipboardLinkArchive): Boolean =
        providerCooldown(archive.provider) != null

    internal fun retryArchive(archive: ClipboardLinkArchive) {
        val retryArchive = updateArchiveWithCurrentStorageState(archive, currentArchiveFileNames())
        val forceRefetchManifest = retryArchive.canRefetchManifest()
        if(!retryArchive.hasRetryableMedia() && !forceRefetchManifest) return

        launchArchiveRetry(retryArchive, forceRefetchManifest = forceRefetchManifest)
    }

    internal fun retryArchiveMedia(item: ClipboardArchiveDownloadListItem) {
        val archive = updateArchiveWithCurrentStorageState(
            linkArchives[item.archiveKey] ?: return,
            currentArchiveFileNames()
        )
        if(!item.canRetry) return

        launchArchiveRetry(archive, sourceUrls = setOf(item.sourceUrl))
    }

    internal fun retryAllArchiveDownloads(items: List<ClipboardArchiveDownloadListItem>) {
        val existingArchiveFileNames = currentArchiveFileNames()
        items
            .filter { it.canRetry }
            .groupBy { it.archiveKey }
            .forEach { (archiveKey, archiveItems) ->
                val archive = updateArchiveWithCurrentStorageState(
                    linkArchives[archiveKey] ?: return@forEach,
                    existingArchiveFileNames
                )
                launchArchiveRetry(
                    archive = archive,
                    sourceUrls = archiveItems.map { it.sourceUrl }.toSet()
                )
            }
    }

    private fun launchArchiveRetry(
        archive: ClipboardLinkArchive,
        sourceUrls: Set<String>? = null,
        includeSkippedTooLarge: Boolean = true,
        forceRefetchManifest: Boolean = false
    ) {
        if(isArchiveDownloadActive(archive.key)) {
            queueArchiveDownloadSourceUrls(archive, sourceUrls)
            return
        }
        if(isArchiveRetryBlockedByCooldown(archive)) return

        launchArchiveDownload(archive.key) {
            if(archive.providerManifestAvailable && !forceRefetchManifest) {
                downloadArchiveMedia(
                    text = null,
                    archiveKey = archive.key,
                    includeSkippedTooLarge = includeSkippedTooLarge,
                    onlySourceUrls = sourceUrls
                )
            } else {
                refetchArchiveManifest(archive)
            }
        }
    }

    private fun queueArchiveDownloadSourceUrls(
        archive: ClipboardLinkArchive,
        sourceUrls: Set<String>?
    ) {
        if(sourceUrls?.isEmpty() == true) return
        val retryableSourceUrls = archive.retryableMedia().map { it.sourceUrl }.toSet()
        val queuedSourceUrls = sourceUrls?.intersect(retryableSourceUrls) ?: retryableSourceUrls
        if(queuedSourceUrls.isEmpty()) return
        archiveDownloadCoordinator.queueSourceUrls(archive.key, queuedSourceUrls)
    }

    private fun currentArchive(
        archive: ClipboardLinkArchive,
        existingArchiveFileNames: Set<String>
    ): ClipboardLinkArchive =
        archive.withMissingArchiveFilesMarked(existingArchiveFileNames, now = archive.updatedAtEpochMs)

    private fun updateArchiveWithCurrentStorageState(
        archive: ClipboardLinkArchive,
        existingArchiveFileNames: Set<String>
    ): ClipboardLinkArchive {
        val updated = archive.withMissingArchiveFilesMarked(existingArchiveFileNames)
        if(updated != archive) {
            linkArchives[updated.key] = updated
            updateEntriesPreviewFromArchiveNow(updated)
            queueArchiveSave(updated)
            saveClipboard(reconcileBeforeSave = false)
        }
        return updated
    }

    private fun scanArchiveFileNames(): Set<String> =
        existingClipboardMediaFileNames(context.clipboardDir, context.clipboardArchiveDir)

    private suspend fun refreshArchiveFileNames(): Set<String> {
        val fileNames = withContext(ClipboardIOContext) {
            scanArchiveFileNames()
        }
        withContext(Dispatchers.Main) {
            applyArchiveFileNames(fileNames)
        }
        return fileNames
    }

    private fun applyArchiveFileNames(fileNames: Set<String>) {
        clipboardStorageSnapshot.value = ClipboardStorageSnapshot(fileNames.toSet())
    }

    private fun currentArchiveFileNames(): Set<String> = clipboardStorageSnapshot.value.fileNames

    private fun noteClipboardMediaFileSaved(fileName: String) {
        val thumbnailName = ClipboardUtil.thumbnailForName(fileName)
        val savedFileNames = buildSet {
            add(fileName)
            if(File(context.clipboardDir, thumbnailName).isFile) {
                add(thumbnailName)
            }
        }
        clipboardStorageSnapshot.value = ClipboardStorageSnapshot(
            clipboardStorageSnapshot.value.fileNames + savedFileNames
        )
    }

    internal fun stopArchiveDownload(item: ClipboardArchiveDownloadListItem) {
        stopArchiveDownload(item.archiveKey, item.sourceUrl, item.sourceIndex)
    }

    private fun stopArchiveDownload(
        archiveKey: String,
        sourceUrl: String? = null,
        sourceIndex: Int? = null
    ) {
        val progress = archiveDownloadCoordinator.progress(archiveKey)
        if(progress == null && !isArchiveDownloadActive(archiveKey)) return
        if(sourceUrl == null) {
            archiveDownloadCoordinator.clearQueuedSourceUrls(archiveKey)
        } else {
            archiveDownloadCoordinator.removeQueuedSourceUrl(archiveKey, sourceUrl)
        }
        previewLoadingByText.remove(archiveKey)
        archiveDownloadCoordinator.cancelActive(archiveKey)
        val stoppedSourceUrl = sourceUrl ?: progress?.sourceUrl ?: return
        discardPendingArchiveSave(archiveKey)
        val stoppedSourceIndex = sourceIndex ?: progress?.sourceIndex
        val attemptedAt = System.currentTimeMillis()
        val archive = updateArchiveWithCurrentStorageState(
            linkArchives[archiveKey] ?: return,
            currentArchiveFileNames()
        )
        val updated = archive.copy(
            media = archive.media.map {
                if(it.sourceUrl == stoppedSourceUrl || stoppedSourceIndex?.let { index -> it.sourceIndex == index } == true) {
                    it.copy(
                        status = if(it.status == ClipboardArchiveMediaStatus.Saved) it.status else ClipboardArchiveMediaStatus.Failed,
                        lastAttemptAtEpochMs = attemptedAt,
                        failureDetail = "Stopped by user"
                    )
                } else {
                    it
                }
            },
            updatedAtEpochMs = attemptedAt
        ).withNormalizedArchiveMedia()
        linkArchives[archiveKey] = updated
        updateEntriesPreviewFromArchiveNow(updated)
        queueArchiveSave(updated)
        saveClipboard(reconcileBeforeSave = true)
    }

    internal fun stopAllArchiveDownloads() {
        val downloadState = archiveDownloadCoordinator.snapshot()
        (downloadState.progressByArchiveKey.keys + downloadState.activeArchiveKeys + previewLoadingByText.keys.filter { it in linkArchives })
            .toList()
            .forEach { stopArchiveDownload(it) }
    }

    private suspend fun setProviderCooldown(cooldown: ClipboardPreviewProviderCooldown) {
        withContext(Dispatchers.Main) {
            archiveDownloadCoordinator.setProviderCooldown(cooldown)
        }
    }

    private suspend fun setProviderCooldown(failure: ClipboardPreviewFetchFailure?) {
        when (failure) {
            is ClipboardPreviewFetchFailure.RateLimited -> setProviderCooldown(
                ClipboardPreviewProviderCooldown(
                    provider = failure.provider,
                    retryAfterEpochMs = failure.retryAfterEpochMs,
                    detail = failure.detail
                )
            )
            is ClipboardPreviewFetchFailure.Unavailable -> Unit
            null -> Unit
        }
    }

    internal fun deleteArchive(archive: ClipboardLinkArchive) {
        deleteArchiveByKey(archive.key)
    }

    internal fun deleteArchiveDownload(item: ClipboardArchiveDownloadListItem) {
        deleteArchiveDownloadMedia(item)
    }

    fun deleteArchiveForEntry(entry: ClipboardEntry) {
        deleteArchiveByKey(archiveForEntry(entry)?.key ?: return)
    }

    private fun deleteArchiveByKey(
        archiveKey: String,
        existingMediaNames: Set<String> = currentArchiveFileNames()
    ) {
        val archive = linkArchives[archiveKey]
        cancelArchiveDownloadState(archiveKey)
        linkArchives.remove(archiveKey)
        tombstoneArchiveKey(archiveKey, reason = "user")
        val archivedFileNames = archive?.media.orEmpty().mapNotNull { it.fileName }.toSet()

        for(i in clipboardHistory.indices) {
            val current = clipboardHistory[i]
            if(current.matchesDeletedArchiveKey(archiveKey)) {
                val retainedPreviewMedia = retainedPreviewMediaAfterArchiveDelete(
                    entry = current,
                    archivedFileNames = archivedFileNames,
                    existingMediaNames = existingMediaNames
                )
                clipboardHistory[i] = current.copy(
                    previewText = retainedPreviewTextAfterArchiveDelete(
                        entry = current,
                        retainedPreviewMedia = retainedPreviewMedia
                    ),
                    previewImageFile = null,
                    previewMediaFiles = retainedPreviewMedia,
                    previewMetadata = null,
                    previewFetchStatus = ClipboardPreviewFetchStatus.Success,
                    previewFetchLastAttemptAt = System.currentTimeMillis(),
                    previewFetchFailureDetail = null,
                    deletedArchiveKeys = emptySet()
                )
            }
        }

        // The in-memory/snapshot mutations above run on the caller's (main) thread so the
        // UI updates immediately; the filesystem work runs off the main thread.
        val tombstones = archiveTombstonesByKey.values.toList()
        val entriesSnapshot = clipboardHistory.toList()
        val archivesSnapshot = linkArchives.values.toList()
        coroutineScope.launch(ClipboardIOContext) {
            saveArchiveTombstones(tombstones)
            deleteArchiveMetadataFile(archiveKey)
            archivedFileNames.forEach { fileName ->
                File(context.clipboardArchiveDir, fileName).delete()
                File(context.clipboardArchiveDir, ClipboardUtil.thumbnailForName(fileName)).delete()
            }
            orphanedSharedArchiveFileNamesAfterArchiveDelete(archivedFileNames, entriesSnapshot)
                .forEach { fileName -> File(context.clipboardDir, fileName).delete() }
            refreshArchiveFileNames()
            saveArchives(archivesSnapshot)
        }
        saveClipboard(reconcileBeforeSave = true)
    }

    // Only updates in-memory tombstone state. Callers are responsible for persisting via
    // saveArchiveTombstones (off the main thread).
    private fun tombstoneArchiveKey(archiveKey: String, reason: String?) {
        deletedArchiveKeys.add(archiveKey)
        archiveTombstonesByKey.putIfAbsent(
            archiveKey,
            ClipboardArchiveTombstone(
                key = archiveKey,
                deletedAtEpochMs = System.currentTimeMillis(),
                reason = reason
            )
        )
    }

    private fun deleteArchiveDownloadMedia(item: ClipboardArchiveDownloadListItem) {
        val archive = updateArchiveWithCurrentStorageState(
            linkArchives[item.archiveKey] ?: return,
            currentArchiveFileNames()
        )
        val remainingMedia = archive.media.filterNot {
            it.sourceIndex == item.sourceIndex
        }
        if(remainingMedia.isEmpty()) {
            deleteArchiveByKey(item.archiveKey)
            return
        }

        val updated = archive.copy(
            media = remainingMedia,
            deletedMediaKeys = archive.deletedMediaKeys + ClipboardArchiveMedia(
                sourceUrl = item.sourceUrl,
                sourceIndex = item.sourceIndex
            ).archiveMediaKey(),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        linkArchives[item.archiveKey] = updated
        archiveDownloadCoordinator.removeQueuedSourceUrl(item.archiveKey, item.sourceUrl)
        updateEntriesPreviewFromArchiveNow(updated)
        queueArchiveSave(updated)
        saveClipboard(reconcileBeforeSave = true)
    }

    private fun cancelArchiveDownloadState(archiveKey: String) {
        previewLoadingByText.remove(archiveKey)
        archiveDownloadCoordinator.cancel(archiveKey)
    }

    fun onPaste(item: ClipboardEntry) {
        val itemPos = clipboardHistory.indexOf(item).coerceAtLeast(0)
        clipboardHistory.removeAll { it == item }
        clipboardHistory.add(itemPos, item.copy(timestamp = System.currentTimeMillis()))
        saveClipboard(reconcileBeforeSave = true)
    }

    fun onTogglePin(item: ClipboardEntry) {
        val itemPos = clipboardHistory.indexOf(item).coerceAtLeast(0)
        val targetPos = if(context.getSetting(ClipboardShowPinnedOnTop)) {
            clipboardHistory.size - 1
        } else {
            itemPos
        }

        replaceEntries(
            clipboardHistory.toMutableList().apply {
                removeAll { it == item }
                add(
                    targetPos.coerceIn(0, size),
                    item.copy(
                        pinned = !item.pinned,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        )
        saveClipboard(reconcileBeforeSave = true)
    }

    fun onRemove(item: ClipboardEntry) {
        removeAll(listOf(item))
    }

    fun removeAll(items: Collection<ClipboardEntry>) {
        if(items.isEmpty()) return

        val itemsToRemove = items.toList()
        val archiveKeysToDelete = archiveKeysOnlyReferencedBy(itemsToRemove)
        applyEntryMutations(itemsToRemove) { null }

        coroutineScope.launch {
            clearPrimaryClipIfNeeded(itemsToRemove)

            if(archiveKeysToDelete.isNotEmpty()) {
                val existingMediaNames = withContext(ClipboardIOContext) {
                    existingClipboardMediaFileNames(context.clipboardDir)
                }
                archiveKeysToDelete.forEach { deleteArchiveByKey(it, existingMediaNames) }
            }
        }
    }

    fun setPinned(items: Collection<ClipboardEntry>, pinned: Boolean) {
        if(items.isEmpty()) return

        val now = System.currentTimeMillis()
        applyEntryMutations(items) { entry ->
            if(entry.pinned == pinned) {
                entry
            } else {
                entry.copy(
                    pinned = pinned,
                    timestamp = now
                )
            }
        }
    }

    override suspend fun cleanUp() {
        flushPendingArchiveSavesOnIo()
        saveClipboard(reconcileBeforeSave = true)?.join()
        val archivesSnapshot = withContext(Dispatchers.Main) { linkArchives.values.toList() }
        withContext(NonCancellable + ClipboardIOContext) {
            saveArchives(archivesSnapshot)
        }
    }

    override fun close() {
        // No-op: this manager is a process-wide singleton, so it must NOT release its
        // clipboard-change listener or screenshot observer when an individual IME
        // service instance is destroyed (UixManager.onDestroy calls close() on every
        // teardown). The listeners live for the process lifetime; the singleton is only
        // reclaimed when the process itself dies.
    }

    private fun currentPreviewState(): ClipboardPreviewState =
        previewState(
            linkPreviewsEnabled = context.getSetting(ClipboardLinkPreviewsEnabled),
            storedEmbedDisplayMode = context.getSetting(ClipboardEmbedDisplayModeSetting)
        )

    private fun canRunAutomaticClipboardNetworkDownloads(): Boolean =
        shouldAllowClipboardNetworkDownload(
            limitMobileData = context.getSetting(ClipboardLimitDownloadsOnMobileData),
            networkState = context.currentClipboardNetworkState(),
            manualRetry = false
        )

    private fun queuePreviewSave(
        delayMillis: Long = 350L,
        reconcileBeforeSave: Boolean = false
    ) {
        scheduledPreviewSaveJob?.cancel()
        scheduledPreviewSaveJob = coroutineScope.launch {
            delay(delayMillis)
            saveClipboard(reconcileBeforeSave = reconcileBeforeSave)
        }
    }

    private suspend fun flushPreviewSave(reconcileBeforeSave: Boolean = false) {
        scheduledPreviewSaveJob?.cancel()
        scheduledPreviewSaveJob = null
        withContext(NonCancellable) {
            saveClipboard(reconcileBeforeSave = reconcileBeforeSave)?.join()
        }
    }

    private suspend fun publishClipboardLoaded(
        data: List<ClipboardEntry>,
        archives: List<ClipboardLinkArchive>,
        tombstones: List<ClipboardArchiveTombstone>,
        archiveFileNames: Set<String>
    ) = withContext(Dispatchers.Main) {
        clipboardHistory.clear()
        clipboardHistory.addAll(deduplicateClipboardEntries(data))
        linkArchives.clear()
        linkArchives.putAll(archives.associateBy { it.key })
        deletedArchiveKeys.clear()
        deletedArchiveKeys.addAll(tombstones.map { it.key })
        archiveTombstonesByKey.clear()
        archiveTombstonesByKey.putAll(tombstones.associateBy { it.key })
        applyArchiveFileNames(archiveFileNames)
        clipboardLoaded = true
        clipboardIOFailureReason = ""
        clipboardIOFailure.value = false
    }

    private suspend fun recoverArchivesFromLocalPreviewEntries() {
        val (entriesSnapshot, existingArchiveKeys, deletedArchiveKeysSnapshot) = withContext(Dispatchers.Main) {
            Triple(
                clipboardHistory.toList(),
                linkArchives.keys.toSet(),
                deletedArchiveKeys.toSet()
            )
        }
        val recoveredArchives = clipboardArchivesFromLocalPreviewEntries(
            entries = entriesSnapshot,
            clipboardDir = context.clipboardDir,
            existingArchiveKeys = existingArchiveKeys,
            deletedArchiveKeys = deletedArchiveKeysSnapshot
        )
        if(recoveredArchives.isEmpty()) return

        val currentArchives = withContext(Dispatchers.Main) { linkArchives.values.toList() }
        val mergedArchives = mergeClipboardArchives(
            currentArchives = currentArchives,
            importedArchives = recoveredArchives
        )
        val mergedByKey = mergedArchives.associateBy { it.key }
        val currentByKey = currentArchives.associateBy { it.key }
        val changedArchives = mergedByKey.filter { (key, archive) ->
            currentByKey[key] != archive
        }.values
        if(changedArchives.isEmpty()) return

        withContext(Dispatchers.Main) {
            linkArchives.clear()
            linkArchives.putAll(mergedByKey)
        }
        changedArchives.forEach(::saveArchive)
        refreshArchiveFileNames()
    }

    private suspend fun publishClipboardLoadFailure(reason: String) = withContext(Dispatchers.Main) {
        clipboardIOFailureReason = reason
        clipboardIOFailure.value = true
    }

    private fun reportError(during: String, e: Exception) {
        BugViewerState.pushBug(BugInfo("ClipboardHistoryManager", """
Clipboard IO error during $during

Cause: ${e.message}

Stack trace: ${e.stackTrace.map { it.toString() }}

Main: ${describeClipboardStorageFile("main", clipboardFile)}
Backup: ${describeClipboardStorageFile("backup", clipboardFileBak)}
Swap: ${describeClipboardStorageFile("swap", clipboardFileSwap)}
"""))
    }

    private suspend fun loadClipboard() = loadMutex.withLock {
        loadClipboardLocked()
    }

    private suspend fun loadClipboardLocked() = withContext(ClipboardIOContext) {
        if(!context.isDirectBootUnlocked) {
            publishClipboardLoadFailure("Direct Boot not unlocked")
            return@withContext
        }

        val clipboardSetting = context.getUnlockedSetting(ClipboardHistoryEnabled)
        if(clipboardSetting == null) {
            publishClipboardLoadFailure("Settings not unlocked")
            return@withContext
        }

        try {
            val loadedEntries = when {
                clipboardSetting == false -> {
                    deleteClipboard()
                    emptyList()
                }

                clipboardFile.exists() -> {
                    try {
                        decodeFile(clipboardFile)
                    } catch(e: Exception) {
                        reportError("loadClipboard main, trying bak", e)
                        if(clipboardFileBak.exists()) {
                            decodeFile(clipboardFileBak)
                        } else {
                            throw e
                        }
                    }
                }

                else -> listOf(DefaultClipboardEntry)
            }

            val storedArchives = archiveStore.load()
            val loadedTombstones = storedArchives.tombstones
            val migratedTombstones = archiveTombstonesForEntries(loadedTombstones, loadedEntries)
            if(migratedTombstones != loadedTombstones) {
                saveArchiveTombstones(migratedTombstones)
            }

            val tombstoneKeys = archiveTombstoneKeys(migratedTombstones)
            val activeEntries = clearEntryArchiveTombstones(loadedEntries)
            val loadedArchives = filterDeletedClipboardArchives(
                archives = storedArchives.archives,
                deletedArchiveKeys = tombstoneKeys
            )
            val archiveFileNames = scanArchiveFileNames()

            publishClipboardLoaded(
                activeEntries,
                loadedArchives,
                migratedTombstones,
                archiveFileNames
            )
            recoverArchivesFromLocalPreviewEntries()
            // Reconcile once, then persist the already-reconciled list. Previously this
            // saved with reconcileBeforeSave=true AND called reconcile again below, running
            // the (now off-main) reconcile twice on first-run/migration loads.
            reconcileClipboardStorage()
            if(activeEntries != loadedEntries) {
                saveClipboard(reconcileBeforeSave = false)
            }
        } catch (e: Exception) {
            publishClipboardLoadFailure("Exception: ${e.message}")
            reportError("loadClipboard", e)
        }
    }

    private fun decodeFile(file: File): List<ClipboardEntry> =
        decodeClipboardEntries(file.readText())

    private fun fetchPreviewForEntry(text: String, manualRetry: Boolean = false): Boolean {
        val request = previewFetchRequest(text, manualRetry) ?: return false
        if(providerCooldown(request.candidate.provider) != null) return false

        coroutineScope.launch {
            previewLoadingByText[request.text] = true
            try {
                var attempt = 0
                var lastFetchedManifest: ClipboardLinkPreviewManifest? = null
                var lastFailureDetail: String? = null
                var unavailableRefresh = false

                while (attempt < request.maxAttempts) {
                    val manifestResult = withContext(ClipboardPreviewFetchContext) {
                        ClipboardLinkPreviewFetcher.fetchManifestResult(
                            request.candidate,
                            context.pixivSessionIdForClipboardPreviews(),
                            context.redditAccessTokenForClipboardPreviews()
                        )
                    }
                    setProviderCooldown(manifestResult.failure)
                    val manifest = manifestResult.manifest
                    lastFailureDetail = manifestResult.failureDetail ?: lastFailureDetail
                    lastFetchedManifest = manifest ?: lastFetchedManifest
                    if(manifestResult.failure is ClipboardPreviewFetchFailure.RateLimited) {
                        break
                    }
                    if(manifestResult.failure is ClipboardPreviewFetchFailure.Unavailable) {
                        unavailableRefresh = true
                        break
                    }

                    if(manifest != null && (manifest.snippet != null || manifest.mediaItems.isNotEmpty() || manifest.metadata != null)) {
                        val attemptedAt = System.currentTimeMillis()
                        withContext(Dispatchers.Main) {
                            reviveDeletedArchiveForPreviewManifest(manifest)
                        }
                        val archive = createOrUpdateArchive(manifest, attemptedAt)
                        val initialMedia = archive?.savedPreviewMedia().orEmpty()
                        val updated = updateLatestTextEntry(request.text) { current ->
                            current.copy(
                                previewText = manifest.snippet,
                                previewImageFile = null,
                                previewMediaFiles = initialMedia,
                                previewMetadata = manifest.metadata ?: current.previewMetadata,
                                previewFetchStatus = ClipboardPreviewFetchStatus.Success,
                                previewFetchLastAttemptAt = attemptedAt,
                                previewFetchFailureDetail = null,
                                deletedArchiveKeys = emptySet()
                            )
                        }

                        if(updated) {
                            queuePreviewSave()
                        }

                        archive
                            ?.takeIf {
                                it.hasAutoDownloadableMedia() &&
                                    (request.manualRetry || canRunAutomaticClipboardNetworkDownloads())
                            }
                            ?.let {
                            downloadArchiveMediaWithLoading(request.text, it.key)
                        }
                        return@launch
                    }

                    attempt++
                    if(attempt < request.maxAttempts) {
                        delay(1500L * attempt)
                    }
                }

                val attemptedAt = System.currentTimeMillis()
                val retryArchiveKey = if(manualRetry && !unavailableRefresh) {
                    withContext(Dispatchers.Main) {
                        val existingArchiveFileNames = currentArchiveFileNames()
                        clipboardHistory.lastOrNull { it.text == request.text }
                            ?.let { archiveForEntry(it) }
                            ?.let { updateArchiveWithCurrentStorageState(it, existingArchiveFileNames) }
                            ?.takeIf { it.hasRetryableMedia() }
                            ?.key
                    }
                } else {
                    null
                }
                if(retryArchiveKey != null) {
                    downloadArchiveMediaWithLoading(request.text, retryArchiveKey)
                    return@launch
                }

                val updated = updateLatestTextEntry(request.text) { current ->
                    current.copy(
                        previewMetadata = lastFetchedManifest?.metadata ?: current.previewMetadata,
                        previewFetchStatus = ClipboardPreviewFetchStatus.Failed,
                        previewFetchLastAttemptAt = attemptedAt,
                        previewFetchFailureDetail = lastFailureDetail
                    )
                }

                if(updated) {
                    queuePreviewSave()
                }
            } finally {
                previewLoadingByText.remove(request.text)
            }
        }
        return true
    }

    private fun previewFetchRequest(
        text: String,
        manualRetry: Boolean
    ): ClipboardPreviewFetchRequest? {
        if(context.getSetting(ClipboardIncognitoMode)) return null

        val previewState = currentPreviewState()
        if(!previewState.linkPreviewsEnabled) return null
        if(!manualRetry && !canRunAutomaticClipboardNetworkDownloads()) return null
        val candidate = ClipboardLinkPreviewFetcher.previewCandidateFor(text) ?: return null
        if(previewLoadingByText[text] == true) return null

        val entry = clipboardHistory.lastOrNull { it.text == text } ?: return null
        val archiveKey = candidate.archiveKey
        if(archiveKey != null && archiveKey in deletedArchiveKeys) return null
        if(manualRetry) {
            if(!canRetryPreview(entry)) return null
        } else if(!entry.canAutoFetchPreview()) {
            return null
        }

        return ClipboardPreviewFetchRequest(
            text = text,
            candidate = candidate,
            maxAttempts = if(candidate.prefersImagePreview) 3 else 1,
            manualRetry = manualRetry
        )
    }

    private suspend fun updateLatestTextEntry(
        text: String,
        transform: (ClipboardEntry) -> ClipboardEntry
    ): Boolean = withContext(Dispatchers.Main) {
        val index = clipboardHistory.indexOfLast { it.text == text }
        if(index == -1) return@withContext false

        val current = clipboardHistory[index]
        val updated = transform(current)
        if(updated == current) return@withContext false

        clipboardHistory[index] = updated
        true
    }

    private fun applyEntryMutations(
        items: Collection<ClipboardEntry>,
        transform: (ClipboardEntry) -> ClipboardEntry?
    ) {
        val itemKeys = items.map { it.selectionKey() }.toSet()
        val updatedEntries = buildList {
            clipboardHistory.forEach { entry ->
                if(entry.selectionKey() !in itemKeys) {
                    add(entry)
                } else {
                    transform(entry)?.let(::add)
                }
            }
        }

        replaceEntries(updatedEntries)
        saveClipboard()
    }

    private fun archiveKeysOnlyReferencedBy(items: Collection<ClipboardEntry>): List<String> {
        val itemKeys = items.map { it.selectionKey() }.toSet()
        return items
            .mapNotNull { it.archiveBackfillKey() ?: it.previewMetadata?.archiveKey() }
            .distinct()
            .filter { archiveKey ->
                clipboardHistory.none { entry ->
                    entry.selectionKey() !in itemKeys && entry.matchesDeletedArchiveKey(archiveKey)
                }
            }
    }

    private fun replaceEntries(updatedEntries: List<ClipboardEntry>) {
        clipboardHistory.clear()
        clipboardHistory.addAll(updatedEntries)
    }

    private suspend fun clearPrimaryClipIfNeeded(items: Collection<ClipboardEntry>) {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        // coerceToText reads the system primary clip; for a content:// clip it performs a
        // binder read into the source app's ContentProvider, which can block for seconds
        // (up to the ANR window). Keep it off the main thread.
        val currentText = withContext(Dispatchers.IO) {
            try {
                clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            } catch(_: Exception) {
                null
            }
        }

        if(currentText != null && items.any { it.text == currentText }) {
            clipboardManager.clearPrimaryClip()
            QuickClip.markQuickClipDismissed()
        }
    }

    private fun createOrUpdateArchive(
        manifest: ClipboardLinkPreviewManifest,
        now: Long
    ): ClipboardLinkArchive? {
        val archiveKey = manifest.archiveKey() ?: return null
        if(archiveKey in deletedArchiveKeys) return null
        val current = linkArchives[archiveKey]?.let {
            updateArchiveWithCurrentStorageState(it, currentArchiveFileNames())
        }
        val updated = reduceArchive(
            archive = current,
            event = ClipboardArchiveEvent.ManifestSeen(manifest, now)
        ) ?: return null
        linkArchives[updated.key] = updated
        queueArchiveSave(updated)
        manifest.referencedManifests.forEach { referencedManifest ->
            val referencedArchive = createOrUpdateArchive(referencedManifest, now)
            if(referencedArchive?.hasAutoDownloadableMedia() == true) {
                startArchiveDownload(text = null, archiveKey = referencedArchive.key)
            }
        }
        return updated
    }

    private suspend fun createFallbackArchiveFromEntry(
        entry: ClipboardEntry,
        now: Long
    ): ClipboardLinkArchive? {
        val metadata = entry.archiveBackfillMetadata() ?: return null
        val key = metadata.archiveKey() ?: return null
        if(key in deletedArchiveKeys) return null
        if(linkArchives[key] != null) return linkArchives[key]

        val savedMedia = withContext(ClipboardIOContext) {
            copyLegacyPreviewMediaToArchive(
                entry = entry,
                metadata = metadata,
                clipboardDir = context.clipboardDir,
                legacyArchiveDir = context.clipboardArchiveDir,
                now = now
            )
        }
        val archive = newFallbackArchiveFromEntry(
            entry = entry,
            metadata = metadata,
            savedMedia = savedMedia,
            now = now
        ) ?: return null

        if(savedMedia.isNotEmpty()) {
            refreshArchiveFileNames()
        }
        linkArchives[archive.key] = archive
        queueArchiveSave(archive)
        return archive
    }

    private suspend fun refetchArchiveManifest(archive: ClipboardLinkArchive) {
        providerCooldown(archive.provider)?.let { return }
        val manifestResult = withContext(ClipboardPreviewFetchContext) {
            ClipboardLinkPreviewFetcher.fetchManifestResult(
                archive.sourceUrl,
                context.pixivSessionIdForClipboardPreviews(),
                context.redditAccessTokenForClipboardPreviews()
            )
        }
        setProviderCooldown(manifestResult.failure)
        if(manifestResult.failure is ClipboardPreviewFetchFailure.Unavailable) {
            markArchiveRefreshUnavailable(archive.key, manifestResult.failure.detail)
            return
        }
        val manifest = manifestResult.manifest ?: return
        if(manifest.snippet == null && manifest.mediaItems.isEmpty() && manifest.metadata == null) return

        val attemptedAt = System.currentTimeMillis()
        val updatedArchive = createOrUpdateArchive(manifest, attemptedAt) ?: return
        if(updatedArchive.hasAutoDownloadableMedia()) {
            downloadArchiveMedia(text = null, archiveKey = updatedArchive.key)
        }
    }

    private suspend fun markArchiveRefreshUnavailable(
        archiveKey: String,
        detail: String
    ) {
        val attemptedAt = System.currentTimeMillis()
        val sourceUrl = withContext(Dispatchers.Main) {
            val archive = updateArchiveWithCurrentStorageState(
                linkArchives[archiveKey] ?: return@withContext null,
                currentArchiveFileNames()
            )
            archive.retryableMedia().firstOrNull()?.sourceUrl
                ?: archive.media.firstOrNull { it.status != ClipboardArchiveMediaStatus.Saved }?.sourceUrl
                ?: archive.media.firstOrNull()?.sourceUrl
        } ?: return
        val updated = reduceArchiveMedia(
            archiveKey = archiveKey,
            event = ClipboardArchiveEvent.MediaDownloadFailed(
                sourceUrl = sourceUrl,
                now = attemptedAt,
                failureDetail = detail
            )
        ) ?: return
        queueArchiveSave(updated)
    }

    private fun startArchiveDownload(text: String?, archiveKey: String) {
        launchArchiveDownload(archiveKey) {
            downloadArchiveMedia(text, archiveKey)
        }
    }

    private suspend fun downloadArchiveMediaWithLoading(text: String?, archiveKey: String) {
        coroutineContext[Job]?.let { archiveDownloadCoordinator.registerCurrentJob(archiveKey, it) }
        try {
            withArchiveLoading(archiveKey) {
                downloadArchiveMedia(text, archiveKey)
            }
        } finally {
            flushArchiveSave(archiveKey)
            flushPreviewSave(reconcileBeforeSave = false)
            archiveDownloadCoordinator.finishRegisteredJob(archiveKey)
            launchQueuedArchiveDownloadIfNeeded(archiveKey)
        }
    }

    private fun launchArchiveDownload(
        archiveKey: String,
        block: suspend () -> Unit
    ) {
        if(isArchiveDownloadActive(archiveKey)) return
        archiveDownloadCoordinator.launch(
            archiveKey = archiveKey,
            block = { withArchiveLoading(archiveKey, block) },
            onFinished = {
                flushArchiveSave(archiveKey)
                flushPreviewSave(reconcileBeforeSave = false)
                launchQueuedArchiveDownloadIfNeeded(archiveKey)
            }
        )
    }

    private fun launchQueuedArchiveDownloadIfNeeded(archiveKey: String) {
        val queuedSourceUrls = archiveDownloadCoordinator.queuedSourceUrls(archiveKey)
        if(queuedSourceUrls.isEmpty()) return
        val archive = linkArchives[archiveKey]?.let {
            updateArchiveWithCurrentStorageState(it, currentArchiveFileNames())
        } ?: return
        val retryableQueuedSourceUrls = retryableQueuedArchiveSourceUrls(archive, queuedSourceUrls)
        if(retryableQueuedSourceUrls.isEmpty()) {
            archiveDownloadCoordinator.clearQueuedSourceUrls(archiveKey)
            return
        }
        if(retryableQueuedSourceUrls.size != queuedSourceUrls.size) {
            archiveDownloadCoordinator.setQueuedSourceUrls(archiveKey, retryableQueuedSourceUrls)
        }
        launchArchiveRetry(archive, sourceUrls = retryableQueuedSourceUrls)
    }

    private fun isArchiveDownloadActive(archiveKey: String): Boolean =
        previewLoadingByText[archiveKey] == true ||
            archiveDownloadCoordinator.isActive(archiveKey)

    private suspend fun withArchiveLoading(
        archiveKey: String,
        block: suspend () -> Unit
    ) {
        val started = withContext(Dispatchers.Main) {
            if(previewLoadingByText[archiveKey] == true) {
                false
            } else {
                previewLoadingByText[archiveKey] = true
                true
            }
        }
        if(!started) return

        try {
            block()
        } finally {
            withContext(Dispatchers.Main) {
                previewLoadingByText.remove(archiveKey)
                archiveDownloadCoordinator.clearProgress(archiveKey)
            }
        }
    }

    private suspend fun downloadArchiveMedia(
        text: String?,
        archiveKey: String,
        includeSkippedTooLarge: Boolean = false,
        onlySourceUrls: Set<String>? = null
    ) {
        val attemptedSourceUrls = mutableSetOf<String>()
        withContext(ClipboardPreviewFetchContext) {
            while(true) {
                val target = withContext(Dispatchers.Main) {
                    val archive = linkArchives[archiveKey]?.let {
                        updateArchiveWithCurrentStorageState(it, currentArchiveFileNames())
                    }
                    if(archive?.provider?.let { providerCooldown(it) } != null) return@withContext null
                    val queuedSourceUrls = archiveDownloadCoordinator.queuedSourceUrls(archiveKey)
                    val targetSourceUrls = if(onlySourceUrls == null && queuedSourceUrls.isEmpty()) {
                        null
                    } else {
                        onlySourceUrls.orEmpty() + queuedSourceUrls
                    }
                    val media = archive?.let {
                        if(includeSkippedTooLarge || queuedSourceUrls.isNotEmpty()) {
                            it.retryableMedia()
                        } else {
                            it.autoDownloadableMedia()
                        }
                    }?.firstOrNull {
                        (targetSourceUrls == null || it.sourceUrl in targetSourceUrls) &&
                            attemptedSourceUrls.add(it.sourceUrl)
                    }
                    if(media == null && queuedSourceUrls.isNotEmpty() && targetSourceUrls != null) {
                        val retryableSourceUrls = archive?.let {
                            retryableQueuedArchiveSourceUrls(it, queuedSourceUrls)
                        }.orEmpty()
                        val remaining = queuedSourceUrls.intersect(retryableSourceUrls)
                        if(remaining.isEmpty()) {
                            archiveDownloadCoordinator.clearQueuedSourceUrls(archiveKey)
                        } else if(remaining.size != queuedSourceUrls.size) {
                            archiveDownloadCoordinator.setQueuedSourceUrls(archiveKey, remaining)
                        }
                    } else if(media != null && media.sourceUrl in queuedSourceUrls) {
                        val remaining = queuedSourceUrls - media.sourceUrl
                        archiveDownloadCoordinator.setQueuedSourceUrls(archiveKey, remaining)
                    }
                    media?.let { ClipboardArchiveMediaDownloadTarget(archive, it) }
                } ?: break
                val media = target.media

                val attemptedAt = System.currentTimeMillis()
                publishArchiveDownloadProgressNow(
                    media = media,
                    completedBytes = 0L,
                    totalBytes = null,
                    archive = target.archive
                )
                var lastProgressAt = 0L
                var lastProgressBytes = 0L
                var currentCompletedBytes = 0L
                var currentTotalBytes: Long? = null
                val result = ClipboardLinkPreviewFetcher.cachePreviewMedia(
                    context = context,
                    mediaUrl = media.sourceUrl,
                    destinationDir = context.clipboardDir,
                    provider = linkArchives[archiveKey]?.provider,
                    thumbnailUrl = media.thumbnailUrl,
                    onProgress = { progress ->
                        coroutineContext.ensureActive()
                        currentCompletedBytes = progress.completedBytes
                        currentTotalBytes = progress.totalBytes
                        val now = System.currentTimeMillis()
                        val bytesDelta = progress.completedBytes - lastProgressBytes
                        if(bytesDelta >= 1024L * 1024L || now - lastProgressAt >= 500L) {
                            lastProgressAt = now
                            lastProgressBytes = progress.completedBytes
                            publishArchiveDownloadProgress(
                                media = media,
                                completedBytes = progress.completedBytes,
                                totalBytes = progress.totalBytes,
                                archive = target.archive
                            )
                        }
                    }
                )
                val event = when (result) {
                    is ClipboardPreviewMediaCacheResult.Saved -> {
                        withContext(Dispatchers.Main) {
                            noteClipboardMediaFileSaved(result.fileName)
                        }
                        ClipboardArchiveEvent.MediaDownloadSaved(
                            sourceUrl = media.sourceUrl,
                            fileName = result.fileName,
                            mimeType = result.mimeType,
                            now = attemptedAt
                        )
                    }
                    is ClipboardPreviewMediaCacheResult.Failed -> ClipboardArchiveEvent.MediaDownloadFailed(
                        sourceUrl = media.sourceUrl,
                        now = attemptedAt,
                        failureDetail = result.detail
                    )
                    is ClipboardPreviewMediaCacheResult.SkippedTooLarge -> ClipboardArchiveEvent.MediaSkippedTooLarge(
                        sourceUrl = media.sourceUrl,
                        now = attemptedAt,
                        failureDetail = result.detail
                    )
                    is ClipboardPreviewMediaCacheResult.RateLimited -> {
                        setProviderCooldown(
                            ClipboardPreviewProviderCooldown(
                                provider = result.provider,
                                retryAfterEpochMs = result.retryAfterEpochMs,
                                detail = result.detail
                            )
                        )
                        ClipboardArchiveEvent.MediaDownloadFailed(
                            sourceUrl = media.sourceUrl,
                            now = attemptedAt,
                            failureDetail = result.detail
                        )
                    }
                }

                val archive = reduceArchiveMedia(
                    archiveKey = archiveKey,
                    event = event,
                    existingArchiveFileNames = currentArchiveFileNames()
                ) ?: continue
                publishArchiveDownloadProgressNow(
                    media = media,
                    completedBytes = currentCompletedBytes,
                    totalBytes = currentTotalBytes,
                    archive = archive
                )
                updateEntriesPreviewFromArchive(text, archive, attemptedAt)
                queueArchiveSave(archive)
                queuePreviewSave(delayMillis = 350L, reconcileBeforeSave = false)
                if(result is ClipboardPreviewMediaCacheResult.RateLimited) break
            }
        }
    }

    private fun publishArchiveDownloadProgress(
        media: ClipboardArchiveMedia,
        completedBytes: Long,
        totalBytes: Long?,
        archive: ClipboardLinkArchive
    ) {
        val snapshot = archiveProgressSnapshot(
            media = media,
            completedBytes = completedBytes,
            totalBytes = totalBytes,
            archive = archive
        )
        coroutineScope.launch(Dispatchers.Main.immediate) {
            applyArchiveDownloadProgress(snapshot)
        }
    }

    private suspend fun publishArchiveDownloadProgressNow(
        media: ClipboardArchiveMedia,
        completedBytes: Long,
        totalBytes: Long?,
        archive: ClipboardLinkArchive
    ) {
        val snapshot = archiveProgressSnapshot(
            media = media,
            completedBytes = completedBytes,
            totalBytes = totalBytes,
            archive = archive
        )
        withContext(Dispatchers.Main.immediate) {
            applyArchiveDownloadProgress(snapshot)
        }
    }

    private fun archiveProgressSnapshot(
        media: ClipboardArchiveMedia,
        completedBytes: Long,
        totalBytes: Long?,
        archive: ClipboardLinkArchive
    ): ClipboardArchiveProgressSnapshot = ClipboardArchiveProgressSnapshot(
        archiveKey = archive.key,
        sourceUrl = media.sourceUrl,
        sourceIndex = media.sourceIndex,
        completedBytes = completedBytes,
        totalBytes = totalBytes,
        savedCount = archive.savedMediaCount(),
        expectedCount = archive.expectedMediaCount()
    )

    private fun applyArchiveDownloadProgress(snapshot: ClipboardArchiveProgressSnapshot) {
        archiveDownloadCoordinator.publishProgress(snapshot.toProgress())
    }

    private suspend fun reduceArchiveMedia(
        archiveKey: String,
        event: ClipboardArchiveEvent,
        existingArchiveFileNames: Set<String> = currentArchiveFileNames()
    ): ClipboardLinkArchive? = withContext(Dispatchers.Main) {
        val archive = updateArchiveWithCurrentStorageState(
            linkArchives[archiveKey] ?: return@withContext null,
            existingArchiveFileNames
        )
        val updated = reduceArchive(archive, event) ?: return@withContext null
        linkArchives[archiveKey] = updated
        updated
    }

    private suspend fun updateEntriesPreviewFromArchive(
        text: String?,
        archive: ClipboardLinkArchive,
        attemptedAt: Long
    ) {
        val savedMedia = archive.savedPreviewMedia()
        if(text != null) {
            updateLatestTextEntry(text) { current ->
                current.withArchivePreviewMedia(archive, savedMedia, attemptedAt)
            }
        } else {
            withContext(Dispatchers.Main) {
                for(i in clipboardHistory.indices) {
                    val current = clipboardHistory[i]
                    if(current.matchesDeletedArchiveKey(archive.key)) {
                        clipboardHistory[i] = current.withArchivePreviewMedia(archive, savedMedia, attemptedAt)
                    }
                }
            }
        }
    }

    private fun updateEntriesPreviewFromArchiveNow(
        archive: ClipboardLinkArchive,
        attemptedAt: Long = System.currentTimeMillis()
    ) {
        val savedMedia = archive.savedPreviewMedia()
        for(i in clipboardHistory.indices) {
            val current = clipboardHistory[i]
            if(current.matchesDeletedArchiveKey(archive.key)) {
                clipboardHistory[i] = current.withArchivePreviewMedia(archive, savedMedia, attemptedAt)
            }
        }
    }

    private fun ClipboardEntry.withArchivePreviewMedia(
        archive: ClipboardLinkArchive,
        savedMedia: List<ClipboardPreviewMedia>,
        attemptedAt: Long
    ): ClipboardEntry =
        copy(
            previewImageFile = null,
            previewMediaFiles = savedMedia,
            previewMetadata = archive.metadata ?: previewMetadata,
            previewFetchStatus = if(previewText != null || savedMedia.isNotEmpty()) {
                ClipboardPreviewFetchStatus.Success
            } else {
                previewFetchStatus
            },
            previewFetchLastAttemptAt = attemptedAt,
            previewFetchFailureDetail = null,
            deletedArchiveKeys = emptySet()
        )

    private fun archiveForEntry(entry: ClipboardEntry): ClipboardLinkArchive? =
        entry.previewMetadata?.archiveKey()?.let { linkArchives[it] }

    private fun saveArchive(archive: ClipboardLinkArchive) {
        if(!context.isDirectBootUnlocked) return
        synchronized(archiveSaveLock) {
            try {
                archiveStore.saveArchive(archive)
            } catch(e: Exception) {
                clipboardIOFailure.value = true
                clipboardIOFailureReason = e.toString()
                reportError("saveArchives", e)
            }
        }
    }

    private fun queueArchiveSave(
        archive: ClipboardLinkArchive,
        delayMillis: Long = 350L
    ) {
        val job = coroutineScope.launch {
            delay(delayMillis)
            flushPendingArchiveSavesOnIo()
            synchronized(archiveSaveLock) {
                if(scheduledArchiveSaveJob == coroutineContext[Job]) {
                    scheduledArchiveSaveJob = null
                }
            }
        }

        synchronized(archiveSaveLock) {
            pendingArchiveSavesByKey[archive.key] = archive
            scheduledArchiveSaveJob?.cancel()
            scheduledArchiveSaveJob = job
        }
    }

    private suspend fun flushArchiveSave(archiveKey: String) {
        val archive = drainPendingArchiveSave(archiveKey) ?: return
        withContext(NonCancellable + ClipboardIOContext) {
            saveArchive(archive)
        }
    }

    private fun discardPendingArchiveSave(archiveKey: String) {
        drainPendingArchiveSave(archiveKey)
    }

    private fun drainPendingArchiveSave(archiveKey: String): ClipboardLinkArchive? =
        synchronized(archiveSaveLock) {
            pendingArchiveSavesByKey.remove(archiveKey)
        }

    private fun flushPendingArchiveSaves() {
        val archives = synchronized(archiveSaveLock) {
            pendingArchiveSavesByKey.values.toList().also {
                pendingArchiveSavesByKey.clear()
            }
        }
        archives.forEach(::saveArchive)
    }

    private suspend fun flushPendingArchiveSavesOnIo() {
        val archives = synchronized(archiveSaveLock) {
            pendingArchiveSavesByKey.values.toList().also {
                pendingArchiveSavesByKey.clear()
            }
        }
        if(archives.isEmpty()) return
        withContext(NonCancellable + ClipboardIOContext) {
            archives.forEach(::saveArchive)
        }
    }

    private fun saveArchives(archives: Collection<ClipboardLinkArchive>) {
        flushPendingArchiveSaves()
        archives.forEach(::saveArchive)
        deleteStaleArchiveMetadataFiles(archives.map { it.key }.toSet())
    }

    private fun saveArchiveTombstones(tombstones: Collection<ClipboardArchiveTombstone>) {
        if(!context.isDirectBootUnlocked) return
        synchronized(archiveSaveLock) {
            try {
                archiveStore.saveTombstones(tombstones)
            } catch(e: Exception) {
                clipboardIOFailure.value = true
                clipboardIOFailureReason = e.toString()
                reportError("saveArchiveTombstones", e)
            }
        }
    }

    private fun deleteArchiveMetadataFile(archiveKey: String) {
        archiveStore.deleteArchiveMetadata(archiveKey)
    }

    private fun deleteStaleArchiveMetadataFiles(retainedArchiveKeys: Set<String>) {
        archiveStore.deleteStaleArchiveMetadata(retainedArchiveKeys)
    }

    // Callers invoke this from ClipboardIOContext; the filesystem work below therefore
    // runs off the main thread, and only the snapshot-map swap is marshalled to Main.
    private suspend fun reconcileArchiveStorage() {
        val archiveSnapshot = withContext(Dispatchers.Main) { linkArchives.values.toList() }
        val archiveMapSnapshot = archiveSnapshot.associateBy { it.key }
        val referencedArchiveFiles = referencedClipboardArchiveFileNames(archiveSnapshot)
        migrateLegacyArchiveMediaFiles(
            legacyArchiveDir = context.clipboardArchiveDir,
            clipboardDir = context.clipboardDir,
            referencedFileNames = referencedArchiveFiles
        )
        val reconciled = reconcileClipboardArchivesWithStorage(
            archives = archiveSnapshot,
            clipboardDir = context.clipboardDir,
            legacyArchiveDir = context.clipboardArchiveDir
        )
        if(reconciled.associateBy { it.key } != archiveMapSnapshot) {
            withContext(Dispatchers.Main) {
                linkArchives.clear()
                linkArchives.putAll(reconciled.associateBy { it.key })
            }
            saveArchives(reconciled)
        }

        context.clipboardArchiveDir.listFiles()?.forEach { file ->
            if(file.name !in referencedArchiveFiles) {
                file.delete()
            }
        }
        context.clipboardArchiveDir.delete()
        refreshArchiveFileNames()
    }
}
