package org.futo.inputmethod.latin.uix.actions.clipboard

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClipboardArchiveStorageSheet(
    inventory: ClipboardStorageInventory,
    downloadsActive: Boolean,
    loading: Boolean,
    busy: Boolean,
    message: String?,
    error: Boolean,
    onDelete: (Set<String>) -> Unit,
    onKeep: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedPaths by remember { mutableStateOf(emptySet<String>()) }
    var pendingDelete by remember { mutableStateOf<List<ClipboardStorageFile>?>(null) }
    var previewFile by remember { mutableStateOf<ClipboardStorageFile?>(null) }
    val files = inventory.unreferencedMediaFiles
    val selected = files.filter { it.relativePath in selectedPaths }
    LaunchedEffect(files) {
        selectedPaths = selectedPaths.intersect(files.map { it.relativePath }.toSet())
    }
    val canChange = !busy && !loading
    val canDelete = canChange && !downloadsActive
    val toggle: (ClipboardStorageFile) -> Unit = { file ->
        selectedPaths = if(file.relativePath in selectedPaths) selectedPaths - file.relativePath
        else selectedPaths + file.relativePath
    }

    ModalBottomSheet(
        onDismissRequest = { if(!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { !busy }
        )
    ) {
        ClipboardArchiveStorageContent(
            files = files,
            selectedPaths = selected.map { it.relativePath }.toSet(),
            loading = loading,
            busy = busy,
            downloadsActive = downloadsActive,
            message = message,
            error = error,
            onToggle = toggle,
            onSelectAll = {
                selectedPaths = if(selected.size == files.size) emptySet()
                else files.map { it.relativePath }.toSet()
            },
            onPreview = { previewFile = it },
            onDelete = { pendingDelete = selected },
            onRetry = onRetry,
            onDismiss = onDismiss,
            modifier = Modifier.fillMaxHeight(0.92f)
        )
    }

    pendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(pluralStringResource(R.plurals.clipboard_cleanup_confirm_title, pending.size, pending.size)) },
            text = {
                Text(stringResource(R.string.clipboard_cleanup_confirm_body, formatClipboardStorageBytes(pending.sumOf { it.bytes })))
            },
            confirmButton = {
                TextButton(
                    enabled = canDelete,
                    onClick = {
                        pendingDelete = null
                        onDelete(pending.map { it.relativePath }.toSet())
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.clipboard_history_archive_delete_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_clipboard_manager_cancel_action_button))
                }
            }
        )
    }

    previewFile?.let { item ->
        val file = remember(item) { File(context.filesDir, item.relativePath) }
        val selectedForDeletion = item.relativePath in selectedPaths
        ClipboardPreviewOverlayDialog(
            items = listOf(ClipboardMediaPreviewItem(file, "1")),
            onDismiss = { previewFile = null },
            fabActions = listOf(
                ClipboardPreviewFabAction(
                    label = stringResource(if(selectedForDeletion) R.string.clipboard_cleanup_deselect else R.string.clipboard_cleanup_select_delete),
                    iconRes = R.drawable.trash,
                    enabled = canChange,
                    onClick = {
                        toggle(item)
                        previewFile = null
                    }
                )
            ),
            headerContent = { showFullscreen ->
                IconButton(onClick = { previewFile = null }) {
                    Icon(painterResource(R.drawable.close), stringResource(R.string.clipboard_cleanup_close_preview), tint = Color.White)
                }
                Text(
                    text = clipboardCleanupItemLabel(item, files.indexOf(item) + 1),
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                if(item.fileName.guessedClipboardMimeType() != null) {
                    TextButton(
                        enabled = canDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                        onClick = {
                            previewFile = null
                            selectedPaths = selectedPaths - item.relativePath
                            onKeep(item.relativePath)
                        }
                    ) { Text(stringResource(R.string.clipboard_cleanup_keep)) }
                }
                if(file.isClipboardVideoFile()) {
                    IconButton(onClick = { showFullscreen(file) }) {
                        Icon(painterResource(R.drawable.maximize), stringResource(R.string.clipboard_history_video_fullscreen), tint = Color.White)
                    }
                }
            },
            placeholder = { _, _ ->
                Text(stringResource(R.string.clipboard_history_image_preview_unavailable), color = Color.White)
            }
        )
    }
}

