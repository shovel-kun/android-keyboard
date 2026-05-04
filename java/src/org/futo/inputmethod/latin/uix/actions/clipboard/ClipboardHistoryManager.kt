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
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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

private data class ClipboardPreviewFetchRequest(
    val text: String,
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
    val urlMetadata = ClipboardLinkPreviewFetcher.metadataForSupportedUrl(text) ?: return null
    return previewMetadata?.takeIf { it.provider == urlMetadata.provider }?.let { legacy ->
        urlMetadata.copy(
            sourceUrl = legacy.sourceUrl ?: urlMetadata.sourceUrl,
            sourceId = legacy.sourceId ?: urlMetadata.sourceId,
            title = legacy.title,
            bodyText = legacy.bodyText ?: previewText,
            authorName = legacy.authorName,
            authorHandle = legacy.authorHandle ?: urlMetadata.authorHandle,
            authorId = legacy.authorId,
            createdAt = legacy.createdAt,
            imageCount = legacy.imageCount,
            selectedImageIndex = legacy.selectedImageIndex ?: urlMetadata.selectedImageIndex,
            tags = legacy.tags,
            stats = legacy.stats,
            flags = legacy.flags
        )
    } ?: urlMetadata.copy(bodyText = previewText)
}

internal fun ClipboardEntry.isEligibleForArchiveBackfill(
    existingArchiveKeys: Set<String>,
    deletedArchiveKeys: Set<String> = emptySet()
): Boolean {
    val archiveKey = archiveBackfillKey() ?: return false
    if(archiveKey in deletedArchiveKeys) return false
    return archiveKey !in existingArchiveKeys
}

