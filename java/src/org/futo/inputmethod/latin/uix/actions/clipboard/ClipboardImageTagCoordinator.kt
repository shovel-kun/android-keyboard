package org.futo.inputmethod.latin.uix.actions.clipboard

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class ClipboardImageTagRequest(
    val archiveKey: String,
    val sourceIndex: Int,
    val inputFile: File?
)

internal data class ClipboardImageTaggingState(
    val remainingCount: Int = 0
)

internal class ClipboardImageTagCoordinator(
    private val scope: CoroutineScope,
    private val taggerFactory: () -> ClipboardImageTagger,
    private val onResult: (ClipboardImageTagRequest, ClipboardImageTaggingResult) -> Unit,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val now: () -> Long = System::currentTimeMillis
) {
    private val pending = linkedSetOf<ClipboardImageTagRequest>()
    private var activeRequest: ClipboardImageTagRequest? = null
    private var drainJob: Job? = null

    val state = mutableStateOf(ClipboardImageTaggingState())

    fun enqueue(request: ClipboardImageTagRequest) {
        enqueue(listOf(request))
    }

    fun enqueue(requests: Collection<ClipboardImageTagRequest>) {
        val changed = requests
            .filter { it != activeRequest }
            .fold(false) { changed, request -> pending.add(request) || changed }
        if(!changed) return
        publishState()
        if(drainJob?.isActive != true) {
            drainJob = scope.launch { drain() }
        }
    }

    private suspend fun drain() {
        val tagger = try {
            withContext(workerDispatcher) { taggerFactory() }
        } catch(e: CancellationException) {
            throw e
        } catch(_: Exception) {
            failPendingRequests()
            return
        }

        try {
            while(pending.isNotEmpty()) {
                val request = pending.first()
                pending.remove(request)
                activeRequest = request
                publishState()

                val attemptedAt = now()
                val inputFile = request.inputFile
                val result = if(inputFile?.isFile == true) {
                    withContext(workerDispatcher) { tagger.tag(inputFile, attemptedAt) }
                } else {
                    ClipboardImageTaggingResult(
                        modelRevision = ClipboardImageTagModelRevision,
                        attemptedAtEpochMs = attemptedAt,
                        failure = ClipboardImageTaggingFailure.UnsupportedInput
                    )
                }
                onResult(request, result)
                activeRequest = null
                publishState()
            }
        } finally {
            withContext(workerDispatcher) { tagger.close() }
            activeRequest = null
            drainJob = null
            publishState()
            if(pending.isNotEmpty()) {
                drainJob = scope.launch { drain() }
            }
        }
    }

    private fun failPendingRequests() {
        val attemptedAt = now()
        pending.toList().forEach { request ->
            onResult(
                request,
                ClipboardImageTaggingResult(
                    modelRevision = ClipboardImageTagModelRevision,
                    attemptedAtEpochMs = attemptedAt,
                    failure = ClipboardImageTaggingFailure.InferenceFailed
                )
            )
        }
        pending.clear()
        drainJob = null
        publishState()
    }

    private fun publishState() {
        state.value = ClipboardImageTaggingState(
            remainingCount = pending.size + if(activeRequest == null) 0 else 1
        )
    }
}
