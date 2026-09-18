package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R

@Composable
internal fun ClipboardOcrStatus(remaining: Int, onExtract: () -> Unit, onCancel: () -> Unit) {
    if(remaining == 0) {
        TextButton(onClick = onExtract) { Text(stringResource(R.string.clipboard_ocr_extract_existing)) }
    } else {
        Column {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Row {
                Text(stringResource(R.string.clipboard_ocr_remaining, remaining), modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.clipboard_ocr_cancel)) }
            }
        }
    }
}

@Composable
internal fun ClipboardOcrDialog(
    result: ClipboardOcrResult?,
    extracting: Boolean,
    onExtract: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val text = result?.takeUnless { it.failed }?.text.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clipboard_ocr_extract)) },
        text = {
            Column {
                if(extracting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.clipboard_ocr_extracting))
                }
                if(text.isNotBlank()) {
                    SelectionContainer {
                        Text(text, modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
                    }
                } else if(!extracting) {
                    Text(stringResource(if(result?.failed == true) R.string.clipboard_ocr_failed else R.string.clipboard_ocr_empty))
                }
                TextButton(onClick = onExtract, enabled = !extracting) {
                    Text(stringResource(R.string.clipboard_ocr_retry))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Extracted text", text))
            }) { Text(stringResource(R.string.clipboard_ocr_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.clipboard_ocr_close)) }
        }
    )
}

@Preview(widthDp = 390, heightDp = 600)
@Composable
private fun ClipboardOcrTextPreview() {
    MaterialTheme {
        ClipboardOcrDialog(
            result = ClipboardOcrResult(
                ClipboardOcrInput("https://example.com/image.png", "image.png", 1),
                ClipboardOcrModelRevision, 2,
                regions = listOf(ClipboardOcrRegion("Hello, world!\nこんにちは、世界！", 0.98f, emptyList()))
            ),
            extracting = false, onExtract = {}, onDismiss = {}
        )
    }
}

@Preview(widthDp = 390, heightDp = 600, fontScale = 1.5f)
@Composable
private fun ClipboardOcrFailedPreview() {
    MaterialTheme {
        ClipboardOcrDialog(
            ClipboardOcrResult(ClipboardOcrInput("url", "image.png", 1), ClipboardOcrModelRevision, 2, failed = true),
            extracting = false, onExtract = {}, onDismiss = {}
        )
    }
}
