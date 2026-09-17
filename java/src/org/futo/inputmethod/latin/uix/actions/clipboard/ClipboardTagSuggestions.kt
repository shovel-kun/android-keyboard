package org.futo.inputmethod.latin.uix.actions.clipboard

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
    val text: String,
    val index: ClipboardSearchIndex,
    val token: ClipboardSearchToken,
    val query: ClipboardSearchQuery,
    val candidates: List<String>
)

private data class ClipboardTagSuggestionResult(
    val request: ClipboardTagSuggestionRequest?,
    val rows: List<ClipboardTagSuggestion>
)

internal class ClipboardTagSearch(
    val text: String,
    val token: ClipboardSearchToken?,
    val suggestions: List<ClipboardTagSuggestion>,
    val canAccept: Boolean,
    val visible: Boolean,
    val queryText: String,
    val dismiss: (String) -> Unit
)

@Composable
internal fun rememberClipboardTagSearch(
    text: String,
    controller: ActionTextEditController,
    index: ClipboardSearchIndex,
    enabled: Boolean,
    candidateArchiveKeys: (ClipboardSearchQuery) -> List<String>
): ClipboardTagSearch {
    var dismissedText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(text) {
        if(dismissedText != text) dismissedText = null
    }
    val token = remember(text, controller.selectionStart, controller.selectionEnd) {
        clipboardSearchToken(text, controller.selectionStart, controller.selectionEnd)
    }
    val active = enabled && controller.focused && text != dismissedText && token != null
    val contextQuery = remember(text, token) {
        if(token == null) parseClipboardSearch(text) else clipboardSearchContext(text, token)
    }
    val currentCandidates by rememberUpdatedState(candidateArchiveKeys)
    val candidates by remember(active, contextQuery) {
        derivedStateOf { if(active) currentCandidates(contextQuery) else emptyList() }
    }
    val request = if(active && token != null) {
        ClipboardTagSuggestionRequest(text, index, token, contextQuery, candidates)
    } else null
    val result by produceState(ClipboardTagSuggestionResult(null, emptyList()), request) {
        val rows = if(request == null) emptyList() else withContext(Dispatchers.Default) {
            request.index.suggestions(
                request.token.prefix, request.query.included + request.query.excluded, request.candidates
            )
        }
        value = ClipboardTagSuggestionResult(request, rows)
    }
    // A catalogue refresh changes counts, not the text a displayed tag will replace.
    // Disable retained rows only when the user has changed the completion context.
    val canAccept = result.request?.let { it.text == text && it.token == token } == true
    val suggestions = result.rows
    val explicitTag = token?.let { text.substring(it.start, it.end).removePrefix("-").startsWith("tag:", true) } == true
    val visible = active && (suggestions.isNotEmpty() || explicitTag)
    // The first lookup for an edit decides whether this token is a tag draft.
    // Background catalogue updates must not change the applied search query.
    var completingTag by remember(text, token) { mutableStateOf<Boolean?>(null) }
    val draft = completingTag ?: (explicitTag || (canAccept && suggestions.isNotEmpty()))
    SideEffect {
        if(completingTag == null && canAccept) completingTag = draft
    }
    val queryText = clipboardEditingSearchText(text, token.takeIf { active && draft })
    return ClipboardTagSearch(text, token, suggestions, canAccept, visible, queryText) { dismissedText = it }
}

