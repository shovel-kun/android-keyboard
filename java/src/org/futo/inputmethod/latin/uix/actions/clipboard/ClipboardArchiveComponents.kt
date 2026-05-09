package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import java.io.File

private val ClipboardArchiveCardSingleImageMaxHeight = 420.dp

internal data class ArchiveDeleteRequest(val archive: ClipboardLinkArchive)

private sealed interface ClipboardArchiveDetailsTarget {
    data class Failure(val item: ClipboardArchiveGalleryItem) : ClipboardArchiveDetailsTarget
    data class ArchiveMetadata(val archive: ClipboardLinkArchive) : ClipboardArchiveDetailsTarget
}

@Composable
internal fun ClipboardArchiveDownloadsScreen(
    items: List<ClipboardArchiveDownloadListItem>,
    providerFilter: ClipboardArchiveProviderFilter,
    modifier: Modifier = Modifier,
    onProviderFilterSelected: (ClipboardArchiveProviderFilter) -> Unit,
    onRetry: (ClipboardArchiveDownloadListItem) -> Unit,
    onDelete: (ClipboardArchiveDownloadListItem) -> Unit,
    onRetryAll: (List<ClipboardArchiveDownloadListItem>) -> Unit,
    onStop: (ClipboardArchiveDownloadListItem) -> Unit,
    onStopAll: (List<ClipboardArchiveDownloadListItem>) -> Unit
) {
    var controlsVisible by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val filteredItems = items.filter { it.matchesProviderFilter(providerFilter) }
    val presentation = archiveDownloadPresentation(filteredItems)

    LaunchedEffect(providerFilter) {
        controlsVisible = true
    }
    rememberArchiveDownloadScrollControlsVisible(
        state = listState,
        controlsVisible = controlsVisible,
        onControlsVisibleChanged = { controlsVisible = it }
    )

    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ClipboardArchiveDownloadControlsVisibility(visible = controlsVisible) {
            ClipboardArchiveDownloadSummaryPanel(presentation.summary)
            ClipboardArchiveDownloadProviderFilterRow(
                providerFilter = providerFilter,
                onProviderFilterSelected = onProviderFilterSelected
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onRetryAll(presentation.retryableItems) },
                    enabled = presentation.retryableItems.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.refresh_cw),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Box(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.clipboard_history_downloads_retry_all))
                }
                OutlinedButton(
                    onClick = { onStopAll(presentation.activeItems) },
                    enabled = presentation.activeItems.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Box(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.clipboard_history_downloads_stop_all))
                }
            }
        }

        if(filteredItems.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.clipboard_history_downloads_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            val activeTitle = stringResource(R.string.clipboard_history_downloads_section_active)
            val waitingTitle = stringResource(R.string.clipboard_history_downloads_section_waiting)
            val attentionTitle = stringResource(R.string.clipboard_history_downloads_section_attention)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                archiveDownloadSection(
                    title = activeTitle,
                    items = presentation.groups.active,
                    onRetry = onRetry,
                    onDelete = onDelete,
                    onStop = onStop
                )
                archiveDownloadSection(
                    title = waitingTitle,
                    items = presentation.groups.waiting,
                    onRetry = onRetry,
                    onDelete = onDelete,
                    onStop = onStop
                )
                archiveDownloadSection(
                    title = attentionTitle,
                    items = presentation.groups.needsAttention,
                    onRetry = onRetry,
                    onDelete = onDelete,
                    onStop = onStop
                )
            }
        }
    }
}

@Composable
private fun ClipboardArchiveDownloadControlsVisibility(
    visible: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Top) + slideInVertically { -it } + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + slideOutVertically { -it } + fadeOut()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
