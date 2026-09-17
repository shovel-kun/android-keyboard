package org.futo.inputmethod.latin.uix.actions.clipboard

import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.futo.inputmethod.latin.uix.ActionEditText
import org.futo.inputmethod.latin.uix.ActionTextEditController

private enum class ChangeDuringTagTap { None, TagIndex, SearchText, TypedQuery, TypedTagQuery }

@Preview(widthDp = 360, heightDp = 192)
@Composable
private fun ClipboardTagStableTapPreview() = ClipboardTagTapRegression(ChangeDuringTagTap.None)

@Preview(widthDp = 360, heightDp = 192)
@Composable
private fun ClipboardTagRefreshingTapPreview() = ClipboardTagTapRegression(ChangeDuringTagTap.TagIndex)

@Preview(widthDp = 360, heightDp = 192)
@Composable
private fun ClipboardTagEditedDuringTapPreview() = ClipboardTagTapRegression(ChangeDuringTagTap.SearchText)

@Preview(widthDp = 360, heightDp = 240)
@Composable
private fun ClipboardTagTypedQueryTapPreview() = ClipboardTagTapRegression(ChangeDuringTagTap.TypedQuery)

@Preview(widthDp = 360, heightDp = 240)
@Composable
private fun ClipboardTagTypedTagQueryTapPreview() = ClipboardTagTapRegression(ChangeDuringTagTap.TypedTagQuery)

// Render these previews to exercise real pointer events across an asynchronous search update.
// Kept in debug sources so the regression harness is not included in release builds.
@Composable
private fun ClipboardTagTapRegression(change: ChangeDuringTagTap) {
    val context = LocalContext.current
    val view = LocalView.current
    val typedQuery = when(change) {
        ChangeDuringTagTap.TypedQuery -> "surpri"
        ChangeDuringTagTap.TypedTagQuery -> "tag:surpri"
        else -> null
    }
    var text by remember { mutableStateOf(if(typedQuery != null) "" else "gotoh") }
    var passed by remember { mutableStateOf(false) }
    val controller = remember { ActionTextEditController() }
    val editor = remember {
        ActionEditText(ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat), inspection = true).apply {
            setText(text)
            setSelection(text.length)
            setTextChangeCallback { text = it }
            controller.attach(this)
            controller.focused = true
        }
    }
    DisposableEffect(editor) { onDispose { controller.detach() } }
    val tags = remember {
        if(typedQuery != null) mapOf("surprised" to ClipboardImageTagCategory.General)
        else mapOf("gotoh_hitori" to ClipboardImageTagCategory.Character, "blue_hair" to ClipboardImageTagCategory.General)
    }
    var index by remember { mutableStateOf(ClipboardSearchIndex(mapOf("a" to tags))) }
    val search = rememberClipboardTagSearch(text, controller, index, true) { listOf("a", "b") }
    val currentSearch by rememberUpdatedState(search)
    var bounds by remember { mutableStateOf(Rect.Zero) }
    MaterialTheme {
        Column(Modifier.background(MaterialTheme.colorScheme.background)) {
            if(typedQuery != null) {
                AndroidView(factory = {
                    editor.apply {
                        setTextColor(android.graphics.Color.BLACK)
                        setOnFocusChangeListener { _, focused -> controller.focused = focused }
                        requestFocus()
                    }
                })
            }
            Box(Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) {
                ClipboardTagSuggestions(search, controller, 120.dp)
            }
            Text("Query: $text")
            Text(if(passed) "Tap check passed" else "Waiting for tap check")
            if(!passed) CircularProgressIndicator()
        }
    }
    LaunchedEffect(Unit) {
        if(typedQuery != null) {
            delay(100)
            editor.requestFocus()
            for(character in typedQuery) {
                editor.append(character.toString())
                editor.setSelection(editor.length())
                delay(50)
            }
            delay(300)
        }
        snapshotFlow { currentSearch.visible && currentSearch.canAccept && bounds.height > 0 }.first { it }
        withFrameNanos { }
        val downTime = SystemClock.uptimeMillis()
        // Hold the original row's coordinates even if a refresh changes the list's bounds.
        val position = bounds.center
        fun touch(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, position.x, position.y, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN)
        if(change == ChangeDuringTagTap.None || typedQuery != null) {
            delay(100)
        } else {
            val beforeChange = currentSearch
            when(change) {
                ChangeDuringTagTap.TagIndex -> index = ClipboardSearchIndex(mapOf("a" to tags, "b" to tags))
                ChangeDuringTagTap.SearchText -> {
                    editor.setText("blue")
                    editor.setSelection(4)
                }
                ChangeDuringTagTap.None, ChangeDuringTagTap.TypedQuery, ChangeDuringTagTap.TypedTagQuery -> Unit
            }
            snapshotFlow { currentSearch }.first { it !== beforeChange }
        }
        touch(MotionEvent.ACTION_UP)
        delay(50)
        val expected = when(change) {
            ChangeDuringTagTap.SearchText -> "blue"
            ChangeDuringTagTap.TypedQuery, ChangeDuringTagTap.TypedTagQuery -> "tag:surprised "
            else -> "tag:gotoh_hitori "
        }
        check(editor.text.toString() == expected && text == expected) {
            "Tag tap with $change: expected '$expected', editor='${editor.text}', query='$text'"
        }
        passed = true
    }
}