@Composable
internal fun ClipboardTagSuggestions(
    search: ClipboardTagSearch,
    controller: ActionTextEditController,
    height: Dp,
    onFinish: () -> Unit = {}
) {
    val token = search.token
    val suggestions = search.suggestions
    val visible = search.visible
    val canAccept = search.canAccept
    var highlightedTag by remember(search.text, token) { mutableStateOf<String?>(null) }
    val highlighted = suggestions.indexOfFirst { it.name == highlightedTag }
    SideEffect {
        if(highlighted < 0) highlightedTag = null
    }
    // A new completion context starts at the best match; index refreshes keep the position.
    val listState = key(search.text, token) { rememberLazyListState() }
    fun finish() {
        search.dismiss(search.text)
        onFinish()
    }
    fun accept(suggestion: ClipboardTagSuggestion) {
        if(token == null || !canAccept) return
        val replacement = clipboardTagReplacement(search.text, token, suggestion.name)
        if(controller.replace(search.text, replacement.start, replacement.end, replacement.text)) {
            search.dismiss(search.text.replaceRange(replacement.start, replacement.end, replacement.text))
            onFinish()
        }
    }
    SideEffect {
        controller.onSubmit = {
            val highlighted = suggestions.indexOfFirst { it.name == highlightedTag }
            if(visible && canAccept && highlighted >= 0) accept(suggestions[highlighted]) else finish()
        }
        controller.onKey = { key ->
            val highlighted = suggestions.indexOfFirst { it.name == highlightedTag }
            when {
                !visible -> false
                key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE -> {
                    finish()
                    true
                }
                key == KeyEvent.KEYCODE_ENTER && highlighted < 0 -> {
                    finish()
                    true
                }
                !canAccept || suggestions.isEmpty() -> false
                key == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    highlightedTag = suggestions[(highlighted + 1).coerceAtMost(suggestions.lastIndex)].name
                    true
                }
                key == KeyEvent.KEYCODE_DPAD_UP -> {
                    highlightedTag = suggestions[(highlighted - 1).coerceAtLeast(0)].name
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
    DisposableEffect(controller) {
        onDispose {
            controller.onKey = null
            controller.onSubmit = null
        }
    }
    LaunchedEffect(highlighted) {
        if(highlighted >= 0) listState.scrollToItem(highlighted)
    }
    if(!visible) return

    ClipboardTagSuggestionList(
        suggestions = suggestions,
        prefix = token?.prefix.orEmpty(),
        negative = token?.negative == true,
        highlighted = highlighted,
        listState = listState,
        height = height,
        enabled = canAccept,
        // A local function reference compares equal across edits and retains stale captures in clickable.
        onAccept = { accept(it) }
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
        modifier = Modifier.fillMaxWidth().heightIn(max = height)
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        if(suggestions.isEmpty() && enabled) {
            item {
                Text(stringResource(R.string.clipboard_tag_no_suggestions), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
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

@Preview(widthDp = 360, heightDp = 120)
@Composable
private fun ClipboardTagSuggestionsCompactPreview() {
    MaterialTheme {
        ClipboardTagSuggestionList(TagSuggestionPreviewRows, "blue", false, -1, rememberLazyListState(), 120.dp) {}
    }
}

@Preview(widthDp = 360, heightDp = 192, fontScale = 1.5f)
@Composable
private fun ClipboardTagSuggestionsLargeTextPreview() {
    MaterialTheme {
        ClipboardTagSuggestionList(TagSuggestionPreviewRows, "blue", true, 0, rememberLazyListState(), 192.dp) {}
    }
}

@Preview(widthDp = 360, heightDp = 192)
@Composable
private fun ClipboardTagSuggestionsSingleMatchPreview() {
    MaterialTheme {
        Column {
            ClipboardTagSuggestionList(TagSuggestionPreviewRows.take(1), "blue", false, -1, rememberLazyListState(), 192.dp) {}
            Text("Results", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(12.dp))
        }
    }
}

// The IME hosts Compose in a service, without an Activity's back dispatcher.
@Preview(widthDp = 360, heightDp = 120)
@Composable
private fun ClipboardTagSuggestionsKeyboardHostPreview() {
    ClipboardTagSuggestionsKeyboardHost(showSuggestions = false)
}

@Preview(widthDp = 360, heightDp = 192)
@Composable
private fun ClipboardTagSuggestionsKeyboardSearchPreview() {
    ClipboardTagSuggestionsKeyboardHost(showSuggestions = true)
}

@Composable
private fun ClipboardTagSuggestionsKeyboardHost(showSuggestions: Boolean) {
    val context = LocalContext.current.applicationContext
    val view = remember(context) { android.view.View(context) }
    CompositionLocalProvider(
        LocalContext provides context,
        LocalView provides view
    ) {
        MaterialTheme {
            Column {
                ClipboardTagSuggestions(
                    search = ClipboardTagSearch(
                        text = if(showSuggestions) "bl" else "",
                        token = if(showSuggestions) ClipboardSearchToken(0, 2, "bl", false) else null,
                        suggestions = if(showSuggestions) TagSuggestionPreviewRows else emptyList(),
                        canAccept = true,
                        visible = showSuggestions,
                        queryText = "",
                        dismiss = {}
                    ),
                    controller = remember { ActionTextEditController() },
                    height = 120.dp
                )
                Text("Clipboard history")
            }
        }
    }
}
