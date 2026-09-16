package org.futo.inputmethod.latin.uix.actions.clipboard

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.ActionTextEditController

private data class ClipboardTagSuggestionRequest(
    val index: ClipboardSearchIndex,
    val token: ClipboardSearchToken,
    val query: ClipboardSearchQuery,
    val candidates: List<String>
)

private data class ClipboardTagSuggestionResult(
    val request: ClipboardTagSuggestionRequest?,
    val rows: List<ClipboardTagSuggestion>
)

@Composable
internal fun ClipboardTagSuggestions(
    text: String,
    controller: ActionTextEditController,
    index: ClipboardSearchIndex,
    enabled: Boolean,
    height: Dp,
    onVisibilityChanged: (Boolean) -> Unit = {},
    candidateArchiveKeys: (ClipboardSearchQuery) -> List<String>
) {
    var dismissed by remember(text, controller.selectionStart, controller.selectionEnd) { mutableStateOf(false) }
    val token = remember(text, controller.selectionStart, controller.selectionEnd) {
        clipboardSearchToken(text, controller.selectionStart, controller.selectionEnd)
    }
    val active = enabled && controller.focused && !dismissed && token != null
    val contextQuery = remember(text, token) {
        if(token == null) parseClipboardSearch(text) else clipboardSearchContext(text, token)
    }
    val currentCandidates by rememberUpdatedState(candidateArchiveKeys)
    val candidates by remember(active, contextQuery) {
        derivedStateOf { if(active) currentCandidates(contextQuery) else emptyList() }
    }
    val request = if(active && token != null) {
        ClipboardTagSuggestionRequest(index, token, contextQuery, candidates)
    } else null
    val result by produceState(ClipboardTagSuggestionResult(null, emptyList()), request) {
        val rows = if(request == null) emptyList() else withContext(Dispatchers.Default) {
            request.index.suggestions(
                request.token.prefix, request.query.included + request.query.excluded, request.candidates
            )
        }
        value = ClipboardTagSuggestionResult(request, rows)
    }
    // Keep the tray's footprint while a new query is computed, but don't accept stale rows.
    val suggestions = result.rows
    val loading = result.request != request
    var highlighted by remember(suggestions) { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val visible = active && suggestions.isNotEmpty()
    fun accept(suggestion: ClipboardTagSuggestion) {
        if(token == null || loading) return
        val replacement = clipboardTagReplacement(text, token, suggestion.name)
        controller.replace(text, replacement.start, replacement.end, replacement.text)
    }
    SideEffect {
        onVisibilityChanged(visible)
        controller.onKey = { key ->
            when {
                !visible -> false
                key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE -> {
                    dismissed = true
                    true
                }
                loading -> false
                key == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    highlighted = (highlighted + 1).coerceAtMost(suggestions.lastIndex)
                    true
                }
                key == KeyEvent.KEYCODE_DPAD_UP -> {
                    highlighted = (highlighted - 1).coerceAtLeast(0)
                    true
                }
                (key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_TAB) && highlighted >= 0 -> {
                    accept(suggestions[highlighted])
                    true
                }
                else -> false
            }
        }
    }
    val currentVisibilityChanged by rememberUpdatedState(onVisibilityChanged)
    DisposableEffect(controller) {
        onDispose {
            controller.onKey = null
            currentVisibilityChanged(false)
        }
    }
    BackHandler(visible) { dismissed = true }
    LaunchedEffect(highlighted) {
        if(highlighted >= 0) listState.scrollToItem(highlighted)
    }
    if(!visible) return

    ClipboardTagSuggestionList(
        suggestions = suggestions,
        prefix = result.request?.token?.prefix.orEmpty(),
        negative = result.request?.token?.negative == true,
        highlighted = highlighted,
        listState = listState,
        height = height,
        enabled = !loading,
        onAccept = ::accept
    )
}

@Composable
private fun ClipboardTagSuggestionList(
    suggestions: List<ClipboardTagSuggestion>,
    prefix: String,
    negative: Boolean,
    highlighted: Int,
    listState: LazyListState,
    height: Dp,
    enabled: Boolean = true,
    onAccept: (ClipboardTagSuggestion) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(height)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        itemsIndexed(suggestions, key = { _, suggestion -> suggestion.name }) { position, suggestion ->
            val category = stringResource(when(suggestion.category) {
                ClipboardImageTagCategory.General -> R.string.clipboard_tag_category_general
                ClipboardImageTagCategory.Character -> R.string.clipboard_tag_category_character
            })
            val count = if(negative) {
                stringResource(R.string.clipboard_tag_excluded_count, suggestion.count)
            } else {
                pluralStringResource(R.plurals.clipboard_tag_match_count, suggestion.count, suggestion.count)
            }
            val label = buildAnnotatedString {
                append(suggestion.name)
                val start = suggestion.name.indexOf(prefix)
                if(prefix.isNotEmpty() && start >= 0) {
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + prefix.length)
                }
            }
            Column(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .background(
                        if(position == highlighted) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer
                    )
                    .semantics { selected = position == highlighted }
                    .clickable(enabled = enabled) { onAccept(suggestion) }.padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text("$category · $count", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private val TagSuggestionPreviewRows = listOf(
    ClipboardTagSuggestion("blue_hair", ClipboardImageTagCategory.General, 42),
    ClipboardTagSuggestion("blue_eyes", ClipboardImageTagCategory.General, 28),
    ClipboardTagSuggestion("hatsune_miku", ClipboardImageTagCategory.Character, 12),
    ClipboardTagSuggestion("dark_blue_hair", ClipboardImageTagCategory.General, 5)
)

@Preview(widthDp = 360, heightDp = 144)
@Composable
private fun ClipboardTagSuggestionsCompactPreview() {
    MaterialTheme {
        ClipboardTagSuggestionList(TagSuggestionPreviewRows, "blue", false, -1, rememberLazyListState(), 144.dp) {}
    }
}

@Preview(widthDp = 360, heightDp = 288, fontScale = 1.5f)
@Composable
private fun ClipboardTagSuggestionsLargeTextPreview() {
    MaterialTheme {
        ClipboardTagSuggestionList(TagSuggestionPreviewRows, "blue", true, 0, rememberLazyListState(), 288.dp) {}
    }
}
