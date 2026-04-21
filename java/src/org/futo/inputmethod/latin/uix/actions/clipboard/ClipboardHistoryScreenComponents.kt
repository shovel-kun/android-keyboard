package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R

internal enum class ClipboardHistoryFilter(
    val labelRes: Int,
    val iconRes: Int?,
    val matches: (ClipboardEntry) -> Boolean
) {
    All(
        labelRes = R.string.clipboard_history_filter_all,
        iconRes = null,
        matches = { true }
    ),
    Text(
        labelRes = R.string.clipboard_history_filter_text,
        iconRes = R.drawable.text_prediction,
        matches = { it.text != null }
    ),
    Images(
        labelRes = R.string.clipboard_history_filter_images,
        iconRes = R.drawable.image,
        matches = { it.backingFile != null }
    ),
    Pinned(
        labelRes = R.string.clipboard_history_filter_pinned,
        iconRes = R.drawable.push_pin,
        matches = { it.pinned }
    );
}

internal sealed interface DeleteRequest {
    data class Single(val entry: ClipboardEntry) : DeleteRequest
    data class Bulk(val entries: List<ClipboardEntry>) : DeleteRequest
}

@Composable
internal fun rememberClipboardHistoryManager(): ClipboardHistoryManager {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val manager = remember(context, lifecycleOwner) {
        ClipboardHistoryManager(context, lifecycleOwner.lifecycleScope)
    }

    DisposableEffect(manager, lifecycleOwner) {
        onDispose {
            manager.close()
            lifecycleOwner.lifecycleScope.launch {
                manager.cleanUp()
            }
        }
    }

    return manager
}

internal fun copyTextClip(context: Context, entry: ClipboardEntry) {
    val text = entry.text ?: return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Clipboard", text))
    Toast.makeText(context, context.getString(R.string.clipboard_history_copied_text), Toast.LENGTH_SHORT).show()
}

internal fun shareClipboardImage(
    context: Context,
    entry: ClipboardEntry,
    previewState: ClipboardPreviewState
) {
    val sharingPreviewImage = entry.backingFile == null &&
        previewState.showsEmbed &&
        entry.previewImageFile != null
    val targetFile = when {
        entry.backingFile != null -> entry.getFile(context)
        sharingPreviewImage -> entry.getPreviewFile(context)
        else -> null
    }?.takeIf { it.isFile } ?: return

    val mimeType = when {
        sharingPreviewImage -> null
        else -> entry.mimeTypes.firstOrNull()
    } ?: when(targetFile.extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "avif" -> "image/avif"
        else -> "image/*"
    }

    val uri = createClipboardContentUri(
        file = targetFile,
        mimeType = mimeType,
        expirationMillis = 30L * 60L * 1000L
    )

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, targetFile.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    val chooser = Intent.createChooser(
        intent,
        context.getString(R.string.clipboard_history_share_image)
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    context.startActivity(chooser)
}

internal fun shouldSkipDeleteConfirmation(
    skipDeleteConfirmation: Boolean,
    entries: List<ClipboardEntry>
): Boolean = skipDeleteConfirmation && entries.isNotEmpty() && entries.all { !it.pinned }

@Composable
internal fun ClipboardHistoryTitle(
    title: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onBack),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(16.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Spacer(modifier = Modifier.width(18.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
    }
}

@Composable
internal fun ClipboardHistoryFilterRow(
    activeFilter: ClipboardHistoryFilter,
    counts: Map<ClipboardHistoryFilter, Int>,
    onFilterSelected: (ClipboardHistoryFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ClipboardHistoryFilter.entries.forEach { filter ->
            val selected = activeFilter == filter
            val background = if(selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
            val foreground = if(selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }

            Surface(
                shape = CircleShape,
                color = background,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onFilterSelected(filter) }
            ) {
                Box(
                    modifier = Modifier
                        .background(background)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        filter.iconRes?.let {
                            Icon(
                                painter = painterResource(it),
                                contentDescription = null,
                                tint = foreground,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "${stringResource(filter.labelRes)} ${counts[filter] ?: 0}",
                            color = foreground
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ClipboardHistorySelectionBottomBar(
    allVisibleSelected: Boolean,
    hasPinnedSelection: Boolean,
    hasUnpinnedSelection: Boolean,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onPinSelected: () -> Unit,
    onUnpinSelected: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ClipboardHistoryBottomBarAction(
                label = stringResource(
                    if(allVisibleSelected) R.string.clipboard_history_action_none
                    else R.string.clipboard_history_action_all
                ),
                contentDescription = stringResource(
                    if(allVisibleSelected) R.string.clipboard_history_unselect_all
                    else R.string.action_select_all_title
                ),
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.check),
                        contentDescription = null
                    )
                },
                onClick = onSelectAll,
                modifier = Modifier.weight(1f)
            )

            if(hasUnpinnedSelection) {
                ClipboardHistoryBottomBarAction(
                    label = stringResource(R.string.clipboard_history_action_pin),
                    contentDescription = stringResource(R.string.clipboard_history_pin_selected),
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.push_pin),
                            contentDescription = null
                        )
                    },
                    onClick = onPinSelected,
                    modifier = Modifier.weight(1f)
                )
            }

            if(hasPinnedSelection) {
                ClipboardHistoryBottomBarAction(
                    label = stringResource(R.string.clipboard_history_action_unpin),
                    contentDescription = stringResource(R.string.clipboard_history_unpin_selected),
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.push_pin),
                            contentDescription = null
                        )
                    },
                    onClick = onUnpinSelected,
                    modifier = Modifier.weight(1f)
                )
            }

            ClipboardHistoryBottomBarAction(
                label = stringResource(R.string.clipboard_history_action_delete),
                contentDescription = stringResource(R.string.action_clipboard_manager_remove_item),
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.trash),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = onDeleteSelected,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ClipboardHistoryBottomBarAction(
    label: String,
    contentDescription: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            icon()
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun ClipboardDeleteConfirmationDialog(
    deleteRequest: DeleteRequest,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val title = when(deleteRequest) {
        is DeleteRequest.Single -> {
            if(deleteRequest.entry.backingFile != null && deleteRequest.entry.text == null) {
                stringResource(R.string.action_clipboard_manager_remove_item_confirm_dialog_image)
            } else {
                stringResource(
                    R.string.action_clipboard_manager_remove_item_confirm_dialog,
                    sanitizeClipboardText(deleteRequest.entry.text ?: "", 24)
                )
            }
        }

        is DeleteRequest.Bulk -> stringResource(
            R.string.clipboard_history_remove_selected_confirm_dialog,
            deleteRequest.entries.size
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_clipboard_manager_remove_item))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_clipboard_manager_cancel_action_button))
            }
        }
    )
}

