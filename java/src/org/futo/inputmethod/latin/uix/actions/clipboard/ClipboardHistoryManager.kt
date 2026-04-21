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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.futo.inputmethod.latin.uix.PersistentActionState
import org.futo.inputmethod.latin.uix.QuickClip
import org.futo.inputmethod.latin.uix.actions.BugInfo
import org.futo.inputmethod.latin.uix.actions.BugViewerState
import org.futo.inputmethod.latin.uix.actions.throwIfDebug
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.getUnlockedSetting
import org.futo.inputmethod.latin.uix.isDirectBootUnlocked
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
private val ClipboardIOContext = Dispatchers.IO.limitedParallelism(1)
@OptIn(ExperimentalCoroutinesApi::class)
private val ClipboardPreviewFetchContext = Dispatchers.IO.limitedParallelism(3)

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

    private val clipboardFile = context.clipboardFile
    private val clipboardFileBak = File(context.filesDir, "$ClipboardFileName.bak")
    private val clipboardFileSwap = File(context.filesDir, "$ClipboardFileName.swap")

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
                uri != null -> importImageEntry(timestamp, uri, mimeTypes)
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

    private fun importImageEntry(timestamp: Long, uri: android.net.Uri, mimeTypes: List<String>) {
        try {
            val targetMime = mimeTypes.firstOrNull { it == "image/png" }
                ?: mimeTypes.firstOrNull { it == "image/jpeg" || it == "image/jpg" }
                ?: mimeTypes.firstOrNull { it == "image/webp" }
                ?: return

            val resolver = context.contentResolver
            val stream = resolver.openInputStream(uri) ?: return
            val md = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8 * 1024)
            var totalBytes = 0L
            var bytesRead: Int

            val tempFile = File(context.cacheDir, "temp_img")
            tempFile.outputStream().use { out ->
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    totalBytes += bytesRead
                    if(totalBytes > 10 * 1024 * 1024) {
                        tempFile.delete()
                        return
                    }
                    md.update(buffer, 0, bytesRead)
                    out.write(buffer, 0, bytesRead)
                }
            }
            stream.close()

            val md5Hex = md.digest().joinToString("") { "%02x".format(it) }
            val extension = when (targetMime) {
                "image/png" -> "png"
                "image/jpeg", "image/jpg" -> "jpg"
                "image/webp" -> "webp"
                else -> "img"
            }

            context.clipboardDir.mkdirs()
            val finalFile = File(context.clipboardDir, "$md5Hex.$extension")
            if(!finalFile.exists()) {
                tempFile.renameTo(finalFile)
                ClipboardUtil.generateThumbnail(finalFile)
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
            if(entry.previewImageFile != null && entry.getPreviewFile(context)?.isFile != true) {
                clipboardHistory[i] = entry.copy(
                    previewImageFile = null,
                    previewFetchStatus = if(
                        entry.previewFetchStatus == ClipboardPreviewFetchStatus.Success &&
                        entry.previewText == null
                    ) {
                        ClipboardPreviewFetchStatus.NeverAttempted
                    } else {
                        entry.previewFetchStatus
                    }
                )
            }
        }

        val stillReferenced = clipboardHistory
            .flatMap { listOfNotNull(it.backingFile, it.previewImageFile) }
            .flatMap { listOf(it, ClipboardUtil.thumbnailForName(it)) }
            .toHashSet()

        context.clipboardDir.listFiles()?.forEach {
            if(it.name !in stillReferenced) {
                it.delete()
            }
        }
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
                clipboardFileSwap.writeText(Json.encodeToString(list))

                val decodedData = decodeFile(clipboardFileSwap)
                if(decodedData != list) {
                    throw Exception("Saved file data does not match expected data. Decoded: $decodedData, expected: $list")
                }

                if(clipboardFile.exists() && !clipboardFile.renameTo(clipboardFileBak)) {
                    throw Exception("Failed to move clipboard file backup")
                }

                if(!clipboardFileSwap.renameTo(clipboardFile)) {
                    throw Exception("Failed to swap new clipboard file")
                }

                if(decodeFile(clipboardFile) != list) {
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

    private suspend fun publishClipboardLoaded(data: List<ClipboardEntry>) = withContext(Dispatchers.Main) {
        clipboardHistory.clear()
        clipboardHistory.addAll(data)
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

            publishClipboardLoaded(loadedEntries)
            reconcileClipboardStorage()
            refreshMissingLinkPreviews()
        } catch (e: Exception) {
            publishClipboardLoadFailure("Exception: ${e.message}")
            reportError("loadClipboard", e)
        }
    }

    private fun decodeFile(file: File): List<ClipboardEntry> =
        Json.decodeFromString(file.readText())

    private fun fetchPreviewForEntry(text: String, manualRetry: Boolean = false) {
        val request = previewFetchRequest(text, manualRetry) ?: return

        coroutineScope.launch {
            previewLoadingByText[request.text] = true
            try {
                var attempt = 0
                var lastFetchedPreview: ClipboardLinkPreview? = null

                while (attempt < request.maxAttempts) {
                    val preview = withContext(ClipboardPreviewFetchContext) {
                        ClipboardLinkPreviewFetcher.fetch(context, request.text)
                    }
                    lastFetchedPreview = preview ?: lastFetchedPreview

                    if(preview != null && (preview.snippet != null || preview.imageFile != null)) {
                        val attemptedAt = System.currentTimeMillis()
                        val updated = updateLatestTextEntry(request.text) { current ->
                            current.copy(
                                previewText = preview.snippet,
                                previewImageFile = preview.imageFile,
                                previewMetadata = preview.metadata,
                                previewFetchStatus = ClipboardPreviewFetchStatus.Success,
                                previewFetchLastAttemptAt = attemptedAt
                            )
                        }

                        if(updated) {
                            queuePreviewSave()
                        }
                        return@launch
                    }

                    attempt++
                    if(attempt < request.maxAttempts) {
                        delay(1500L * attempt)
                    }
                }

                val attemptedAt = System.currentTimeMillis()
                val updated = updateLatestTextEntry(request.text) { current ->
                    current.copy(
                        previewMetadata = lastFetchedPreview?.metadata ?: current.previewMetadata,
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
            if(!entry.shouldShowManualPreviewRetry()) return null
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
}
