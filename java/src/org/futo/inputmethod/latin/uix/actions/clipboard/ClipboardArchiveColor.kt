package org.futo.inputmethod.latin.uix.actions.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.max
import kotlin.math.min

private const val ClipboardArchiveColorSampleMaxSide = 64
private const val ClipboardArchiveColorMinimumFraction = 0.05f

internal fun detectClipboardArchiveColors(files: List<File>): Set<ClipboardArchiveColorFilter> = buildSet {
    files.forEach { file ->
        val bitmap = decodeClipboardArchiveColorSample(file) ?: return@forEach
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            addAll(prominentClipboardArchiveColors(pixels))
        } finally {
            bitmap.recycle()
        }
    }
}

private fun decodeClipboardArchiveColorSample(mediaFile: File): Bitmap? {
    val thumbnail = ClipboardUtil.thumbnailFor(mediaFile)
    val source = when {
        thumbnail.isFile -> thumbnail
        mediaFile.isClipboardVideoFile() -> ClipboardUtil.generateThumbnail(mediaFile)
        mediaFile.isFile -> mediaFile
        else -> null
    } ?: return null

    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, options)
    if(options.outWidth <= 0 || options.outHeight <= 0) return null

    var sampleSize = 1
    while(max(options.outWidth, options.outHeight) / sampleSize > ClipboardArchiveColorSampleMaxSide) {
        sampleSize *= 2
    }

    return BitmapFactory.decodeFile(
        source.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    )
}

internal fun prominentClipboardArchiveColors(
    pixels: IntArray,
    minimumFraction: Float = ClipboardArchiveColorMinimumFraction
): Set<ClipboardArchiveColorFilter> {
    val counts = mutableMapOf<ClipboardArchiveColorFilter, Int>()
    var opaquePixelCount = 0

    pixels.forEach { pixel ->
        if(pixel ushr 24 < 128) return@forEach
        opaquePixelCount += 1
        val color = clipboardArchiveColorForRgb(
            red = pixel shr 16 and 0xff,
            green = pixel shr 8 and 0xff,
            blue = pixel and 0xff
        )
        counts[color] = counts.getOrDefault(color, 0) + 1
    }

    if(opaquePixelCount == 0) return emptySet()
    val minimumCount = max(1, (opaquePixelCount * minimumFraction).toInt())
    return counts.filterValues { it >= minimumCount }.keys
}

private fun clipboardArchiveColorForRgb(
    red: Int,
    green: Int,
    blue: Int
): ClipboardArchiveColorFilter {
    val redFraction = red / 255f
    val greenFraction = green / 255f
    val blueFraction = blue / 255f
    val maximum = max(redFraction, max(greenFraction, blueFraction))
    val minimum = min(redFraction, min(greenFraction, blueFraction))
    val delta = maximum - minimum
    val saturation = if(maximum == 0f) 0f else delta / maximum

    if(maximum <= 0.18f) return ClipboardArchiveColorFilter.Black
    if(saturation <= 0.16f) {
        return if(maximum >= 0.84f) ClipboardArchiveColorFilter.White else ClipboardArchiveColorFilter.Gray
    }

    val hue = when(maximum) {
        redFraction -> 60f * (((greenFraction - blueFraction) / delta) % 6f)
        greenFraction -> 60f * (((blueFraction - redFraction) / delta) + 2f)
        else -> 60f * (((redFraction - greenFraction) / delta) + 4f)
    }.let { if(it < 0f) it + 360f else it }

    if(hue in 15f..<50f && maximum < 0.62f) return ClipboardArchiveColorFilter.Brown
    return when {
        hue < 15f || hue >= 345f -> ClipboardArchiveColorFilter.Red
        hue < 45f -> ClipboardArchiveColorFilter.Orange
        hue < 70f -> ClipboardArchiveColorFilter.Yellow
        hue < 165f -> ClipboardArchiveColorFilter.Green
        hue < 255f -> ClipboardArchiveColorFilter.Blue
        hue < 315f -> ClipboardArchiveColorFilter.Purple
        else -> ClipboardArchiveColorFilter.Pink
    }
}
