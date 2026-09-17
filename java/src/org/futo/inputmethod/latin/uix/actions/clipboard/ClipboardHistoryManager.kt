package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
private val ClipboardIOContext = Dispatchers.IO.limitedParallelism(1)
@OptIn(ExperimentalCoroutinesApi::class)
private val ClipboardPreviewFetchContext = Dispatchers.IO.limitedParallelism(3)
@OptIn(ExperimentalCoroutinesApi::class)
private val ClipboardPinIOContext = Dispatchers.IO.limitedParallelism(1)
private const val ClipboardStoredMediaMaxBytes = 50L * 1024L * 1024L
private const val ClipboardStartupPreviewFetchLimit = 8
private const val ClipboardArchiveBackfillConcurrency = 3
private const val ClipboardArchiveResumeConcurrency = 3

private data class ClipboardPreviewFetchRequest(
    val text: String,
    val candidate: ClipboardPreviewCandidate,
    val maxAttempts: Int,
    val manualRetry: Boolean
)

private data class PrimaryClipboardImport(
    val timestamp: Long,
    val text: String?,
    val uri: Uri?,
    val mimeTypes: List<String>,
    val isSensitive: Boolean
)

private data class PendingArchiveEntryUpdate(
    val archive: ClipboardLinkArchive,
    val attemptedAt: Long
)

private data class ResolvedClipboardEntries(
    val entries: List<ClipboardEntry>,
    val archiveKeyByEntryKey: Map<String, String>
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
    val fileNames: Set<String>,
    val inventory: ClipboardStorageInventory
)

internal class ClipboardArchiveSaveQueue {
    private val pendingByKey = linkedMapOf<String, ClipboardLinkArchive>()
    private var flushScheduled = false

    @Synchronized
    fun enqueue(archive: ClipboardLinkArchive): Boolean {
        pendingByKey[archive.key] = archive
        if(flushScheduled) return false
        flushScheduled = true
        return true
    }

    @Synchronized
    fun remove(archiveKey: String): ClipboardLinkArchive? = pendingByKey.remove(archiveKey)

    @Synchronized
    fun drain(): List<ClipboardLinkArchive> {
        flushScheduled = false
        return pendingByKey.values.toList().also { pendingByKey.clear() }
    }
}

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

internal class ClipboardSaveRequestQueue {
    private var pending = false
    private var reconcileBeforeSave = false

    fun enqueue(reconcileBeforeSave: Boolean) {
        pending = true
        this.reconcileBeforeSave = this.reconcileBeforeSave || reconcileBeforeSave
    }

    fun take(): Boolean? {
        if(!pending) return null
        val request = reconcileBeforeSave
        pending = false
        reconcileBeforeSave = false
        return request
    }
}

private const val ClipboardPinMutationJournalName = "clipboard-pin-mutations"

private data class ClipboardPinMutationState(
    val revision: Long,
    val pinnedByKeyHash: Map<String, Boolean>
)

internal class ClipboardPinMutationJournal(private val filesDir: File) {
    private val file = File(filesDir, ClipboardPinMutationJournalName)
    private val backupFile = File(filesDir, "$ClipboardPinMutationJournalName.bak")
    private val swapFile = File(filesDir, "$ClipboardPinMutationJournalName.swap")
    private var state = readStateFromDisk()

    @Synchronized
    fun record(entries: Collection<ClipboardEntry>, pinned: Boolean): Long {
        val current = state ?: ClipboardPinMutationState(0L, emptyMap())
        val updated = current.pinnedByKeyHash.toMutableMap()
        entries.forEach { updated[it.pinMutationKeyHash()] = pinned }
        val nextState = ClipboardPinMutationState(current.revision + 1L, updated)
        writeState(nextState)
        state = nextState
        return nextState.revision
    }

    @Synchronized
    fun apply(entries: List<ClipboardEntry>): List<ClipboardEntry> {
        val mutations = state?.pinnedByKeyHash ?: return entries
        return entries.map { entry ->
            mutations[entry.pinMutationKeyHash()]?.let { pinned ->
                if(entry.pinned == pinned) entry else entry.copy(pinned = pinned)
            } ?: entry
        }
    }

    @Synchronized
    fun revision(): Long = state?.revision ?: 0L

    @Synchronized
    fun clearIfRevision(revision: Long) {
        if(state?.revision != revision) return
        file.delete()
        backupFile.delete()
        swapFile.delete()
        state = null
    }

    private fun readStateFromDisk(): ClipboardPinMutationState? =
        listOf(swapFile, file, backupFile)
            .firstNotNullOfOrNull { candidate ->
                candidate.takeIf(File::isFile)?.readText()?.decodePinMutationState()
            }

    private fun writeState(state: ClipboardPinMutationState) {
        val encoded = buildString {
            append("v1\t")
            append(state.revision)
            append('\n')
            state.pinnedByKeyHash.toSortedMap().forEach { (keyHash, pinned) ->
                append(keyHash)
                append('\t')
                append(if(pinned) '1' else '0')
                append('\n')
            }
        }
        FileOutputStream(swapFile).use { output ->
            output.write(encoded.toByteArray())
            output.fd.sync()
        }
        replaceFileWithBackup(swapFile, file, backupFile)
    }
}

