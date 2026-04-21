package org.futo.inputmethod.latin.uix.actions.clipboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val selectedKeys = remember { mutableStateListOf<String>() }
    var selectionMode by remember { mutableStateOf(false) }
    var previewEntryKey by remember { mutableStateOf<String?>(null) }
    var deleteRequest by remember { mutableStateOf<DeleteRequest?>(null) }

    LaunchedEffect(uiState.shouldRefreshPreviews) {
        if(uiState.shouldRefreshPreviews) {
            manager.refreshMissingLinkPreviews()
        }
    }

    val allEntries = sortedClipboardEntries(
        entries = manager.clipboardHistory.toList(),
        showPinnedOnTop = showPinnedOnTop
    ).filter { it != DefaultClipboardEntry }
    val hasHistoryEntries = allEntries.isNotEmpty()
    val filterCounts = ClipboardHistoryFilter.entries.associateWith { filter ->
        allEntries.count { filter.matches(it) }
    }
    val visibleEntries = allEntries.filter {
        activeFilter.matches(it) && it.matchesQuery(query.value)
    }
    val visibleKeySet = visibleEntries.map { it.selectionKey() }.toSet()
    val selectedEntries = visibleEntries.filter { it.selectionKey() in selectedKeys }
    val allVisibleSelected = visibleEntries.isNotEmpty() && selectedEntries.size == visibleEntries.size
    val hasPinnedSelection = selectedEntries.any { it.pinned }
    val hasUnpinnedSelection = selectedEntries.any { !it.pinned }
    val canBrowseHistory = uiState.historyVisible && hasHistoryEntries

    LaunchedEffect(visibleKeySet, canBrowseHistory) {
        selectedKeys.retainAll { it in visibleKeySet }
        if(selectedKeys.isEmpty()) {
            selectionMode = false
        }
        if(!canBrowseHistory) {
            selectedKeys.clear()
            selectionMode = false
            previewEntryKey = null
        }
    }

    val previewEntry = previewEntryKey?.let { key ->
        allEntries.firstOrNull { it.selectionKey() == key }
    }

    fun clearSelection() {
        selectedKeys.clear()
        selectionMode = false
    }

    fun handleBack() {
        if(selectionMode) clearSelection() else navController.navigateUp()
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

    BackHandler(enabled = selectionMode) {
        clearSelection()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ClipboardHistoryTitle(
            title = if(selectionMode) {
                if(selectedKeys.isEmpty()) {
                    context.getString(R.string.clipboard_history_select_items)
                } else {
                    context.getString(R.string.clipboard_history_selected_count, selectedKeys.size)
                }
            } else {
                context.getString(R.string.typing_settings_enable_clipboard_history)
            },
            onBack = ::handleBack
        )

        when {
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

            !hasHistoryEntries -> {
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
                        trailingContent = if(query.value.isNotBlank()) {
                            {
                                IconButton(onClick = { query.value = "" }) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = stringResource(R.string.clipboard_history_clear_search)
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        placeholder = stringResource(R.string.clipboard_history_search_placeholder),
                        autofocus = true,
                        forceQwerty = true
                    )
                }

                if(!selectionMode) {
                    ClipboardHistoryFilterRow(
                        activeFilter = activeFilter,
                        counts = filterCounts,
                        onFilterSelected = { activeFilter = it }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if(visibleEntries.isEmpty()) {
                    ScrollableList(modifier = Modifier.weight(1f)) {
                        PaymentSurface(isPrimary = true) {
                            PaymentSurfaceHeading(title = stringResource(R.string.clipboard_history_no_results_title))
                            ParagraphText(stringResource(R.string.clipboard_history_no_results_text))
                            if(activeFilter != ClipboardHistoryFilter.All) {
                                OutlinedButton(
                                    onClick = { activeFilter = ClipboardHistoryFilter.All },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.clipboard_history_reset_filters))
                                }
                            }
                        }
                    }
                } else {
                    LazyVerticalStaggeredGrid(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        columns = if(useSingleColumn) {
                            StaggeredGridCells.Fixed(1)
                        } else {
                            StaggeredGridCells.Adaptive(160.dp)
                        },
                        verticalItemSpacing = 4.dp,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                    ) {
                        items(
                            items = visibleEntries,
                            key = {
                                it.text?.takeIf { value -> value.length <= 512 }
                                    ?: it.text?.toFNV1aHash()
                                    ?: it.backingFile
                                    ?: it.selectionKey()
                            }
                        ) { entry ->
                            val isSelected = entry.selectionKey() in selectedKeys
                            val canPreview = uiState.previewState.showsEmbed &&
                                (entry.backingFile != null || entry.previewImageFile != null)

                            ClipboardEntryView(
                                modifier = Modifier.fillMaxWidth(),
                                clipboardEntry = entry,
                                previewLoading = uiState.previewState.showsEmbed &&
                                    entry.text?.let { manager.previewLoadingByText[it] == true } == true,
                                embedDisplayMode = uiState.previewState.embedDisplayMode,
                                onPaste = {
                                    when {
                                        selectionMode -> toggleSelection(entry)
                                        canPreview -> previewEntryKey = entry.selectionKey()
                                        entry.text != null -> copyTextClip(context, entry)
                                    }
                                },
                                onRemove = { requestDelete(listOf(entry)) },
                                onPin = { manager.onTogglePin(entry) },
                                onCopy = { copyTextClip(context, entry) },
                                onRetryPreview = { manager.retryPreviewForEntry(entry) },
                                onLongClick = { toggleSelection(entry) },
                                showWrapAction = false,
                                showCopyAction = !selectionMode && entry.text != null && canPreview,
                                showRetryPreviewAction = !selectionMode &&
                                    uiState.previewState.linkPreviewsEnabled &&
                                    entry.shouldShowManualPreviewRetry() &&
                                    entry.text?.let { manager.previewLoadingByText[it] != true } == true,
                                showPinAction = !selectionMode,
                                showRemoveAction = !selectionMode,
                                showPreviewAction = false,
                                onPreview = { previewEntryKey = entry.selectionKey() },
                                selectionMode = selectionMode,
                                isSelected = isSelected
                            )
                        }
                    }
                }
            }
        }

        if(selectionMode) {
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
            onShare = { shareClipboardImage(context, entry, uiState.previewState) }
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
}