private fun ClipboardEntry.archiveBackfillKey(): String? {
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

internal fun ClipboardEntry.matchesDeletedArchiveKey(archiveKey: String): Boolean =
    previewMetadata?.archiveKey() == archiveKey ||
        archiveBackfillMetadata()?.archiveKey() == archiveKey

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
    clipboardDir: File
): List<ClipboardPreviewMedia> =
    entry.previewMedia().filter {
        it.fileName !in archivedFileNames ||
            File(clipboardDir, it.fileName).isFile
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

class ClipboardHistoryManager(
    val context: Context,
    val coroutineScope: LifecycleCoroutineScope
) : PersistentActionState {
    var clipboardIOFailureReason = ""
    val clipboardIOFailure = mutableStateOf(false)
    val previewLoadingByText = mutableStateMapOf<String, Boolean>()
    private val archiveDownloadProgressByKey = mutableStateMapOf<String, ClipboardArchiveDownloadProgress>()
    private val archiveDownloadQueuedSourceUrlsByKey = mutableStateMapOf<String, Set<String>>()
    private val archiveFileNames = mutableStateOf<Set<String>>(emptySet())
    private val providerCooldownByProvider = mutableStateMapOf<ClipboardPreviewProvider, ClipboardPreviewProviderCooldown>()
    val archiveBackfillInProgress = mutableStateOf(false)
    val archiveBackfillRemainingCount = mutableStateOf(0)

    companion object {
        val onClipboardImportedFlow = MutableSharedFlow<File>()
    }

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipboardHistory = mutableStateListOf<ClipboardEntry>()
    val linkArchives = mutableStateMapOf<String, ClipboardLinkArchive>()

    private val clipboardFile = context.clipboardFile
    private val clipboardFileBak = File(context.filesDir, "$ClipboardFileName.bak")
    private val clipboardFileSwap = File(context.filesDir, "$ClipboardFileName.swap")
    private val archiveFile = context.clipboardArchiveFile
    private val archiveFileBak = File(context.filesDir, "$ClipboardArchiveFileName.bak")
    private val archiveTombstonesFile = context.clipboardArchiveTombstonesFile
    private val archiveTombstonesFileBak = File(context.filesDir, "$ClipboardArchiveTombstonesFileName.bak")
    private val archiveTombstonesFileSwap = File(context.filesDir, "$ClipboardArchiveTombstonesFileName.swap")
    private val archiveMetadataDir = context.clipboardArchiveMetadataDir
    private val archiveSaveLock = Any()

    private var scheduledPreviewSaveJob: Job? = null
    private var saveClipboardLoadJob: Job? = null
    private var clipboardLoaded = false
    private var archiveFileNamesLoaded = false
    private val archiveBackfillAttemptedKeys = mutableSetOf<String>()
    private val deletedArchiveKeys = mutableSetOf<String>()
    private val archiveTombstonesByKey = mutableMapOf<String, ClipboardArchiveTombstone>()
    private var archiveBackfillCompletionPending = false
    private var archiveBackfillBlockedByCooldown = false
    private val archiveDownloadJobsByKey = mutableMapOf<String, Job>()

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

    private fun importTextEntry(timestamp: Long, text: String, mimeTypes: List<String>) {
        val existingEntries = clipboardHistory.filter { it.text == text }
        val preservedEntry = existingEntries.lastOrNull { it.hasRetainedPreviewState() }
            ?: existingEntries.lastOrNull()
        val isAlreadyPinned = existingEntries.any { it.pinned }

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

        saveClipboard()
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
            saveClipboard()
        }
    }

    private suspend fun onClipboardImported(file: File) {
        if(file != clipboardFile && file.name != clipboardFile.name) return

        loadClipboard()
        refreshMissingLinkPreviews(forceArchiveBackfill = true)
    }

    suspend fun reconcileClipboardStorage() = withContext(Dispatchers.Main) {
        val deduplicated = deduplicateClipboardEntries(clipboardHistory)
        if(deduplicated.size < clipboardHistory.size || deduplicated != clipboardHistory.toList()) {
            clipboardHistory.clear()
            clipboardHistory.addAll(deduplicated)
        }

        clipboardHistory.removeAll {
            it.backingFile != null && it.getFile(context)?.isFile != true
        }

        for(i in clipboardHistory.indices) {
            val entry = clipboardHistory[i]
            val retainedPreviewMedia = entry.previewMedia()
                .filter {
                    File(context.clipboardDir, it.fileName).isFile == true
                }
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

        val clipReferencedFileNames = referencedClipboardFileNames(clipboardHistory)
        val stillReferenced = clipReferencedFileNames + referencedClipboardArchiveFileNames(linkArchives.values)

        context.clipboardDir.listFiles()?.forEach {
            if(it.name !in stillReferenced) {
                it.delete()
            }
        }

        reconcileArchiveStorage()
    }

    internal fun saveClipboard(exiting: Boolean = false): Job? {
        if(!context.isDirectBootUnlocked) return null
        if(!clipboardLoaded) {
            if(saveClipboardLoadJob?.isActive == true) return null

            val currentEntries = clipboardHistory.toList()
            saveClipboardLoadJob = coroutineScope.launch {
                loadClipboard()

                if(clipboardLoaded) {
                    clipboardHistory.addAll(currentEntries)
                    saveClipboard(exiting)
                } else {
                    clipboardIOFailure.value = true
                }
            }

            return saveClipboardLoadJob
        }

        return coroutineScope.launch(context = ClipboardIOContext) {
            try {
                if(!exiting) reconcileClipboardStorage()

                val list = clipboardHistory.map { it.copy(deletedArchiveKeys = emptySet()) }
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

    fun refreshMissingLinkPreviews(forceArchiveBackfill: Boolean = false) {
        if(context.getSetting(ClipboardIncognitoMode)) return
        if(!currentPreviewState().shouldFetchPreviews) return
        if(!canRunAutomaticClipboardNetworkDownloads()) return

        clipboardHistory.toList().forEach { entry ->
            val text = entry.text ?: return@forEach
            if(entry.canAutoFetchPreview()) {
                fetchPreviewForEntry(text)
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
        archiveForEntry(entry)?.media?.size?.takeIf { it > entry.previewMedia().size }

    private fun runLegacyArchiveBackfillIfNeeded(forceCompletedVersion: Boolean = false) {
        if(archiveBackfillInProgress.value) return
        if(!canRunAutomaticClipboardNetworkDownloads()) return
        val incognito = context.getSetting(ClipboardIncognitoMode)
        val previewsEnabled = currentPreviewState().shouldFetchPreviews
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
        if(!currentPreviewState().shouldFetchPreviews) return false
        if(!canRunAutomaticClipboardNetworkDownloads()) return false
        val text = request.entry.text ?: return false
        val archiveKey = request.archiveKey
        if(!ClipboardLinkPreviewFetcher.supportsPreview(text)) return false
        val metadata = request.entry.archiveBackfillMetadata() ?: return false
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
                    ClipboardLinkPreviewFetcher.fetchManifestResult(text)
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
        linkArchives.values
            .filter { it.providerManifestAvailable && it.hasAutoDownloadableMedia() && !isArchiveRetryBlockedByCooldown(it) }
            .forEach { startArchiveDownload(text = null, archiveKey = it.key) }
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

    internal fun archiveRecords(): List<ClipboardLinkArchive> {
        val existingArchiveFileNames = currentArchiveFileNames()
        return sortedClipboardArchives(
            linkArchives.values.map {
                archiveWithCurrentStorageState(it, existingArchiveFileNames)
            }
        )
    }

    internal fun archivePreviewFiles(archive: ClipboardLinkArchive): List<File> =
        archiveWithCurrentStorageState(archive, currentArchiveFileNames())
            .savedPreviewMedia()
            .mapNotNull { clipboardMediaFile(context.clipboardDir, it.fileName) }

    internal fun archivePreviewFilesByKey(archives: Collection<ClipboardLinkArchive>): Map<String, List<File>> {
        val existingArchiveFileNames = currentArchiveFileNames()
        return archives.associate { archive ->
            archive.key to archiveWithCurrentStorageState(archive, existingArchiveFileNames)
                .savedPreviewMedia()
                .mapNotNull { clipboardMediaFile(context.clipboardDir, it.fileName) }
        }
    }

    internal fun galleryItems(archive: ClipboardLinkArchive): List<ClipboardArchiveGalleryItem> =
        archive.galleryItems(context.clipboardDir, currentArchiveFileNames())

    internal fun isArchiveLoading(archive: ClipboardLinkArchive): Boolean =
        previewLoadingByText[archive.key] == true

    internal fun archiveDownloadProgress(archive: ClipboardLinkArchive): ClipboardArchiveDownloadProgress? =
        archiveDownloadProgressByKey[archive.key]

    internal fun archiveDownloadItems(): List<ClipboardArchiveDownloadListItem> =
        archiveDownloadItems(
            archives = linkArchives.values,
            progressByArchiveKey = archiveDownloadProgressByKey,
            loadingArchiveKeys = previewLoadingByText.filterValues { it }.keys,
            queuedSourceUrlsByArchiveKey = archiveDownloadQueuedSourceUrlsByKey,
            cooldownsByProvider = activeProviderCooldowns(),
            existingArchiveFileNames = currentArchiveFileNames()
        )

    internal fun archiveDownloadActionCount(): Int =
        archiveDownloadActionCount(
            archives = linkArchives.values,
            existingArchiveFileNames = currentArchiveFileNames()
        )

    internal fun providerCooldown(provider: ClipboardPreviewProvider): ClipboardPreviewProviderCooldown? {
        val cooldown = providerCooldownByProvider[provider] ?: return null
        if(cooldown.retryAfterEpochMs <= System.currentTimeMillis()) {
            providerCooldownByProvider.remove(provider)
            return null
        }
        return cooldown
    }

    private fun ClipboardEntry.previewProviderCooldown(): ClipboardPreviewProviderCooldown? =
        text
            ?.let { ClipboardLinkPreviewFetcher.metadataForSupportedUrl(it)?.provider }
            ?.let { providerCooldown(it) }

    internal fun isArchiveRetryBlockedByCooldown(archive: ClipboardLinkArchive): Boolean =
        providerCooldown(archive.provider) != null

    internal fun retryArchive(archive: ClipboardLinkArchive) {
        val retryArchive = archiveWithCurrentStorageState(archive, currentArchiveFileNames(forceRefresh = true))
        if(!retryArchive.hasRetryableMedia()) return

        launchArchiveRetry(retryArchive)
    }

    internal fun retryArchiveMedia(item: ClipboardArchiveDownloadListItem) {
        val archive = archiveWithCurrentStorageState(
            linkArchives[item.archiveKey] ?: return,
            currentArchiveFileNames(forceRefresh = true)
        )
        if(!item.canRetry) return

        launchArchiveRetry(archive, sourceUrls = setOf(item.sourceUrl))
    }

    internal fun retryAllArchiveDownloads(items: List<ClipboardArchiveDownloadListItem>) {
        val existingArchiveFileNames = currentArchiveFileNames(forceRefresh = true)
        items
            .filter { it.canRetry }
            .groupBy { it.archiveKey }
            .forEach { (archiveKey, archiveItems) ->
                val archive = archiveWithCurrentStorageState(
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
        includeSkippedTooLarge: Boolean = true
    ) {
        if(isArchiveDownloadActive(archive.key)) {
            queueArchiveDownloadSourceUrls(archive, sourceUrls)
            return
        }
        if(isArchiveRetryBlockedByCooldown(archive)) return

        launchArchiveDownload(archive.key) {
            if(archive.providerManifestAvailable) {
                downloadArchiveMedia(
                    text = null,
                    archiveKey = archive.key,
                    includeSkippedTooLarge = includeSkippedTooLarge,
                    onlySourceUrls = sourceUrls
                )
            } else {
                refetchFallbackArchive(archive)
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
        val current = archiveDownloadQueuedSourceUrlsByKey[archive.key].orEmpty()
        archiveDownloadQueuedSourceUrlsByKey[archive.key] = current + queuedSourceUrls
    }

    private fun archiveWithCurrentStorageState(
        archive: ClipboardLinkArchive,
        existingArchiveFileNames: Set<String>
    ): ClipboardLinkArchive =
        archive.withMissingArchiveFilesMarked(existingArchiveFileNames, now = archive.updatedAtEpochMs)

    private fun scanArchiveFileNames(): Set<String> =
        existingClipboardMediaFileNames(context.clipboardDir, context.clipboardArchiveDir)

    private fun refreshArchiveFileNames(): Set<String> {
        val fileNames = scanArchiveFileNames()
        archiveFileNames.value = fileNames
        archiveFileNamesLoaded = true
        return fileNames
    }

    private fun currentArchiveFileNames(forceRefresh: Boolean = false): Set<String> {
        if(forceRefresh || !archiveFileNamesLoaded) {
            return refreshArchiveFileNames()
        }
        return archiveFileNames.value
    }

    internal fun stopArchiveDownload(item: ClipboardArchiveDownloadListItem) {
        stopArchiveDownload(item.archiveKey, item.sourceUrl, item.sourceIndex)
    }

    private fun stopArchiveDownload(
        archiveKey: String,
        sourceUrl: String? = null,
        sourceIndex: Int? = null
    ) {
        val progress = archiveDownloadProgressByKey[archiveKey]
        if(progress == null && !isArchiveDownloadActive(archiveKey)) return
        if(sourceUrl == null) {
            archiveDownloadQueuedSourceUrlsByKey.remove(archiveKey)
        } else {
            archiveDownloadQueuedSourceUrlsByKey[archiveKey] =
                archiveDownloadQueuedSourceUrlsByKey[archiveKey].orEmpty() - sourceUrl
        }
        val job = archiveDownloadJobsByKey.remove(archiveKey)
        previewLoadingByText.remove(archiveKey)
        archiveDownloadProgressByKey.remove(archiveKey)
        job?.cancel()
        val stoppedSourceUrl = sourceUrl ?: progress?.sourceUrl ?: return
        val stoppedSourceIndex = sourceIndex ?: progress?.sourceIndex
        val attemptedAt = System.currentTimeMillis()
        val archive = linkArchives[archiveKey] ?: return
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
        saveArchive(updated)
        queuePreviewSave(delayMillis = 0L)
    }

    internal fun stopAllArchiveDownloads() {
        (archiveDownloadProgressByKey.keys + archiveDownloadJobsByKey.keys + previewLoadingByText.keys.filter { it in linkArchives })
            .toList()
            .forEach { stopArchiveDownload(it) }
    }

    private fun activeProviderCooldowns(): Map<ClipboardPreviewProvider, ClipboardPreviewProviderCooldown> {
        val now = System.currentTimeMillis()
        providerCooldownByProvider
            .filterValues { it.retryAfterEpochMs <= now }
            .keys
            .toList()
            .forEach(providerCooldownByProvider::remove)
        return providerCooldownByProvider.toMap()
    }

    private suspend fun setProviderCooldown(cooldown: ClipboardPreviewProviderCooldown) {
        withContext(Dispatchers.Main) {
            val current = providerCooldownByProvider[cooldown.provider]
            if(current == null || cooldown.retryAfterEpochMs > current.retryAfterEpochMs) {
                providerCooldownByProvider[cooldown.provider] = cooldown
            }
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

    private fun deleteArchiveByKey(archiveKey: String) {
        val archive = linkArchives[archiveKey]
        cancelArchiveDownloadState(archiveKey)
        linkArchives.remove(archiveKey)
        tombstoneArchiveKey(archiveKey, reason = "user")
        deleteArchiveMetadataFile(archiveKey)
        val archivedFileNames = archive?.media.orEmpty().mapNotNull { it.fileName }.toSet()
        archivedFileNames.forEach { fileName ->
            File(context.clipboardArchiveDir, fileName).delete()
            File(context.clipboardArchiveDir, ClipboardUtil.thumbnailForName(fileName)).delete()
        }
        refreshArchiveFileNames()

        for(i in clipboardHistory.indices) {
            val current = clipboardHistory[i]
            if(current.matchesDeletedArchiveKey(archiveKey)) {
                val retainedPreviewMedia = retainedPreviewMediaAfterArchiveDelete(
                    entry = current,
                    archivedFileNames = archivedFileNames,
                    clipboardDir = context.clipboardDir
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
        orphanedSharedArchiveFileNamesAfterArchiveDelete(archivedFileNames, clipboardHistory)
            .forEach { fileName -> File(context.clipboardDir, fileName).delete() }
        saveArchives()
        saveClipboard()
    }

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
        saveArchiveTombstones(archiveTombstonesByKey.values)
    }

    private fun deleteArchiveDownloadMedia(item: ClipboardArchiveDownloadListItem) {
        val archive = linkArchives[item.archiveKey] ?: return
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
        archiveDownloadQueuedSourceUrlsByKey[item.archiveKey] =
            archiveDownloadQueuedSourceUrlsByKey[item.archiveKey].orEmpty() - item.sourceUrl
        updateEntriesPreviewFromArchiveNow(updated)
        saveArchive(updated)
        saveClipboard()
    }

    private fun cancelArchiveDownloadState(archiveKey: String) {
        archiveDownloadQueuedSourceUrlsByKey.remove(archiveKey)
        archiveDownloadProgressByKey.remove(archiveKey)
        previewLoadingByText.remove(archiveKey)
        archiveDownloadJobsByKey.remove(archiveKey)?.cancel()
    }

    fun onPaste(item: ClipboardEntry) {
        val itemPos = clipboardHistory.indexOf(item).coerceAtLeast(0)
        clipboardHistory.removeAll { it == item }
        clipboardHistory.add(itemPos, item.copy(timestamp = System.currentTimeMillis()))
        saveClipboard()
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
        saveClipboard()
    }

    fun onRemove(item: ClipboardEntry) {
        removeAll(listOf(item))
    }

    fun removeAll(items: Collection<ClipboardEntry>) {
        if(items.isEmpty()) return

        clearPrimaryClipIfNeeded(items)
        deleteArchivesOnlyReferencedBy(items)
        applyEntryMutations(items) { null }
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
        saveClipboard()?.join()
        saveArchives()
    }

    override fun close() {
        clipboardManager.removePrimaryClipChangedListener(primaryClipChangedListener)
        screenshotHelper.onDestroy()
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

    private fun queuePreviewSave(delayMillis: Long = 350L) {
        scheduledPreviewSaveJob?.cancel()
        scheduledPreviewSaveJob = coroutineScope.launch {
            delay(delayMillis)
            saveClipboard()
        }
    }

    private suspend fun publishClipboardLoaded(
        data: List<ClipboardEntry>,
        archives: List<ClipboardLinkArchive>,
        tombstones: List<ClipboardArchiveTombstone>
    ) = withContext(Dispatchers.Main) {
        clipboardHistory.clear()
        clipboardHistory.addAll(deduplicateClipboardEntries(data))
        linkArchives.clear()
        linkArchives.putAll(archives.associateBy { it.key })
        deletedArchiveKeys.clear()
        deletedArchiveKeys.addAll(tombstones.map { it.key })
        archiveTombstonesByKey.clear()
        archiveTombstonesByKey.putAll(tombstones.associateBy { it.key })
        refreshArchiveFileNames()
        clipboardLoaded = true
        clipboardIOFailureReason = ""
        clipboardIOFailure.value = false
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

--- main data start --- snip ---
${if(clipboardFile.exists()) { clipboardFile.readText() } else { "File does not exist" }}
--- main data end --- snip ---


--- bak data start --- snip ---
${if(clipboardFileBak.exists()) { clipboardFileBak.readText() } else { "File does not exist" }}
--- bak data end --- snip ---

--- swap data start --- snip ---
${if(clipboardFileSwap.exists()) { clipboardFileSwap.readText() } else { "File does not exist" }}
--- swap data end --- snip ---
"""))
    }

    private suspend fun loadClipboard() = withContext(ClipboardIOContext) {
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

            val loadedTombstones = loadArchiveTombstones()
            val migratedTombstones = archiveTombstonesForEntries(loadedTombstones, loadedEntries)
            if(migratedTombstones != loadedTombstones) {
                saveArchiveTombstones(migratedTombstones)
            }

            val tombstoneKeys = archiveTombstoneKeys(migratedTombstones)
            val activeEntries = clearEntryArchiveTombstones(loadedEntries)
            val loadedArchives = filterDeletedClipboardArchives(
                archives = loadArchives(),
                deletedArchiveKeys = tombstoneKeys
            )

            publishClipboardLoaded(activeEntries, loadedArchives, migratedTombstones)
            if(activeEntries != loadedEntries) {
                saveClipboard()
            }
            reconcileClipboardStorage()
            refreshMissingLinkPreviews()
        } catch (e: Exception) {
            publishClipboardLoadFailure("Exception: ${e.message}")
            reportError("loadClipboard", e)
        }
    }

    private fun decodeFile(file: File): List<ClipboardEntry> =
        decodeClipboardEntries(file.readText())

    private fun loadArchives(): List<ClipboardLinkArchive> =
        mergeStoredClipboardArchives(
            legacyArchives = loadLegacyArchiveFile(),
            metadataArchives = loadClipboardArchivesFromMetadataDir(archiveMetadataDir)
        )

    private fun loadLegacyArchiveFile(): List<ClipboardLinkArchive> =
        if(archiveFile.exists()) {
            try {
                archiveFile.decodeLegacyClipboardArchives()
            } catch(e: Exception) {
                reportError("loadArchives main, trying bak", e)
                if(archiveFileBak.exists()) {
                    archiveFileBak.decodeLegacyClipboardArchives()
                } else {
                    throw e
                }
            }
        } else if(archiveFileBak.exists()) {
            archiveFileBak.decodeLegacyClipboardArchives()
        } else {
            emptyList()
        }

    private fun loadArchiveTombstones(): List<ClipboardArchiveTombstone> =
        if(archiveTombstonesFile.exists()) {
            try {
                archiveTombstonesFile.decodeClipboardArchiveTombstones()
            } catch(e: Exception) {
                reportError("loadArchiveTombstones main, trying bak", e)
                if(archiveTombstonesFileBak.exists()) {
                    archiveTombstonesFileBak.decodeClipboardArchiveTombstones()
                } else {
                    throw e
                }
            }
        } else if(archiveTombstonesFileBak.exists()) {
            archiveTombstonesFileBak.decodeClipboardArchiveTombstones()
        } else {
            emptyList()
        }

    private fun fetchPreviewForEntry(text: String, manualRetry: Boolean = false) {
        val request = previewFetchRequest(text, manualRetry) ?: return
        ClipboardLinkPreviewFetcher.metadataForSupportedUrl(request.text)?.provider?.let {
            if(providerCooldown(it) != null) return
        }

        coroutineScope.launch {
            previewLoadingByText[request.text] = true
            try {
                var attempt = 0
                var lastFetchedManifest: ClipboardLinkPreviewManifest? = null
                var lastFailureDetail: String? = null
                var unavailableRefresh = false

                while (attempt < request.maxAttempts) {
                    val manifestResult = withContext(ClipboardPreviewFetchContext) {
                        ClipboardLinkPreviewFetcher.fetchManifestResult(request.text)
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
                        val archiveKey = manifest.archiveKey()
                        val previewWasDeleted = withContext(Dispatchers.Main) {
                            archiveKey != null && archiveKey in deletedArchiveKeys
                        }
                        if(previewWasDeleted) break
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
                        clipboardHistory.lastOrNull { it.text == request.text }
                            ?.let { archiveForEntry(it) }
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
    }

    private fun previewFetchRequest(
        text: String,
        manualRetry: Boolean
    ): ClipboardPreviewFetchRequest? {
        if(context.getSetting(ClipboardIncognitoMode)) return null

        val previewState = currentPreviewState()
        if(!previewState.linkPreviewsEnabled) return null
        if(!manualRetry && !previewState.shouldFetchPreviews) return null
        if(!manualRetry && !canRunAutomaticClipboardNetworkDownloads()) return null
        if(!ClipboardLinkPreviewFetcher.supportsPreview(text)) return null
        if(previewLoadingByText[text] == true) return null

        val entry = clipboardHistory.lastOrNull { it.text == text } ?: return null
        val archiveKey = ClipboardLinkPreviewFetcher.metadataForSupportedUrl(text)?.archiveKey()
        if(archiveKey != null && archiveKey in deletedArchiveKeys) return null
        if(manualRetry) {
            if(!canRetryPreview(entry)) return null
        } else if(!entry.canAutoFetchPreview()) {
            return null
        }

        return ClipboardPreviewFetchRequest(
            text = text,
            maxAttempts = if(ClipboardLinkPreviewFetcher.prefersImagePreview(text)) 3 else 1,
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

    private fun deleteArchivesOnlyReferencedBy(items: Collection<ClipboardEntry>) {
        val itemSet = items.toHashSet()
        items
            .mapNotNull { it.archiveBackfillKey() ?: it.previewMetadata?.archiveKey() }
            .distinct()
            .filter { archiveKey ->
                clipboardHistory.none { entry ->
                    entry !in itemSet && entry.matchesDeletedArchiveKey(archiveKey)
                }
            }
            .forEach(::deleteArchiveByKey)
    }

    private fun replaceEntries(updatedEntries: List<ClipboardEntry>) {
        clipboardHistory.clear()
        clipboardHistory.addAll(updatedEntries)
    }

    private fun clearPrimaryClipIfNeeded(items: Collection<ClipboardEntry>) {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        val currentText = try {
            clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        } catch(_: Exception) {
            null
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
        val updated = reduceArchive(
            archive = linkArchives[archiveKey],
            event = ClipboardArchiveEvent.ManifestSeen(manifest, now)
        ) ?: return null
        linkArchives[updated.key] = updated
        saveArchive(updated)
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
        saveArchive(archive)
        return archive
    }

    private suspend fun refetchFallbackArchive(archive: ClipboardLinkArchive) {
        providerCooldown(archive.provider)?.let { return }
        val manifestResult = withContext(ClipboardPreviewFetchContext) {
            ClipboardLinkPreviewFetcher.fetchManifestResult(archive.sourceUrl)
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
            val archive = linkArchives[archiveKey] ?: return@withContext null
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
        saveArchive(updated)
    }

    private fun startArchiveDownload(text: String?, archiveKey: String) {
        launchArchiveDownload(archiveKey) {
            downloadArchiveMedia(text, archiveKey)
        }
    }

    private suspend fun downloadArchiveMediaWithLoading(text: String?, archiveKey: String) {
        coroutineContext[Job]?.let { archiveDownloadJobsByKey[archiveKey] = it }
        try {
            withArchiveLoading(archiveKey) {
                downloadArchiveMedia(text, archiveKey)
            }
        } finally {
            archiveDownloadJobsByKey.remove(archiveKey)
            launchQueuedArchiveDownloadIfNeeded(archiveKey)
        }
    }

    private fun launchArchiveDownload(
        archiveKey: String,
        block: suspend () -> Unit
    ) {
        if(isArchiveDownloadActive(archiveKey)) return
        val job = coroutineScope.launch {
            try {
                withArchiveLoading(archiveKey, block)
            } finally {
                archiveDownloadJobsByKey.remove(archiveKey)
                launchQueuedArchiveDownloadIfNeeded(archiveKey)
            }
        }
        archiveDownloadJobsByKey[archiveKey] = job
    }

    private fun launchQueuedArchiveDownloadIfNeeded(archiveKey: String) {
        val queuedSourceUrls = archiveDownloadQueuedSourceUrlsByKey[archiveKey].orEmpty()
        if(queuedSourceUrls.isEmpty()) return
        val archive = linkArchives[archiveKey]?.let {
            archiveWithCurrentStorageState(it, currentArchiveFileNames(forceRefresh = true))
        } ?: return
        val retryableQueuedSourceUrls = queuedSourceUrls.intersect(archive.retryableMedia().map { it.sourceUrl }.toSet())
        if(retryableQueuedSourceUrls.isEmpty()) {
            archiveDownloadQueuedSourceUrlsByKey.remove(archiveKey)
            return
        }
        if(retryableQueuedSourceUrls.size != queuedSourceUrls.size) {
            archiveDownloadQueuedSourceUrlsByKey[archiveKey] = retryableQueuedSourceUrls
        }
        launchArchiveRetry(archive, sourceUrls = retryableQueuedSourceUrls)
    }

    private fun isArchiveDownloadActive(archiveKey: String): Boolean =
        previewLoadingByText[archiveKey] == true ||
            archiveDownloadJobsByKey[archiveKey]?.isActive == true

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
                archiveDownloadProgressByKey.remove(archiveKey)
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
                        archiveWithCurrentStorageState(it, currentArchiveFileNames(forceRefresh = true))
                    }
                    if(archive?.provider?.let { providerCooldown(it) } != null) return@withContext null
                    val queuedSourceUrls = archiveDownloadQueuedSourceUrlsByKey[archiveKey].orEmpty()
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
                        val retryableSourceUrls = archive?.retryableMedia()?.map { it.sourceUrl }?.toSet().orEmpty()
                        val remaining = queuedSourceUrls.intersect(retryableSourceUrls)
                        if(remaining.isEmpty()) {
                            archiveDownloadQueuedSourceUrlsByKey.remove(archiveKey)
                        } else if(remaining.size != queuedSourceUrls.size) {
                            archiveDownloadQueuedSourceUrlsByKey[archiveKey] = remaining
                        }
                    } else if(media != null && media.sourceUrl in queuedSourceUrls) {
                        val remaining = queuedSourceUrls - media.sourceUrl
                        if(remaining.isEmpty()) {
                            archiveDownloadQueuedSourceUrlsByKey.remove(archiveKey)
                        } else {
                            archiveDownloadQueuedSourceUrlsByKey[archiveKey] = remaining
                        }
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
                            refreshArchiveFileNames()
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

                val archive = reduceArchiveMedia(archiveKey, event) ?: continue
                publishArchiveDownloadProgressNow(
                    media = media,
                    completedBytes = currentCompletedBytes,
                    totalBytes = currentTotalBytes,
                    archive = archive
                )
                updateEntriesPreviewFromArchive(text, archive, attemptedAt)
                saveArchive(archive)
                queuePreviewSave(delayMillis = 0L)
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
        val progress = snapshot.toProgress()
        val previous = archiveDownloadProgressByKey[snapshot.archiveKey]
        if(!shouldPublishArchiveDownloadProgress(previous, progress)) return
        archiveDownloadProgressByKey[snapshot.archiveKey] = progress
    }

    private fun shouldPublishArchiveDownloadProgress(
        previous: ClipboardArchiveDownloadProgress?,
        next: ClipboardArchiveDownloadProgress
    ): Boolean {
        if(previous == null) return true
        if(previous == next) return false
        if(previous.sourceUrl != next.sourceUrl || previous.sourceIndex != next.sourceIndex) return true
        if(previous.savedCount != next.savedCount || previous.expectedCount != next.expectedCount) return true
        if(previous.totalBytes != next.totalBytes) return true
        val byteDelta = next.completedBytes - previous.completedBytes
        if(byteDelta < 0L) return true
        return previous.progressPercent() != next.progressPercent() || byteDelta >= 1024L * 1024L
    }

    private suspend fun reduceArchiveMedia(
        archiveKey: String,
        event: ClipboardArchiveEvent
    ): ClipboardLinkArchive? = withContext(Dispatchers.Main) {
        val archive = linkArchives[archiveKey] ?: return@withContext null
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
                    if(current.previewMetadata?.archiveKey() == archive.key) {
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
            if(current.previewMetadata?.archiveKey() == archive.key) {
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
                archiveMetadataDir.mkdirs()
                val archiveFile = archiveMetadataDir.clipboardArchiveMetadataFile(archive.key)
                val archiveFileBak = File(archiveMetadataDir, "${archiveFile.name}.bak")
                val archiveFileSwap = File(archiveMetadataDir, "${archiveFile.name}.swap")
                val encoded = encodeClipboardArchive(archive)
                archiveFileSwap.writeText(encoded)
                if(archiveFileSwap.decodeClipboardArchive() != archive) {
                    throw Exception("Saved archive data does not match expected data")
                }

                replaceFileWithBackup(
                    swapFile = archiveFileSwap,
                    targetFile = archiveFile,
                    backupFile = archiveFileBak
                )

                if(archiveFile.decodeClipboardArchive() != archive) {
                    throw Exception("Saved archive data does not match expected data")
                }

                deleteLegacyArchiveAggregateFiles()
            } catch(e: Exception) {
                clipboardIOFailure.value = true
                clipboardIOFailureReason = e.toString()
                reportError("saveArchives", e)
            }
        }
    }

    private fun saveArchives() {
        linkArchives.values.forEach(::saveArchive)
        deleteStaleArchiveMetadataFiles()
        deleteLegacyArchiveAggregateFiles()
    }

    private fun saveArchiveTombstones(tombstones: Collection<ClipboardArchiveTombstone>) {
        if(!context.isDirectBootUnlocked) return
        synchronized(archiveSaveLock) {
            try {
                val encoded = encodeClipboardArchiveTombstones(tombstones)
                archiveTombstonesFileSwap.writeText(encoded)
                if(archiveTombstonesFileSwap.decodeClipboardArchiveTombstones() != tombstones.sortedBy { it.key }) {
                    throw Exception("Saved archive tombstones do not match expected data")
                }

                replaceFileWithBackup(
                    swapFile = archiveTombstonesFileSwap,
                    targetFile = archiveTombstonesFile,
                    backupFile = archiveTombstonesFileBak
                )

                if(archiveTombstonesFile.decodeClipboardArchiveTombstones() != tombstones.sortedBy { it.key }) {
                    throw Exception("Saved archive tombstones do not match expected data")
                }
            } catch(e: Exception) {
                clipboardIOFailure.value = true
                clipboardIOFailureReason = e.toString()
                reportError("saveArchiveTombstones", e)
            }
        }
    }

    private fun deleteArchiveMetadataFile(archiveKey: String) {
        val file = archiveMetadataDir.clipboardArchiveMetadataFile(archiveKey)
        listOf(file, File(archiveMetadataDir, "${file.name}.bak"), File(archiveMetadataDir, "${file.name}.swap"))
            .forEach { it.delete() }
    }

    private fun deleteStaleArchiveMetadataFiles() {
        val expectedNames = linkArchives.keys
            .map(::clipboardArchiveMetadataFileName)
            .flatMap { listOf(it, "$it.bak", "$it.swap") }
            .toSet()
        archiveMetadataDir.listFiles()?.forEach { file ->
            if(file.name !in expectedNames) {
                file.delete()
            }
        }
    }

    private fun deleteLegacyArchiveAggregateFiles() {
        listOf(archiveFile, archiveFileBak, File(context.filesDir, "$ClipboardArchiveFileName.swap"))
            .forEach { it.delete() }
    }

    private fun reconcileArchiveStorage() {
        val referencedArchiveFiles = referencedClipboardArchiveFileNames(linkArchives.values)
        migrateLegacyArchiveMediaFiles(
            legacyArchiveDir = context.clipboardArchiveDir,
            clipboardDir = context.clipboardDir,
            referencedFileNames = referencedArchiveFiles
        )
        val reconciled = reconcileClipboardArchivesWithStorage(
            archives = linkArchives.values,
            clipboardDir = context.clipboardDir,
            legacyArchiveDir = context.clipboardArchiveDir
        )
        if(reconciled.associateBy { it.key } != linkArchives.toMap()) {
            linkArchives.clear()
            linkArchives.putAll(reconciled.associateBy { it.key })
            saveArchives()
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
