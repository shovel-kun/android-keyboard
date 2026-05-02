package org.futo.inputmethod.latin.uix.actions.clipboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

@Composable
internal fun ClipboardPreviewMediaCollage(
    itemCount: Int,
    totalMediaCount: Int,
    modifier: Modifier = Modifier,
    overflowTextStyle: TextStyle = MaterialTheme.typography.titleMedium,
    tileContent: @Composable BoxScope.(Int) -> Unit
) {
    val visibleCount = itemCount.coerceIn(1, 4)
    val overflowCount = (totalMediaCount - 4).coerceAtLeast(0)

    @Composable
    fun Tile(index: Int, modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            tileContent(index)
            if(index == 3 && overflowCount > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.46f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+$overflowCount",
                        style = overflowTextStyle,
                        color = Color.White
                    )
                }
            }
        }
    }

    when (visibleCount) {
        1 -> {
            Tile(
                index = 0,
                modifier = modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )
        }

        2 -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .aspectRatio(2f),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                repeat(2) { index ->
                    Tile(
                        index = index,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                    )
                }
            }
        }

        3 -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Tile(
                    index = 0,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Tile(
                        index = 1,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                    Tile(
                        index = 2,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            }
        }

        else -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                repeat(2) { row ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        repeat(2) { column ->
                            val index = row * 2 + column
                            Tile(
                                index = index,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}
