package org.futo.inputmethod.latin.uix.actions.clipboard

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.SettingsTextEdit
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.pages.ParagraphText
import org.futo.inputmethod.latin.uix.settings.pages.PaymentSurface
import org.futo.inputmethod.latin.uix.settings.pages.PaymentSurfaceHeading
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue

private data class ClipboardArchiveColorAnalysis(
    val fileKeys: List<String> = emptyList(),
    val colorsByArchiveKey: Map<String, Set<ClipboardArchiveColorFilter>> = emptyMap()
)

@Composable
fun ClipboardHistoryScreen(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val manager = rememberClipboardHistoryManager()
    val clipboardHistoryEnabled = useDataStore(ClipboardHistoryEnabled, blocking = true)
    val uiState = rememberClipboardUiState(manager)
    val showPinnedOnTop = useDataStoreValue(ClipboardShowPinnedOnTop)
    val useSingleColumn = useDataStoreValue(ClipboardSingleColumn)
    val skipDeleteConfirmation = useDataStoreValue(ClipboardSkipDeleteConfirmation)
    val imageTaggingEnabled = useDataStoreValue(ClipboardImageTaggingEnabled)
    val archiveSortModeSetting = useDataStore(ClipboardArchiveSortModeSetting)
    val archiveSortMode = ClipboardArchiveSortMode.fromStoredValue(archiveSortModeSetting.value)

    val query = remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(ClipboardHistoryFilter.All) }
    var activeMode by remember { mutableStateOf(ClipboardHistoryContentMode.Clips) }
    var archiveProviderFilter by remember { mutableStateOf(ClipboardArchiveProviderFilter.All) }
    var archiveStatusFilter by remember { mutableStateOf(ClipboardArchiveStatusFilter.All) }
    var archiveColorFilter by remember { mutableStateOf(ClipboardArchiveColorFilter.All) }
    var archiveColorAnalysis by remember { mutableStateOf(ClipboardArchiveColorAnalysis()) }
    var archiveDownloadProviderFilter by remember { mutableStateOf(ClipboardArchiveProviderFilter.All) }
    var archiveFiltersVisible by remember { mutableStateOf(false) }
    var archiveStorageVisible by remember { mutableStateOf(false) }
    var archiveStorageBusy by remember { mutableStateOf(false) }
    var archiveStorageCleanupError by remember { mutableStateOf(false) }
    val selectedKeys = remember { mutableStateListOf<String>() }
    var selectionMode by remember { mutableStateOf(false) }
    var previewEntryKey by remember { mutableStateOf<String?>(null) }
    var previewArchiveKey by remember { mutableStateOf<String?>(null) }
    var deleteRequest by remember { mutableStateOf<DeleteRequest?>(null) }
    var archiveDeleteRequest by remember { mutableStateOf<ArchiveDeleteRequest?>(null) }
    var downloadsVisible by remember { mutableStateOf(false) }
    var clipboardControlsVisible by remember { mutableStateOf(true) }
    val clipsGridState = rememberLazyStaggeredGridState()
    val archivesGridState = rememberLazyStaggeredGridState()
    val activeGridState = if(activeMode == ClipboardHistoryContentMode.Clips) clipsGridState else archivesGridState
    val archiveBackfillInProgress by manager.archiveBackfillInProgress
    val archiveBackfillRemainingCount by manager.archiveBackfillRemainingCount
    val storageInventory by manager.clipboardStorageInventory
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(uiState.shouldRefreshPreviews) {
        if(uiState.shouldRefreshPreviews) {
            manager.refreshMissingLinkPreviews()
        }
    }

    LaunchedEffect(archiveStorageVisible) {
        if(archiveStorageVisible) {
            archiveStorageBusy = true
            try {
                manager.refreshClipboardStorageInventory()
            } finally {
                archiveStorageBusy = false
            }
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
    val archiveUiSnapshot by remember {
        derivedStateOf { manager.archiveUiSnapshot() }
    }
    val allArchives by remember(archiveSortMode) {
        derivedStateOf {
            sortedClipboardArchives(
                archives = archiveUiSnapshot.archives,
                entries = manager.clipboardHistory.toList(),
                sortMode = archiveSortMode
            )
        }
    }
    val archiveColorFileKeys by remember {
        derivedStateOf {
            archiveUiSnapshot.previewFilesByArchiveKey
                .toSortedMap()
                .flatMap { (archiveKey, files) ->
                    files.map { file ->
                        "$archiveKey|${file.absolutePath}"
                    }
                }
        }
    }
    val archiveColorAnalysisInProgress by remember {
        derivedStateOf {
            archiveColorFilter != ClipboardArchiveColorFilter.All &&
                archiveColorAnalysis.fileKeys != archiveColorFileKeys
        }
    }

    LaunchedEffect(archiveColorFilter, archiveColorFileKeys) {
        if(archiveColorFilter != ClipboardArchiveColorFilter.All &&
            archiveColorAnalysis.fileKeys != archiveColorFileKeys
        ) {
            val filesByArchiveKey = archiveUiSnapshot.previewFilesByArchiveKey
            val colorsByArchiveKey = withContext(Dispatchers.IO) {
                filesByArchiveKey.mapValues { (_, files) -> detectClipboardArchiveColors(files) }
            }
            archiveColorAnalysis = ClipboardArchiveColorAnalysis(
                fileKeys = archiveColorFileKeys,
                colorsByArchiveKey = colorsByArchiveKey
            )
        }
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
                    it.matchesStatusFilter(archiveStatusFilter) &&
                    it.matchesColorFilter(
                        archiveColorFilter,
                        archiveColorAnalysis.colorsByArchiveKey[it.key].orEmpty()
                    )
            }
        }
    }
    val archivePreviewFilesByKey by remember {
        derivedStateOf {
            archiveUiSnapshot.previewFilesByArchiveKey.filterKeys { key ->
                visibleArchives.any { it.key == key }
            }
        }
    }
    val archiveDownloadItems by remember {
        derivedStateOf {
            if(downloadsVisible) {
                archiveUiSnapshot.downloadItems
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
                archiveUiSnapshot.downloadActionCount
            }
        }
    }
    val archiveFiltersActive by remember(archiveSortMode) {
        derivedStateOf {
            archiveProviderFilter != ClipboardArchiveProviderFilter.All ||
                archiveStatusFilter != ClipboardArchiveStatusFilter.All ||
                archiveColorFilter != ClipboardArchiveColorFilter.All ||
                archiveSortMode != ClipboardArchiveSortMode.ClipDate
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

    LaunchedEffect(activeMode, activeFilter, archiveProviderFilter, archiveStatusFilter, archiveSortMode, selectionMode, query.value, downloadsVisible) {
        clipboardControlsVisible = true
    }

    rememberScrollControlsVisible(
        state = activeGridState,
        controlsVisible = clipboardControlsVisible,
        onControlsVisibleChanged = { clipboardControlsVisible = it }
    )

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
            badgeCount = archiveDownloadItems.size.takeIf { downloadsVisible },
            actions = {
                if(!selectionMode && !downloadsVisible && uiState.historyEnabled && uiState.historyVisible) {
                    if(activeMode == ClipboardHistoryContentMode.Archives) {
                        IconButton(
                            onClick = {
                                archiveStorageCleanupError = false
                                archiveStorageVisible = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = stringResource(R.string.clipboard_history_storage_open)
                            )
                        }
                    }
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
                    providerFilter = archiveDownloadProviderFilter,
                    modifier = Modifier.weight(1f),
                    onProviderFilterSelected = { archiveDownloadProviderFilter = it },
                    onRetry = { manager.retryArchiveMedia(it) },
                    onDelete = { manager.deleteArchiveDownload(it) },
                    onRetryAll = { manager.retryAllArchiveDownloads(it) },
                    onStop = { manager.stopArchiveDownload(it) },
                    onStopAll = { items -> items.forEach { manager.stopArchiveDownload(it) } }
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
                ClipboardScrollControlsVisibility(visible = clipboardControlsVisible) {
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
                        if(activeMode == ClipboardHistoryContentMode.Archives && imageTaggingEnabled) {
                            ClipboardImageTaggingStatus(
                                state = archiveUiSnapshot.imageTaggingState,
                                eligibleCount = archiveUiSnapshot.imageTagEligibleCount,
                                onTagExisting = manager::tagExistingArchiveImages
                            )
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
                }

                when (activeMode) {
                    ClipboardHistoryContentMode.Clips -> ClipboardClipsContent(
                        modifier = Modifier.weight(1f),
                        gridState = clipsGridState,
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
                        gridState = archivesGridState,
                        visibleArchives = visibleArchives,
                        previewFilesByKey = archivePreviewFilesByKey,
                        colorAnalysisInProgress = archiveColorAnalysisInProgress,
                        archiveBackfillInProgress = archiveBackfillInProgress,
                        archiveBackfillRemainingCount = archiveBackfillRemainingCount,
                        useSingleColumn = useSingleColumn,
                        loadingArchiveKeys = archiveUiSnapshot.loadingArchiveKeys,
                        progressByArchiveKey = archiveUiSnapshot.progressByArchiveKey,
                        onResetFilters = {
                            archiveProviderFilter = ClipboardArchiveProviderFilter.All
                            archiveStatusFilter = ClipboardArchiveStatusFilter.All
                            archiveColorFilter = ClipboardArchiveColorFilter.All
                            archiveSortModeSetting.setValue(ClipboardArchiveSortMode.ClipDate.storedValue)
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
            items = archiveUiSnapshot.galleryItemsByArchiveKey[archive.key].orEmpty(),
            loading = archive.key in archiveUiSnapshot.loadingArchiveKeys,
            progress = archiveUiSnapshot.progressByArchiveKey[archive.key],
            onDismiss = { previewArchiveKey = null },
            onRetry = { manager.retryArchive(archive) },
            onTagImage = { manager.tagArchiveMedia(archive.key, it.media.sourceIndex) },
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
            storedBytes = storageInventory.archiveBytesByKey[request.archive.key] ?: 0L,
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
            colorFilter = archiveColorFilter,
            sortMode = archiveSortMode,
            onProviderFilterSelected = { archiveProviderFilter = it },
            onStatusFilterSelected = { archiveStatusFilter = it },
            onColorFilterSelected = { archiveColorFilter = it },
            onSortModeSelected = { archiveSortModeSetting.setValue(it.storedValue) },
            onResetFilters = {
                archiveProviderFilter = ClipboardArchiveProviderFilter.All
                archiveStatusFilter = ClipboardArchiveStatusFilter.All
                archiveColorFilter = ClipboardArchiveColorFilter.All
                archiveSortModeSetting.setValue(ClipboardArchiveSortMode.ClipDate.storedValue)
            },
            onDismiss = { archiveFiltersVisible = false }
        )
    }

    if(archiveStorageVisible) {
        ClipboardArchiveStorageSheet(
            inventory = storageInventory,
            downloadsActive = manager.hasActiveArchiveDownloads(),
            cleanupInProgress = archiveStorageBusy,
            cleanupError = archiveStorageCleanupError,
            onDeleteUnused = {
                coroutineScope.launch {
                    archiveStorageBusy = true
                    archiveStorageCleanupError = false
                    try {
                        manager.deleteUnreferencedClipboardMedia()
                    } catch(e: CancellationException) {
                        throw e
                    } catch(e: Exception) {
                        archiveStorageCleanupError = true
                    } finally {
                        archiveStorageBusy = false
                    }
                }
            },
            onDismiss = { archiveStorageVisible = false }
        )
    }
}

@Composable
private fun ClipboardScrollControlsVisibility(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Top) + slideInVertically { -it } + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + slideOutVertically { -it } + fadeOut()
    ) {
        Column {
            content()
        }
    }
}

@Composable
private fun rememberScrollControlsVisible(
    state: LazyStaggeredGridState,
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
private fun ClipboardClipsContent(
    modifier: Modifier,
    gridState: LazyStaggeredGridState,
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
        state = gridState,
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
    gridState: LazyStaggeredGridState,
    visibleArchives: List<ClipboardLinkArchive>,
    previewFilesByKey: Map<String, List<java.io.File>>,
    colorAnalysisInProgress: Boolean,
    archiveBackfillInProgress: Boolean,
    archiveBackfillRemainingCount: Int,
    useSingleColumn: Boolean,
    loadingArchiveKeys: Set<String>,
    progressByArchiveKey: Map<String, ClipboardArchiveDownloadProgress>,
    onResetFilters: () -> Unit,
    onOpen: (ClipboardLinkArchive) -> Unit,
    onRetry: (ClipboardLinkArchive) -> Unit,
    onDelete: (ClipboardLinkArchive) -> Unit
) {
    if(colorAnalysisInProgress) {
        ScrollableList(modifier = modifier) {
            PaymentSurface(isPrimary = true) {
                PaymentSurfaceHeading(
                    title = stringResource(R.string.clipboard_history_archive_analyzing_colors)
                )
                ParagraphText(stringResource(R.string.clipboard_history_archive_analyzing_colors_description))
            }
        }
        return
    }

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
        state = gridState,
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
                loading = archive.key in loadingArchiveKeys,
                progress = progressByArchiveKey[archive.key],
                onOpen = { onOpen(archive) },
                onRetry = { onRetry(archive) },
                onDelete = { onDelete(archive) }
            )
        }
    }
}
