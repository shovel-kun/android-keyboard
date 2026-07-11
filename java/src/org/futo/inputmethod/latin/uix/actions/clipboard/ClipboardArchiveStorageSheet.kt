package org.futo.inputmethod.latin.uix.actions.clipboard

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClipboardArchiveStorageSheet(
    inventory: ClipboardStorageInventory,
    downloadsActive: Boolean,
    cleanupInProgress: Boolean,
    cleanupError: Boolean,
    onDeleteUnused: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        ClipboardArchiveStorageContent(
            inventory = inventory,
            downloadsActive = downloadsActive,
            cleanupInProgress = cleanupInProgress,
            cleanupError = cleanupError,
            onDeleteUnused = { confirmDelete = true },
            onDismiss = onDismiss
        )
    }

    if(confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.clipboard_history_storage_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.clipboard_history_storage_delete_text,
                        formatClipboardStorageBytes(inventory.unreferencedMediaBytes)
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDeleteUnused()
                    }
                ) {
                    Text(stringResource(R.string.clipboard_history_storage_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_clipboard_manager_cancel_action_button))
                }
            }
        )
    }
}

@Composable
private fun ClipboardArchiveStorageContent(
    inventory: ClipboardStorageInventory,
    downloadsActive: Boolean,
    cleanupInProgress: Boolean,
    cleanupError: Boolean,
    onDeleteUnused: () -> Unit,
    onDismiss: () -> Unit
) {
    val dataBytes = (inventory.totalBytes - inventory.mediaBytes).coerceAtLeast(0L)
    val mediaFraction = if(inventory.totalBytes > 0L) {
        (inventory.mediaBytes.toFloat() / inventory.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val unusedFileCount = pluralStringResource(
        R.plurals.clipboard_history_storage_file_count,
        inventory.unreferencedMediaFileCount,
        inventory.unreferencedMediaFileCount
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.clipboard_history_storage_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = stringResource(R.string.clipboard_history_storage_close)
                    )
                }
            }
        }

        item {
            Column(
                modifier = Modifier.padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = formatClipboardStorageBytes(inventory.totalBytes),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.clipboard_history_storage_total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { mediaFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ClipboardArchiveStorageLegend(
                        color = MaterialTheme.colorScheme.primary,
                        label = stringResource(R.string.clipboard_history_storage_media),
                        value = formatClipboardStorageBytes(inventory.mediaBytes)
                    )
                    ClipboardArchiveStorageLegend(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        label = stringResource(R.string.clipboard_history_storage_data),
                        value = formatClipboardStorageBytes(dataBytes)
                    )
                }
            }
        }

        item {
            Column(
                modifier = Modifier.padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.clipboard_history_storage_media_references),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        ClipboardArchiveStorageReferenceRow(
                            icon = R.drawable.file_text,
                            label = stringResource(R.string.clipboard_history_storage_archive_media),
                            value = formatClipboardStorageBytes(inventory.archiveMediaBytes)
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        ClipboardArchiveStorageReferenceRow(
                            icon = R.drawable.link,
                            label = stringResource(R.string.clipboard_history_storage_shared_media),
                            value = formatClipboardStorageBytes(inventory.sharedMediaBytes),
                            supportingText = stringResource(R.string.clipboard_history_storage_shared_media_description)
                        )
                    }
                }
            }
        }

        item {
            ClipboardArchiveUnusedFilesHeader(
                fileCountAndSize = if(inventory.unreferencedMediaFileCount == 0) {
                    stringResource(R.string.clipboard_history_storage_no_unused)
                } else {
                    "$unusedFileCount · ${formatClipboardStorageBytes(inventory.unreferencedMediaBytes)}"
                },
                hasFiles = inventory.unreferencedMediaFiles.isNotEmpty()
            )
        }

        items(
            items = inventory.unreferencedMediaFiles,
            key = ClipboardStorageFile::relativePath
        ) { file ->
            ClipboardArchiveUnusedFileRow(file)
        }

        if(inventory.unreferencedMediaFiles.isNotEmpty()) {
            item {
                ClipboardArchiveUnusedFilesFooter(
                    deleteLabel = stringResource(
                        R.string.clipboard_history_storage_delete_unused_size,
                        formatClipboardStorageBytes(inventory.unreferencedMediaBytes)
                    ),
                    enabled = !downloadsActive && !cleanupInProgress,
                    onDeleteUnused = onDeleteUnused
                )
            }
        }

        item {
            Column(
                modifier = Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if(downloadsActive) {
                    Text(
                        text = stringResource(R.string.clipboard_history_storage_downloads_active),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if(cleanupError) {
                    Text(
                        text = stringResource(R.string.clipboard_history_storage_cleanup_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Button(
                    onClick = onDismiss,
                    enabled = !cleanupInProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.clipboard_history_archive_filter_done))
                }
            }
        }
    }
}

@Composable
private fun ClipboardArchiveStorageLegend(color: Color, label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ClipboardArchiveStorageReferenceRow(
    icon: Int,
    label: String,
    value: String,
    supportingText: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if(supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ClipboardArchiveUnusedFilesHeader(fileCountAndSize: String, hasFiles: Boolean) {
    Surface(
        shape = if(hasFiles) {
            RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        } else {
            RoundedCornerShape(12.dp)
        },
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.clipboard_history_storage_unused),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = fileCountAndSize,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = stringResource(R.string.clipboard_history_storage_unused_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ClipboardArchiveUnusedFileRow(file: ClipboardStorageFile) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.file_text),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.relativePath,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = formatClipboardStorageBytes(file.bytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ClipboardArchiveUnusedFilesFooter(
    deleteLabel: String,
    enabled: Boolean,
    onDeleteUnused: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            OutlinedButton(
                onClick = onDeleteUnused,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.trash),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Box(modifier = Modifier.width(8.dp))
                Text(deleteLabel)
            }
        }
    }
}

@Composable
internal fun formatClipboardStorageBytes(bytes: Long): String =
    Formatter.formatShortFileSize(LocalContext.current, bytes)

@Preview(widthDp = 390, heightDp = 1000)
@Composable
private fun ClipboardArchiveStorageContentPreview() {
    MaterialTheme {
        Surface {
            ClipboardArchiveStorageContent(
                inventory = ClipboardStorageInventory.Empty.copy(
                    totalBytes = 734_003_200L,
                    mediaBytes = 713_031_680L,
                    archiveMediaBytes = 610_000_000L,
                    sharedMediaBytes = 83_886_080L,
                    unreferencedMediaFiles = listOf(
                        ClipboardStorageFile(
                            fileName = "fxtwitter-video-thumb.jpg",
                            relativePath = "clipboardfiles/fxtwitter-video-thumb.jpg",
                            bytes = 5_242_880L
                        ),
                        ClipboardStorageFile(
                            fileName = "reddit-gallery-3.webp",
                            relativePath = "clipboardfiles/reddit-gallery-3.webp",
                            bytes = 3_670_016L
                        ),
                        ClipboardStorageFile(
                            fileName = "pixiv-illust-128930441.jpg",
                            relativePath = "clipboardarchivefiles/pixiv-illust-128930441.jpg",
                            bytes = 2_621_440L
                        ),
                        ClipboardStorageFile(
                            fileName = "quoted-post-preview.jpg",
                            relativePath = "clipboardfiles/quoted-post-preview.jpg",
                            bytes = 1_048_576L
                        )
                    )
                ),
                downloadsActive = false,
                cleanupInProgress = false,
                cleanupError = false,
                onDeleteUnused = {},
                onDismiss = {}
            )
        }
    }
}
