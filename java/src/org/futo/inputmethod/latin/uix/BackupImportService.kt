package org.futo.inputmethod.latin.uix

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardHistoryManager
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardImportMode
import java.io.FilterInputStream
import java.io.InputStream

internal enum class BackupImportPhase { Idle, Preparing, Applying, Complete, Cancelled, Failed }

internal data class BackupImportStatus(
    val phase: BackupImportPhase = BackupImportPhase.Idle,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null
) {
    val running get() = phase == BackupImportPhase.Preparing || phase == BackupImportPhase.Applying
}

/** Owns one user-initiated import independently of the activity's lifecycle. */
class BackupImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var timedOut = false

    companion object {
        private const val Channel = "backup_import"
        private const val NotificationId = 1801146882
        private const val CancelAction = "cancel_backup_import"
        private const val ModeExtra = "clipboard_mode"
        const val ShowProgressExtra = "show_backup_import_progress"
        private val mutableStatus = MutableStateFlow(BackupImportStatus())
        internal val status = mutableStatus.asStateFlow()

        fun start(context: Context, uri: Uri, mode: ClipboardImportMode? = null) {
            val intent = Intent(context, BackupImportService::class.java).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                mode?.let { putExtra(ModeExtra, it.name) }
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch(e: RuntimeException) {
                mutableStatus.value = BackupImportStatus(BackupImportPhase.Failed, error = e.message)
            }
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, BackupImportService::class.java).setAction(CancelAction))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(Channel, getString(R.string.backup_import_title), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action == CancelAction) {
            if(mutableStatus.value.phase == BackupImportPhase.Preparing) job?.cancel()
            if(job == null) stopSelf()
            return START_NOT_STICKY
        }
        if(job?.isActive == true) return START_NOT_STICKY
        val uri = intent?.data ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        timedOut = false
        mutableStatus.value = BackupImportStatus(BackupImportPhase.Preparing)
        ServiceCompat.startForeground(
            this, NotificationId, notification(),
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        val mode = intent.getStringExtra(ModeExtra)?.let(ClipboardImportMode::valueOf)
        job = scope.launch {
            val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:backup-import")
            try {
                wakeLock.acquire(6 * 60 * 60 * 1000L)
                val manager = ClipboardHistoryManager.getInstance(applicationContext)
                manager.withBackupImport {
                    withContext(Dispatchers.IO) {
                        // A killed process cannot run finally blocks. Reclaim only import-owned staging.
                        cacheDir.listFiles()?.filter {
                            it.name == "settings_backup_import" || it.name.startsWith("clipboard_backup_import_")
                        }?.forEach { it.deleteRecursively() }
                        filesDir.listFiles()?.filter { it.name.startsWith(".clipboard-store-stage-") }
                            ?.forEach { it.deleteRecursively() }
                        val total = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                            if(it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
                        } ?: 0L
                        var lastUpdate = 0L
                        val importContext = coroutineContext
                        contentResolver.openInputStream(uri)!!.use { source ->
                            val input = BackupProgressInputStream(source) { bytes ->
                                importContext.ensureActive()
                                val now = SystemClock.elapsedRealtime()
                                if(now - lastUpdate >= 500L) {
                                    lastUpdate = now
                                    mutableStatus.value = BackupImportStatus(BackupImportPhase.Preparing, bytes, total)
                                    updateNotification()
                                }
                            }
                            val beforeApply: suspend () -> Unit = {
                                withContext(Dispatchers.Main) {
                                    coroutineContext.ensureActive()
                                    mutableStatus.value = mutableStatus.value.copy(phase = BackupImportPhase.Applying)
                                    updateNotification()
                                }
                            }
                            if(mode == null) {
                                SettingsExporter.loadSettings(applicationContext, input, true, beforeApply)
                            } else {
                                SettingsExporter.loadClipboardBackup(applicationContext, input, mode, beforeApply)
                            }
                        }
                    }
                }
                mutableStatus.value = BackupImportStatus(BackupImportPhase.Complete)
            } catch(e: CancellationException) {
                mutableStatus.value = BackupImportStatus(
                    if(timedOut) BackupImportPhase.Failed else BackupImportPhase.Cancelled,
                    error = if(timedOut) getString(R.string.backup_import_timeout) else null
                )
            } catch(e: Exception) {
                mutableStatus.value = BackupImportStatus(BackupImportPhase.Failed, error = e.message ?: e.javaClass.simpleName)
            } finally {
                if(wakeLock.isHeld) wakeLock.release()
                stopForeground(STOP_FOREGROUND_DETACH)
                updateNotification()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        job?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NotificationId, notification())
    }

    private fun notification(): android.app.Notification {
        val state = mutableStatus.value
        val open = Intent(this, ImportResourceActivity::class.java)
            .putExtra(ShowProgressExtra, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val builder = NotificationCompat.Builder(this, Channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.backup_import_title))
            .setContentText(state.description(this))
            .setContentIntent(
                PendingIntent.getActivity(
                    this, NotificationId, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOnlyAlertOnce(true)
            .setOngoing(state.running)
            .setAutoCancel(!state.running)
        if(state.running) {
            val percent = if(state.totalBytes > 0) {
                (state.bytes.toDouble() / state.totalBytes * 100).toInt().coerceIn(0, 100)
            } else {
                0
            }
            builder.setProgress(100, percent, state.totalBytes <= 0 || state.phase == BackupImportPhase.Applying)
        }
        if(state.phase == BackupImportPhase.Preparing) {
            val cancel = PendingIntent.getService(
                this, NotificationId,
                Intent(this, BackupImportService::class.java).setAction(CancelAction),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(android.R.string.cancel), cancel)
        }
        return builder.build()
    }
}

internal fun BackupImportStatus.description(context: Context): String = when(phase) {
    BackupImportPhase.Idle -> context.getString(R.string.backup_import_idle)
    BackupImportPhase.Preparing -> context.getString(R.string.backup_import_preparing, Formatter.formatFileSize(context, bytes))
    BackupImportPhase.Applying -> context.getString(R.string.backup_import_applying)
    BackupImportPhase.Complete -> context.getString(R.string.backup_import_complete)
    BackupImportPhase.Cancelled -> context.getString(R.string.backup_import_cancelled)
    BackupImportPhase.Failed -> context.getString(R.string.backup_import_failed, error.orEmpty())
}

internal class BackupProgressInputStream(
    input: InputStream,
    private val onRead: (Long) -> Unit
) : FilterInputStream(input) {
    private var bytes = 0L

    override fun read(): Int {
        onRead(bytes)
        return `in`.read().also {
            if(it >= 0) {
                bytes++
                onRead(bytes)
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        onRead(bytes)
        return `in`.read(buffer, offset, length).also {
            if(it > 0) {
                bytes += it
                onRead(bytes)
            }
        }
    }
}
