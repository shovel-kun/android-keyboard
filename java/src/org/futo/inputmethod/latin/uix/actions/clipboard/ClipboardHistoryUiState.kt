package org.futo.inputmethod.latin.uix.actions.clipboard

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.settings.useDataStore

enum class ClipboardEmbedDisplayMode(
    val storedValue: Int,
    @DrawableRes val icon: Int,
    @StringRes val contentDescription: Int
) {
    ShowEmbed(
        storedValue = 0,
        icon = R.drawable.image,
        contentDescription = R.string.action_clipboard_manager_embed_mode_show_embed
    ),
    ShowEmbedBlurred(
        storedValue = 1,
        icon = R.drawable.blur,
        contentDescription = R.string.action_clipboard_manager_embed_mode_show_embed_blurred
    ),
    ShowRawClipboard(
        storedValue = 2,
        icon = R.drawable.clipboard,
        contentDescription = R.string.action_clipboard_manager_embed_mode_show_raw_clipboard
    );

    fun next(): ClipboardEmbedDisplayMode = when (this) {
        ShowEmbed -> ShowEmbedBlurred
        ShowEmbedBlurred -> ShowRawClipboard
        ShowRawClipboard -> ShowEmbed
    }

    companion object {
        fun fromStoredValue(value: Int): ClipboardEmbedDisplayMode =
            values().firstOrNull { it.storedValue == value } ?: ShowEmbed
    }
}

internal data class ClipboardPreviewState(
    val linkPreviewsEnabled: Boolean,
    val embedDisplayMode: ClipboardEmbedDisplayMode
) {
    val showsEmbed: Boolean
        get() = embedDisplayMode != ClipboardEmbedDisplayMode.ShowRawClipboard

    val shouldFetchPreviews: Boolean
        get() = linkPreviewsEnabled && showsEmbed

    val shouldArchivePreviews: Boolean
        get() = linkPreviewsEnabled
}

internal data class ClipboardUiState(
    val historyEnabled: Boolean,
    val incognitoMode: Boolean,
    val ioFailure: Boolean,
    val previewState: ClipboardPreviewState
) {
    val historyVisible: Boolean
        get() = historyEnabled && !incognitoMode && !ioFailure

    val previewControlsVisible: Boolean
        get() = historyVisible && previewState.linkPreviewsEnabled

    val shouldRefreshPreviews: Boolean
        get() = historyVisible && previewState.shouldFetchPreviews
}

internal fun previewState(
    linkPreviewsEnabled: Boolean,
    storedEmbedDisplayMode: Int
): ClipboardPreviewState = ClipboardPreviewState(
    linkPreviewsEnabled = linkPreviewsEnabled,
    embedDisplayMode = if(linkPreviewsEnabled) {
        ClipboardEmbedDisplayMode.fromStoredValue(storedEmbedDisplayMode)
    } else {
        ClipboardEmbedDisplayMode.ShowRawClipboard
    }
)

@Composable
internal fun rememberClipboardUiState(manager: ClipboardHistoryManager): ClipboardUiState {
    val historyEnabled = useDataStore(ClipboardHistoryEnabled, blocking = true)
    val incognitoMode = useDataStore(ClipboardIncognitoMode, blocking = true)
    val linkPreviewsEnabled = useDataStore(ClipboardLinkPreviewsEnabled, blocking = true)
    val storedEmbedDisplayMode = useDataStore(ClipboardEmbedDisplayModeSetting, blocking = true)

    return ClipboardUiState(
        historyEnabled = historyEnabled.value,
        incognitoMode = incognitoMode.value,
        ioFailure = manager.clipboardIOFailure.value,
        previewState = previewState(
            linkPreviewsEnabled = linkPreviewsEnabled.value,
            storedEmbedDisplayMode = storedEmbedDisplayMode.value
        )
    )
}
