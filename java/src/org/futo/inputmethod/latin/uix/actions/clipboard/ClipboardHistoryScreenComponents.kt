package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import java.io.File

private const val ClipboardHistoryPreviewZoomedThreshold = 1.01f
private const val ClipboardHistoryPreviewEdgeEpsilonPx = 0.5f
private const val ClipboardPreviewChromeEnterMillis = 220
private const val ClipboardPreviewChromeExitMillis = 160
private const val ClipboardPreviewChromeFadeInMillis = 160
private const val ClipboardPreviewChromeFadeOutMillis = 120
private val ClipboardHistoryPreviewEdgeSwipeThreshold = 48.dp
internal val ClipboardPreviewFabThumbnailEndInset = 96.dp
private val ClipboardMediaPreviewThumbnailSize = 64.dp

internal enum class ClipboardHistoryPreviewEdgeSwipe {
    Previous,
    Next
}

internal data class ClipboardHistoryPreviewEdgeSwipeProgress(
    val direction: ClipboardHistoryPreviewEdgeSwipe?,
    val accumulatedPx: Float
)

internal data class ClipboardMediaPreviewItem(
    val file: File?,
    val thumbnailLabel: String
)

internal data class ClipboardPreviewFabAction(
    val label: String,
    val iconRes: Int? = null,
    val imageVector: ImageVector? = null,
    val iconRotationDegrees: Float = 0f,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

internal data class ClipboardPreviewShareTarget(
    val file: File,
    val mimeType: String
)

internal fun isClipboardHistoryPreviewZoomed(zoom: Float): Boolean =
    zoom > ClipboardHistoryPreviewZoomedThreshold

internal fun clampClipboardHistoryPreviewOffset(
    offset: Offset,
    zoom: Float,
    baseImageWidthPx: Float,
    baseImageHeightPx: Float,
    containerWidthPx: Float,
    containerHeightPx: Float
): Offset {
    val maxX = ((baseImageWidthPx * zoom) - containerWidthPx).coerceAtLeast(0f) / 2f
    val maxY = ((baseImageHeightPx * zoom) - containerHeightPx).coerceAtLeast(0f) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY)
    )
}

internal fun clipboardHistoryPreviewEdgeSwipeProgress(
    offsetX: Float,
    panX: Float,
    maxOffsetX: Float,
    accumulatedPx: Float,
    thresholdPx: Float
): ClipboardHistoryPreviewEdgeSwipeProgress {
    if(maxOffsetX <= 0f) return ClipboardHistoryPreviewEdgeSwipeProgress(null, 0f)
    val direction = when {
        panX > 0f && offsetX >= maxOffsetX - ClipboardHistoryPreviewEdgeEpsilonPx ->
            ClipboardHistoryPreviewEdgeSwipe.Previous
        panX < 0f && offsetX <= -maxOffsetX + ClipboardHistoryPreviewEdgeEpsilonPx ->
            ClipboardHistoryPreviewEdgeSwipe.Next
        else -> null
    }
    if(direction == null) return ClipboardHistoryPreviewEdgeSwipeProgress(null, 0f)

    val nextAccumulated = accumulatedPx + kotlin.math.abs(panX)
    return ClipboardHistoryPreviewEdgeSwipeProgress(
        direction = direction.takeIf { nextAccumulated >= thresholdPx },
        accumulatedPx = if(nextAccumulated >= thresholdPx) 0f else nextAccumulated
    )
}

internal fun clipboardPreviewShareTarget(
    context: Context,
    entry: ClipboardEntry,
    previewState: ClipboardPreviewState,
    page: Int = 0
): ClipboardPreviewShareTarget? {
    val mediaFiles = when {
        entry.text == null -> listOfNotNull(entry.getFile(context))
        previewState.showsEmbed -> entry.getPreviewFiles(context)
        else -> emptyList()
    }
    return clipboardPreviewShareTarget(
        entry = entry,
        previewState = previewState,
        mediaFiles = mediaFiles,
        page = page
    )
}

