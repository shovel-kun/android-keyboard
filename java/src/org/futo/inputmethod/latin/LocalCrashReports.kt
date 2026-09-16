package org.futo.inputmethod.latin

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.uix.settings.NavigationItem
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import java.io.File
import java.io.IOException

internal fun localCrashReportStore(context: Context) = LocalCrashReportStore(
    File(context.createDeviceProtectedStorageContext().filesDir, "crash-reports")
)

private suspend fun shareCrashReport(context: Context, report: File): Boolean {
    return try {
        val exported = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "crash-reports").apply { mkdirs() }
            val exported = report.copyTo(File(directory, report.name), overwrite = true)
            directory.listFiles().orEmpty().sortedByDescending { it.name }
                .drop(MAX_CRASH_REPORTS).forEach { it.delete() }
            exported
        }
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.crash-reports", exported)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Crash report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.crash_log_share)))
        true
    } catch(error: IOException) {
        Log.e("CrashReports", "Could not export crash report", error)
        Toast.makeText(context, R.string.crash_log_share_failed, Toast.LENGTH_LONG).show()
        false
    } catch(error: ActivityNotFoundException) {
        Toast.makeText(context, R.string.crash_log_share_failed, Toast.LENGTH_LONG).show()
        false
    }
}

@Composable
internal fun LocalCrashReportPrompt() {
    val context = LocalContext.current
    val store = remember(context) { localCrashReportStore(context) }
    val report by produceState<File?>(null, store) {
        value = withContext(Dispatchers.IO) {
            try {
                store.pending()
            } catch(error: IOException) {
                Log.e("CrashReports", "Could not read crash reports", error)
                null
            }
        }
    }
    var dismissed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pending = report ?: return
    if(dismissed) return
    fun finish(share: Boolean) {
        if(busy) return
        busy = true
        scope.launch {
            if(!share || shareCrashReport(context, pending)) {
                withContext(Dispatchers.IO) {
                    try {
                        store.acknowledge(pending)
                    } catch(error: IOException) {
                        Log.e("CrashReports", "Could not dismiss crash report", error)
                    }
                }
                dismissed = true
            }
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = { finish(false) },
        title = { Text(stringResource(R.string.crash_log_saved_title)) },
        text = { Text(stringResource(R.string.crash_log_saved_message)) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { finish(true) }) {
                Text(stringResource(R.string.crash_log_share))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { finish(false) }) {
                Text(stringResource(R.string.crash_log_dismiss))
            }
        }
    )
}

@Composable
internal fun ShareLatestCrashLogOption() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    NavigationItem(
        title = stringResource(R.string.crash_log_share),
        subtitle = stringResource(R.string.crash_log_share_subtitle),
        style = NavigationItemStyle.MiscNoArrow,
        navigate = {
            scope.launch {
                val report = withContext(Dispatchers.IO) { localCrashReportStore(context).latest() }
                if(report == null) Toast.makeText(context, R.string.crash_log_none, Toast.LENGTH_SHORT).show()
                else shareCrashReport(context, report)
            }
        }
    )
}
