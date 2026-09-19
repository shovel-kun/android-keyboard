package org.futo.inputmethod.latin.uix.actions.clipboard

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import java.io.File

@Preview(widthDp = 360)
@Composable
private fun ClipboardCopyButtonsPreview() {
    MaterialTheme {
        Surface(color = Color.Black) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Media preview", color = Color.White, modifier = Modifier.weight(1f))
                ClipboardPreviewCopyButtons(
                    media = ClipboardPreviewShareTarget(File("preview.jpg"), "image/jpeg"),
                    link = "https://example.com/post/123"
                )
            }
        }
    }
}