internal fun clipboardPreviewShareTarget(
    entry: ClipboardEntry,
    previewState: ClipboardPreviewState,
    mediaFiles: List<File>,
    page: Int
): ClipboardPreviewShareTarget? {
    val file = mediaFiles.getOrNull(page)?.takeIf { it.isFile } ?: return null
    val mimeType = when {
        entry.backingFile != null -> entry.mimeTypes.firstOrNull()
        previewState.showsEmbed -> entry.previewMedia().getOrNull(page)?.mimeType
        else -> null
    } ?: file.guessedClipboardMimeType()
        ?: if(file.isClipboardVideoFile()) "video/*" else "image/*"

    return ClipboardPreviewShareTarget(file = file, mimeType = mimeType)
}

@Composable
internal fun ClipboardPreviewActionFabMenu(
    actions: List<ClipboardPreviewFabAction>,
    modifier: Modifier = Modifier
) {
    if(actions.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomEnd
    ) {
        if(expanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { expanded = false }
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if(expanded) {
                actions.asReversed().forEach { action ->
                    ClipboardPreviewFabActionRow(
                        action = action,
                        onSelected = {
                            expanded = false
                            action.onClick()
                        }
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .clickable { expanded = !expanded }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.more_horizontal),
                        contentDescription = stringResource(R.string.action_more_actions_title)
                    )
                }
            }
        }
    }
}

