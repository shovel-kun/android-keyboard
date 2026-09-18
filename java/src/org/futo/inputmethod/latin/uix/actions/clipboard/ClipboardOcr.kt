package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.Context
import android.graphics.ImageDecoder
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.engine.OCREngine
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.serialization.Serializable
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

internal const val ClipboardOcrModelRevision = "pp-ocrv6-small-policy1"
private const val OcrMaxImageSide = 2560

@Serializable
data class ClipboardOcrInput(val sourceUrl: String, val fileName: String, val savedAt: Long?)

@Serializable
data class ClipboardOcrPoint(val x: Float, val y: Float)

@Serializable
data class ClipboardOcrRegion(
    val text: String,
    val confidence: Float,
    val points: List<ClipboardOcrPoint>
)

@Serializable
data class ClipboardOcrResult(
    val input: ClipboardOcrInput,
    val modelRevision: String,
    val attemptedAtEpochMs: Long,
    // Coordinates refer to this decoded, EXIF-oriented image, before detector resizing.
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val regions: List<ClipboardOcrRegion> = emptyList(),
    val failed: Boolean = false
) {
    val text: String get() = regions.joinToString("\n") { it.text }
}

internal fun ClipboardArchiveMedia.ocrInput(): ClipboardOcrInput? =
    fileName?.let { ClipboardOcrInput(sourceUrl, it, lastAttemptAtEpochMs) }

internal fun ClipboardArchiveMedia.canExtractText(): Boolean =
    status == ClipboardArchiveMediaStatus.Saved && fileName != null &&
        ClipboardMediaFilter.Images.matches(mimeType, fileName) &&
        !ClipboardMediaFilter.Gifs.matches(mimeType, fileName)

internal fun ClipboardLinkArchive.mediaNeedingOcr(): List<ClipboardArchiveMedia> =
    media.filter { item ->
        item.canExtractText() &&
            item.archiveMediaKey() !in deletedMediaKeys &&
            "${item.sourceIndex}:${item.sourceUrl}" !in deletedMediaKeys &&
            (item.ocr?.modelRevision != ClipboardOcrModelRevision || item.ocr.failed)
    }

internal fun String.normalizeOcrSearchText(): String =
    Normalizer.normalize(this, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()

internal interface ClipboardOcr : AutoCloseable {
    fun extract(file: File, input: ClipboardOcrInput, attemptedAt: Long): ClipboardOcrResult
}

internal class PaddleClipboardOcr(context: Context) : ClipboardOcr {
    private val engine: OCREngine

    init {
        check(OpenCVUtils.init(context)) { "OpenCV initialization failed" }
        engine = OCREngine(
            context, PaddleOCRConfig(detMaxSideLimit = OcrMaxImageSide, recScoreThresh = 0.5f),
            EngineConfig(numThreads = 2),
            detModelAsset = "image-ocr/det-inference.onnx",
            recModelAsset = "image-ocr/rec-inference.onnx",
            recConfigAsset = "image-ocr/rec-inference.yml"
        )
    }

    override fun extract(file: File, input: ClipboardOcrInput, attemptedAt: Long): ClipboardOcrResult {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1.0, OcrMaxImageSide.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1)
            )
        }
        try {
            return ClipboardOcrResult(
                input = input,
                modelRevision = ClipboardOcrModelRevision,
                attemptedAtEpochMs = attemptedAt,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                regions = engine.run(bitmap).results.filter { it.text.isNotBlank() }.map { result ->
                    ClipboardOcrRegion(
                        text = result.text,
                        confidence = result.confidence,
                        points = result.box.points.map { ClipboardOcrPoint(it.x, it.y) }
                    )
                }
            )
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() = engine.release()
}
