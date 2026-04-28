package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
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
import android.webkit.MimeTypeMap
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
private val ClipboardIOContext = Dispatchers.IO.limitedParallelism(1)
@OptIn(ExperimentalCoroutinesApi::class)
private val ClipboardPreviewFetchContext = Dispatchers.IO.limitedParallelism(3)
private const val ClipboardStoredMediaMaxBytes = 50L * 1024L * 1024L

private data class ClipboardPreviewFetchRequest(
    val text: String,
    val maxAttempts: Int
)

class ClipboardHistoryManager(
    val context: Context,
    val coroutineScope: LifecycleCoroutineScope
) : PersistentActionState {
    var clipboardIOFailureReason = ""
    val clipboardIOFailure = mutableStateOf(false)
    val previewLoadingByText = mutableStateMapOf<String, Boolean>()

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
    private val archiveFileSwap = File(context.filesDir, "$ClipboardArchiveFileName.swap")

    private var scheduledPreviewSaveJob: Job? = null
    private var saveClipboardLoadJob: Job? = null
    private var clipboardLoaded = false

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
            previewImageFile = preservedEntry?.previewImageFile,
            previewMediaFiles = preservedEntry?.previewMedia().orEmpty(),
            previewMetadata = preservedEntry?.previewMetadata,
            previewFetchStatus = preservedEntry?.previewFetchStatus
                ?: ClipboardPreviewFetchStatus.NeverAttempted,
            previewFetchLastAttemptAt = preservedEntry?.previewFetchLastAttemptAt
        )
        clipboardHistory.add(newEntry)

        if(newEntry.canAutoFetchPreview()) {
            fetchPreviewForEntry(text)
        }

        saveClipboard()
    }

    private fun importMediaEntry(timestamp: Long, uri: android.net.Uri, mimeTypes: List<String>) {
        try {
            val targetMime = mimeTypes.firstOrNull {
                it.startsWith("image/") || it.startsWith("video/")
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

            val isAlreadyPinned = clipboardHistory.firstOrNull {
                it.backingFile == finalFile.name && it.pinned
            }?.pinned == true

            clipboardHistory.removeAll { it.backingFile == finalFile.name }
            clipboardHistory.add(
                ClipboardEntry(
                    timestamp = timestamp,
                    pinned = isAlreadyPinned,
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
    }

    suspend fun reconcileClipboardStorage() = withContext(Dispatchers.Main) {
        val deduplicated = clipboardHistory.toSet()
        if(deduplicated.size < clipboardHistory.size) {
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
                    File(context.clipboardDir, it.fileName).isFile == true ||
                        File(context.clipboardArchiveDir, it.fileName).isFile == true
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

        val stillReferenced = clipboardHistory
            .flatMap { listOfNotNull(it.backingFile) + it.previewMediaFileNames() }
            .flatMap { listOf(it, ClipboardUtil.thumbnailForName(it)) }
            .toHashSet()

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

                val list = clipboardHistory.toList()
                val encoded = encodeClipboardEntries(list)
                val normalizedList = decodeClipboardEntries(encoded)
                clipboardFileSwap.writeText(encoded)

                val decodedData = decodeFile(clipboardFileSwap)
                if(decodedData != normalizedList) {
                    throw Exception("Saved file data does not match expected data. Decoded: $decodedData, expected: $normalizedList")
                }

                if(clipboardFile.exists() && !clipboardFile.renameTo(clipboardFileBak)) {
                    throw Exception("Failed to move clipboard file backup")
                }

                if(!clipboardFileSwap.renameTo(clipboardFile)) {
                    throw Exception("Failed to swap new clipboard file")
                }

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

    fun refreshMissingLinkPreviews() {
        if(context.getSetting(ClipboardIncognitoMode)) return
        if(!currentPreviewState().shouldFetchPreviews) return

        clipboardHistory.toList().forEach { entry ->
            val text = entry.text ?: return@forEach
            if(entry.canAutoFetchPreview()) {
                fetchPreviewForEntry(text)
            }
        }
    }

    fun retryPreviewForEntry(entry: ClipboardEntry) {
        val text = entry.text ?: return
        fetchPreviewForEntry(text, manualRetry = true)
    }

    fun canRetryPreview(entry: ClipboardEntry): Boolean =
        entry.shouldShowManualPreviewRetry() || archiveForEntry(entry)?.hasRetryableMedia() == true

    fun expectedPreviewMediaCount(entry: ClipboardEntry): Int? =
        archiveForEntry(entry)?.media?.size?.takeIf { it > entry.previewMedia().size }

    fun deleteArchiveForEntry(entry: ClipboardEntry) {
        val archive = archiveForEntry(entry) ?: return
        linkArchives.remove(archive.key)
        archive.media.mapNotNull { it.fileName }.forEach { fileName ->
            File(context.clipboardArchiveDir, fileName).delete()
            File(context.clipboardArchiveDir, ClipboardUtil.thumbnailForName(fileName)).delete()
        }

        val archivedFileNames = archive.media.mapNotNull { it.fileName }.toSet()
        for(i in clipboardHistory.indices) {
            val current = clipboardHistory[i]
            if(current.previewMetadata?.archiveKey() == archive.key) {
                val retainedPreviewMedia = current.previewMedia()
                    .filter { it.fileName !in archivedFileNames }
                clipboardHistory[i] = current.copy(
                    previewImageFile = null,
                    previewMediaFiles = retainedPreviewMedia
                )
            }
        }
        saveArchives()
        saveClipboard()
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
    }

    private fun currentPreviewState(): ClipboardPreviewState =
        previewState(
            linkPreviewsEnabled = context.getSetting(ClipboardLinkPreviewsEnabled),
            storedEmbedDisplayMode = context.getSetting(ClipboardEmbedDisplayModeSetting)
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
        archives: List<ClipboardLinkArchive>
    ) = withContext(Dispatchers.Main) {
        clipboardHistory.clear()
        clipboardHistory.addAll(data)
        linkArchives.clear()
        linkArchives.putAll(archives.associateBy { it.key })
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

            val loadedArchives = if(archiveFile.exists()) {
                runCatching { archiveFile.decodeClipboardArchives() }.getOrElse { emptyList() }
            } else {
                emptyList()
            }

            publishClipboardLoaded(loadedEntries, loadedArchives)
            reconcileClipboardStorage()
            refreshMissingLinkPreviews()
        } catch (e: Exception) {
            publishClipboardLoadFailure("Exception: ${e.message}")
            reportError("loadClipboard", e)
        }
    }

    private fun decodeFile(file: File): List<ClipboardEntry> =
        decodeClipboardEntries(file.readText())

    private fun fetchPreviewForEntry(text: String, manualRetry: Boolean = false) {
        val request = previewFetchRequest(text, manualRetry) ?: return

        coroutineScope.launch {
            previewLoadingByText[request.text] = true
            try {
                var attempt = 0
                var lastFetchedManifest: ClipboardLinkPreviewManifest? = null

                while (attempt < request.maxAttempts) {
                    val manifest = withContext(ClipboardPreviewFetchContext) {
                        ClipboardLinkPreviewFetcher.fetchManifest(request.text)
                    }
                    lastFetchedManifest = manifest ?: lastFetchedManifest

                    if(manifest != null && (manifest.snippet != null || manifest.mediaItems.isNotEmpty() || manifest.metadata != null)) {
                        val attemptedAt = System.currentTimeMillis()
                        val archive = createOrUpdateArchive(manifest, attemptedAt)
                        val initialMedia = archive?.savedPreviewMedia().orEmpty()
                        val updated = updateLatestTextEntry(request.text) { current ->
                            current.copy(
                                previewText = manifest.snippet,
                                previewImageFile = initialMedia.firstOrNull()?.fileName,
                                previewMediaFiles = initialMedia,
                                previewMetadata = manifest.metadata ?: current.previewMetadata,
                                previewFetchStatus = ClipboardPreviewFetchStatus.Success,
                                previewFetchLastAttemptAt = attemptedAt
                            )
                        }

                        if(updated) {
                            queuePreviewSave()
                        }

                        archive?.let {
                            downloadArchiveMedia(request.text, it.key)
                        }
                        return@launch
                    }

                    attempt++
                    if(attempt < request.maxAttempts) {
                        delay(1500L * attempt)
                    }
                }

                val attemptedAt = System.currentTimeMillis()
                val retryArchiveKey = if(manualRetry) {
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
                    downloadArchiveMedia(request.text, retryArchiveKey)
                    return@launch
                }

                val updated = updateLatestTextEntry(request.text) { current ->
                    current.copy(
                        previewMetadata = lastFetchedManifest?.metadata ?: current.previewMetadata,
                        previewFetchStatus = ClipboardPreviewFetchStatus.Failed,
                        previewFetchLastAttemptAt = attemptedAt
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
        if(!ClipboardLinkPreviewFetcher.supportsPreview(text)) return null
        if(previewLoadingByText[text] == true) return null

        val entry = clipboardHistory.lastOrNull { it.text == text } ?: return null
        if(manualRetry) {
            if(!canRetryPreview(entry)) return null
        } else if(!entry.canAutoFetchPreview()) {
            return null
        }

        return ClipboardPreviewFetchRequest(
            text = text,
            maxAttempts = if(ClipboardLinkPreviewFetcher.prefersImagePreview(text)) 3 else 1
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
        val itemSet = items.toHashSet()
        val updatedEntries = buildList {
            clipboardHistory.forEach { entry ->
                if(entry !in itemSet) {
                    add(entry)
                } else {
                    transform(entry)?.let(::add)
                }
            }
        }

        replaceEntries(updatedEntries)
        saveClipboard()
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
        val newArchive = newArchiveFromManifest(manifest, now) ?: return null
        val updated = linkArchives[newArchive.key]?.let {
            mergeArchiveWithManifest(it, manifest, now)
        } ?: newArchive
        linkArchives[newArchive.key] = updated
        saveArchives()
        return updated
    }

    private suspend fun downloadArchiveMedia(text: String, archiveKey: String) {
        withContext(ClipboardPreviewFetchContext) {
            while(true) {
                val media = withContext(Dispatchers.Main) {
                    linkArchives[archiveKey]?.retryableMedia()?.firstOrNull()
                } ?: break

                val attemptedAt = System.currentTimeMillis()
                val result = ClipboardLinkPreviewFetcher.cachePreviewMedia(
                    context = context,
                    mediaUrl = media.sourceUrl,
                    destinationDir = context.clipboardArchiveDir
                )
                val updatedMedia = when (result) {
                    is ClipboardPreviewMediaCacheResult.Saved -> media.copy(
                        fileName = result.fileName,
                        mimeType = result.mimeType ?: media.mimeType,
                        status = ClipboardArchiveMediaStatus.Saved,
                        lastAttemptAtEpochMs = attemptedAt
                    )
                    ClipboardPreviewMediaCacheResult.Failed -> media.copy(
                        status = ClipboardArchiveMediaStatus.Failed,
                        lastAttemptAtEpochMs = attemptedAt
                    )
                    ClipboardPreviewMediaCacheResult.SkippedTooLarge -> media.copy(
                        status = ClipboardArchiveMediaStatus.SkippedTooLarge,
                        lastAttemptAtEpochMs = attemptedAt
                    )
                }

                val archive = updateArchiveMedia(archiveKey, updatedMedia, attemptedAt) ?: continue
                updateEntryPreviewFromArchive(text, archive, attemptedAt)
                saveArchives()
                queuePreviewSave(delayMillis = 0L)
            }
        }
    }

    private suspend fun updateArchiveMedia(
        archiveKey: String,
        updatedMedia: ClipboardArchiveMedia,
        now: Long
    ): ClipboardLinkArchive? = withContext(Dispatchers.Main) {
        val archive = linkArchives[archiveKey] ?: return@withContext null
        val updated = archive.copy(
            media = archive.media.map {
                if(it.sourceUrl == updatedMedia.sourceUrl) updatedMedia else it
            },
            updatedAtEpochMs = now
        )
        linkArchives[archiveKey] = updated
        updated
    }

    private suspend fun updateEntryPreviewFromArchive(
        text: String,
        archive: ClipboardLinkArchive,
        attemptedAt: Long
    ) {
        val savedMedia = archive.savedPreviewMedia()
        updateLatestTextEntry(text) { current ->
            current.copy(
                previewImageFile = savedMedia.firstOrNull()?.fileName,
                previewMediaFiles = savedMedia,
                previewMetadata = archive.metadata ?: current.previewMetadata,
                previewFetchStatus = if(current.previewText != null || savedMedia.isNotEmpty()) {
                    ClipboardPreviewFetchStatus.Success
                } else {
                    current.previewFetchStatus
                },
                previewFetchLastAttemptAt = attemptedAt
            )
        }
    }

    private fun archiveForEntry(entry: ClipboardEntry): ClipboardLinkArchive? =
        entry.previewMetadata?.archiveKey()?.let { linkArchives[it] }

    private fun saveArchives() {
        if(!context.isDirectBootUnlocked) return
        val archives = linkArchives.values.toList()
        try {
            val encoded = encodeClipboardArchives(archives)
            archiveFileSwap.writeText(encoded)

            if(archiveFile.exists() && !archiveFile.renameTo(archiveFileBak)) {
                throw Exception("Failed to move clipboard archive backup")
            }

            if(!archiveFileSwap.renameTo(archiveFile)) {
                throw Exception("Failed to swap clipboard archive file")
            }
        } catch(e: Exception) {
            clipboardIOFailure.value = true
            clipboardIOFailureReason = e.toString()
            reportError("saveArchives", e)
        }
    }

    private fun reconcileArchiveStorage() {
        val reconciled = reconcileClipboardArchivesWithStorage(
            archives = linkArchives.values,
            archiveDir = context.clipboardArchiveDir
        )
        if(reconciled.associateBy { it.key } != linkArchives.toMap()) {
            linkArchives.clear()
            linkArchives.putAll(reconciled.associateBy { it.key })
            saveArchives()
        }

        val stillReferenced = referencedClipboardArchiveFileNames(linkArchives.values)
        context.clipboardArchiveDir.listFiles()?.forEach { file ->
            if(file.name !in stillReferenced) {
                file.delete()
            }
        }
    }
}