private fun String.decodePinMutationState(): ClipboardPinMutationState? {
    val lines = lineSequence().filter(String::isNotBlank).toList()
    val header = lines.firstOrNull()?.split('\t') ?: return null
    if(header.size != 2 || header[0] != "v1") return null
    val revision = header[1].toLongOrNull() ?: return null
    val mutations = lines.drop(1).associate { line ->
        val fields = line.split('\t')
        if(fields.size != 2 || fields[0].length != 64) return null
        val pinned = when(fields[1]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        fields[0] to pinned
    }
    return ClipboardPinMutationState(revision, mutations)
}

private fun ClipboardEntry.pinMutationKeyHash(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(selectionKey().toByteArray())
        .joinToString("") { "%02x".format(it) }

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
    deletedArchiveKeys: Set<String> = emptySet(),
    limit: Int? = null
): List<ClipboardArchiveBackfillRequest> {
    val scheduledArchiveKeys = (existingArchiveKeys + attemptedArchiveKeys).toMutableSet()
    return entries.mapNotNull { entry ->
        val archiveKey = entry.archiveBackfillKey() ?: return@mapNotNull null
        if(archiveKey in deletedArchiveKeys) return@mapNotNull null
        if(!scheduledArchiveKeys.add(archiveKey)) return@mapNotNull null
        ClipboardArchiveBackfillRequest(entry = entry, archiveKey = archiveKey)
    }.let { requests -> limit?.let(requests::take) ?: requests }
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
    resolvedArchiveKey() == archiveKey

private fun ClipboardEntry.resolvedArchiveKey(): String? =
    previewMetadata?.archiveKey() ?: archiveBackfillMetadata()?.archiveKey()

private fun Context.pixivSessionIdForClipboardPreviews(): String? =
    getSetting(ClipboardPixivSessionId).trim().takeIf { it.isNotBlank() }

private fun Context.fanboxSessionIdForClipboardPreviews(): String? =
    getSetting(ClipboardFanboxSessionId).trim().takeIf { it.isNotBlank() }

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
    private val imageTagCoordinator = ClipboardImageTagCoordinator(
        scope = coroutineScope,
        taggerFactory = { OnnxClipboardImageTagger(context) },
        onResult = ::applyImageTaggingResult
    )

    // Serializes clipboard loads so the initial load and an unlock-triggered load
    // cannot interleave their clear/repopulate of the in-memory lists.
    private val loadMutex = Mutex()
    private val pinMutationMutex = Mutex()

    var clipboardIOFailureReason = ""
    val clipboardIOFailure = mutableStateOf(false)
    val previewLoadingByText = mutableStateMapOf<String, Boolean>()
    private val clipboardStorageSnapshot = mutableStateOf(
        ClipboardStorageSnapshot(emptySet(), ClipboardStorageInventory.Empty)
    )
    internal val clipboardStorageInventory = derivedStateOf { clipboardStorageSnapshot.value.inventory }
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
    internal var searchIndex by mutableStateOf(ClipboardSearchIndex())
        private set

    private val clipboardFile = context.clipboardFile
    private val clipboardFileBak = File(context.filesDir, "$ClipboardFileName.bak")
    private val clipboardFileSwap = File(context.filesDir, "$ClipboardFileName.swap")
    private val pinMutationJournal = ClipboardPinMutationJournal(context.filesDir)
    private val archiveStore = ClipboardArchiveStore(context.filesDir)
    private val archiveSaveLock = Any()

    private var scheduledPreviewSaveJob: Job? = null
    private var archiveEntryUpdateJob: Job? = null
    private val pendingArchiveEntryUpdates = mutableMapOf<String, PendingArchiveEntryUpdate>()
    private var clipboardSaveDrainJob: Job? = null
    private val pendingClipboardSaves = ClipboardSaveRequestQueue()
    private var saveClipboardLoadJob: Job? = null
    internal var backupImportInProgress by mutableStateOf(false)
        private set
    internal var clipboardLoaded by mutableStateOf(false)
        private set
    private val archiveBackfillAttemptedKeys = mutableSetOf<String>()
    private val deletedArchiveKeys = mutableSetOf<String>()
    private val archiveTombstonesByKey = mutableMapOf<String, ClipboardArchiveTombstone>()
    private var archiveBackfillCompletionPending = false
    private var archiveBackfillBlockedByCooldown = false
    private var archiveBackfillForceRunPending = false
    private var storageCleanupInProgress = false
    private val pendingArchiveSaves = ClipboardArchiveSaveQueue()
    private val archiveResumeQueue = ClipboardArchiveResumeQueue(ClipboardArchiveResumeConcurrency)

    private val screenshotHelper = ScreenshotHelper(
        context = context,
        lifecycleScope = coroutineScope,
        listener = object : ScreenshotListener {
            override fun onScreenshotAdded(mime: String, uri: Uri) {
                coroutineScope.launch { importScreenshotEntry(mime, uri) }
            }
        }
    )

    override suspend fun onDeviceUnlocked() {
        // The singleton's init already loads on construction; only load here if that
        // hasn't happened yet (e.g. the manager was built while still device-locked).
        if(clipboardLoaded) return
        loadClipboard()
    }

    private var clipboardChangeJob: Job? = null

    private val primaryClipChangedListener = object : ClipboardManager.OnPrimaryClipChangedListener {
        override fun onPrimaryClipChanged() {
            clipboardChangeJob?.cancel()
            if(backupImportInProgress) return
            val cleanLinks = context.getSettingBlocking(ClipboardCleanLinks)
            if(!cleanLinks && !shouldImportClipboardChanges()) return

            clipboardChangeJob = coroutineScope.launch {
                val clip = readPrimaryClip() ?: return@launch
                if(cleanLinks) {
                    val cleaned = withContext(Dispatchers.Default) { cleanClipboardClip(clip) }
                    if(backupImportInProgress) return@launch
                    if(!sameClipboardContents(clip, readPrimaryClip())) return@launch
                    if(cleaned != null && context.getSettingBlocking(ClipboardCleanLinks)) {
                        clipboardManager.setPrimaryClip(cleaned)
                        // The resulting notification imports the cleaned clip once.
                        return@launch
                    }
                }
                if(!shouldImportClipboardChanges()) return@launch
                val clipboardImport = readPrimaryClipboardImport(clip) ?: return@launch
                if(!shouldImportClipboardChanges()) return@launch
                if(clipboardImport.isSensitive && !context.getSetting(ClipboardHistorySaveSensitive)) {
                    return@launch
                }

                when {
                    clipboardImport.text != null -> importTextEntry(
                        clipboardImport.timestamp,
                        clipboardImport.text,
                        clipboardImport.mimeTypes
                    )
                    clipboardImport.uri != null -> importMediaEntry(
                        clipboardImport.timestamp,
                        clipboardImport.uri,
                        clipboardImport.mimeTypes
                    )
                }
            }
        }
    }

    init {
        coroutineScope.launch {
            snapshotFlow { clipboardSearchSource(linkArchives.values.toList(), clipboardHistory.toList()) }
                .collectLatest { source ->
                    searchIndex = withContext(Dispatchers.Default) { buildClipboardSearchIndex(source) }
                }
        }
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

    internal suspend fun withBackupImport(block: suspend () -> Unit) {
        try {
            withContext(Dispatchers.Main) {
                backupImportInProgress = true
                scheduledPreviewSaveJob?.cancelAndJoin()
                archiveEntryUpdateJob?.cancelAndJoin()
                clipboardSaveDrainJob?.join()
                saveClipboardLoadJob?.cancelAndJoin()
                archiveDownloadCoordinator.cancelAll()
                imageTagCoordinator.cancelAll()
                pendingArchiveSaves.drain()
            }
            loadMutex.withLock {
                // Wait for already-running clipboard file writes before replacing storage.
                withContext(ClipboardIOContext) { synchronized(archiveSaveLock) {} }
                block()
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                backupImportInProgress = false
                loadClipboard()
            }
        }
    }

    private fun shouldImportClipboardChanges(): Boolean =
        !backupImportInProgress && context.getSettingBlocking(ClipboardHistoryEnabled) &&
            !context.getSettingBlocking(ClipboardIncognitoMode)

    private fun readPrimaryClip(): ClipData? = try {
        clipboardManager.primaryClip
    } catch(_: SecurityException) {
        null
    }

    private suspend fun readPrimaryClipboardImport(clip: ClipData): PrimaryClipboardImport? =
        withContext(Dispatchers.IO) {
            val item = clip.getItemAt(0) ?: return@withContext null
            val uri = item.uri
            val mimeTypes = List(clip.description.mimeTypeCount) { index ->
                clip.description.getMimeType(index)
            }
            val text = if(uri == null || mimeTypes.any { it.startsWith("text/") }) {
                item.coerceToText(context)?.takeIf { it.length <= 500_000 }?.toString()
            } else {
                null
            }
            if(text == null && uri == null) return@withContext null

            PrimaryClipboardImport(
                timestamp = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    clip.description.timestamp
                } else {
                    null
                } ?: System.currentTimeMillis(),
                text = text,
                uri = uri,
                mimeTypes = mimeTypes,
                isSensitive = clip.description.extras?.getBoolean(
                    ClipDescription.EXTRA_IS_SENSITIVE,
                    false
                ) == true
            )
        }

    private fun importTextEntry(timestamp: Long, rawText: String, mimeTypes: List<String>) {
        if(backupImportInProgress) return
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
        val tombstones = archiveTombstonesByKey.values.toList()
        coroutineScope.launch(ClipboardIOContext) {
            saveArchiveTombstones(tombstones)
        }
    }

    private suspend fun importScreenshotEntry(mime: String, uri: Uri) {
        if(backupImportInProgress) return
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

    private suspend fun importMediaEntry(
        timestamp: Long,
        uri: Uri,
        mimeTypes: List<String>,
        imagesOnly: Boolean = false
    ) {
        val entry = try {
            withContext(Dispatchers.IO) {
                importMediaEntryFromProvider(timestamp, uri, mimeTypes, imagesOnly)
            }
        } catch(e: Exception) {
            throwIfDebug(e)
            null
        }
        entry ?: return

        entry.backingFile?.let(::noteClipboardMediaFileSaved)
        upsertClipboardMediaEntry(clipboardHistory, entry)
        saveClipboard(reconcileBeforeSave = true)
    }

    private fun importMediaEntryFromProvider(
        timestamp: Long,
        uri: Uri,
        mimeTypes: List<String>,
        imagesOnly: Boolean
    ): ClipboardEntry? {
        val targetMime = mimeTypes.firstOrNull {
            it.startsWith("image/") || (!imagesOnly && it.startsWith("video/"))
        } ?: return null
        val tempFile = File.createTempFile("clipboard-media-", ".tmp", context.cacheDir)

        try {
            val md = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8 * 1024)
            var totalBytes = 0L
            context.contentResolver.openInputStream(uri)?.use { stream ->
                tempFile.outputStream().use { output ->
                    while(true) {
                        val bytesRead = stream.read(buffer)
                        if(bytesRead == -1) break
                        totalBytes += bytesRead
                        if(totalBytes > ClipboardStoredMediaMaxBytes) return null
                        md.update(buffer, 0, bytesRead)
                        output.write(buffer, 0, bytesRead)
                    }
                }
            } ?: return null

            val md5Hex = md.digest().joinToString("") { "%02x".format(it) }
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(targetMime)
                ?: uri.lastPathSegment?.substringAfterLast('.', "")
                    ?.substringBefore('?')
                    ?.takeIf { it.isNotBlank() }
                ?: "bin"
            context.clipboardDir.mkdirs()
            val finalFile = File(context.clipboardDir, "$md5Hex.$extension")
            if(!finalFile.exists()) {
                if(!tempFile.renameTo(finalFile) && !finalFile.exists()) {
                    throw IllegalStateException("Failed to store clipboard media")
                }
                ClipboardUtil.generateThumbnail(finalFile, targetMime)
            }

            return ClipboardEntry(
                timestamp = timestamp,
                pinned = false,
                text = null,
                uri = null,
                backingFile = finalFile.name,
                sizeMb = totalBytes / (1024f * 1024f),
                mimeTypes = listOf(targetMime)
            )
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun onClipboardImported(file: File) {
        if(backupImportInProgress) return // The import owner reloads once after applying all files.
        if(file != clipboardFile && file.name != clipboardFile.name) return

        loadClipboard()
        reconcileClipboardStorage()
        refreshMissingLinkPreviews(forceArchiveBackfill = true)
    }

    suspend fun reconcileClipboardStorage(): Boolean = withContext(ClipboardIOContext) {
        if(backupImportInProgress) return@withContext false
        reconcileArchiveStorage()

        // Enumerate the clipboard media directory once on a background thread rather
        // than issuing a File.isFile stat per entry/preview-media on the UI thread.
        // Both checks below only ever target context.clipboardDir, so membership in
        // this set is equivalent to the previous per-file .isFile checks.
        val existingMediaNames = existingClipboardMediaFileNames(context.clipboardDir)
        withContext(Dispatchers.Main) {
            val currentEntries = clipboardHistory.toList()
            val reconciledEntries = restoreClipboardEntriesFromArchives(
                entries = reconcileClipboardEntriesWithStorage(
                    entries = deduplicateClipboardEntries(currentEntries),
                    existingMediaNames = existingMediaNames
                ),
                archives = linkArchives.values
            )
            if(reconciledEntries != currentEntries) {
                clipboardHistory.clear()
                clipboardHistory.addAll(reconciledEntries)
            }
            reconciledEntries != currentEntries
        }
    }

    internal fun saveClipboard(
        exiting: Boolean = false,
        reconcileBeforeSave: Boolean = true
    ): Job? {
        if(backupImportInProgress || !context.isDirectBootUnlocked) return null
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

        return coroutineScope.launch {
            pendingClipboardSaves.enqueue(reconcileBeforeSave)
            if(clipboardSaveDrainJob?.isActive != true) {
                clipboardSaveDrainJob = coroutineScope.launch {
                    while(true) {
                        val shouldReconcile = pendingClipboardSaves.take() ?: break
                        persistClipboard(shouldReconcile)
                    }
                }
            }
            clipboardSaveDrainJob?.join()
        }
    }

    private suspend fun persistClipboard(reconcileBeforeSave: Boolean) =
        withContext(ClipboardIOContext) {
            try {
                if(reconcileBeforeSave) reconcileClipboardStorage()

                val (list, pinMutationRevision) = withContext(Dispatchers.Main) {
                    clipboardHistory.map { it.copy(deletedArchiveKeys = emptySet()) } to
                        pinMutationJournal.revision()
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

                pinMutationJournal.clearIfRevision(pinMutationRevision)

                clipboardIOFailure.value = false
            } catch (e: Exception) {
                clipboardIOFailure.value = true
                clipboardIOFailureReason = e.toString()
                reportError("saveClipboard", e)
            }
        }

    fun deleteClipboard() {
        if(backupImportInProgress) return
        listOf(clipboardFile, clipboardFileSwap, clipboardFileBak).forEach {
            if(it.exists()) it.delete()
        }
    }

    fun refreshMissingLinkPreviews(forceArchiveBackfill: Boolean = false) {
        if(backupImportInProgress) return
        if(context.getSetting(ClipboardIncognitoMode)) return
        if(!currentPreviewState().shouldArchivePreviews) return
        if(!canRunAutomaticClipboardNetworkDownloads()) return

        var scheduledPreviewFetches = 0
        for(text in startupPreviewFetchTextSequence(clipboardHistory.toList())) {
            if(scheduledPreviewFetches >= ClipboardStartupPreviewFetchLimit) break
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

        archiveBackfillForceRunPending = archiveBackfillForceRunPending || forceCompletedVersion
        val requests = archiveBackfillRequests(
            entries = clipboardHistory.toList(),
            existingArchiveKeys = linkArchives.keys,
            attemptedArchiveKeys = archiveBackfillAttemptedKeys,
            deletedArchiveKeys = deletedArchiveKeys,
            limit = ClipboardArchiveBackfillConcurrency
        )
        val shouldCompleteMigration = !forceCompletedVersion &&
            context.getSetting(ClipboardArchiveBackfillCompletedVersion) < ClipboardArchiveBackfillVersion
        if(requests.isEmpty()) {
            archiveBackfillForceRunPending = false
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
                        candidate = candidate,
                        pixivSessionId = context.pixivSessionIdForClipboardPreviews(),
                        redditAccessToken = context.redditAccessTokenForClipboardPreviews(),
                        fanboxSessionId = context.fanboxSessionIdForClipboardPreviews()
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
            val archives = linkArchives.values.toList()
            val blockedProviders = archives
                .map { it.provider }
                .distinct()
                .filter { providerCooldown(it) != null }
                .toSet()
            val archiveKeys = withContext(Dispatchers.Default) {
                providerArchiveDownloadResumeKeys(
                    archives = archives,
                    existingArchiveFileNames = existingArchiveFileNames,
                    isRetryBlocked = { it.provider in blockedProviders }
                )
            }
            archiveResumeQueue.enqueue(archiveKeys)
            launchPendingProviderArchiveDownloads()
        }
    }

    private fun launchPendingProviderArchiveDownloads() {
        archiveResumeQueue.takeAvailable { archiveKey ->
            linkArchives[archiveKey]?.let { archive ->
                !isArchiveDownloadActive(archiveKey) && !isArchiveRetryBlockedByCooldown(archive)
            } == true
        }.forEach { archiveKey ->
            launchArchiveDownload(
                archiveKey = archiveKey,
                onFinished = {
                    archiveResumeQueue.finished(archiveKey)
                    launchPendingProviderArchiveDownloads()
                }
            ) {
                downloadArchiveMedia(text = null, archiveKey = archiveKey)
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
        if(archiveBackfillRemainingCount.value == 0 && !archiveBackfillBlockedByCooldown) {
            if(archiveBackfillCompletionPending || archiveBackfillForceRunPending) {
                runLegacyArchiveBackfillIfNeeded(archiveBackfillForceRunPending)
            }
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
        imageTagEligibleCount = imageTagEligibleCount()
    )

    internal fun archiveActivitySnapshot(): ClipboardArchiveActivitySnapshot {
        val downloadState = archiveDownloadCoordinator.snapshot()
        return ClipboardArchiveActivitySnapshot(
            loadingArchiveKeys = previewLoadingByText.filterValues { it }.keys.toSet(),
            progressByArchiveKey = downloadState.progressByArchiveKey,
            imageTaggingState = imageTagCoordinator.state.value
        )
    }

    internal fun archiveDownloadItemsForUi(): List<ClipboardArchiveDownloadListItem> {
        val downloadState = archiveDownloadCoordinator.snapshot()
        return archiveDownloadItems(
            archives = linkArchives.values,
            progressByArchiveKey = downloadState.progressByArchiveKey,
            loadingArchiveKeys = previewLoadingByText.filterValues { it }.keys,
            queuedSourceUrlsByArchiveKey = downloadState.queuedSourceUrlsByArchiveKey,
            cooldownsByProvider = downloadState.cooldownsByProvider,
            existingArchiveFileNames = currentArchiveFileNames()
        )
    }

    internal fun archiveGalleryItems(archive: ClipboardLinkArchive): List<ClipboardArchiveGalleryItem> =
        archive.galleryItems(context.clipboardDir, currentArchiveFileNames())

    internal fun tagExistingArchiveImages() {
        if(!context.getSetting(ClipboardImageTaggingEnabled)) return
        val archives = linkArchives.values.toList()
        coroutineScope.launch {
            val requests = withContext(Dispatchers.IO) {
                imageTagEligibleRequests(archives)
            }
            imageTagCoordinator.enqueue(requests)
        }
    }

    internal fun tagArchiveMedia(archiveKey: String, sourceIndex: Int) {
        val archive = linkArchives[archiveKey] ?: return
        val media = archive.media.firstOrNull { it.sourceIndex == sourceIndex } ?: return
        coroutineScope.launch {
            withContext(Dispatchers.IO) { imageTagRequest(archive, media) }
                ?.let(imageTagCoordinator::enqueue)
        }
    }

    private fun imageTagEligibleRequests(
        archives: Collection<ClipboardLinkArchive>
    ): List<ClipboardImageTagRequest> =
        archives.flatMap { archive ->
            archive.media.mapNotNull { media ->
                if(media.needsImageTagging()) {
                    imageTagRequest(archive, media)
                } else {
                    null
                }
            }
        }

    private fun imageTagEligibleCount(): Int =
        linkArchives.values.sumOf { archive ->
            archive.media.count { it.needsImageTagging() }
        }

    private fun ClipboardArchiveMedia.needsImageTagging(): Boolean =
        status == ClipboardArchiveMediaStatus.Saved &&
            fileName != null &&
            imageTagging?.modelRevision != ClipboardImageTagModelRevision

    private fun imageTagRequest(
        archive: ClipboardLinkArchive,
        media: ClipboardArchiveMedia
    ): ClipboardImageTagRequest? {
        val fileName = media.fileName ?: return null
        val mediaFile = File(context.clipboardDir, fileName).takeIf { it.isFile } ?: return null
        val mimeType = media.mimeType ?: mediaFile.guessedClipboardMimeType()
        val inputFile = if(
            mimeType == "image/jpeg" ||
            mimeType == "image/png" ||
            mediaFile.extension.lowercase() in setOf("jpg", "jpeg", "png")
        ) {
            mediaFile
        } else {
            ClipboardUtil.thumbnailFor(mediaFile).takeIf { it.isFile }
        }
        return ClipboardImageTagRequest(
            archiveKey = archive.key,
            sourceIndex = media.sourceIndex,
            inputFile = inputFile
        )
    }

    private fun applyImageTaggingResult(
        request: ClipboardImageTagRequest,
        result: ClipboardImageTaggingResult
    ) {
        val archive = linkArchives[request.archiveKey] ?: return
        val updated = reduceArchive(
            archive,
            ClipboardArchiveEvent.MediaTagged(request.sourceIndex, result)
        ) ?: return
        linkArchives[updated.key] = updated
        queueArchiveSave(updated)
    }

    internal fun hasActiveArchiveDownloads(): Boolean =
        archiveResumeQueue.hasPendingOrActive() ||
            archiveDownloadCoordinator.snapshot().activeArchiveKeys.isNotEmpty() ||
            previewLoadingByText.keys.any { it in linkArchives }

    internal suspend fun refreshClipboardStorageInventory(): ClipboardStorageInventory {
        val (entries, archives) = withContext(Dispatchers.Main) {
            clipboardHistory.toList() to linkArchives.values.toList()
        }
        val inventory = withContext(ClipboardIOContext) {
            archiveStore.storageInventory(entries, archives)
        }
        withContext(Dispatchers.Main) {
            clipboardStorageSnapshot.value = ClipboardStorageSnapshot(
                fileNames = inventory.mediaFileNames,
                inventory = inventory
            )
        }
        return inventory
    }

    internal suspend fun deleteUnreferencedClipboardMedia(): Boolean {
        val snapshots = withContext(Dispatchers.Main) {
            if(storageCleanupInProgress || hasActiveArchiveDownloads()) {
                null
            } else {
                storageCleanupInProgress = true
                Triple(
                    clipboardHistory.toList(),
                    linkArchives.values.toList(),
                    clipboardStorageSnapshot.value.inventory.unreferencedMediaFileNames
                )
            }
        } ?: return false
        return try {
            val inventory = withContext(ClipboardIOContext) {
                archiveStore.deleteUnreferencedMedia(snapshots.first, snapshots.second, snapshots.third)
            }
            withContext(Dispatchers.Main) {
                clipboardStorageSnapshot.value = ClipboardStorageSnapshot(
                    fileNames = inventory.mediaFileNames,
                    inventory = inventory
                )
            }
            true
        } finally {
            withContext(Dispatchers.Main) {
                storageCleanupInProgress = false
            }
        }
    }

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
            queueEntriesPreviewFromArchive(updated)
            queueArchiveSave(updated)
        }
        return updated
    }

    private fun scanArchiveFileNames(archives: Collection<ClipboardLinkArchive>): Set<String> =
        existingReferencedClipboardArchiveFileNames(
            archives,
            context.clipboardDir,
            context.clipboardArchiveDir
        )

    private suspend fun refreshArchiveFileNames(): Set<String> {
        val archives = withContext(Dispatchers.Main) { linkArchives.values.toList() }
        val fileNames = withContext(ClipboardIOContext) { scanArchiveFileNames(archives) }
        withContext(Dispatchers.Main) { applyArchiveFileNames(fileNames) }
        return fileNames
    }

    private fun applyArchiveFileNames(fileNames: Set<String>) {
        clipboardStorageSnapshot.value = clipboardStorageSnapshot.value.copy(fileNames = fileNames.toSet())
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
        clipboardStorageSnapshot.value = clipboardStorageSnapshot.value.copy(
            fileNames = clipboardStorageSnapshot.value.fileNames + savedFileNames
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
        queueEntriesPreviewFromArchive(updated)
        queueArchiveSave(updated)
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
        existingMediaNames: Set<String> = currentArchiveFileNames(),
        updateEntries: Boolean = true
    ) {
        coroutineScope.launch {
            val resolvedEntries = if(updateEntries) resolveClipboardEntryArchiveKeys() else null
            val archive = linkArchives[archiveKey]
            cancelArchiveDownloadState(archiveKey)
            linkArchives.remove(archiveKey)
            tombstoneArchiveKey(archiveKey, reason = "user")
            val archivedFileNames = archive?.media.orEmpty().mapNotNull { it.fileName }.toSet()
            val matchingEntryKeys = if(resolvedEntries != null) {
                resolvedEntries.archiveKeyByEntryKey
                    .filterValues { it == archiveKey }
                    .keys
            } else {
                emptySet()
            }
            if(matchingEntryKeys.isNotEmpty()) {
                clearArchiveFromEntries(matchingEntryKeys, archivedFileNames, existingMediaNames)
            }
            val tombstones = archiveTombstonesByKey.values.toList()
            val entriesSnapshot = clipboardHistory.toList()
            withContext(ClipboardIOContext) {
                saveArchiveTombstones(tombstones)
                deleteArchiveMetadataFile(archiveKey)
                archivedFileNames.forEach { fileName ->
                    File(context.clipboardArchiveDir, fileName).delete()
                    File(context.clipboardArchiveDir, ClipboardUtil.thumbnailForName(fileName)).delete()
                }
                orphanedSharedArchiveFileNamesAfterArchiveDelete(archivedFileNames, entriesSnapshot)
                    .forEach { fileName -> File(context.clipboardDir, fileName).delete() }
                refreshArchiveFileNames()
            }
            saveClipboard(reconcileBeforeSave = true)
        }
    }

    private fun clearArchiveFromEntries(
        matchingEntryKeys: Set<String>,
        archivedFileNames: Set<String>,
        existingMediaNames: Set<String>
    ) {
        for(i in clipboardHistory.indices) {
            val current = clipboardHistory[i]
            if(current.selectionKey() !in matchingEntryKeys) continue
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
        queueEntriesPreviewFromArchive(updated)
        queueArchiveSave(updated)
    }

    private fun cancelArchiveDownloadState(archiveKey: String) {
        previewLoadingByText.remove(archiveKey)
        archiveResumeQueue.remove(archiveKey)
        archiveDownloadCoordinator.cancel(archiveKey)
    }

    fun onPaste(item: ClipboardEntry) {
        val itemPos = clipboardHistory.indexOf(item).coerceAtLeast(0)
        clipboardHistory.removeAll { it == item }
        clipboardHistory.add(itemPos, item.copy(timestamp = System.currentTimeMillis()))
        saveClipboard(reconcileBeforeSave = true)
    }

    fun onTogglePin(item: ClipboardEntry) {
        val itemKey = item.selectionKey()
        coroutineScope.launch {
            pinMutationMutex.withLock {
                val currentItem = clipboardHistory.lastOrNull { it.selectionKey() == itemKey }
                    ?: return@withLock
                val updatedItem = currentItem.copy(
                    pinned = !currentItem.pinned,
                    timestamp = System.currentTimeMillis()
                )
                withContext(ClipboardPinIOContext) {
                    pinMutationJournal.record(listOf(currentItem, updatedItem), updatedItem.pinned)
                }
                val itemPos = clipboardHistory.indexOf(currentItem)
                val targetPos = if(context.getSetting(ClipboardShowPinnedOnTop)) {
                    clipboardHistory.size - 1
                } else {
                    itemPos
                }

                replaceEntries(
                    clipboardHistory.toMutableList().apply {
                        removeAll { it.selectionKey() == itemKey }
                        add(
                            targetPos.coerceIn(0, size),
                            updatedItem
                        )
                    }
                )
                saveClipboard(reconcileBeforeSave = false)
            }
        }
    }

    fun onRemove(item: ClipboardEntry) {
        removeAll(listOf(item))
    }

    fun removeAll(items: Collection<ClipboardEntry>) {
        if(items.isEmpty()) return

        val itemsToRemove = items.toList()
        coroutineScope.launch {
            val resolvedEntries = resolveClipboardEntryArchiveKeys()
            val archiveKeysToDelete = archiveKeysOnlyReferencedBy(itemsToRemove, resolvedEntries)
            applyEntryMutations(itemsToRemove) { null }
            clearPrimaryClipIfNeeded(itemsToRemove)

            if(archiveKeysToDelete.isNotEmpty()) {
                val existingMediaNames = withContext(ClipboardIOContext) {
                    existingClipboardMediaFileNames(context.clipboardDir)
                }
                archiveKeysToDelete.forEach {
                    deleteArchiveByKey(it, existingMediaNames, updateEntries = false)
                }
            }
        }
    }

    fun setPinned(items: Collection<ClipboardEntry>, pinned: Boolean) {
        if(items.isEmpty()) return

        val now = System.currentTimeMillis()
        fun updated(entry: ClipboardEntry): ClipboardEntry =
            if(entry.pinned == pinned) {
                entry
            } else {
                entry.copy(
                    pinned = pinned,
                    timestamp = now
                )
            }

        val itemsSnapshot = items.toList()
        coroutineScope.launch {
            pinMutationMutex.withLock {
                val itemKeys = itemsSnapshot.map { it.selectionKey() }.toSet()
                val affectedEntries = clipboardHistory.filter { it.selectionKey() in itemKeys }
                withContext(ClipboardPinIOContext) {
                    pinMutationJournal.record(
                        affectedEntries.flatMap { listOf(it, updated(it)) },
                        pinned
                    )
                }
                applyEntryMutations(itemsSnapshot, reconcileBeforeSave = false) { entry ->
                    updated(entry)
                }
            }
        }
    }

    override suspend fun cleanUp() {
        flushPendingArchiveSavesOnIo()
        saveClipboard(reconcileBeforeSave = true)?.join()
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
        if(backupImportInProgress) return@withContext
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
            val activeEntries = pinMutationJournal.apply(clearEntryArchiveTombstones(loadedEntries))
            val loadedArchives = filterDeletedClipboardArchives(
                archives = storedArchives.archives,
                deletedArchiveKeys = tombstoneKeys
            )
            val archiveFileNames = scanArchiveFileNames(loadedArchives)

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
            val clipboardStorageChanged = reconcileClipboardStorage()
            if(activeEntries != loadedEntries || clipboardStorageChanged) {
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
                            candidate = request.candidate,
                            pixivSessionId = context.pixivSessionIdForClipboardPreviews(),
                            redditAccessToken = context.redditAccessTokenForClipboardPreviews(),
                            fanboxSessionId = context.fanboxSessionIdForClipboardPreviews()
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
        reconcileBeforeSave: Boolean = true,
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
        saveClipboard(reconcileBeforeSave = reconcileBeforeSave)
    }

    private suspend fun resolveClipboardEntryArchiveKeys(): ResolvedClipboardEntries {
        while(true) {
            val entries = clipboardHistory.toList()
            val archiveKeyByEntryKey = withContext(Dispatchers.Default) {
                entries.mapNotNull { entry ->
                    entry.resolvedArchiveKey()?.let { entry.selectionKey() to it }
                }.toMap()
            }
            if(clipboardHistory.toList() == entries) {
                return ResolvedClipboardEntries(entries, archiveKeyByEntryKey)
            }
        }
    }

    private fun archiveKeysOnlyReferencedBy(
        items: Collection<ClipboardEntry>,
        resolvedEntries: ResolvedClipboardEntries
    ): List<String> {
        val itemKeys = items.map { it.selectionKey() }.toSet()
        val remainingArchiveKeys = resolvedEntries.entries
            .asSequence()
            .filter { it.selectionKey() !in itemKeys }
            .mapNotNull { resolvedEntries.archiveKeyByEntryKey[it.selectionKey()] }
            .toSet()
        return items
            .mapNotNull { resolvedEntries.archiveKeyByEntryKey[it.selectionKey()] }
            .distinct()
            .filterNot { it in remainingArchiveKeys }
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

    private suspend fun createOrUpdateArchive(
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
        flushArchiveSave(updated.key)
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
        flushArchiveSave(archive.key)
        return archive
    }

    private suspend fun refetchArchiveManifest(archive: ClipboardLinkArchive) {
        providerCooldown(archive.provider)?.let { return }
        val manifestResult = withContext(ClipboardPreviewFetchContext) {
            ClipboardLinkPreviewFetcher.fetchManifestResult(
                rawText = archive.sourceUrl,
                pixivSessionId = context.pixivSessionIdForClipboardPreviews(),
                redditAccessToken = context.redditAccessTokenForClipboardPreviews(),
                fanboxSessionId = context.fanboxSessionIdForClipboardPreviews()
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
        if(withContext(Dispatchers.Main) { storageCleanupInProgress }) return
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
        onFinished: suspend () -> Unit = {},
        block: suspend () -> Unit
    ): Job? {
        if(isArchiveDownloadActive(archiveKey)) return null
        return archiveDownloadCoordinator.launch(
            archiveKey = archiveKey,
            block = { withArchiveLoading(archiveKey, block) },
            onFinished = {
                flushArchiveSave(archiveKey)
                flushPreviewSave(reconcileBeforeSave = false)
                launchQueuedArchiveDownloadIfNeeded(archiveKey)
                onFinished()
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
                flushArchiveSave(archive.key)
                if(event is ClipboardArchiveEvent.MediaDownloadSaved &&
                    context.getSetting(ClipboardImageTaggingEnabled)
                ) {
                    archive.media
                        .firstOrNull { it.sourceUrl == event.sourceUrl }
                        ?.let { imageTagRequest(archive, it) }
                        ?.let(imageTagCoordinator::enqueue)
                }
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
                queueEntriesPreviewFromArchive(archive, attemptedAt)
            }
        }
    }

    private fun queueEntriesPreviewFromArchive(
        archive: ClipboardLinkArchive,
        attemptedAt: Long = System.currentTimeMillis()
    ) {
        pendingArchiveEntryUpdates[archive.key] = PendingArchiveEntryUpdate(archive, attemptedAt)
        if(archiveEntryUpdateJob?.isActive == true) return

        archiveEntryUpdateJob = coroutineScope.launch {
            while(pendingArchiveEntryUpdates.isNotEmpty()) {
                val updates = pendingArchiveEntryUpdates.toMap()
                pendingArchiveEntryUpdates.clear()
                val resolvedEntries = resolveClipboardEntryArchiveKeys()
                val savedMediaByArchiveKey = updates.mapValues { it.value.archive.savedPreviewMedia() }
                var changed = false
                for(i in clipboardHistory.indices) {
                    val current = clipboardHistory[i]
                    val archiveKey = resolvedEntries.archiveKeyByEntryKey[current.selectionKey()] ?: continue
                    val update = updates[archiveKey] ?: continue
                    clipboardHistory[i] = current.withArchivePreviewMedia(
                        update.archive,
                        savedMediaByArchiveKey.getValue(archiveKey),
                        update.attemptedAt
                    )
                    changed = true
                }
                if(changed) saveClipboard(reconcileBeforeSave = false)
            }
        }
    }

    private fun archiveForEntry(entry: ClipboardEntry): ClipboardLinkArchive? =
        entry.previewMetadata?.archiveKey()?.let { linkArchives[it] }

    private fun saveArchive(archive: ClipboardLinkArchive) {
        if(backupImportInProgress) return
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
        if(!pendingArchiveSaves.enqueue(archive)) return
        coroutineScope.launch {
            delay(delayMillis)
            flushPendingArchiveSavesOnIo()
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
        pendingArchiveSaves.remove(archiveKey)

    private suspend fun flushPendingArchiveSavesOnIo() {
        val archives = pendingArchiveSaves.drain()
        if(archives.isEmpty()) return
        withContext(NonCancellable + ClipboardIOContext) {
            archives.forEach(::saveArchive)
        }
    }

    private fun saveArchiveTombstones(tombstones: Collection<ClipboardArchiveTombstone>) {
        if(backupImportInProgress) return
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
        if(backupImportInProgress) return
        archiveStore.deleteArchiveMetadata(archiveKey)
    }

    private fun deleteStaleArchiveMetadataFiles(retainedArchiveKeys: Set<String>) {
        if(backupImportInProgress) return
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
            val changedArchives = reconciled.filter { archiveMapSnapshot[it.key] != it }
            withContext(Dispatchers.Main) {
                linkArchives.clear()
                linkArchives.putAll(reconciled.associateBy { it.key })
            }
            changedArchives.forEach(::saveArchive)
        }
        deleteStaleArchiveMetadataFiles(reconciled.map { it.key }.toSet())

        context.clipboardArchiveDir.listFiles()?.forEach { file ->
            if(file.name !in referencedArchiveFiles) {
                file.delete()
            }
        }
        context.clipboardArchiveDir.delete()
        refreshArchiveFileNames()
    }
}