private fun rememberArchiveDownloadScrollControlsVisible(
    state: LazyListState,
    controlsVisible: Boolean,
    onControlsVisibleChanged: (Boolean) -> Unit
) {
    var previousPosition by remember(state) {
        mutableStateOf(
            ClipboardScrollControlsPosition(
                firstVisibleItemIndex = state.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset
            )
        )
    }
    val currentPosition = ClipboardScrollControlsPosition(
        firstVisibleItemIndex = state.firstVisibleItemIndex,
        firstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset
    )

    LaunchedEffect(currentPosition, controlsVisible) {
        val visible = scrollControlsVisibleAfterScroll(previousPosition, currentPosition, controlsVisible)
        previousPosition = currentPosition
        if(visible != controlsVisible) {
            onControlsVisibleChanged(visible)
        }
    }
}

@Composable
private fun ClipboardArchiveDownloadProviderFilterRow(
    providerFilter: ClipboardArchiveProviderFilter,
    onProviderFilterSelected: (ClipboardArchiveProviderFilter) -> Unit
) {
    ClipboardArchiveChipRow(
        labels = ClipboardArchiveProviderFilter.entries.map { it to it.labelText() },
        selected = providerFilter,
        onSelected = onProviderFilterSelected
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.archiveDownloadSection(
    title: String,
    items: List<ClipboardArchiveDownloadListItem>,
    onRetry: (ClipboardArchiveDownloadListItem) -> Unit,
    onDelete: (ClipboardArchiveDownloadListItem) -> Unit,
    onStop: (ClipboardArchiveDownloadListItem) -> Unit
) {
    if(items.isEmpty()) return
    item(key = "section:$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = when(items.first().status) {
                ClipboardArchiveDownloadRowStatus.Active -> MaterialTheme.colorScheme.primary
                ClipboardArchiveDownloadRowStatus.Waiting -> MaterialTheme.colorScheme.tertiary
                ClipboardArchiveDownloadRowStatus.Failed -> MaterialTheme.colorScheme.error
            },
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    items(
        items = items,
        key = { "${it.archiveKey}:${it.sourceIndex}:${it.sourceUrl}" }
    ) {
        ClipboardArchiveDownloadRow(
            item = it,
            onRetry = { onRetry(it) },
            onDelete = { onDelete(it) },
            onStop = { onStop(it) }
        )
    }
}

@Composable
private fun ClipboardArchiveDownloadSummaryPanel(summary: ClipboardArchiveDownloadSummary) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ClipboardArchiveDownloadSummaryCell(
                count = summary.activeCount,
                label = stringResource(R.string.clipboard_history_downloads_summary_active),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            ClipboardArchiveDownloadSummaryDivider()
            ClipboardArchiveDownloadSummaryCell(
                count = summary.waitingCount,
                label = stringResource(R.string.clipboard_history_downloads_summary_waiting),
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f)
            )
            ClipboardArchiveDownloadSummaryDivider()
            ClipboardArchiveDownloadSummaryCell(
                count = summary.retryCount,
                label = stringResource(R.string.clipboard_history_downloads_summary_retry),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ClipboardArchiveDownloadSummaryCell(
    count: Int,
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ClipboardArchiveDownloadSummaryDivider() {
    Box(
        modifier = Modifier
            .height(52.dp)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun ClipboardArchiveDownloadRow(
    item: ClipboardArchiveDownloadListItem,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ClipboardArchiveDownloadProviderIcon(item.provider, item.providerLabel)
                        Text(
                            text = item.subtitle ?: item.sourceUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.image),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(
                                R.string.clipboard_history_downloads_media_position,
                                item.sourceIndex + 1,
                                item.mediaCount
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        ClipboardArchiveDownloadStatusPill(item)
                    }
                    item.lastAttemptAtEpochMs?.let {
                        Text(
                            text = stringResource(
                                R.string.clipboard_history_downloads_last_attempt,
                                it.archiveReadableDateTime()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    item.retryAvailableAtEpochMs?.let {
                        Text(
                            text = stringResource(
                                R.string.clipboard_history_downloads_retry_available,
                                it.archiveReadableDateTime()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    item.progress?.let {
                        Text(
                            text = archiveDownloadProgressLabel(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ClipboardArchiveDownloadProgressIndicator(it)
                    }
                }
                ClipboardArchiveDownloadRowAction(
                    item = item,
                    onRetry = onRetry,
                    onDelete = onDelete,
                    onStop = onStop
                )
            }
        }
    }
}

@Composable
private fun ClipboardArchiveDownloadStatusPill(item: ClipboardArchiveDownloadListItem) {
    val foreground = when(item.status) {
        ClipboardArchiveDownloadRowStatus.Active -> MaterialTheme.colorScheme.primary
        ClipboardArchiveDownloadRowStatus.Waiting -> MaterialTheme.colorScheme.tertiary
        ClipboardArchiveDownloadRowStatus.Failed -> MaterialTheme.colorScheme.error
    }
    val label = item.failureSummaryLabelRes
        ?.takeIf { item.status == ClipboardArchiveDownloadRowStatus.Failed }
        ?.let { stringResource(it) }
        ?: item.status.labelText()

    Surface(
        color = foreground.copy(alpha = 0.16f),
        contentColor = foreground,
        border = BorderStroke(1.dp, foreground.copy(alpha = 0.45f)),
        shape = CircleShape
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ClipboardArchiveDownloadProviderIcon(
    provider: ClipboardPreviewProvider,
    providerLabel: String
) {
    Image(
        painter = painterResource(provider.providerIconRes()),
        contentDescription = providerLabel,
        colorFilter = null,
        modifier = Modifier.size(22.dp)
    )
}

@Composable
private fun ClipboardArchiveDownloadRowAction(
    item: ClipboardArchiveDownloadListItem,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onStop: () -> Unit
) {
    val isActive = item.canStop
    val enabled = if(isActive) item.canStop else item.canRetry
    val color = if(isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = Color.Transparent,
            contentColor = color,
            border = BorderStroke(1.dp, color.copy(alpha = if(enabled) 0.7f else 0.3f)),
            shape = CircleShape,
            modifier = Modifier.size(46.dp)
        ) {
            IconButton(
                onClick = if(isActive) onStop else onRetry,
                enabled = enabled,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    painter = painterResource(if(isActive) R.drawable.close else R.drawable.refresh_cw),
                    contentDescription = stringResource(
                        if(isActive) {
                            R.string.clipboard_history_downloads_stop
                        } else {
                            R.string.action_clipboard_manager_retry_preview
                        }
                    ),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if(item.status == ClipboardArchiveDownloadRowStatus.Failed) {
            Surface(
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.error,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
                shape = CircleShape,
                modifier = Modifier.size(46.dp)
            ) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.trash),
                        contentDescription = stringResource(R.string.action_clipboard_manager_remove_item),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipboardArchiveDownloadRowStatus.labelText(): String = when (this) {
    ClipboardArchiveDownloadRowStatus.Active -> stringResource(R.string.clipboard_history_downloads_active)
    ClipboardArchiveDownloadRowStatus.Waiting -> stringResource(R.string.clipboard_history_downloads_waiting)
    ClipboardArchiveDownloadRowStatus.Failed -> stringResource(R.string.clipboard_history_archive_status_retry)
}

@Composable
private fun archiveDownloadProgressLabel(progress: ClipboardArchiveDownloadProgress): String {
    val percent = progress.progressPercent()
    val completed = formatDownloadBytes(progress.completedBytes)
    val total = progress.totalBytes?.let(::formatDownloadBytes)
    return when {
        percent != null && total != null -> stringResource(
            R.string.clipboard_history_downloads_progress_known,
            completed,
            total,
            percent
        )
        total != null -> stringResource(
            R.string.clipboard_history_downloads_progress_unknown_percent,
            completed,
            total
        )
        else -> stringResource(R.string.clipboard_history_downloads_progress_bytes, completed)
    }
}

private fun formatDownloadBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }

internal fun shareArchiveMedia(
    context: Context,
    item: ClipboardArchiveGalleryItem
) {
    val targetFile = item.file?.takeIf { it.isFile } ?: return
    shareMediaFile(context, targetFile, archiveMediaShareMimeType(item.media, targetFile))
}

@Composable
internal fun ClipboardArchiveBackfillStatus(
    remainingCount: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.clipboard_history_archive_backfill_saving),
                    style = MaterialTheme.typography.labelMedium
                )
                if(remainingCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.clipboard_history_archive_backfill_remaining,
                            remainingCount
                        ),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun ClipboardArchiveFilterButton(
    filtersActive: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = {
                if(filtersActive) {
                    Badge()
                }
            }
        ) {
            Icon(
                painter = painterResource(R.drawable.clipboard_filter),
                contentDescription = stringResource(R.string.clipboard_history_archive_filter_open)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClipboardArchiveFilterSheet(
    providerFilter: ClipboardArchiveProviderFilter,
    statusFilter: ClipboardArchiveStatusFilter,
    sortMode: ClipboardArchiveSortMode,
    onProviderFilterSelected: (ClipboardArchiveProviderFilter) -> Unit,
    onStatusFilterSelected: (ClipboardArchiveStatusFilter) -> Unit,
    onSortModeSelected: (ClipboardArchiveSortMode) -> Unit,
    onResetFilters: () -> Unit,
    onDismiss: () -> Unit
) {
    val filtersActive = providerFilter != ClipboardArchiveProviderFilter.All ||
        statusFilter != ClipboardArchiveStatusFilter.All ||
        sortMode != ClipboardArchiveSortMode.ClipDate
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.clipboard_history_archive_filters_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            ClipboardArchiveFilterGroup(
                title = stringResource(R.string.clipboard_history_archive_filter_provider),
                labels = ClipboardArchiveProviderFilter.entries.map { it to it.labelText() },
                selected = providerFilter,
                onSelected = onProviderFilterSelected
            )
            ClipboardArchiveFilterGroup(
                title = stringResource(R.string.clipboard_history_archive_filter_status),
                labels = ClipboardArchiveStatusFilter.entries.map { it to it.labelText() },
                selected = statusFilter,
                onSelected = onStatusFilterSelected
            )
            ClipboardArchiveFilterGroup(
                title = stringResource(R.string.clipboard_history_archive_filter_sort),
                labels = ClipboardArchiveSortMode.entries.map { it to it.labelText() },
                selected = sortMode,
                onSelected = onSortModeSelected
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onResetFilters,
                    enabled = filtersActive,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.clipboard_history_archive_reset_filters))
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.clipboard_history_archive_filter_done))
                }
            }
        }
    }
}

@Composable
private fun <T> ClipboardArchiveFilterGroup(
    title: String,
    labels: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ClipboardArchiveChipRow(
            labels = labels,
            selected = selected,
            onSelected = onSelected
        )
    }
}

@Composable
internal fun ClipboardArchiveFilterRow(
    providerFilter: ClipboardArchiveProviderFilter,
    statusFilter: ClipboardArchiveStatusFilter,
    onProviderFilterSelected: (ClipboardArchiveProviderFilter) -> Unit,
    onStatusFilterSelected: (ClipboardArchiveStatusFilter) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ClipboardArchiveChipRow(
            labels = ClipboardArchiveProviderFilter.entries.map { it to it.labelText() },
            selected = providerFilter,
            onSelected = onProviderFilterSelected
        )
        ClipboardArchiveChipRow(
            labels = ClipboardArchiveStatusFilter.entries.map { it to it.labelText() },
            selected = statusFilter,
            onSelected = onStatusFilterSelected
        )
    }
}

@Composable
private fun ClipboardArchiveProviderFilter.labelText(): String = stringResource(labelRes())

@Composable
private fun ClipboardArchiveStatusFilter.labelText(): String = when (this) {
    ClipboardArchiveStatusFilter.All -> stringResource(R.string.clipboard_history_archive_filter_all)
    ClipboardArchiveStatusFilter.Complete -> stringResource(R.string.clipboard_history_archive_filter_complete)
    ClipboardArchiveStatusFilter.Partial -> stringResource(R.string.clipboard_history_archive_filter_partial)
    ClipboardArchiveStatusFilter.FailedInProgress -> stringResource(R.string.clipboard_history_archive_filter_attention)
}

@Composable
private fun ClipboardArchiveSortMode.labelText(): String = when (this) {
    ClipboardArchiveSortMode.ClipDate -> stringResource(R.string.clipboard_history_archive_sort_clip_date)
    ClipboardArchiveSortMode.ArchiveAdded -> stringResource(R.string.clipboard_history_archive_sort_archive_added)
    ClipboardArchiveSortMode.LastUpdated -> stringResource(R.string.clipboard_history_archive_sort_last_updated)
    ClipboardArchiveSortMode.PostDate -> stringResource(R.string.clipboard_history_archive_sort_post_date)
    ClipboardArchiveSortMode.Status -> stringResource(R.string.clipboard_history_archive_sort_status)
}

@Composable
private fun ClipboardArchiveDisplayStatus.labelText(): String = when (this) {
    ClipboardArchiveDisplayStatus.Complete -> stringResource(R.string.clipboard_history_archive_status_complete)
    ClipboardArchiveDisplayStatus.Saving -> stringResource(R.string.clipboard_history_archive_status_saving)
    ClipboardArchiveDisplayStatus.Waiting -> stringResource(R.string.clipboard_history_archive_status_waiting)
    ClipboardArchiveDisplayStatus.Partial -> stringResource(R.string.clipboard_history_archive_status_partial)
    ClipboardArchiveDisplayStatus.Failed -> stringResource(R.string.clipboard_history_archive_status_failed)
    ClipboardArchiveDisplayStatus.Retry -> stringResource(R.string.clipboard_history_archive_status_retry)
}

@Composable
private fun <T> ClipboardArchiveChipRow(
    labels: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEach { (value, label) ->
            val isSelected = value == selected
            val shape = RoundedCornerShape(8.dp)
            Surface(
                shape = shape,
                color = if(isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(shape)
                    .clickable { onSelected(value) }
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if(isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
internal fun ClipboardArchiveCard(
    archive: ClipboardLinkArchive,
    previewFiles: List<File>,
    loading: Boolean,
    progress: ClipboardArchiveDownloadProgress?,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val bitmaps = rememberClipboardBitmaps(
        imageFiles = previewFiles,
        bitmapOverrides = null,
        preferThumbnail = true,
        maxCount = 4
    )
    val savedCount = archive.savedMediaCount()
    val expectedCount = archive.expectedMediaCount()
    val status = archive.displayStatus(loading)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .padding(2.dp)
            .clickable(onClick = onOpen)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = archive.providerLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if(status != ClipboardArchiveDisplayStatus.Complete) {
                    Text(
                        text = status.labelText(),
                        style = MaterialTheme.typography.labelSmall,
                        color = when (status) {
                            ClipboardArchiveDisplayStatus.Saving,
                            ClipboardArchiveDisplayStatus.Waiting -> MaterialTheme.colorScheme.onSurfaceVariant
                            ClipboardArchiveDisplayStatus.Partial,
                            ClipboardArchiveDisplayStatus.Retry,
                            ClipboardArchiveDisplayStatus.Failed -> MaterialTheme.colorScheme.error
                            ClipboardArchiveDisplayStatus.Complete -> MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }

            Text(
                text = archive.displayTitle(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            archive.displaySubtitle()?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            archive.failureSummaryLabelRes()?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            ClipboardArchiveCardMedia(
                bitmaps = bitmaps,
                savedCount = savedCount,
                expectedCount = expectedCount,
                status = status
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = archiveSavedCountLabel(savedCount, expectedCount, progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                ClipboardArchiveIconAction(
                    visible = archive.hasRetryableMedia(),
                    icon = R.drawable.refresh_cw,
                    contentDescription = R.string.action_clipboard_manager_retry_preview,
                    onClick = onRetry
                )
                ClipboardArchiveIconAction(
                    visible = true,
                    icon = R.drawable.trash,
                    contentDescription = R.string.action_clipboard_manager_remove_item,
                    onClick = onDelete
                )
            }
            if(loading) {
                ClipboardArchiveDownloadProgressIndicator(progress)
            }
        }
    }
}

@Composable
private fun archiveSavedCountLabel(
    savedCount: Int,
    expectedCount: Int,
    progress: ClipboardArchiveDownloadProgress?
): String {
    val savedText = stringResource(
        R.string.clipboard_history_archive_saved_count,
        progress?.savedCount ?: savedCount,
        progress?.expectedCount ?: expectedCount
    )
    return progress?.progressPercent()?.let { "$savedText · $it%" } ?: savedText
}

@Composable
private fun ClipboardArchiveDownloadProgressIndicator(
    progress: ClipboardArchiveDownloadProgress?
) {
    val fraction = progress?.progressFraction
    if(fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ClipboardArchiveIconAction(
    visible: Boolean,
    icon: Int,
    contentDescription: Int,
    onClick: () -> Unit
) {
    if(!visible) return
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(contentDescription),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun ClipboardArchiveCardMedia(
    bitmaps: List<ImageBitmap>,
    savedCount: Int,
    expectedCount: Int,
    status: ClipboardArchiveDisplayStatus
) {
    val visibleBitmaps = bitmaps.take(4)
    if(expectedCount == 0) {
        return
    }
    if(expectedCount == 1 && savedCount == 1 && visibleBitmaps.size == 1) {
        ClipboardArchiveSingleImageCardMedia(visibleBitmaps.single())
        return
    }

    val tileCount = expectedCount.coerceAtMost(4).coerceAtLeast(if(visibleBitmaps.isEmpty()) 1 else visibleBitmaps.size)

    ClipboardPreviewMediaCollage(
        itemCount = tileCount,
        totalMediaCount = expectedCount,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(5.dp))
    ) { index ->
        val bitmap = visibleBitmaps.getOrNull(index)
        if(bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = stringResource(archiveCardMediaPlaceholderLabelRes(status)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun archiveCardMediaPlaceholderLabelRes(status: ClipboardArchiveDisplayStatus): Int =
    if(status == ClipboardArchiveDisplayStatus.Waiting || status == ClipboardArchiveDisplayStatus.Saving) {
        R.string.clipboard_history_archive_waiting
    } else {
        R.string.clipboard_history_archive_missing
    }

@Composable
private fun ClipboardArchiveSingleImageCardMedia(bitmap: ImageBitmap) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f))
    ) {
        val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val naturalHeight = maxWidth / aspectRatio
        val useNaturalHeight = naturalHeight <= ClipboardArchiveCardSingleImageMaxHeight

        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = if(useNaturalHeight) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if(useNaturalHeight) {
                        Modifier.aspectRatio(aspectRatio)
                    } else {
                        Modifier.height(ClipboardArchiveCardSingleImageMaxHeight)
                    }
                )
        )
    }
}

@Composable
internal fun ClipboardArchiveDeleteConfirmationDialog(
    request: ArchiveDeleteRequest,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clipboard_history_archive_delete_title)) },
        text = {
            Text(
                stringResource(
                    R.string.clipboard_history_archive_delete_text,
                    request.archive.displayTitle()
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.clipboard_history_archive_delete_action))
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
internal fun ClipboardArchiveGalleryDialog(
    archive: ClipboardLinkArchive,
    items: List<ClipboardArchiveGalleryItem>,
    loading: Boolean,
    progress: ClipboardArchiveDownloadProgress?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onShare: (ClipboardArchiveGalleryItem) -> Unit
) {
    var currentPage by remember { mutableStateOf(0) }
    val currentItem = items.getOrNull(currentPage)
    var detailsTarget by remember { mutableStateOf<ClipboardArchiveDetailsTarget?>(null) }
    val currentVideoFile = currentItem
        ?.file
        ?.takeIf { it.isClipboardVideoFile() }
    val mediaItems = remember(items) {
        items.map {
            ClipboardMediaPreviewItem(
                file = it.file,
                thumbnailLabel = it.position.toString()
            )
        }
    }
    val showRetry = archive.hasRetryableMedia()
    val showShare = currentItem?.isShareable == true
    val showDetails = {
        detailsTarget = currentItem
            ?.takeIf { it.hasFailureDetails }
            ?.let(ClipboardArchiveDetailsTarget::Failure)
            ?: ClipboardArchiveDetailsTarget.ArchiveMetadata(archive)
    }
    val galleryActions = buildList {
        if(showRetry) {
            add(
                ClipboardPreviewFabAction(
                    label = stringResource(R.string.action_clipboard_manager_retry_preview),
                    iconRes = R.drawable.refresh_cw,
                    enabled = !loading,
                    onClick = onRetry
                )
            )
        }
        add(
            ClipboardPreviewFabAction(
                label = stringResource(R.string.clipboard_history_archive_details_action),
                iconRes = R.drawable.file_text,
                onClick = showDetails
            )
        )
        currentItem?.takeIf { showShare }?.let { shareItem ->
            add(
                ClipboardPreviewFabAction(
                    label = stringResource(R.string.clipboard_history_share_image),
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    onClick = { onShare(shareItem) }
                )
            )
        }
        add(
            ClipboardPreviewFabAction(
                label = stringResource(R.string.action_clipboard_manager_remove_item),
                iconRes = R.drawable.trash,
                destructive = true,
                onClick = onDelete
            )
        )
    }

    ClipboardPreviewOverlayDialog(
        items = mediaItems,
        thumbnailStripEndInset = ClipboardPreviewFabThumbnailEndInset,
        fabActions = galleryActions,
        onDismiss = onDismiss,
        onPageChanged = { currentPage = it },
        placeholder = { page, _ ->
            val item = items.getOrNull(page)
            if(item == null) {
                ClipboardArchiveGalleryPlaceholder(
                    stringResource(R.string.clipboard_history_archive_no_media)
                )
            } else if(item.file != null) {
                ClipboardArchiveGalleryPlaceholder(
                    stringResource(R.string.clipboard_history_archive_unavailable)
                )
            } else {
                ClipboardArchiveGalleryPlaceholder(item)
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

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = archive.displayTitle(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${archive.providerLabel()} · ${
                        archiveSavedCountLabel(
                            archive.savedMediaCount(),
                            archive.expectedMediaCount(),
                            progress
                        )
                    }${archive.displayStatus(loading).takeIf { it != ClipboardArchiveDisplayStatus.Complete }?.let { " · ${it.labelText()}" }.orEmpty()}",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

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

    when(val target = detailsTarget) {
        is ClipboardArchiveDetailsTarget.Failure -> {
            ClipboardArchiveFailureDetailsSheet(
                item = target.item,
                onDismiss = { detailsTarget = null }
            )
        }
        is ClipboardArchiveDetailsTarget.ArchiveMetadata -> {
            ClipboardArchiveMetadataDetailsSheet(
                archive = target.archive,
                onDismiss = { detailsTarget = null }
            )
        }
        null -> {}
    }

}

@Composable
private fun ClipboardArchiveGalleryPlaceholder(item: ClipboardArchiveGalleryItem) {
    val label = item.media.failureSummaryLabelRes()?.let { stringResource(it) }
        ?: item.displayStatus.labelText()
    ClipboardArchiveGalleryPlaceholder(label)
}

@Composable
private fun ClipboardArchiveGalleryPlaceholder(label: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipboardArchiveMetadataDetailsSheet(
    archive: ClipboardLinkArchive,
    onDismiss: () -> Unit
) {
    val sections = remember(archive) { archive.archiveMetadataDetailSections() }
    val details = remember(archive) { archive.archiveMetadataDetailsText() }
    ClipboardArchiveDetailsSheetScaffold(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.clipboard_history_archive_metadata_details_title),
        subtitle = "${archive.providerLabel()} · ${archive.status.detailsLabel()} · ${archive.savedMediaCount()}/${archive.expectedMediaCount()} saved",
        copyLabel = stringResource(R.string.clipboard_history_archive_metadata_copy_details),
        copyText = details
    ) {
        sections.forEach { section ->
            ClipboardArchiveDetailSectionCard(section)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipboardArchiveFailureDetailsSheet(
    item: ClipboardArchiveGalleryItem,
    onDismiss: () -> Unit
) {
    val rawDetails = item.media.failureDetail.orEmpty()
    val copyDetails = remember(item.media) { item.media.failureDetailsText() }
    ClipboardArchiveDetailsSheetScaffold(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.clipboard_history_archive_failure_details_title),
        subtitle = item.media.failureSummaryText() ?: item.displayStatus.labelText(),
        copyLabel = stringResource(R.string.clipboard_history_archive_failure_copy_details),
        copyText = copyDetails,
        copyEnabled = rawDetails.isNotBlank()
    ) {
        ClipboardArchiveFailureDetailSection(item)
        ClipboardArchiveFailureRawSection(rawDetails)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipboardArchiveDetailsSheetScaffold(
    title: String,
    subtitle: String?,
    copyLabel: String,
    copyText: String,
    onDismissRequest: () -> Unit,
    copyEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.96f)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ClipboardArchiveDetailsSheetHeader(
                title = title,
                subtitle = subtitle
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
            Button(
                onClick = {
                    copyArchiveDetails(
                        context = context,
                        label = title,
                        details = copyText
                    )
                },
                enabled = copyEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(copyLabel)
            }
        }
    }
}

@Composable
private fun ClipboardArchiveDetailsSheetHeader(
    title: String,
    subtitle: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ClipboardArchiveDetailSectionCard(section: ClipboardArchiveDetailSection) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            section.rows.forEach {
                ClipboardArchiveDetailRowView(it)
            }
        }
    }
}

@Composable
private fun ClipboardArchiveDetailRowView(row: ClipboardArchiveDetailRow) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SelectionContainer {
            Text(
                text = row.value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ClipboardArchiveFailureDetailSection(item: ClipboardArchiveGalleryItem) {
    ClipboardArchiveDetailSectionCard(
        ClipboardArchiveDetailSection(
            title = "Source",
            rows = listOfNotNull(
                ClipboardArchiveDetailRow("Summary", item.media.failureSummaryText() ?: item.displayStatus.labelText()),
                ClipboardArchiveDetailRow("Source URL", item.media.sourceUrl),
                ClipboardArchiveDetailRow("Source index", (item.media.sourceIndex + 1).toString()),
                item.media.mimeType?.let { ClipboardArchiveDetailRow("MIME type", it) },
                item.media.fileName?.let { ClipboardArchiveDetailRow("File name", it) },
                item.media.lastAttemptAtEpochMs?.let {
                    ClipboardArchiveDetailRow("Last attempted", it.archiveReadableDateTime())
                }
            )
        )
    )
}

@Composable
private fun ClipboardArchiveFailureRawSection(details: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Raw detail",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            ClipboardArchiveRawDetailBlock(details)
        }
    }
}

@Composable
private fun ClipboardArchiveRawDetailBlock(details: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        SelectionContainer {
            Text(
                text = details.ifBlank { "No raw detail" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
    }
}

private fun copyArchiveDetails(
    context: Context,
    label: String,
    details: String
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(
        ClipData.newPlainText(
            label,
            details
        )
    )
}
