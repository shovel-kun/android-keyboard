package org.futo.inputmethod.latin.uix.actions.clipboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import java.io.File

@Preview(widthDp = 390, heightDp = 780)
@Composable
private fun ClipboardCleanupSheetPreview() = ClipboardCleanupPreview(sheet = true)

@Preview(widthDp = 390, heightDp = 780)
@Composable
private fun ClipboardCleanupReviewPreview() = ClipboardCleanupPreview(selected = false)

@Preview(widthDp = 390, heightDp = 780)
@Composable
private fun ClipboardCleanupSelectedPreview() = ClipboardCleanupPreview(selected = true)

@Preview(widthDp = 360, heightDp = 780, fontScale = 1.6f)
@Composable
private fun ClipboardCleanupLargeTextPreview() = ClipboardCleanupPreview(selected = true)

@Preview(widthDp = 390, heightDp = 780)
@Composable
private fun ClipboardCleanupDarkPreview() = ClipboardCleanupPreview(selected = true, dark = true)

@Preview(widthDp = 390, heightDp = 780)
@Composable
private fun ClipboardCleanupEmptyPreview() = ClipboardCleanupPreview(empty = true)

@Preview(widthDp = 390, heightDp = 780)
@Composable
private fun ClipboardCleanupLoadingPreview() = ClipboardCleanupPreview(loading = true)

@Preview(widthDp = 390, heightDp = 780)
@Composable
private fun ClipboardCleanupErrorPreview() = ClipboardCleanupPreview(error = true)

@Preview(widthDp = 390, heightDp = 780)
@Composable
private fun ClipboardCleanupDownloadsPreview() = ClipboardCleanupPreview(downloads = true)

@Composable
private fun ClipboardCleanupPreview(
    selected: Boolean = false,
    dark: Boolean = false,
    empty: Boolean = false,
    loading: Boolean = false,
    error: Boolean = false,
    downloads: Boolean = false,
    sheet: Boolean = false
) {
    val context = LocalContext.current
    val initialFiles = remember {
        val dir = File(context.filesDir, "cleanup-preview").apply { mkdirs() }
        (0..7).map { index ->
            val file = File(dir, "sample-$index.jpg")
            writeCleanupSample(file, index)
            ClipboardStorageFile(file.name, "cleanup-preview/${file.name}", (8 - index) * 1_048_576L)
        }
    }
    var files by remember { mutableStateOf(if(empty) emptyList() else initialFiles) }
    var selection by remember { mutableStateOf(if(selected) initialFiles.take(2).map { it.relativePath }.toSet() else emptySet()) }
    var message by remember { mutableStateOf(if(error) "Some files could not be removed. Review the remaining items and try again." else null) }
    MaterialTheme(colorScheme = if(dark) darkColorScheme() else lightColorScheme()) {
        Surface {
            if(sheet) {
                ClipboardArchiveStorageSheet(
                    inventory = ClipboardStorageInventory.Empty.copy(unreferencedMediaFiles = files),
                    downloadsActive = downloads,
                    loading = loading,
                    busy = false,
                    message = message,
                    error = error,
                    onDelete = { paths ->
                        files = files.filter { it.relativePath !in paths }
                        message = "Selected items deleted."
                    },
                    onKeep = { path ->
                        files = files.filter { it.relativePath != path }
                        message = "Added to your pinned clips."
                    },
                    onRetry = {},
                    onDismiss = {}
                )
            } else ClipboardArchiveStorageContent(
                files = files,
                selectedPaths = selection,
                loading = loading,
                busy = false,
                downloadsActive = downloads,
                message = message,
                error = error,
                onToggle = { selection = if(it.relativePath in selection) selection - it.relativePath else selection + it.relativePath },
                onSelectAll = { selection = if(selection.size == files.size) emptySet() else files.map { it.relativePath }.toSet() },
                onPreview = {},
                onDelete = {
                    files = files.filter { it.relativePath !in selection }
                    selection = emptySet()
                    message = "Selected items deleted."
                },
                onRetry = {},
                onDismiss = {},
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// Deterministic image fixtures exercise the real on-disk thumbnail loader without network access.
private fun writeCleanupSample(file: File, index: Int) {
    val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun color(value: String) { paint.color = AndroidColor.parseColor(value) }
    when(index % 3) {
        0 -> {
            canvas.drawColor(AndroidColor.parseColor("#E8C6AB"))
            color("#F9E6BB")
            canvas.drawCircle(340f, 126f, 54f, paint)
            color("#769C9D")
            canvas.drawRect(0f, 250f, 480f, 480f, paint)
            color("#426873")
            canvas.drawPath(Path().apply {
                moveTo(0f, 250f); lineTo(135f, 138f); lineTo(250f, 264f)
                lineTo(380f, 218f); lineTo(480f, 275f); lineTo(480f, 330f); lineTo(0f, 330f); close()
            }, paint)
            color("#CAD0BC")
            canvas.drawOval(-100f, 380f, 620f, 600f, paint)
        }
        1 -> {
            canvas.drawColor(AndroidColor.parseColor("#F1ECDD"))
            color("#D8CEB9")
            canvas.drawRoundRect(80f, 52f, 410f, 444f, 10f, 10f, paint)
            color("#FFFCF5")
            canvas.drawRoundRect(65f, 40f, 395f, 432f, 10f, 10f, paint)
            color("#354D45")
            paint.textSize = 28f
            canvas.drawText("WEEKEND", 96f, 108f, paint)
            paint.textSize = 18f
            canvas.drawText("A few things to remember", 96f, 148f, paint)
            color("#A5B3A5")
            for(line in 0..5) canvas.drawRoundRect(96f, 190f + line * 32f, 340f - (line % 2) * 50f, 197f + line * 32f, 3f, 3f, paint)
        }
        else -> {
            canvas.drawColor(AndroidColor.parseColor("#D9E4D2"))
            color("#54735B")
            paint.strokeWidth = 8f
            canvas.drawLine(240f, 420f, 240f, 90f, paint)
            for(leaf in 0..3) {
                color(if(leaf % 2 == 0) "#54735B" else "#88A076")
                canvas.drawOval(100f, 90f + leaf * 65f, 245f, 165f + leaf * 65f, paint)
                canvas.drawOval(235f, 115f + leaf * 65f, 370f, 190f + leaf * 65f, paint)
            }
            color("#B67F62")
            canvas.drawRoundRect(173f, 365f, 307f, 480f, 12f, 12f, paint)
        }
    }
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    bitmap.recycle()
}
