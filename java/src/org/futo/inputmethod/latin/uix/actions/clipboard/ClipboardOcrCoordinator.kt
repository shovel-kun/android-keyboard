package org.futo.inputmethod.latin.uix.actions.clipboard

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class ClipboardOcrRequest(
    val archiveKey: String,
    val sourceIndex: Int,
    val input: ClipboardOcrInput,
    val file: File
)

internal class ClipboardOcrCoordinator(
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher,
    private val factory: () -> ClipboardOcr,
    private val onResult: (ClipboardOcrRequest, ClipboardOcrResult) -> Unit
) {
    private val pending = linkedSetOf<ClipboardOcrRequest>()
    private var active: ClipboardOcrRequest? = null
    private var job: Job? = null
    val requests = mutableStateOf<Set<ClipboardOcrRequest>>(emptySet())

    fun enqueue(requests: Collection<ClipboardOcrRequest>) {
        pending.addAll(requests.filter { it != active })
        publish()
        if(job?.isActive == true || pending.isEmpty()) return
        job = scope.launch {
            var ocr: ClipboardOcr? = null
            try {
                val engine = try {
                    // Retain ownership even if cancellation discards the dispatched return value.
                    withContext(workerDispatcher) { factory().also { ocr = it } }
                } catch(e: CancellationException) {
                    throw e
                } catch(e: Exception) {
                    Log.w("ClipboardOcr", "OCR initialization failed", e)
                    pending.toList().forEach { onResult(it, failure(it, System.currentTimeMillis())) }
                    pending.clear()
                    return@launch
                }
                while(pending.isNotEmpty()) {
                    val request = pending.first()
                    pending.remove(request)
                    active = request
                    publish()
                    val attemptedAt = System.currentTimeMillis()
                    val result = try {
                        withContext(workerDispatcher) { engine.extract(request.file, request.input, attemptedAt) }
                    } catch(e: CancellationException) {
                        throw e
                    } catch(e: Exception) {
                        Log.w("ClipboardOcr", "Text extraction failed", e)
                        failure(request, attemptedAt)
                    }
                    onResult(request, result)
                    active = null
                    publish()
                }
            } finally {
                try {
                    withContext(NonCancellable + workerDispatcher) { ocr?.close() }
                } finally {
                    active = null
                    job = null
                    publish()
                    // Requests can arrive while the worker is closing its models.
                    if(currentCoroutineContext().isActive && pending.isNotEmpty()) enqueue(emptyList())
                }
            }
        }
    }

    suspend fun cancelAll() {
        pending.clear()
        job?.cancelAndJoin()
        pending.clear()
        publish()
    }

    private fun failure(request: ClipboardOcrRequest, attemptedAt: Long) =
        ClipboardOcrResult(request.input, ClipboardOcrModelRevision, attemptedAt, failed = true)

    private fun publish() {
        requests.value = pending.toSet() + listOfNotNull(active)
    }
}
