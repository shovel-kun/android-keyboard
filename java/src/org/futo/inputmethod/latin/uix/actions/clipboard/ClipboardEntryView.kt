package org.futo.inputmethod.latin.uix.actions.clipboard

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.collection.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.actions.fonttyper.SuperheroRenderer
import org.futo.inputmethod.latin.uix.theme.Typography
import java.io.File

private object ClipboardThumbCache {
    val cache = LruCache<String, ImageBitmap>(128)
}

private data class ClipboardBitmapSource(
    val file: File,
    val cacheKey: String
)

internal sealed class ClipboardBitmapLoadState {
    data object Loading : ClipboardBitmapLoadState()
    data object Unavailable : ClipboardBitmapLoadState()
    data class Loaded(val bitmap: ImageBitmap) : ClipboardBitmapLoadState()
}

private fun clipboardBitmapSource(
    imageFile: File,
    preferThumbnail: Boolean
): ClipboardBitmapSource? {
    val thumbnail = ClipboardUtil.thumbnailFor(imageFile)
    val file = when {
        preferThumbnail && thumbnail.exists() -> thumbnail
        imageFile.exists() -> imageFile
        thumbnail.exists() -> thumbnail
        else -> return null
    }
    return ClipboardBitmapSource(
        file = file,
        cacheKey = listOf(
            file.absolutePath,
            "thumbnail=$preferThumbnail",
            "modified=${file.lastModified()}",
            "length=${file.length()}"
        ).joinToString("|")
    )
}

internal fun clipboardBitmapCacheKey(
    imageFile: File,
    preferThumbnail: Boolean = true
): String? =
    clipboardBitmapSource(imageFile, preferThumbnail)?.cacheKey

internal fun decodeClipboardBitmap(
    imageFile: File,
    preferThumbnail: Boolean = true
): ImageBitmap? {
    val source = clipboardBitmapSource(imageFile, preferThumbnail) ?: return null
    return ClipboardThumbCache.cache[source.cacheKey]
        ?: BitmapFactory.decodeFile(source.file.absolutePath)?.asImageBitmap()?.also {
            ClipboardThumbCache.cache.put(source.cacheKey, it)
        }
}

private fun cachedClipboardBitmap(
    imageFile: File,
    preferThumbnail: Boolean
): ImageBitmap? =
    clipboardBitmapCacheKey(imageFile, preferThumbnail)?.let(ClipboardThumbCache.cache::get)

private fun requestedClipboardBitmapFiles(
    imageFiles: List<File>,
    maxCount: Int?
): List<File> =
    maxCount?.let { imageFiles.take(it) } ?: imageFiles

private fun cachedClipboardBitmaps(
    imageFiles: List<File>,
    preferThumbnail: Boolean,
    maxCount: Int?
): List<ImageBitmap> =
    requestedClipboardBitmapFiles(imageFiles, maxCount)
        .mapNotNull { cachedClipboardBitmap(it, preferThumbnail) }

private fun clipboardBitmapRequestKeys(
    imageFiles: List<File>,
    preferThumbnail: Boolean,
    maxCount: Int?
): List<String> =
    requestedClipboardBitmapFiles(imageFiles, maxCount)
        .map { clipboardBitmapCacheKey(it, preferThumbnail) ?: "${it.absolutePath}|missing|thumbnail=$preferThumbnail" }

@Composable
internal fun rememberClipboardBitmap(
    imageFile: File?,
    bitmapOverride: ImageBitmap?,
    preferThumbnail: Boolean = true
): ImageBitmap? {
    if(bitmapOverride != null || imageFile == null) return bitmapOverride
    val requestKey = clipboardBitmapCacheKey(imageFile, preferThumbnail)
        ?: "${imageFile.absolutePath}|missing|thumbnail=$preferThumbnail"

    return produceState<ImageBitmap?>(
        initialValue = cachedClipboardBitmap(imageFile, preferThumbnail),
        requestKey
    ) {
        value = withContext(Dispatchers.IO) {
            decodeClipboardBitmap(imageFile, preferThumbnail)
        }
    }.value
}