private enum class SearchRefresh { Highlight, SuggestionsAppear, SuggestionsDisappear }

@Preview(widthDp = 360, heightDp = 192)
@Composable
private fun ClipboardHighlightedTagRefreshPreview() = ClipboardSearchRefreshRegression(SearchRefresh.Highlight)

@Preview(widthDp = 360, heightDp = 192)
@Composable
private fun ClipboardSuggestionsAppearPreview() = ClipboardSearchRefreshRegression(SearchRefresh.SuggestionsAppear)

@Preview(widthDp = 360, heightDp = 192)
@Composable
private fun ClipboardSuggestionsDisappearPreview() = ClipboardSearchRefreshRegression(SearchRefresh.SuggestionsDisappear)

@Composable
private fun ClipboardSearchRefreshRegression(refresh: SearchRefresh) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("gotoh") }
    var passed by remember { mutableStateOf(false) }
    val controller = remember { ActionTextEditController() }
    val editor = remember {
        ActionEditText(ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat), inspection = true).apply {
            setText(text)
            setSelection(text.length)
            setTextChangeCallback { text = it }
            controller.attach(this)
            controller.focused = true
        }
    }
    DisposableEffect(editor) { onDispose { controller.detach() } }
    val tags = remember {
        buildMap {
            put("gotoh_hitori", ClipboardImageTagCategory.Character)
            if(refresh == SearchRefresh.Highlight) put("gotoh_ikuyo", ClipboardImageTagCategory.Character)
        }
    }
    var index by remember {
        mutableStateOf(ClipboardSearchIndex(if(refresh == SearchRefresh.SuggestionsAppear) emptyMap() else mapOf("a" to tags)))
    }
    val search = rememberClipboardTagSearch(text, controller, index, true) { listOf("a", "b") }
    val currentSearch by rememberUpdatedState(search)
    MaterialTheme {
        Column(Modifier.background(MaterialTheme.colorScheme.background)) {
            ClipboardTagSuggestions(search, controller, 120.dp)
            Text("Query: $text")
            Text(if(passed) "Refresh check passed" else "Waiting for refresh check")
            // Keep preview frames advancing while the background lookup completes.
            if(!passed) CircularProgressIndicator()
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { currentSearch.canAccept }.first { it }
        delay(50)
        val initialQuery = currentSearch.queryText
        check(initialQuery == if(refresh == SearchRefresh.SuggestionsAppear) "gotoh" else "")
        if(refresh == SearchRefresh.Highlight) controller.onKey?.invoke(KeyEvent.KEYCODE_DPAD_DOWN)
        index = ClipboardSearchIndex(when(refresh) {
            SearchRefresh.Highlight -> mapOf("a" to tags, "b" to mapOf("gotoh_ikuyo" to ClipboardImageTagCategory.Character))
            SearchRefresh.SuggestionsAppear -> mapOf("a" to tags)
            SearchRefresh.SuggestionsDisappear -> emptyMap()
        })
        snapshotFlow {
            when(refresh) {
                SearchRefresh.Highlight -> currentSearch.suggestions.firstOrNull()?.count == 2
                SearchRefresh.SuggestionsAppear -> currentSearch.suggestions.isNotEmpty()
                SearchRefresh.SuggestionsDisappear -> currentSearch.suggestions.isEmpty()
            }
        }.first { it }
        delay(50)
        if(refresh == SearchRefresh.Highlight) controller.onKey?.invoke(KeyEvent.KEYCODE_ENTER)
        delay(50)
        val success = if(refresh == SearchRefresh.Highlight) text == "tag:gotoh_hitori " && editor.text.toString() == text else currentSearch.queryText == initialQuery
        check(success) {
            "Search refresh with $refresh: initial='$initialQuery', now='${currentSearch.queryText}', text='$text'"
        }
        passed = true
    }
}
