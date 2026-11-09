package org.futo.inputmethod.latin.uix.actions.clipboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue

/**
 * Shows how many clips and archives still need fetching, with a button that starts the catch-up.
 * Opening clipboard history never goes online by itself; this is the only way to start it.
 * Hidden in offline mode and when nothing is missing.
 */
@Composable
internal fun ClipboardFetchMissingBar(
    manager: ClipboardHistoryManager,
    modifier: Modifier = Modifier
) {
    val offlineMode = useDataStoreValue(ClipboardOfflineMode)
    val missingCount by remember(manager) { derivedStateOf { manager.missingLinkPreviewCount() } }
    if(offlineMode || missingCount == 0) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.refresh_cw),
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = pluralStringResource(R.plurals.clipboard_history_missing_previews, missingCount, missingCount),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = manager::fetchMissingLinkPreviews) {
                Text(stringResource(R.string.clipboard_history_fetch_missing))
            }
        }
    }
}