@Composable
internal fun ClipboardPreviewOverlayDialog(
    items: List<ClipboardMediaPreviewItem>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialPage: Int = 0,
    thumbnailStripEndInset: Dp = 0.dp,
    fabActions: List<ClipboardPreviewFabAction>,
    onPageChanged: (Int) -> Unit = {},
    headerContent: @Composable RowScope.((File) -> Unit) -> Unit,
    placeholder: @Composable (Int, ClipboardMediaPreviewItem?) -> Unit
) {
    var fullscreenVideoFile by remember { mutableStateOf<File?>(null) }
    var chromeVisible by remember { mutableStateOf(true) }
    val density = LocalDensity.current
    val fabSlideDistancePx = with(density) { ClipboardMediaPreviewThumbnailSize.roundToPx() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black.copy(alpha = 0.94f)
        ) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    ClipboardMediaPreviewPager(
                        items = items,
                        initialPage = initialPage,
                        fullscreenVideoFile = fullscreenVideoFile,
                        chromeVisible = chromeVisible,
                        thumbnailStripEndInset = thumbnailStripEndInset,
                        modifier = Modifier.fillMaxSize(),
                        onPageChanged = onPageChanged,
                        onMediaTap = { chromeVisible = !chromeVisible },
                        placeholder = placeholder
                    )

                    AnimatedVisibility(
                        visible = chromeVisible,
                        enter = slideInVertically(
                            animationSpec = tween(
                                durationMillis = ClipboardPreviewChromeEnterMillis,
                                easing = LinearOutSlowInEasing
                            )
                        ) { -it } + fadeIn(
                            animationSpec = tween(ClipboardPreviewChromeFadeInMillis)
                        ),
                        exit = slideOutVertically(
                            animationSpec = tween(
                                durationMillis = ClipboardPreviewChromeExitMillis,
                                easing = FastOutLinearInEasing
                            )
                        ) { -it } + fadeOut(
                            animationSpec = tween(ClipboardPreviewChromeFadeOutMillis)
                        ),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            headerContent { fullscreenVideoFile = it }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = slideInVertically(
                        animationSpec = tween(
                            durationMillis = ClipboardPreviewChromeEnterMillis,
                            easing = LinearOutSlowInEasing
                        )
                    ) { fabSlideDistancePx } + fadeIn(
                        animationSpec = tween(ClipboardPreviewChromeFadeInMillis)
                    ),
                    exit = slideOutVertically(
                        animationSpec = tween(
                            durationMillis = ClipboardPreviewChromeExitMillis,
                            easing = FastOutLinearInEasing
                        )
                    ) { fabSlideDistancePx } + fadeOut(
                        animationSpec = tween(ClipboardPreviewChromeFadeOutMillis)
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {
                    ClipboardPreviewActionFabMenu(
                        actions = fabActions,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    fullscreenVideoFile?.let { mediaFile ->
        ClipboardHistoryVideoFullscreenDialog(
            mediaFile = mediaFile,
            onDismiss = { fullscreenVideoFile = null }
        )
    }
}

@Composable
private fun ClipboardPreviewFabActionRow(
    action: ClipboardPreviewFabAction,
    onSelected: () -> Unit
) {
    val containerColor = when {
        action.destructive -> MaterialTheme.colorScheme.errorContainer
        action.enabled -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
    }
    val contentColor = when {
        action.destructive -> MaterialTheme.colorScheme.onErrorContainer
        action.enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Text(
                text = action.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        Surface(
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(enabled = action.enabled, onClick = onSelected)
        ) {
            Box(contentAlignment = Alignment.Center) {
                val iconModifier = Modifier
                    .size(22.dp)
                    .rotate(action.iconRotationDegrees)
                action.iconRes?.let { iconRes ->
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = action.label,
                        tint = contentColor,
                        modifier = iconModifier
                    )
                } ?: action.imageVector?.let { imageVector ->
                    Icon(
                        imageVector = imageVector,
                        contentDescription = action.label,
                        tint = contentColor,
                        modifier = iconModifier
                    )
                }
            }
        }
    }
}

internal enum class ClipboardHistoryContentMode {
    Clips,
    Archives
}

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
        matches = { it.backingFile != null || it.previewMedia().isNotEmpty() }
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

internal fun shareClipboardMedia(
    context: Context,
    entry: ClipboardEntry,
    previewState: ClipboardPreviewState
) {
    val target = clipboardPreviewShareTarget(context, entry, previewState) ?: return
    shareMediaFile(context, target.file, target.mimeType)
}

internal fun shareMediaFile(
    context: Context,
    targetFile: File,
    mimeType: String
) {
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
internal fun ClipboardHistoryModeRow(
    mode: ClipboardHistoryContentMode,
    clipCount: Int,
    archiveCount: Int,
    onModeSelected: (ClipboardHistoryContentMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ClipboardHistoryModeChip(
            label = stringResource(R.string.clipboard_history_mode_clips, clipCount),
            selected = mode == ClipboardHistoryContentMode.Clips,
            onClick = { onModeSelected(ClipboardHistoryContentMode.Clips) },
            modifier = Modifier.weight(1f)
        )
        ClipboardHistoryModeChip(
            label = stringResource(R.string.clipboard_history_mode_archives, archiveCount),
            selected = mode == ClipboardHistoryContentMode.Archives,
            onClick = { onModeSelected(ClipboardHistoryContentMode.Archives) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ClipboardHistoryModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            color = if(selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun ClipboardHistoryTitle(
    title: String,
    onBack: () -> Unit,
    badgeCount: Int? = null,
    actions: @Composable RowScope.() -> Unit = {}
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
            badgeCount?.let {
                Spacer(modifier = Modifier.width(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = CircleShape
                ) {
                    Text(
                        text = it.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
        actions()
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
    onShare: (File, String) -> Unit
) {
    val context = LocalContext.current
    val mediaFiles = remember(entry, previewState, context) {
        when {
            entry.text == null -> listOfNotNull(entry.getFile(context))
            previewState.showsEmbed -> entry.getPreviewFiles(context)
            else -> emptyList()
        }
    }
    val initialPage = entry.previewMetadata?.selectedImageIndex
        ?.coerceIn(0, (mediaFiles.size - 1).coerceAtLeast(0))
        ?: 0
    val mediaItems = remember(mediaFiles) {
        mediaFiles.mapIndexed { index, file ->
            ClipboardMediaPreviewItem(file = file, thumbnailLabel = (index + 1).toString())
        }
    }
    var currentPage by remember(mediaItems, initialPage) { mutableStateOf(initialPage) }
    val currentVideoFile = mediaItems
        .getOrNull(currentPage)
        ?.file
        ?.takeIf { it.isClipboardVideoFile() }
    val currentShareTarget = remember(entry, previewState, mediaFiles, currentPage) {
        clipboardPreviewShareTarget(entry, previewState, mediaFiles, currentPage)
    }
    val previewActions = listOf(
        ClipboardPreviewFabAction(
            label = stringResource(
                if(entry.pinned) {
                    R.string.action_clipboard_manager_unpin_item
                } else {
                    R.string.action_clipboard_manager_pin_item
                }
            ),
            iconRes = R.drawable.push_pin,
            iconRotationDegrees = if(entry.pinned) 0f else 45f,
            onClick = onTogglePin
        ),
        ClipboardPreviewFabAction(
            label = stringResource(R.string.clipboard_history_share_image),
            imageVector = Icons.AutoMirrored.Filled.Send,
            onClick = {
                val shareTarget = currentShareTarget ?: return@ClipboardPreviewFabAction
                onShare(shareTarget.file, shareTarget.mimeType)
            }
        ),
        ClipboardPreviewFabAction(
            label = stringResource(R.string.action_clipboard_manager_remove_item),
            iconRes = R.drawable.trash,
            destructive = true,
            onClick = onDelete
        )
    )

    ClipboardPreviewOverlayDialog(
        items = mediaItems,
        initialPage = initialPage,
        thumbnailStripEndInset = ClipboardPreviewFabThumbnailEndInset,
        fabActions = previewActions,
        onDismiss = onDismiss,
        onPageChanged = { currentPage = it },
        placeholder = { _, _ ->
            if(previewLoading) {
                Text(
                    text = stringResource(R.string.action_clipboard_manager_loading_preview),
                    color = Color.White
                )
            } else {
                Text(
                    text = stringResource(R.string.clipboard_history_image_preview_unavailable),
                    color = Color.White
                )
            }
        },
        headerContent = { showFullscreen ->
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
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )

            currentVideoFile?.let { videoFile ->
                IconButton(onClick = { showFullscreen(videoFile) }) {
                    Icon(
                        painter = painterResource(R.drawable.maximize),
                        contentDescription = stringResource(R.string.clipboard_history_video_fullscreen),
                        tint = Color.White
                    )
                }
            }
        }
    )
}

@Composable
internal fun ClipboardHistoryVideoPreview(
    mediaFile: File,
    modifier: Modifier = Modifier,
    paused: Boolean = false
) {
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            VideoView(viewContext).apply {
                val mediaController = MediaController(viewContext)
                mediaController.setAnchorView(this)
                setMediaController(mediaController)
                setVideoURI(mediaFile.toUri())
                setOnPreparedListener { player: MediaPlayer ->
                    player.isLooping = true
                    if(!paused) start()
                }
            }
        },
        update = { videoView ->
            if(paused) {
                videoView.pause()
            } else if(videoView.tag != mediaFile.absolutePath) {
                videoView.tag = mediaFile.absolutePath
                videoView.setVideoURI(mediaFile.toUri())
                videoView.start()
            } else if(!videoView.isPlaying) {
                videoView.start()
            }
        },
        onRelease = { videoView ->
            videoView.stopPlayback()
            (videoView.parent as? ViewGroup)?.removeView(videoView)
        }
    )
}

@Composable
internal fun ClipboardHistoryVideoFullscreenDialog(
    mediaFile: File,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ClipboardHistoryVideoPreview(
                    mediaFile = mediaFile,
                    modifier = Modifier.fillMaxSize()
                )

                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.56f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(16.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = stringResource(R.string.action_clipboard_manager_cancel_action_button),
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ClipboardMediaPreviewPager(
    items: List<ClipboardMediaPreviewItem>,
    modifier: Modifier = Modifier,
    initialPage: Int = 0,
    fullscreenVideoFile: File? = null,
    chromeVisible: Boolean = true,
    thumbnailStripEndInset: Dp = 0.dp,
    onPageChanged: (Int) -> Unit = {},
    onMediaTap: () -> Unit = {},
    placeholder: @Composable (Int, ClipboardMediaPreviewItem?) -> Unit
) {
    val pageCount = items.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, pageCount - 1),
        pageCount = { pageCount }
    )
    val scope = rememberCoroutineScope()
    var currentPageZoomed by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) {
        currentPageZoomed = false
        onPageChanged(pagerState.currentPage)
    }

    Box(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !currentPageZoomed,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = items.getOrNull(page)
            val mediaFile = item?.file
            val isVideo = mediaFile?.isClipboardVideoFile() == true
            val bitmapState = rememberClipboardBitmapLoadState(
                imageFile = mediaFile?.takeUnless { isVideo },
                bitmapOverride = null,
                preferThumbnail = false
            )
            val bitmap = (bitmapState as? ClipboardBitmapLoadState.Loaded)?.bitmap

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if(bitmap == null) {
                            Modifier.pointerInput(onMediaTap) {
                                detectTapGestures(onTap = { onMediaTap() })
                            }
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    mediaFile != null && isVideo -> ClipboardHistoryVideoPreview(
                        mediaFile = mediaFile,
                        modifier = Modifier.fillMaxSize(),
                        paused = fullscreenVideoFile == mediaFile
                    )
                    bitmap != null -> ClipboardHistoryZoomablePreviewImage(
                        bitmap = bitmap,
                        onTap = onMediaTap,
                        onZoomedChange = { zoomed ->
                            if(page == pagerState.currentPage) {
                                currentPageZoomed = zoomed
                            }
                        },
                        onRequestPrevious = {
                            if(pagerState.currentPage > 0) {
                                scope.launch {
                                    currentPageZoomed = false
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            }
                        },
                        onRequestNext = {
                            if(pagerState.currentPage < items.lastIndex) {
                                scope.launch {
                                    currentPageZoomed = false
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            }
                        }
                    )
                    bitmapState == ClipboardBitmapLoadState.Loading -> Text(
                        text = stringResource(R.string.action_clipboard_manager_loading_preview),
                        color = Color.White
                    )
                    else -> placeholder(page, item)
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible && items.size > 1,
            enter = slideInVertically(
                animationSpec = tween(
                    durationMillis = ClipboardPreviewChromeEnterMillis,
                    easing = LinearOutSlowInEasing
                )
            ) { it } + fadeIn(
                animationSpec = tween(ClipboardPreviewChromeFadeInMillis)
            ),
            exit = slideOutVertically(
                animationSpec = tween(
                    durationMillis = ClipboardPreviewChromeExitMillis,
                    easing = FastOutLinearInEasing
                )
            ) { it } + fadeOut(
                animationSpec = tween(ClipboardPreviewChromeFadeOutMillis)
            ),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
        ) {
            ClipboardMediaPreviewThumbnailStrip(
                items = items,
                selectedIndex = pagerState.currentPage,
                endInset = thumbnailStripEndInset,
                onSelected = { index ->
                    scope.launch {
                        currentPageZoomed = false
                        pagerState.animateScrollToPage(index)
                    }
                }
            )
        }
    }
}

@Composable
private fun ClipboardMediaPreviewThumbnailStrip(
    items: List<ClipboardMediaPreviewItem>,
    selectedIndex: Int,
    endInset: Dp = 0.dp,
    onSelected: (Int) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(end = endInset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        itemsIndexed(items) { index, item ->
            val bitmap = rememberClipboardBitmap(
                imageFile = item.file?.takeUnless { it.isClipboardVideoFile() },
                bitmapOverride = null,
                preferThumbnail = true
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.White.copy(alpha = 0.10f),
                border = androidx.compose.foundation.BorderStroke(
                    width = if(index == selectedIndex) 2.dp else 1.dp,
                    color = if(index == selectedIndex) Color.White else Color.White.copy(alpha = 0.25f)
                ),
                modifier = Modifier
                    .size(ClipboardMediaPreviewThumbnailSize)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelected(index) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if(bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = item.thumbnailLabel,
                            color = Color.White.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ClipboardHistoryZoomablePreviewImage(
    bitmap: ImageBitmap,
    onTap: () -> Unit = {},
    onZoomedChange: (Boolean) -> Unit = {},
    onRequestPrevious: () -> Unit = {},
    onRequestNext: () -> Unit = {}
) {
    val imageAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
    var zoom by remember(bitmap) { mutableStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var zoomed by remember(bitmap) { mutableStateOf(false) }
    var edgeSwipeAccumulatedPx by remember(bitmap) { mutableStateOf(0f) }

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
        val edgeSwipeThresholdPx = with(density) { ClipboardHistoryPreviewEdgeSwipeThreshold.toPx() }

        val baseImageWidth = with(density) { baseImageWidthPx.toDp() }
        val baseImageHeight = with(density) { baseImageHeightPx.toDp() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap, containerWidthPx, containerHeightPx) {
                awaitPointerEventScope {
                    while(true) {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        val shouldHandleGesture = zoomed || pressedCount > 1

                        if(shouldHandleGesture) {
                            val zoomChange = event.calculateZoom()
                            val nextZoom = (zoom * zoomChange).coerceIn(1f, 4f)
                            val nextZoomed = isClipboardHistoryPreviewZoomed(nextZoom)
                            zoom = nextZoom

                            if(zoomed != nextZoomed) {
                                zoomed = nextZoomed
                                onZoomedChange(nextZoomed)
                            }

                            offset = if(!nextZoomed) {
                                edgeSwipeAccumulatedPx = 0f
                                Offset.Zero
                            } else {
                                val pan = event.calculatePan()
                                val maxOffsetX = ((baseImageWidthPx * nextZoom) - containerWidthPx).coerceAtLeast(0f) / 2f
                                val edgeSwipeProgress = clipboardHistoryPreviewEdgeSwipeProgress(
                                    offsetX = offset.x,
                                    panX = pan.x,
                                    maxOffsetX = maxOffsetX,
                                    accumulatedPx = edgeSwipeAccumulatedPx,
                                    thresholdPx = edgeSwipeThresholdPx
                                )
                                edgeSwipeAccumulatedPx = edgeSwipeProgress.accumulatedPx
                                when(edgeSwipeProgress.direction) {
                                    ClipboardHistoryPreviewEdgeSwipe.Previous -> onRequestPrevious()
                                    ClipboardHistoryPreviewEdgeSwipe.Next -> onRequestNext()
                                    null -> {}
                                }
                                clampClipboardHistoryPreviewOffset(
                                    offset = offset + pan,
                                    zoom = nextZoom,
                                    baseImageWidthPx = baseImageWidthPx,
                                    baseImageHeightPx = baseImageHeightPx,
                                    containerWidthPx = containerWidthPx,
                                    containerHeightPx = containerHeightPx
                                )
                            }

                            event.changes
                                .filter { it.positionChanged() }
                                .forEach { it.consume() }
                        }
                    }
                }
            }
                .pointerInput(bitmap) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = {
                            zoom = 1f
                            offset = Offset.Zero
                            edgeSwipeAccumulatedPx = 0f
                            if(zoomed) {
                                zoomed = false
                                onZoomedChange(false)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
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
            )
        }
    }
}
