package org.futo.inputmethod.latin.uix.actions.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.media.MediaMetadataRetriever
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap
import kotlin.math.max
import kotlin.math.min

private const val ClipboardThumbnailVersion = "v2"
private const val ClipboardThumbnailMaxSidePx = 768
private const val ClipboardThumbnailJpegQuality = 90

object ClipboardUtil {
    fun thumbnailForName(name: String): String
            = "$name.thumb.$ClipboardThumbnailVersion.jpg"

    fun thumbnailFor(imageFile: File): File
            = File(imageFile.parent, thumbnailForName(imageFile.name))

    fun generateThumbnail(mediaFile: File, mimeType: String? = mediaFile.guessedClipboardMimeType()): File? {
        val thumbFile = thumbnailFor(mediaFile)
        if (thumbFile.exists()) return thumbFile

        return when {
            mimeType?.startsWith("video/") == true || mediaFile.isClipboardVideoFile() ->
                generateVideoThumbnail(mediaFile, thumbFile)
            mimeType == "image/gif" || mediaFile.isClipboardGifFile() ->
                generateGifThumbnail(mediaFile, thumbFile)
            else ->
                generateImageThumbnail(mediaFile, thumbFile)
        }
    }

    private fun writeSquareImageThumbnail(bitmap: Bitmap, thumbFile: File): File? {
        val cropSize = min(bitmap.width, bitmap.height)
        if (cropSize <= 0) return null

        val cropX = (bitmap.width - cropSize) / 2
        val cropY = (bitmap.height - cropSize) / 2
        var croppedBitmap: Bitmap? = null
        var finalBmp: Bitmap? = null

        try {
            croppedBitmap = if (cropSize == bitmap.width && cropSize == bitmap.height) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, cropX, cropY, cropSize, cropSize)
            }

            val maxSide = 384
            finalBmp = if (croppedBitmap.width != maxSide || croppedBitmap.height != maxSide) {
                croppedBitmap.scale(maxSide, maxSide)
            } else {
                croppedBitmap
            }

            FileOutputStream(thumbFile).use { out ->
                finalBmp.compress(Bitmap.CompressFormat.JPEG, ClipboardThumbnailJpegQuality, out)
            }

            return thumbFile
        } finally {
            if (finalBmp !== croppedBitmap && finalBmp !== bitmap) {
                finalBmp?.recycle()
            }
            if (croppedBitmap !== bitmap) {
                croppedBitmap?.recycle()
            }
        }
    }

    private fun generateImageThumbnail(mediaFile: File, thumbFile: File): File? {
        var bitmap: Bitmap? = null
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(mediaFile.absolutePath, options)

            val (w, h) = options.outWidth to options.outHeight
            if (w <= 0 || h <= 0) return null

            val cropSize = min(w, h)

            val maxSide = 384
            var sample = 1
            while (cropSize / sample > maxSide) sample *= 2

            options.inJustDecodeBounds = false
            options.inSampleSize = sample

            bitmap = BitmapFactory.decodeFile(mediaFile.absolutePath, options) ?: return null

            return writeSquareImageThumbnail(bitmap, thumbFile)
        } catch (e: Exception) {
            thumbFile.delete()
            return null
        } finally {
            bitmap?.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun generateGifThumbnail(mediaFile: File, thumbFile: File): File? {
        var bitmap: Bitmap? = null
        try {
            bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(mediaFile)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                android.graphics.Movie.decodeFile(mediaFile.absolutePath)?.let { movie ->
                    val width = movie.width().takeIf { it > 0 } ?: return null
                    val height = movie.height().takeIf { it > 0 } ?: return null
                    createBitmap(width, height).also {
                        movie.setTime(0)
                        movie.draw(Canvas(it), 0f, 0f)
                    }
                }
            } ?: return null

            return writeSquareImageThumbnail(bitmap, thumbFile)
        } catch (e: Exception) {
            thumbFile.delete()
            return null
        } finally {
            bitmap?.recycle()
        }
    }

    private fun generateVideoThumbnail(mediaFile: File, thumbFile: File): File? {
        var bitmap: Bitmap? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(mediaFile.absolutePath)
            bitmap = retriever.getFrameAtTime(-1) ?: retriever.frameAtTime ?: return null

            val maxSide = ClipboardThumbnailMaxSidePx
            val finalBmp = if (bitmap.width > maxSide || bitmap.height > maxSide) {
                val scale = maxSide.toFloat() / max(bitmap.width, bitmap.height)
                bitmap.scale(
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1)
                )
            } else {
                bitmap
            }

            FileOutputStream(thumbFile).use { out ->
                finalBmp.compress(Bitmap.CompressFormat.JPEG, ClipboardThumbnailJpegQuality, out)
            }

            return thumbFile
        } catch (e: Exception) {
            thumbFile.delete()
            return null
        } finally {
            runCatching { retriever.release() }
            bitmap?.recycle()
        }
    }


    fun generateCheckerboardBitmap(
        width: Int = 256,
        height: Int = 256,
        squares: Int = 8
    ): ImageBitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val squareSize = width / squares

        val lightPaint = Paint().apply { color = android.graphics.Color.LTGRAY }
        val darkPaint = Paint().apply { color = android.graphics.Color.DKGRAY }

        for (row in 0 until squares) {
            for (col in 0 until squares) {
                val paint = if ((row + col) % 2 == 0) lightPaint else darkPaint
                canvas.drawRect(
                    col * squareSize.toFloat(),
                    row * squareSize.toFloat(),
                    (col + 1) * squareSize.toFloat(),
                    (row + 1) * squareSize.toFloat(),
                    paint
                )
            }
        }

        return bitmap.asImageBitmap()
    }

    fun generateTestPatternBitmap(
        width: Int = 256,
        height: Int = 256,
        gridSize: Int = 64,
        gradientStart: Int = android.graphics.Color.MAGENTA,
        gradientEnd: Int = android.graphics.Color.CYAN,
        gridColor: Int = android.graphics.Color.WHITE
    ): ImageBitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)

        // 1. Gradient background
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                gradientStart, gradientEnd,
                Shader.TileMode.CLAMP
            )
            isAntiAlias = true
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 2. Grid lines
        val gridPaint = Paint().apply {
            color = gridColor
            alpha = 120 // Semi-transparent
            strokeWidth = 2f
            isAntiAlias = true
        }

        // Vertical lines
        for (x in 0..width step gridSize) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), gridPaint)
        }

        // Horizontal lines
        for (y in 0..height step gridSize) {
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), gridPaint)
        }

        // 3. Test pattern markers
        // Center crosshair
        val markerPaint = Paint().apply {
            color = android.graphics.Color.YELLOW
            strokeWidth = 4f
            alpha = 200
        }
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawLine(cx - 40, cy, cx + 40, cy, markerPaint)
        canvas.drawLine(cx, cy - 40, cx, cy + 40, markerPaint)

        // Corner markers
        val cornerSize = 40f
        canvas.drawLine(0f, 0f, cornerSize, 0f, markerPaint)
        canvas.drawLine(0f, 0f, 0f, cornerSize, markerPaint)
        canvas.drawLine(width.toFloat(), 0f, width - cornerSize, 0f, markerPaint)
        canvas.drawLine(width.toFloat(), 0f, width.toFloat(), cornerSize, markerPaint)

        // Border
        val borderPaint = Paint().apply {
            color = android.graphics.Color.BLACK
            strokeWidth = 8f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(4f, 4f, width - 4f, height - 4f, borderPaint)

        return bitmap.asImageBitmap()
    }
}
