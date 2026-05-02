package org.futo.inputmethod.latin.uix.actions.clipboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.SettingsTextEdit
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.pages.ParagraphText
import org.futo.inputmethod.latin.uix.settings.pages.PaymentSurface
import org.futo.inputmethod.latin.uix.settings.pages.PaymentSurfaceHeading
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue

@Composable
fun ClipboardHistoryScreen(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val manager = rememberClipboardHistoryManager()
    val clipboardHistoryEnabled = useDataStore(ClipboardHistoryEnabled, blocking = true)
    val uiState = rememberClipboardUiState(manager)
    val showPinnedOnTop = useDataStoreValue(ClipboardShowPinnedOnTop)
    val useSingleColumn = useDataStoreValue(ClipboardSingleColumn)
    val skipDeleteConfirmation = useDataStoreValue(ClipboardSkipDeleteConfirmation)

    val query = remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(ClipboardHistoryFilter.All) }
    var activeMode by remember { mutableStateOf(ClipboardHistoryContentMode.Clips) }
    var archiveProviderFilter by remember { mutableStateOf(ClipboardArchiveProviderFilter.All) }
    var archiveStatusFilter by remember { mutableStateOf(ClipboardArchiveStatusFilter.All) }
    var archiveFiltersVisible by remember { mutableStateOf(false) }
    val selectedKeys = remember { mutableStateListOf<String>() }
    var selectionMode by remember { mutableStateOf(false) }
    var previewEntryKey by remember { mutableStateOf<String?>(null) }
    var previewArchiveKey by remember { mutableStateOf<String?>(null) }
    var deleteRequest by remember { mutableStateOf<DeleteRequest?>(null) }
    var archiveDeleteRequest by remember { mutableStateOf<ArchiveDeleteRequest?>(null) }
    var downloadsVisible by remember { mutableStateOf(false) }
    val archiveBackfillInProgress by manager.archiveBackfillInProgress
    val archiveBackfillRemainingCount by manager.archiveBackfillRemainingCount

    LaunchedEffect(uiState.shouldRefreshPreviews) {
        if(uiState.shouldRefreshPreviews) {
            manager.refreshMissingLinkPreviews()
        }
    }

    val allEntries by remember(showPinnedOnTop) {
        derivedStateOf {
            sortedClipboardEntries(
                entries = manager.clipboardHistory.toList(),
                showPinnedOnTop = showPinnedOnTop
            ).filter { it != DefaultClipboardEntry }
        }
    }
    val allArchives by remember {
        derivedStateOf { manager.archiveRecords() }
    }
    val hasHistoryEntries by remember {
        derivedStateOf { allEntries.isNotEmpty() }
    }
    val hasArchiveRecords by remember {
        derivedStateOf { allArchives.isNotEmpty() }
    }
    val filterCounts by remember {
        derivedStateOf {
            ClipboardHistoryFilter.entries.associateWith { filter ->
                allEntries.count { filter.matches(it) }
            }
        }
    }
    val visibleEntries by remember {
        derivedStateOf {
            allEntries.filter {
                activeFilter.matches(it) && it.matchesQuery(query.value)
            }
        }
    }
    val visibleArchives by remember {
        derivedStateOf {
            allArchives.filter {
                it.matchesArchiveQuery(query.value) &&
                    it.matchesProviderFilter(archiveProviderFilter) &&
                    it.matchesStatusFilter(archiveStatusFilter)
            }
        }
    }
    val archivePreviewFilesByKey by remember {
        derivedStateOf {
            manager.archivePreviewFilesByKey(visibleArchives)
        }
    }
    val archiveDownloadItems by remember {
        derivedStateOf {
            if(downloadsVisible) {
                manager.archiveDownloadItems()
            } else {
                emptyList()
            }
        }
    }
    val archiveDownloadActionCount by remember {
        derivedStateOf {
            if(downloadsVisible) {
                archiveDownloadItems.size
            } else {
                manager.archiveDownloadActionCount()
            }
        }
    }
    val archiveFiltersActive by remember {
        derivedStateOf {
            archiveProviderFilter != ClipboardArchiveProviderFilter.All ||
                archiveStatusFilter != ClipboardArchiveStatusFilter.All
        }
    }
    val visibleKeySet by remember {
        derivedStateOf { visibleEntries.map { it.selectionKey() }.toSet() }
    }
    val selectedEntries by remember {
        derivedStateOf { visibleEntries.filter { it.selectionKey() in selectedKeys } }
    }
    val allVisibleSelected by remember {
        derivedStateOf { visibleEntries.isNotEmpty() && selectedEntries.size == visibleEntries.size }
    }
    val hasPinnedSelection by remember {
        derivedStateOf { selectedEntries.any { it.pinned } }
    }
    val hasUnpinnedSelection by remember {
        derivedStateOf { selectedEntries.any { !it.pinned } }
    }
    val canBrowseHistory by remember {
        derivedStateOf { uiState.historyVisible && hasHistoryEntries }
    }

    LaunchedEffect(hasHistoryEntries, hasArchiveRecords) {
        if(!hasHistoryEntries && hasArchiveRecords) {
            activeMode = ClipboardHistoryContentMode.Archives
        } else if(hasHistoryEntries && !hasArchiveRecords) {
            activeMode = ClipboardHistoryContentMode.Clips
        }
    }

    LaunchedEffect(visibleKeySet, canBrowseHistory) {
        selectedKeys.retainAll { it in visibleKeySet }
        if(selectedKeys.isEmpty()) {
            selectionMode = false
        }
        if(activeMode != ClipboardHistoryContentMode.Clips || !canBrowseHistory) {
            selectedKeys.clear()
            selectionMode = false
            previewEntryKey = null
        }
    }

    val previewEntry = previewEntryKey?.let { key ->
        allEntries.firstOrNull { it.selectionKey() == key }
    }
    val previewArchive = previewArchiveKey?.let { key ->
        allArchives.firstOrNull { it.key == key }
    }

    fun clearSelection() {
        selectedKeys.clear()
        selectionMode = false
    }

    fun handleBack() {
        when {
            downloadsVisible -> downloadsVisible = false
            selectionMode -> clearSelection()
            else -> navController.navigateUp()
        }
    }

    fun toggleSelection(entry: ClipboardEntry) {
        val key = entry.selectionKey()
        if(key in selectedKeys) selectedKeys.remove(key) else selectedKeys.add(key)
        selectionMode = selectedKeys.isNotEmpty()
    }

    fun requestDelete(entries: List<ClipboardEntry>) {
        if(entries.isEmpty()) return

        if(shouldSkipDeleteConfirmation(skipDeleteConfirmation, entries)) {
            manager.removeAll(entries)
            selectedKeys.removeAll(entries.map { it.selectionKey() }.toSet())
            if(selectedKeys.isEmpty()) selectionMode = false
            if(previewEntryKey != null && entries.any { it.selectionKey() == previewEntryKey }) {
                previewEntryKey = null
            }
        } else {
            deleteRequest = if(entries.size == 1) {
                DeleteRequest.Single(entries.first())
            } else {
                DeleteRequest.Bulk(entries)
            }
        }
    }

    BackHandler(enabled = selectionMode || downloadsVisible) {
        handleBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ClipboardHistoryTitle(
            title = if(selectionMode) {
                if(selectedKeys.isEmpty()) {
                    context.getString(R.string.clipboard_history_select_items)
                } else {
                    context.getString(R.string.clipboard_history_selected_count, selectedKeys.size)
                }
            } else if(downloadsVisible) {
                context.getString(R.string.clipboard_history_downloads_title)
            } else {
                context.getString(R.string.typing_settings_enable_clipboard_history)
            },
            onBack = ::handleBack,
            actions = {
                if(!selectionMode && !downloadsVisible && uiState.historyEnabled && uiState.historyVisible) {
                    IconButton(onClick = { downloadsVisible = true }) {
                        BadgedBox(
                            badge = {
                                if(archiveDownloadActionCount > 0) {
                                    Badge {
                                        Text(archiveDownloadActionCount.toString())
                                    }
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.clipboard_manager),
                                contentDescription = stringResource(R.string.clipboard_history_downloads_open)
                            )
                        }
                    }
                }
            }
        )

        when {
            downloadsVisible && uiState.historyEnabled && uiState.historyVisible -> {
                ClipboardArchiveDownloadsScreen(
                    items = archiveDownloadItems,
                    modifier = Modifier.weight(1f),
                    onRetry = { manager.retryArchiveMedia(it) },
                    onRetryAll = { manager.retryAllArchiveDownloads(it) },
                    onStop = { manager.stopArchiveDownload(it.archiveKey) },
                    onStopAll = { manager.stopAllArchiveDownloads() }
                )
            }

            uiState.ioFailure -> {
                ScrollableList(modifier = Modifier.weight(1f)) {
                    PaymentSurface(isPrimary = true) {
                        PaymentSurfaceHeading(title = stringResource(R.string.action_clipboard_manager_error_general_title))
                        ParagraphText(
                            stringResource(
                                R.string.action_clipboard_manager_error_general_text,
                                manager.clipboardIOFailureReason
                            )
                        )
                        Button(
                            onClick = { manager.saveClipboard() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.action_clipboard_manager_retry_saving_loading))
                        }
                        Button(
                            onClick = {
                                manager.clipboardIOFailure.value = false
                                clipboardHistoryEnabled.setValue(false)
                                manager.deleteClipboard()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.action_clipboard_manager_delete_corrupted_clipboard_button))
                        }
                    }
                }
            }

            !uiState.historyEnabled -> {
                ScrollableList(modifier = Modifier.weight(1f)) {
                    PaymentSurface(isPrimary = true) {
                        PaymentSurfaceHeading(title = stringResource(R.string.action_clipboard_manager_error_clipboard_history_disabled_title))
                        ParagraphText(stringResource(R.string.action_clipboard_manager_error_clipboard_history_disabled_text_v2))
                        Button(
                            onClick = { clipboardHistoryEnabled.setValue(true) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.action_clipboard_manager_enable_clipboard_history_button))
                        }
                    }
                }
            }

            uiState.incognitoMode -> {
                ScrollableList(modifier = Modifier.weight(1f)) {
                    PaymentSurface(isPrimary = true) {
                        PaymentSurfaceHeading(title = stringResource(R.string.action_clipboard_manager_incognito_title))
                        ParagraphText(stringResource(R.string.action_clipboard_manager_incognito_text))
                        Button(
                            onClick = {
                                navController.navigate(
                                    ClipboardHistoryAction.settingsMenu?.navPath ?: "actions/clipboard_history"
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.clipboard_history_open_settings))
                        }
                    }
                }
            }

            !hasHistoryEntries && !hasArchiveRecords -> {
                ScrollableList(modifier = Modifier.weight(1f)) {
                    PaymentSurface(isPrimary = true) {
                        PaymentSurfaceHeading(title = stringResource(R.string.typing_settings_enable_clipboard_history))
                        ParagraphText(stringResource(R.string.clipboard_history_empty))
                    }
                }
            }

            else -> {
                Box(Modifier.padding(8.dp)) {
                    SettingsTextEdit(
                        text = query,
                        icon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.settings_search_menu_title)
                            )
                        },
                        trailingContent = if(
                            query.value.isNotBlank() ||
                            (activeMode == ClipboardHistoryContentMode.Archives && !selectionMode)
                        ) {
                            {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if(query.value.isNotBlank()) {
                                        IconButton(onClick = { query.value = "" }) {
                                            Icon(
                                                Icons.Default.Clear,
                                                contentDescription = stringResource(R.string.clipboard_history_clear_search)
                                            )
                                        }
                                    }
                                    if(activeMode == ClipboardHistoryContentMode.Archives && !selectionMode) {
                                        ClipboardArchiveFilterButton(
                                            filtersActive = archiveFiltersActive,
                                            onClick = { archiveFiltersVisible = true }
                                        )
                                    }
                                }
                            }
                        } else {
                            null
                        },
                        placeholder = stringResource(R.string.clipboard_history_search_placeholder),
                        forceQwerty = true
                    )
                }

                if(!selectionMode) {
                    ClipboardHistoryModeRow(
                        mode = activeMode,
                        clipCount = allEntries.size,
                        archiveCount = allArchives.size,
                        onModeSelected = {
                            activeMode = it
                            if(it == ClipboardHistoryContentMode.Archives) {
                                clearSelection()
                                previewEntryKey = null
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if(archiveBackfillInProgress) {
                        ClipboardArchiveBackfillStatus(remainingCount = archiveBackfillRemainingCount)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if(activeMode == ClipboardHistoryContentMode.Clips) {
                        ClipboardHistoryFilterRow(
                            activeFilter = activeFilter,
                            counts = filterCounts,
                            onFilterSelected = { activeFilter = it }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                when (activeMode) {
                    ClipboardHistoryContentMode.Clips -> ClipboardClipsContent(
                        modifier = Modifier.weight(1f),
                        visibleEntries = visibleEntries,
                        activeFilter = activeFilter,
                        useSingleColumn = useSingleColumn,
                        selectionMode = selectionMode,
                        selectedKeys = selectedKeys,
                        manager = manager,
                        previewState = uiState.previewState,
                        onResetFilter = { activeFilter = ClipboardHistoryFilter.All },
                        onToggleSelection = ::toggleSelection,
                        onOpenPreview = { previewEntryKey = it.selectionKey() },
                        onDelete = { requestDelete(listOf(it)) },
                        onCopy = { copyTextClip(context, it) }
                    )

                    ClipboardHistoryContentMode.Archives -> ClipboardArchivesContent(
                        modifier = Modifier.weight(1f),
                        visibleArchives = visibleArchives,
                        previewFilesByKey = archivePreviewFilesByKey,
                        archiveBackfillInProgress = archiveBackfillInProgress,
                        archiveBackfillRemainingCount = archiveBackfillRemainingCount,
                        useSingleColumn = useSingleColumn,
                        manager = manager,
                        onResetFilters = {
                            archiveProviderFilter = ClipboardArchiveProviderFilter.All
                            archiveStatusFilter = ClipboardArchiveStatusFilter.All
                        },
                        onOpen = { previewArchiveKey = it.key },
                        onRetry = { manager.retryArchive(it) },
                        onDelete = { archiveDeleteRequest = ArchiveDeleteRequest(it) }
                    )
                }
            }
        }

        if(selectionMode && activeMode == ClipboardHistoryContentMode.Clips) {
            ClipboardHistorySelectionBottomBar(
                allVisibleSelected = allVisibleSelected,
                hasPinnedSelection = hasPinnedSelection,
                hasUnpinnedSelection = hasUnpinnedSelection,
                onSelectAll = {
                    if(allVisibleSelected) {
                        selectedKeys.clear()
                    } else {
                        selectedKeys.clear()
                        selectedKeys.addAll(visibleEntries.map { it.selectionKey() })
                    }
                    selectionMode = selectedKeys.isNotEmpty()
                },
                onDeleteSelected = { requestDelete(selectedEntries) },
                onPinSelected = {
                    manager.setPinned(selectedEntries, true)
                    clearSelection()
                },
                onUnpinSelected = {
                    manager.setPinned(selectedEntries, false)
                    clearSelection()
                }
            )
        }
    }

    previewEntry?.let { entry ->
        ClipboardHistoryImagePreviewDialog(
            entry = entry,
            previewState = uiState.previewState,
            previewLoading = entry.text?.let { manager.previewLoadingByText[it] == true } == true,
            onDismiss = { previewEntryKey = null },
            onTogglePin = { manager.onTogglePin(entry) },
            onDelete = { requestDelete(listOf(entry)) },
            onShare = { file, mimeType -> shareMediaFile(context, file, mimeType) }
        )
    }

    previewArchive?.let { archive ->
        ClipboardArchiveGalleryDialog(
            archive = archive,
            items = manager.galleryItems(archive),
            loading = manager.isArchiveLoading(archive),
            progress = manager.archiveDownloadProgress(archive),
            onDismiss = { previewArchiveKey = null },
            onRetry = { manager.retryArchive(archive) },
            onDelete = { archiveDeleteRequest = ArchiveDeleteRequest(archive) },
            onShare = { shareArchiveMedia(context, it) }
        )
    }

    deleteRequest?.let { request ->
        ClipboardDeleteConfirmationDialog(
            deleteRequest = request,
            onDismiss = { deleteRequest = null },
            onConfirm = {
                val entries = when(request) {
                    is DeleteRequest.Single -> listOf(request.entry)
                    is DeleteRequest.Bulk -> request.entries
                }
                manager.removeAll(entries)
                selectedKeys.removeAll(entries.map { it.selectionKey() }.toSet())
                if(selectedKeys.isEmpty()) selectionMode = false
                if(previewEntryKey != null && entries.any { it.selectionKey() == previewEntryKey }) {
                    previewEntryKey = null
                }
                deleteRequest = null
            }
        )
    }

    archiveDeleteRequest?.let { request ->
        ClipboardArchiveDeleteConfirmationDialog(
            request = request,
            onDismiss = { archiveDeleteRequest = null },
            onConfirm = {
                manager.deleteArchive(request.archive)
                if(previewArchiveKey == request.archive.key) {
                    previewArchiveKey = null
                }
                archiveDeleteRequest = null
            }
        )
    }

    if(archiveFiltersVisible) {
        ClipboardArchiveFilterSheet(
            providerFilter = archiveProviderFilter,
            statusFilter = archiveStatusFilter,
            onProviderFilterSelected = { archiveProviderFilter = it },
            onStatusFilterSelected = { archiveStatusFilter = it },
            onResetFilters = {
                archiveProviderFilter = ClipboardArchiveProviderFilter.All
                archiveStatusFilter = ClipboardArchiveStatusFilter.All
            },
            onDismiss = { archiveFiltersVisible = false }
        )
    }
}

@Composable
private fun ClipboardClipsContent(
    modifier: Modifier,
    visibleEntries: List<ClipboardEntry>,
    activeFilter: ClipboardHistoryFilter,
    useSingleColumn: Boolean,
    selectionMode: Boolean,
    selectedKeys: Collection<String>,
    manager: ClipboardHistoryManager,
    previewState: ClipboardPreviewState,
    onResetFilter: () -> Unit,
    onToggleSelection: (ClipboardEntry) -> Unit,
    onOpenPreview: (ClipboardEntry) -> Unit,
    onDelete: (ClipboardEntry) -> Unit,
    onCopy: (ClipboardEntry) -> Unit
) {
    if(visibleEntries.isEmpty()) {
        ScrollableList(modifier = modifier) {
            PaymentSurface(isPrimary = true) {
                PaymentSurfaceHeading(title = stringResource(R.string.clipboard_history_no_results_title))
                ParagraphText(stringResource(R.string.clipboard_history_no_results_text))
                if(activeFilter != ClipboardHistoryFilter.All) {
                    OutlinedButton(
                        onClick = onResetFilter,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.clipboard_history_reset_filters))
                    }
                }
            }
        }
        return
    }

    LazyVerticalStaggeredGrid(
        modifier = modifier.fillMaxWidth(),
        columns = if(useSingleColumn) {
            StaggeredGridCells.Fixed(1)
        } else {
            StaggeredGridCells.Adaptive(160.dp)
        },
        verticalItemSpacing = 4.dp,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(
            items = visibleEntries,
            key = { index, entry -> entry.lazyListKey(index) }
        ) { _, entry ->
            val isSelected = entry.selectionKey() in selectedKeys
            val canPreview = previewState.showsEmbed &&
                (entry.backingFile != null || entry.previewMedia().isNotEmpty())

            ClipboardEntryView(
                modifier = Modifier.fillMaxWidth(),
                clipboardEntry = entry,
                previewMediaTotalCount = manager.expectedPreviewMediaCount(entry),
                previewLoading = previewState.showsEmbed &&
                    entry.text?.let { manager.previewLoadingByText[it] == true } == true,
                embedDisplayMode = previewState.embedDisplayMode,
                onPaste = {
                    when {
                        selectionMode -> onToggleSelection(entry)
                        canPreview -> onOpenPreview(entry)
                        entry.text != null -> onCopy(entry)
                    }
                },
                onRemove = { onDelete(entry) },
                onPin = { manager.onTogglePin(entry) },
                onCopy = { onCopy(entry) },
                onRetryPreview = { manager.retryPreviewForEntry(entry) },
                onLongClick = { onToggleSelection(entry) },
                showWrapAction = false,
                showCopyAction = !selectionMode && entry.text != null && canPreview,
                showRetryPreviewAction = !selectionMode &&
                    previewState.linkPreviewsEnabled &&
                    entry.shouldShowManualPreviewRetry() &&
                    entry.text?.let { manager.previewLoadingByText[it] != true } == true,
                retryPreviewActionEnabled = manager.canRetryPreview(entry),
                showPinAction = !selectionMode,
                showRemoveAction = !selectionMode,
                showPreviewAction = false,
                onPreview = { onOpenPreview(entry) },
                selectionMode = selectionMode,
                isSelected = isSelected
            )
        }
    }
}

@Composable
private fun ClipboardArchivesContent(
    modifier: Modifier,
    visibleArchives: List<ClipboardLinkArchive>,
    previewFilesByKey: Map<String, List<java.io.File>>,
    archiveBackfillInProgress: Boolean,
    archiveBackfillRemainingCount: Int,
    useSingleColumn: Boolean,
    manager: ClipboardHistoryManager,
    onResetFilters: () -> Unit,
    onOpen: (ClipboardLinkArchive) -> Unit,
    onRetry: (ClipboardLinkArchive) -> Unit,
    onDelete: (ClipboardLinkArchive) -> Unit
) {
    if(visibleArchives.isEmpty()) {
        ScrollableList(modifier = modifier) {
            PaymentSurface(isPrimary = true) {
                PaymentSurfaceHeading(title = stringResource(R.string.clipboard_history_no_results_title))
                ParagraphText(
                    stringResource(
                        if(archiveBackfillInProgress) {
                            R.string.clipboard_history_archive_backfill_saving
                        } else {
                            R.string.clipboard_history_archive_no_results
                        }
                    )
                )
                if(archiveBackfillInProgress) {
                    ParagraphText(
                        stringResource(
                            R.string.clipboard_history_archive_backfill_remaining,
                            archiveBackfillRemainingCount
                        )
                    )
                }
                OutlinedButton(
                    onClick = onResetFilters,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.clipboard_history_archive_reset_filters))
                }
            }
        }
        return
    }

    LazyVerticalStaggeredGrid(
        modifier = modifier.fillMaxWidth(),
        columns = if(useSingleColumn) {
            StaggeredGridCells.Fixed(1)
        } else {
            StaggeredGridCells.Adaptive(180.dp)
        },
        verticalItemSpacing = 4.dp,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        items(
            items = visibleArchives,
            key = { it.key }
        ) { archive ->
            ClipboardArchiveCard(
                archive = archive,
                previewFiles = previewFilesByKey[archive.key].orEmpty(),
                loading = manager.isArchiveLoading(archive),
                progress = manager.archiveDownloadProgress(archive),
                onOpen = { onOpen(archive) },
                onRetry = { onRetry(archive) },
                onDelete = { onDelete(archive) }
            )
        }
    }
}