@Composable
internal fun rememberClipboardBitmapLoadState(
    imageFile: File?,
    bitmapOverride: ImageBitmap?,
    preferThumbnail: Boolean = true
): ClipboardBitmapLoadState {
    bitmapOverride?.let { return ClipboardBitmapLoadState.Loaded(it) }
    if(imageFile == null) return ClipboardBitmapLoadState.Unavailable

    val cachedBitmap = cachedClipboardBitmap(imageFile, preferThumbnail)
    val source = clipboardBitmapSource(imageFile, preferThumbnail)
    val requestKey = source?.cacheKey
        ?: "${imageFile.absolutePath}|missing|thumbnail=$preferThumbnail"
    val initialState = when {
        cachedBitmap != null -> ClipboardBitmapLoadState.Loaded(cachedBitmap)
        source != null -> ClipboardBitmapLoadState.Loading
        else -> ClipboardBitmapLoadState.Unavailable
    }

    return produceState<ClipboardBitmapLoadState>(
        initialValue = initialState,
        requestKey
    ) {
        value = withContext(Dispatchers.IO) {
            decodeClipboardBitmap(imageFile, preferThumbnail)
        }?.let(ClipboardBitmapLoadState::Loaded)
            ?: ClipboardBitmapLoadState.Unavailable
    }.value
}