@Composable
internal fun ClipboardArchiveStorageContent(
    files: List<ClipboardStorageFile>,
    selectedPaths: Set<String>,
    loading: Boolean,
    busy: Boolean,
    downloadsActive: Boolean,
    message: String?,
    error: Boolean,
    onToggle: (ClipboardStorageFile) -> Unit,
    onSelectAll: () -> Unit,
    onPreview: (ClipboardStorageFile) -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = files.filter { it.relativePath in selectedPaths }
    Column(modifier.navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.clipboard_cleanup_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, enabled = !busy) {
                Icon(painterResource(R.drawable.close), stringResource(R.string.clipboard_cleanup_close))
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(144.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if(loading) {
                        Row(Modifier.padding(vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text(stringResource(R.string.clipboard_cleanup_loading))
                        }
                    } else if(files.isNotEmpty()) {
                        Text(
                            formatClipboardStorageBytes(files.sumOf { it.bytes }),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            pluralStringResource(R.plurals.clipboard_cleanup_found, files.size, files.size),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(R.string.clipboard_cleanup_explanation),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if(!loading && files.isEmpty() && !error) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(painterResource(R.drawable.check), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.clipboard_cleanup_empty), style = MaterialTheme.typography.titleLarge)
                            Text(
                                stringResource(R.string.clipboard_cleanup_empty_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if(!loading && files.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.clipboard_cleanup_preview_hint),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onSelectAll, enabled = !busy) {
                            Text(stringResource(if(selected.size == files.size) R.string.clipboard_cleanup_deselect_all else R.string.clipboard_cleanup_select_all))
                        }
                    }
                }
                itemsIndexed(files, key = { _, file -> file.relativePath }) { index, file ->
                    val label = clipboardCleanupItemLabel(file, index + 1)
                    val checked = file.relativePath in selectedPaths
                    Surface(
                        onClick = { onPreview(file) },
                        enabled = !busy,
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = if(checked) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Column {
                            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                                ClipboardCleanupThumbnail(file)
                                Surface(
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { onToggle(file) },
                                        enabled = !busy,
                                        modifier = Modifier.semantics { contentDescription = label }
                                    )
                                }
                            }
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(label, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    formatClipboardStorageBytes(file.bytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if(message != null) {
                Surface(
                    color = if(error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        if(error) {
                            TextButton(onClick = onRetry, enabled = !busy && !loading) {
                                Text(stringResource(R.string.clipboard_cleanup_refresh))
                            }
                        }
                    }
                }
            }
            if(downloadsActive && files.isNotEmpty()) {
                Text(stringResource(R.string.clipboard_cleanup_downloads), style = MaterialTheme.typography.bodySmall)
            }
            if(files.isNotEmpty() && !loading) {
                Text(
                    if(selected.isEmpty()) stringResource(R.string.clipboard_cleanup_select_hint)
                    else pluralStringResource(R.plurals.clipboard_cleanup_selected, selected.size, selected.size),
                    style = MaterialTheme.typography.labelLarge
                )
                Button(
                    onClick = onDelete,
                    enabled = selected.isNotEmpty() && !busy && !downloadsActive,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if(busy) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        if(busy) stringResource(R.string.clipboard_cleanup_working)
                        else if(selected.isEmpty()) stringResource(R.string.clipboard_cleanup_delete_selected)
                        else stringResource(R.string.clipboard_cleanup_delete_size, formatClipboardStorageBytes(selected.sumOf { it.bytes }))
                    )
                }
            } else {
                Button(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.clipboard_history_archive_filter_done))
                }
            }
        }
    }
}

@Composable
private fun ClipboardCleanupThumbnail(item: ClipboardStorageFile) {
    val context = LocalContext.current
    val file = remember(item.relativePath) { File(context.filesDir, item.relativePath) }
    val bitmap = rememberClipboardBitmapLoadState(imageFile = file, bitmapOverride = null)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        when(bitmap) {
            is ClipboardBitmapLoadState.Loaded -> Image(
                bitmap.bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
            ClipboardBitmapLoadState.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
            ClipboardBitmapLoadState.Unavailable -> Text(
                stringResource(R.string.clipboard_history_image_preview_unavailable),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if(file.isClipboardVideoFile() || file.isClipboardGifFile()) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                contentColor = Color.White,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
            ) {
                Text(
                    stringResource(if(file.isClipboardVideoFile()) R.string.clipboard_cleanup_video else R.string.clipboard_cleanup_gif),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun clipboardCleanupItemLabel(file: ClipboardStorageFile, position: Int): String = stringResource(
    when {
        file.fileName.isClipboardVideoFileName() -> R.string.clipboard_cleanup_video_number
        file.fileName.isClipboardGifFileName() -> R.string.clipboard_cleanup_gif_number
        file.fileName.isClipboardImageFileName() -> R.string.clipboard_cleanup_image_number
        else -> R.string.clipboard_cleanup_file_number
    }, position
)

@Composable
internal fun formatClipboardStorageBytes(bytes: Long): String =
    Formatter.formatShortFileSize(LocalContext.current, bytes)
