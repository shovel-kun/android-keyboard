package org.futo.inputmethod.latin.uix.actions.clipboard

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class ClipboardArchiveDownloadStateSnapshot(
    val progressByArchiveKey: Map<String, ClipboardArchiveDownloadProgress>,
    val queuedSourceUrlsByArchiveKey: Map<String, Set<String>>,
    val cooldownsByProvider: Map<ClipboardPreviewProvider, ClipboardPreviewProviderCooldown>,
    val activeArchiveKeys: Set<String>
)

internal class ClipboardArchiveDownloadCoordinator(
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val jobsByArchiveKey = mutableMapOf<String, Job>()
    private val progressByArchiveKey = mutableStateMapOf<String, ClipboardArchiveDownloadProgress>()
    private val queuedSourceUrlsByArchiveKey = mutableStateMapOf<String, Set<String>>()
    private val cooldownsByProvider = mutableStateMapOf<ClipboardPreviewProvider, ClipboardPreviewProviderCooldown>()

    fun snapshot(): ClipboardArchiveDownloadStateSnapshot = ClipboardArchiveDownloadStateSnapshot(
        progressByArchiveKey = progressByArchiveKey.toMap(),
        queuedSourceUrlsByArchiveKey = queuedSourceUrlsByArchiveKey.mapValues { it.value.toSet() },
        cooldownsByProvider = activeCooldowns(),
        activeArchiveKeys = jobsByArchiveKey.filterValues { it.isActive }.keys.toSet()
    )

    fun progress(archiveKey: String): ClipboardArchiveDownloadProgress? = progressByArchiveKey[archiveKey]

    fun queuedSourceUrls(archiveKey: String): Set<String> =
        queuedSourceUrlsByArchiveKey[archiveKey].orEmpty()

    fun setQueuedSourceUrls(archiveKey: String, sourceUrls: Set<String>) {
        if(sourceUrls.isEmpty()) {
            queuedSourceUrlsByArchiveKey.remove(archiveKey)
        } else {
            queuedSourceUrlsByArchiveKey[archiveKey] = sourceUrls.toSet()
        }
    }

    fun queueSourceUrls(archiveKey: String, sourceUrls: Set<String>) {
        if(sourceUrls.isEmpty()) return
        setQueuedSourceUrls(archiveKey, queuedSourceUrls(archiveKey) + sourceUrls)
    }

    fun removeQueuedSourceUrl(archiveKey: String, sourceUrl: String) {
        setQueuedSourceUrls(archiveKey, queuedSourceUrls(archiveKey) - sourceUrl)
    }

    fun clearQueuedSourceUrls(archiveKey: String) {
        queuedSourceUrlsByArchiveKey.remove(archiveKey)
    }

    fun providerCooldown(provider: ClipboardPreviewProvider): ClipboardPreviewProviderCooldown? {
        val cooldown = cooldownsByProvider[provider] ?: return null
        if(cooldown.retryAfterEpochMs <= now()) {
            cooldownsByProvider.remove(provider)
            return null
        }
        return cooldown
    }

    fun activeCooldowns(): Map<ClipboardPreviewProvider, ClipboardPreviewProviderCooldown> {
        cooldownsByProvider.keys.toList().forEach(::providerCooldown)
        return cooldownsByProvider.toMap()
    }

    fun setProviderCooldown(cooldown: ClipboardPreviewProviderCooldown) {
        val current = cooldownsByProvider[cooldown.provider]
        if(current == null || cooldown.retryAfterEpochMs > current.retryAfterEpochMs) {
            cooldownsByProvider[cooldown.provider] = cooldown
        }
    }

    fun publishProgress(progress: ClipboardArchiveDownloadProgress) {
        val previous = progressByArchiveKey[progress.archiveKey]
        if(shouldPublishArchiveDownloadProgress(previous, progress)) {
            progressByArchiveKey[progress.archiveKey] = progress
        }
    }

    fun clearProgress(archiveKey: String) {
        progressByArchiveKey.remove(archiveKey)
    }

    fun isActive(archiveKey: String): Boolean = jobsByArchiveKey[archiveKey]?.isActive == true

    fun registerCurrentJob(archiveKey: String, job: Job) {
        jobsByArchiveKey[archiveKey] = job
    }

    fun launch(
        archiveKey: String,
        block: suspend () -> Unit,
        onFinished: suspend () -> Unit
    ): Job? {
        if(isActive(archiveKey)) return null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                jobsByArchiveKey.remove(archiveKey)
                onFinished()
            }
        }
        jobsByArchiveKey[archiveKey] = job
        job.start()
        return job
    }

    fun finishRegisteredJob(archiveKey: String) {
        jobsByArchiveKey.remove(archiveKey)
    }

    fun cancel(archiveKey: String) {
        clearQueuedSourceUrls(archiveKey)
        cancelActive(archiveKey)
    }

    fun cancelActive(archiveKey: String) {
        clearProgress(archiveKey)
        jobsByArchiveKey.remove(archiveKey)?.cancel()
    }
}

internal fun shouldPublishArchiveDownloadProgress(
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