@Composable
internal fun rememberClipboardBitmaps(
    imageFiles: List<File>,
    bitmapOverrides: List<ImageBitmap>?,
    preferThumbnail: Boolean = true,
    maxCount: Int? = null
): List<ImageBitmap> {
    if(bitmapOverrides != null) return bitmapOverrides
    val requestKeys = clipboardBitmapRequestKeys(imageFiles, preferThumbnail, maxCount)

    return produceState<List<ImageBitmap>>(
        initialValue = cachedClipboardBitmaps(imageFiles, preferThumbnail, maxCount),
        requestKeys
    ) {
        value = withContext(Dispatchers.IO) {
            requestedClipboardBitmapFiles(imageFiles, maxCount)
                .mapNotNull { decodeClipboardBitmap(it, preferThumbnail) }
        }
    }.value
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipboardEntryView(
    modifier: Modifier,
    clipboardEntry: ClipboardEntry,
    onPaste: (ClipboardEntry) -> Unit,
    onRemove: (ClipboardEntry) -> Unit,
    onPin: (ClipboardEntry) -> Unit,
    onWrapAndPaste: (ClipboardEntry) -> Unit = {},
    onCopy: ((ClipboardEntry) -> Unit)? = null,
    onRetryPreview: ((ClipboardEntry) -> Unit)? = null,
    onLongClick: ((ClipboardEntry) -> Unit)? = null,
    showWrapAction: Boolean = clipboardEntry.text != null,
    showCopyAction: Boolean = false,
    showRetryPreviewAction: Boolean = false,
    retryPreviewActionEnabled: Boolean = true,
    showPinAction: Boolean = true,
    showRemoveAction: Boolean = true,
    showPreviewAction: Boolean = false,
    onPreview: ((ClipboardEntry) -> Unit)? = null,
    previewLoading: Boolean = false,
    embedDisplayMode: ClipboardEmbedDisplayMode = ClipboardEmbedDisplayMode.ShowEmbed,
    bitmapOverride: ImageBitmap? = null,
    bitmapOverrides: List<ImageBitmap>? = null,
    previewMediaTotalCount: Int? = null,
    selectionMode: Boolean = false,
    isSelected: Boolean = false
) {
    val context = LocalContext.current
    val showEmbed = embedDisplayMode != ClipboardEmbedDisplayMode.ShowRawClipboard
    val shouldBlurPreviewImage = embedDisplayMode == ClipboardEmbedDisplayMode.ShowEmbedBlurred
    val imageFiles = remember(clipboardEntry, embedDisplayMode) {
        when {
            clipboardEntry.text == null -> listOfNotNull(clipboardEntry.getFile(context))
            showEmbed -> clipboardEntry.getPreviewFiles(context)
            else -> emptyList()
        }
    }
    val previewBitmaps = rememberClipboardBitmaps(
        imageFiles = imageFiles,
        bitmapOverrides = bitmapOverrides ?: bitmapOverride?.let { listOf(it) }
    )

    val cardColor = if(clipboardEntry.pinned) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val borderColor = when {
        selectionMode && isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val contentColor = contentColorFor(cardColor)
    val containerColor = if(selectionMode && isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        cardColor
    }

    Surface(
        color = containerColor,
        border = BorderStroke(if(selectionMode && isSelected) 2.dp else 1.dp, borderColor),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .padding(2.dp)
            .combinedClickable(
                onClick = { onPaste(clipboardEntry) },
                onLongClick = { (onLongClick ?: onPin).invoke(clipboardEntry) }
            )
    ) {
        Column {
            ClipboardEntryActionRow(
                clipboardEntry = clipboardEntry,
                selectionMode = selectionMode,
                isSelected = isSelected,
                showPinAction = showPinAction,
                showPreviewAction = showPreviewAction,
                showRetryPreviewAction = showRetryPreviewAction,
                retryPreviewActionEnabled = retryPreviewActionEnabled,
                showCopyAction = showCopyAction,
                showWrapAction = showWrapAction,
                showRemoveAction = showRemoveAction,
                contentColor = contentColor,
                containerColor = containerColor,
                onPin = onPin,
                onPreview = onPreview,
                onRetryPreview = onRetryPreview,
                onCopy = onCopy,
                onWrapAndPaste = onWrapAndPaste,
                onRemove = onRemove
            )

            ClipboardEntryTextBlock(
                clipboardEntry = clipboardEntry,
                showEmbed = showEmbed,
                previewLoading = previewLoading,
            )

            ClipboardEntryLoadingState(
                visible = showEmbed && previewLoading && previewBitmaps.isEmpty() && clipboardEntry.text != null,
                containerColor = containerColor
            )

            if(previewBitmaps.isNotEmpty()) {
                ClipboardEntryPreviewMedia(
                    bitmaps = previewBitmaps,
                    totalMediaCount = when {
                        previewMediaTotalCount != null -> previewMediaTotalCount
                        bitmapOverrides != null -> bitmapOverrides.size
                        else -> imageFiles.size
                    },
                    containerColor = containerColor,
                    shouldBlurPreviewImage = shouldBlurPreviewImage && clipboardEntry.text != null
                )
            }

            ClipboardEntryOriginalLink(
                clipboardEntry = clipboardEntry,
                showEmbed = showEmbed,
                previewLoading = previewLoading
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ClipboardEntryActionRow(
    clipboardEntry: ClipboardEntry,
    selectionMode: Boolean,
    isSelected: Boolean,
    showPinAction: Boolean,
    showPreviewAction: Boolean,
    showRetryPreviewAction: Boolean,
    retryPreviewActionEnabled: Boolean,
    showCopyAction: Boolean,
    showWrapAction: Boolean,
    showRemoveAction: Boolean,
    contentColor: androidx.compose.ui.graphics.Color,
    containerColor: androidx.compose.ui.graphics.Color,
    onPin: (ClipboardEntry) -> Unit,
    onPreview: ((ClipboardEntry) -> Unit)?,
    onRetryPreview: ((ClipboardEntry) -> Unit)?,
    onCopy: ((ClipboardEntry) -> Unit)?,
    onWrapAndPaste: (ClipboardEntry) -> Unit,
    onRemove: (ClipboardEntry) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if(selectionMode) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if(clipboardEntry.pinned) {
                    Icon(
                        painter = painterResource(id = R.drawable.push_pin),
                        contentDescription = null,
                        tint = contentColor.copy(alpha = 0.75f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        } else if(showPinAction) {
            IconButton(
                onClick = { onPin(clipboardEntry) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.push_pin),
                    contentDescription = if(clipboardEntry.pinned) {
                        stringResource(R.string.action_clipboard_manager_unpin_item)
                    } else {
                        stringResource(R.string.action_clipboard_manager_pin_item)
                    },
                    tint = if(clipboardEntry.pinned) {
                        contentColor
                    } else {
                        contentColor.copy(alpha = 0.5f)
                    },
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(if(clipboardEntry.pinned) 0f else 45f)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        if(selectionMode) {
            ClipboardEntrySelectionIndicator(isSelected = isSelected)
        } else {
            ClipboardEntryOptionalAction(
                visible = showPreviewAction && onPreview != null,
                icon = R.drawable.image,
                contentDescription = R.string.clipboard_history_preview_title,
                tint = contentColor
            ) {
                onPreview?.invoke(clipboardEntry)
            }
            ClipboardEntryOptionalAction(
                visible = showRetryPreviewAction && onRetryPreview != null,
                enabled = retryPreviewActionEnabled,
                icon = R.drawable.refresh_cw,
                contentDescription = R.string.action_clipboard_manager_retry_preview,
                tint = contentColor
            ) {
                onRetryPreview?.invoke(clipboardEntry)
            }
            ClipboardEntryOptionalAction(
                visible = showCopyAction && onCopy != null,
                icon = R.drawable.copy,
                contentDescription = R.string.clipboard_history_copy_text,
                tint = contentColor
            ) {
                onCopy?.invoke(clipboardEntry)
            }
            ClipboardEntryOptionalAction(
                visible = showWrapAction && clipboardEntry.text != null,
                icon = R.drawable.wrap_selection,
                contentDescription = R.string.action_wrap_selection_title,
                tint = contentColor
            ) {
                onWrapAndPaste(clipboardEntry)
            }

            if(showRemoveAction) {
                IconButton(
                    onClick = { onRemove(clipboardEntry) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.close),
                        contentDescription = stringResource(R.string.action_clipboard_manager_remove_item),
                        tint = contentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipboardEntrySelectionIndicator(isSelected: Boolean) {
    Surface(
        modifier = Modifier.size(28.dp),
        shape = CircleShape,
        color = if(isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f)
        },
        border = if(isSelected) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            if(isSelected) {
                Icon(
                    painter = painterResource(R.drawable.check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun ClipboardEntryOptionalAction(
    visible: Boolean,
    enabled: Boolean = true,
    icon: Int,
    contentDescription: Int,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    if(!visible) return

    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(contentDescription),
            tint = if(enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun ClipboardEntryTextBlock(
    clipboardEntry: ClipboardEntry,
    showEmbed: Boolean,
    previewLoading: Boolean
) {
    val text = if(clipboardEntry.text != null) {
        remember(
            clipboardEntry.previewText,
            clipboardEntry.previewMediaFiles,
            clipboardEntry.previewImageFile,
            clipboardEntry.text,
            previewLoading,
            showEmbed
        ) {
            when {
                !showEmbed -> sanitizeClipboardText(clipboardEntry.text, 160)
                previewLoading -> null
                clipboardEntry.previewText != null -> sanitizeClipboardText(clipboardEntry.previewText, 160)
                clipboardEntry.previewMedia().isNotEmpty() -> null
                else -> sanitizeClipboardText(clipboardEntry.text, 160)
            }
        }
    } else {
        null
    }

    if(text != null) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp),
            style = Typography.SmallMl
        )
    }
}

@Composable
private fun ClipboardEntryLoadingState(
    visible: Boolean,
    containerColor: androidx.compose.ui.graphics.Color
) {
    if(!visible) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
            Text(
                text = stringResource(R.string.action_clipboard_manager_loading_preview),
                style = Typography.Small,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ClipboardEntryPreviewMedia(
    bitmaps: List<ImageBitmap>,
    totalMediaCount: Int,
    containerColor: androidx.compose.ui.graphics.Color,
    shouldBlurPreviewImage: Boolean
) {
    if(bitmaps.size == 1 && totalMediaCount <= 1) {
        ClipboardEntryPreviewImage(
            bitmap = bitmaps.first(),
            containerColor = containerColor,
            shouldBlurPreviewImage = shouldBlurPreviewImage
        )
    } else {
        ClipboardEntryPreviewCollage(
            bitmaps = bitmaps,
            totalMediaCount = totalMediaCount.coerceAtLeast(bitmaps.size),
            containerColor = containerColor,
            shouldBlurPreviewImage = shouldBlurPreviewImage
        )
    }
}

@Composable
private fun ClipboardEntryPreviewImage(
    bitmap: ImageBitmap,
    containerColor: androidx.compose.ui.graphics.Color,
    shouldBlurPreviewImage: Boolean
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor)
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(6.dp))
    ) {
        val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val naturalHeight = maxWidth / aspectRatio
        val useFit = naturalHeight <= 220.dp
        val imageModifier = if(shouldBlurPreviewImage) {
            Modifier.blur(24.dp)
        } else {
            Modifier
        }

        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = if(useFit) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if(useFit) {
                        Modifier.aspectRatio(aspectRatio)
                    } else {
                        Modifier.height(220.dp)
                    }
                )
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f))
                .then(imageModifier)
        )
    }
}

@Composable
private fun ClipboardEntryPreviewCollage(
    bitmaps: List<ImageBitmap>,
    totalMediaCount: Int,
    containerColor: androidx.compose.ui.graphics.Color,
    shouldBlurPreviewImage: Boolean
) {
    val visibleBitmaps = bitmaps.take(4)
    val imageModifier = if(shouldBlurPreviewImage) Modifier.blur(24.dp) else Modifier

    ClipboardPreviewMediaCollage(
        itemCount = visibleBitmaps.size,
        totalMediaCount = totalMediaCount,
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor)
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(6.dp)),
        overflowTextStyle = Typography.SmallMl
    ) { index ->
        Image(
            bitmap = visibleBitmaps[index],
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(imageModifier)
        )
    }
}

@Composable
private fun ClipboardEntryOriginalLink(
    clipboardEntry: ClipboardEntry,
    showEmbed: Boolean,
    previewLoading: Boolean
) {
    if(!showEmbed || clipboardEntry.text == null) return
    if(clipboardEntry.previewText == null && clipboardEntry.previewMedia().isEmpty() && !previewLoading) return

    val originalLink = remember(clipboardEntry.text) {
        wrapDisplayTextAnywhere(sanitizeClipboardText(clipboardEntry.text, 160))
    }
    Text(
        text = originalLink,
        modifier = Modifier.padding(8.dp, 6.dp, 8.dp, 0.dp),
        style = Typography.Smallest,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Preview(showBackground = true)
@Composable
fun ClipboardEntryViewPreview() {
    val sampleText = listOf(
        "This is an entry",
        "Copying text a lot",
        "hunter2",
        "https://www.example.com/forum/viewpost/1234573193.html?parameter=1234"
    )
    val twitterPreviewEntries = listOf(
        ClipboardEntry(
            timestamp = 0L,
            pinned = true,
            text = "https://x.com/futo/status/1912345678901234567",
            uri = null,
            mimeTypes = listOf(),
            previewText = "Shipping this keyboard-side preview path was the easy part. The harder part is keeping it robust when copied links come in messy real-world forms."
        ),
        ClipboardEntry(
            timestamp = 0L,
            pinned = false,
            text = "https://fxtwitter.com/futo/status/1912345678901234567",
            uri = null,
            mimeTypes = listOf(),
            previewText = "A tweet with photos should show the snippet first and the image below it.",
            previewMediaFiles = listOf(ClipboardPreviewMedia("[preview]"))
        ),
        ClipboardEntry(
            timestamp = 0L,
            pinned = false,
            text = "https://x.com/futo/status/1912345678901234568",
            uri = null,
            mimeTypes = listOf(),
            previewText = "Two images should read as a paired preview instead of a tiny strip.",
            previewMediaFiles = List(2) { ClipboardPreviewMedia("[preview-$it]", sourceIndex = it) }
        ),
        ClipboardEntry(
            timestamp = 0L,
            pinned = false,
            text = "https://x.com/futo/status/1912345678901234569",
            uri = null,
            mimeTypes = listOf(),
            previewText = "Three images should use one lead image with two supporting thumbnails.",
            previewMediaFiles = List(3) { ClipboardPreviewMedia("[preview-$it]", sourceIndex = it) }
        ),
        ClipboardEntry(
            timestamp = 0L,
            pinned = false,
            text = "https://fixupx.com/futo/status/1912345678901234567",
            uri = null,
            mimeTypes = listOf(),
            previewMediaFiles = listOf(ClipboardPreviewMedia("[preview]"))
        ),
        ClipboardEntry(
            timestamp = 0L,
            pinned = false,
            text = "https://www.pixiv.net/en/artworks/12345678",
            uri = null,
            mimeTypes = listOf(),
            previewMediaFiles = List(6) { ClipboardPreviewMedia("[preview-$it]", sourceIndex = it) },
            previewMetadata = ClipboardPreviewMetadata(
                provider = ClipboardPreviewProvider.PIXIV,
                title = "Multi-page Pixiv preview",
                imageCount = 6
            )
        )
    )

    val previewBitmaps = listOf(
        ClipboardUtil.generateTestPatternBitmap(),
        ClipboardUtil.generateCheckerboardBitmap(),
        ClipboardUtil.generateTestPatternBitmap(
            gradientStart = android.graphics.Color.BLUE,
            gradientEnd = android.graphics.Color.GREEN
        ),
        ClipboardUtil.generateCheckerboardBitmap(),
        ClipboardUtil.generateTestPatternBitmap(
            gradientStart = android.graphics.Color.RED,
            gradientEnd = android.graphics.Color.YELLOW
        ),
        ClipboardUtil.generateCheckerboardBitmap()
    )

    androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid(
        modifier = Modifier.fillMaxWidth(),
        columns = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells.Adaptive(160.dp),
        verticalItemSpacing = 4.dp,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            ClipboardEntryView(
                modifier = Modifier,
                clipboardEntry = ClipboardEntry(0L, false, null, null, listOf("image/png"), "[test]", 0.0f),
                onPin = {},
                onPaste = {},
                onRemove = {},
                bitmapOverride = ClipboardUtil.generateCheckerboardBitmap()
            )
        }
        items(sampleText.size) {
            ClipboardEntryView(
                modifier = Modifier,
                clipboardEntry = ClipboardEntry(0L, it % 2 == 0, sampleText[it], null, listOf()),
                onPin = {},
                onPaste = {},
                onRemove = {},
                onWrapAndPaste = {}
            )
        }
        items(twitterPreviewEntries.size) {
            ClipboardEntryView(
                modifier = Modifier,
                clipboardEntry = twitterPreviewEntries[it],
                onPin = {},
                onPaste = {},
                onRemove = {},
                onWrapAndPaste = {},
                bitmapOverrides = when (it) {
                    1 -> previewBitmaps.take(1)
                    2 -> previewBitmaps.take(2)
                    3 -> previewBitmaps.take(3)
                    4 -> previewBitmaps.take(1)
                    5 -> previewBitmaps
                    else -> null
                }
            )
        }
        item {
            ClipboardEntryView(
                modifier = Modifier,
                clipboardEntry = twitterPreviewEntries.last(),
                onPin = {},
                onPaste = {},
                onRemove = {},
                onWrapAndPaste = {},
                embedDisplayMode = ClipboardEmbedDisplayMode.ShowEmbedBlurred,
                bitmapOverrides = previewBitmaps.take(4)
            )
        }
        item {
            ClipboardEntryView(
                modifier = Modifier,
                clipboardEntry = ClipboardEntry(0L, false, null, null, listOf("image/png"), "[test]", 0.0f),
                onPin = {},
                onPaste = {},
                onRemove = {},
                bitmapOverride = ClipboardUtil.generateTestPatternBitmap()
            )
        }
        item {
            ClipboardEntryView(
                modifier = Modifier,
                clipboardEntry = ClipboardEntry(0L, false, null, null, listOf("image/png"), "[test]", 0.0f),
                onPin = {},
                onPaste = {},
                onRemove = {},
                bitmapOverride = SuperheroRenderer.render(LocalContext.current, "my clipboard image")?.asImageBitmap()
            )
        }
        items(sampleText.size / 2) {
            ClipboardEntryView(
                modifier = Modifier,
                clipboardEntry = ClipboardEntry(0L, it % 2 == 0, sampleText[it], null, listOf()),
                onPin = {},
                onPaste = {},
                onRemove = {}
            )
        }
        item {
            ClipboardEntryView(
                modifier = Modifier,
                clipboardEntry = ClipboardEntry(0L, false, null, null, listOf("image/png"), "[test]", 0.0f),
                onPin = {},
                onPaste = {},
                onRemove = {},
                bitmapOverride = SuperheroRenderer.render(LocalContext.current, "hey")?.asImageBitmap()
            )
        }
        items(sampleText.size / 2) {
            ClipboardEntryView(
                modifier = Modifier,
                clipboardEntry = ClipboardEntry(0L, it % 2 == 0, sampleText[it], null, listOf()),
                onPin = {},
                onPaste = {},
                onRemove = {}
            )
        }
    }
}
