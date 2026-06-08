package org.futo.inputmethod.latin.uix.actions.clipboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.uix.ActionHeaderSearch
import org.futo.inputmethod.latin.uix.DialogRequestItem
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.actions.BugViewerAction
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.pages.ParagraphText
import org.futo.inputmethod.latin.uix.settings.pages.PaymentSurface
import org.futo.inputmethod.latin.uix.settings.pages.PaymentSurfaceHeading
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import java.io.File

@Composable
internal fun RowScope.ClipboardHistoryActionToolbarControls(
    unlocked: Boolean,
    clipboardHistoryManager: ClipboardHistoryManager
) {
    if(!unlocked) return

    val uiState = rememberClipboardUiState(clipboardHistoryManager)
    val storedEmbedDisplayMode = useDataStore(ClipboardEmbedDisplayModeSetting, blocking = true)
    if(!uiState.previewControlsVisible) return

    val currentMode = uiState.previewState.embedDisplayMode
    IconButton(onClick = {
        storedEmbedDisplayMode.setValue(currentMode.next().storedValue)
    }) {
        Icon(
            painter = painterResource(id = currentMode.icon),
            contentDescription = stringResource(currentMode.contentDescription)
        )
    }
}

@Composable
internal fun RowScope.ClipboardHistoryActionTitleBar(
    clipboardHistoryManager: ClipboardHistoryManager,
    unlocked: Boolean,
    searchActive: MutableState<Boolean>,
    searchText: MutableState<String>
) {
    val uiState = rememberClipboardUiState(clipboardHistoryManager)
    if(!uiState.historyEnabled || !unlocked || !uiState.historyVisible) return

    if(searchActive.value) {
        ActionHeaderSearch(
            searchText,
            Modifier.weight(1.0f),
            placeholder = stringResource(R.string.action_clipboard_manager_enter_your_search)
        )
        IconButton(onClick = {
            searchText.value = ""
            searchActive.value = false
        }) {
            Icon(
                painter = painterResource(id = R.drawable.close),
                contentDescription = stringResource(R.string.clipboard_history_clear_search)
            )
        }
        return
    }

    IconButton(onClick = { searchActive.value = true }) {
        Icon(
            Icons.Default.Search,
            contentDescription = stringResource(R.string.action_clipboard_manager_search)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ClipboardHistoryActionWindowContents(
    manager: KeyboardManagerForAction,
    clipboardHistoryManager: ClipboardHistoryManager,
    unlocked: Boolean,
    searchText: String
) {
    val view = LocalView.current
    val context = LocalContext.current
    val clipboardHistory = useDataStore(ClipboardHistoryEnabled, blocking = true)
    val uiState = rememberClipboardUiState(clipboardHistoryManager)

    LaunchedEffect(unlocked, uiState) {
        if(unlocked && uiState.shouldRefreshPreviews) {
            clipboardHistoryManager.refreshMissingLinkPreviews()
        }
    }

    when {
        !unlocked -> {
            ScrollableList {
                PaymentSurface(isPrimary = true) {
                    PaymentSurfaceHeading(title = stringResource(R.string.action_clipboard_manager_error_device_locked_title))
                    ParagraphText(stringResource(R.string.action_clipboard_manager_error_device_locked_text))
                }
            }
        }

        uiState.ioFailure -> {
            ScrollableList {
                PaymentSurface(isPrimary = true) {
                    PaymentSurfaceHeading(title = stringResource(R.string.action_clipboard_manager_error_general_title))
                    ParagraphText(
                        stringResource(
                            R.string.action_clipboard_manager_error_general_text,
                            clipboardHistoryManager.clipboardIOFailureReason
                        )
                    )
                    androidx.compose.material3.Button(
                        onClick = { manager.activateAction(BugViewerAction) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_clipboard_manager_inspect_error_via_bugs_action))
                    }
                    androidx.compose.material3.Button(
                        onClick = { clipboardHistoryManager.saveClipboard() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_clipboard_manager_retry_saving_loading))
                    }
                    androidx.compose.material3.Button(
                        onClick = {
                            manager.requestDialog(
                                context.getString(R.string.action_clipboard_manager_delete_corrupted_clipboard_text),
                                listOf(
                                    DialogRequestItem(context.getString(R.string.action_clipboard_manager_cancel_action_button)) {},
                                    DialogRequestItem(context.getString(R.string.action_clipboard_manager_delete_corrupted_clipboard_button)) {
                                        clipboardHistoryManager.clipboardIOFailure.value = false
                                        clipboardHistory.setValue(false)
                                        clipboardHistoryManager.deleteClipboard()
                                    },
                                ),
                                {}
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(context.getString(R.string.action_clipboard_manager_delete_corrupted_clipboard_button))
                    }
                }
            }
        }

        !uiState.historyEnabled -> {
            ScrollableList {
                PaymentSurface(isPrimary = true) {
                    PaymentSurfaceHeading(title = stringResource(R.string.action_clipboard_manager_error_clipboard_history_disabled_title))
                    ParagraphText(stringResource(R.string.action_clipboard_manager_error_clipboard_history_disabled_text_v2))
                    androidx.compose.material3.Button(
                        onClick = { clipboardHistory.setValue(true) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_clipboard_manager_enable_clipboard_history_button))
                    }
                }
            }
        }

        uiState.incognitoMode -> {
            ScrollableList {
                PaymentSurface(isPrimary = true) {
                    PaymentSurfaceHeading(title = stringResource(R.string.action_clipboard_manager_incognito_title))
                    ParagraphText(stringResource(R.string.action_clipboard_manager_incognito_text))
                }
            }
        }

        else -> {
            val sortedList = when {
                useDataStoreValue(ClipboardShowPinnedOnTop) -> clipboardHistoryManager.clipboardHistory.sortedBy { it.pinned }
                else -> clipboardHistoryManager.clipboardHistory
            }
            val displayedList = sortedList.filter { it.matchesQuery(searchText) }
            if(displayedList.isEmpty() && searchText.isNotBlank() && sortedList.isNotEmpty()) {
                ScrollableList {
                    PaymentSurface(isPrimary = true) {
                        ParagraphText(stringResource(R.string.action_clipboard_manager_no_clips_found))
                    }
                }
                return
            }

            val useSingleColumn = useDataStoreValue(ClipboardSingleColumn)
            val columns = if(useSingleColumn) {
                StaggeredGridCells.Fixed(1)
            } else {
                StaggeredGridCells.Adaptive(140.dp)
            }

            LazyVerticalStaggeredGrid(
                modifier = Modifier.fillMaxWidth(),
                columns = columns,
                verticalItemSpacing = 4.dp,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(displayedList.size, key = { reverseIndex ->
                    val index = displayedList.size - reverseIndex - 1
                    val entry = displayedList[index]
                    entry.lazyListKey(index)
                }) { reverseIndex ->
                    val index = displayedList.size - reverseIndex - 1
                    val entry = displayedList[index]
                    ClipboardEntryView(
                        modifier = Modifier,
                        clipboardEntry = entry,
                        previewMediaTotalCount = clipboardHistoryManager.expectedPreviewMediaCount(entry),
                        previewLoading = uiState.previewState.showsEmbed &&
                            entry.text?.let { clipboardHistoryManager.previewLoadingByText[it] == true } == true,
                        embedDisplayMode = uiState.previewState.embedDisplayMode,
                        onPaste = {
                            when {
                                it.text != null -> manager.typeText(
                                    ClipboardLinkPreviewFetcher.normalizedTextForClipboardImport(it.text)
                                )
                                it.backingFile != null && it.mimeTypes.isNotEmpty() -> {
                                    val uri = createClipboardContentUri(
                                        file = File(context.clipboardDir, it.backingFile),
                                        mimeType = it.mimeTypes.first()
                                    )
                                    manager.typeUri(uri, it.mimeTypes, true)
                                }
                            }

                            clipboardHistoryManager.onPaste(it)
                            manager.performHapticAndAudioFeedback(Constants.CODE_OUTPUT_TEXT, view)
                        },
                        onRemove = {
                            if(context.getSetting(ClipboardSkipDeleteConfirmation) && !it.pinned) {
                                clipboardHistoryManager.onRemove(it)
                                manager.performHapticAndAudioFeedback(Constants.CODE_TAB, view)
                            } else {
                                manager.requestDialog(
                                    if(it.backingFile != null && it.text == null) {
                                        context.getString(R.string.action_clipboard_manager_remove_item_confirm_dialog_image)
                                    } else {
                                        context.getString(
                                            R.string.action_clipboard_manager_remove_item_confirm_dialog,
                                            sanitizeClipboardText(it.text ?: "", 24)
                                        )
                                    },
                                    listOf(
                                        DialogRequestItem(
                                            context.getString(R.string.action_clipboard_manager_cancel_action_button)
                                        ) {},
                                        DialogRequestItem(
                                            context.getString(R.string.action_clipboard_manager_remove_item)
                                        ) {
                                            clipboardHistoryManager.onRemove(it)
                                            manager.performHapticAndAudioFeedback(Constants.CODE_TAB, view)
                                        }
                                    )
                                ) {}
                            }
                        },
                        onPin = {
                            clipboardHistoryManager.onTogglePin(it)
                            manager.performHapticAndAudioFeedback(Constants.CODE_TAB, view)
                        },
                        onRetryPreview = {
                            clipboardHistoryManager.retryPreviewForEntry(it)
                            manager.performHapticAndAudioFeedback(Constants.CODE_TAB, view)
                        },
                        onWrapAndPaste = { clipEntry ->
                            when {
                                clipEntry.uri != null -> manager.typeUri(clipEntry.uri, clipEntry.mimeTypes)
                                clipEntry.text != null -> manager.typeText(
                                    "||${ClipboardLinkPreviewFetcher.normalizedTextForClipboardImport(clipEntry.text)}||"
                                )
                            }
                            clipboardHistoryManager.onPaste(clipEntry)
                            manager.performHapticAndAudioFeedback(Constants.CODE_OUTPUT_TEXT, view)
                        },
                        showRetryPreviewAction = uiState.previewState.linkPreviewsEnabled &&
                            entry.shouldShowManualPreviewRetry() &&
                            entry.text?.let { clipboardHistoryManager.previewLoadingByText[it] != true } == true,
                        retryPreviewActionEnabled = clipboardHistoryManager.canRetryPreview(entry)
                    )
                }
            }
        }
    }
}
