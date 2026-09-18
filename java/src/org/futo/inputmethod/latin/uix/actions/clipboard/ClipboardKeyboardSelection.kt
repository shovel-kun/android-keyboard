package org.futo.inputmethod.latin.uix.actions.clipboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R

internal class ClipboardKeyboardSelection {
    private var keys by mutableStateOf(emptySet<String>())
    val active: Boolean get() = keys.isNotEmpty()

    fun contains(entry: ClipboardEntry): Boolean = entry.lazyListKey() in keys

    fun toggle(entry: ClipboardEntry) {
        val key = entry.lazyListKey()
        keys = if(key in keys) keys - key else keys + key
    }

    fun entries(visible: List<ClipboardEntry>): List<ClipboardEntry> = visible.filter(::contains)

    fun retainVisible(visible: List<ClipboardEntry>) {
        keys = keys.intersect(visible.map { it.lazyListKey() }.toSet())
    }

    fun clear() {
        keys = emptySet()
    }
}

internal fun clipboardSelectionText(
    entries: List<ClipboardEntry>,
    transform: (String) -> String = { it }
): String? {
    if(entries.isEmpty() || entries.any { it.text == null }) return null
    return entries.joinToString("\n") { transform(requireNotNull(it.text)) }
}

@Composable
internal fun ClipboardKeyboardSelectionBar(
    count: Int,
    canPaste: Boolean,
    onCancel: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) {
                Icon(painterResource(R.drawable.close), stringResource(R.string.clipboard_history_cancel_selection))
            }
            Text(
                stringResource(R.string.clipboard_history_selected_count, count),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium
            )
            TextButton(onClick = onPaste, enabled = canPaste) {
                Text(stringResource(R.string.clipboard_keyboard_paste))
            }
            IconButton(onClick = onDelete, enabled = count > 0) {
                Icon(painterResource(R.drawable.delete), stringResource(R.string.clipboard_history_delete_selected))
            }
            IconButton(onClick = onPin, enabled = count > 0) {
                Icon(painterResource(R.drawable.push_pin), stringResource(R.string.clipboard_history_pin_selected))
            }
        }
        if(!canPaste && count > 0) {
            Text(
                stringResource(R.string.clipboard_keyboard_text_only),
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun ClipboardKeyboardSelectionPreview() {
    MaterialTheme {
        Column {
            ClipboardKeyboardSelectionBar(2, true, {}, {}, {}, {})
            ClipboardEntryView(
                modifier = Modifier,
                clipboardEntry = ClipboardEntry(0L, false, "Selected clip", null, emptyList()),
                onPaste = {},
                onRemove = {},
                onPin = {},
                selectionMode = true,
                isSelected = true
            )
            ClipboardKeyboardSelectionBar(2, false, {}, {}, {}, {})
        }
    }
}