@Composable
internal fun ClipboardHistoryImagePreviewDialog(
    entry: ClipboardEntry,
    previewState: ClipboardPreviewState,
    previewLoading: Boolean,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    val imageFile = remember(entry, previewState, context) {
        when {
            entry.text == null -> entry.getFile(context)
            previewState.showsEmbed -> entry.getPreviewFile(context)
            else -> null
        }
    }
    val bitmap = rememberClipboardBitmap(
        imageFile = imageFile,
        bitmapOverride = null,
        preferThumbnail = false
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black.copy(alpha = 0.94f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_clipboard_manager_cancel_action_button),
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = stringResource(R.string.clipboard_history_preview_title),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        bitmap != null -> ClipboardHistoryZoomablePreviewImage(bitmap)
                        previewLoading -> Text(
                            text = stringResource(R.string.action_clipboard_manager_loading_preview),
                            color = Color.White
                        )
                        else -> Text(
                            text = stringResource(R.string.clipboard_history_image_preview_unavailable),
                            color = Color.White
                        )
                    }
                }

                ClipboardHistoryImagePreviewActions(
                    entry = entry,
                    onTogglePin = onTogglePin,
                    onShare = onShare,
                    onDelete = onDelete
                )
            }
        }
    }
}

@Composable
private fun ClipboardHistoryZoomablePreviewImage(bitmap: ImageBitmap) {
    val imageAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
    var zoom by remember(bitmap) { mutableStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val widthConstrained = containerWidthPx / imageAspectRatio <= containerHeightPx
        val baseImageWidthPx = if(widthConstrained) containerWidthPx else containerHeightPx * imageAspectRatio
        val baseImageHeightPx = if(widthConstrained) containerWidthPx / imageAspectRatio else containerHeightPx

        fun clampOffset(nextOffset: Offset, nextZoom: Float): Offset {
            val maxX = ((baseImageWidthPx * nextZoom) - containerWidthPx).coerceAtLeast(0f) / 2f
            val maxY = ((baseImageHeightPx * nextZoom) - containerHeightPx).coerceAtLeast(0f) / 2f
            return Offset(
                x = nextOffset.x.coerceIn(-maxX, maxX),
                y = nextOffset.y.coerceIn(-maxY, maxY)
            )
        }

        val baseImageWidth = with(density) { baseImageWidthPx.toDp() }
        val baseImageHeight = with(density) { baseImageHeightPx.toDp() }

        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(width = baseImageWidth, height = baseImageHeight)
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                }
                .pointerInput(bitmap, containerWidthPx, containerHeightPx) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        val nextZoom = (zoom * zoomChange).coerceIn(1f, 4f)
                        zoom = nextZoom
                        offset = if(nextZoom == 1f) {
                            Offset.Zero
                        } else {
                            clampOffset(offset + pan, nextZoom)
                        }
                    }
                }
                .pointerInput(bitmap) {
                    detectTapGestures(onDoubleTap = {
                        zoom = 1f
                        offset = Offset.Zero
                    })
                }
        )
    }
}

@Composable
private fun ClipboardHistoryImagePreviewActions(
    entry: ClipboardEntry,
    onTogglePin: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val pinContainerColor = if(entry.pinned) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.White.copy(alpha = 0.08f)
    }
    val pinContentColor = if(entry.pinned) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        Color.White.copy(alpha = 0.72f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onTogglePin,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = pinContainerColor,
                contentColor = pinContentColor
            )
        ) {
            Icon(
                painter = painterResource(R.drawable.push_pin),
                contentDescription = if(entry.pinned) {
                    stringResource(R.string.action_clipboard_manager_unpin_item)
                } else {
                    stringResource(R.string.action_clipboard_manager_pin_item)
                },
                modifier = Modifier.rotate(if(entry.pinned) 0f else 45f)
            )
        }

        Button(
            onClick = onShare,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.clipboard_history_share_image)
            )
        }

        Button(
            onClick = onDelete,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Icon(
                painter = painterResource(R.drawable.trash),
                contentDescription = stringResource(R.string.action_clipboard_manager_remove_item)
            )
        }
    }
}
