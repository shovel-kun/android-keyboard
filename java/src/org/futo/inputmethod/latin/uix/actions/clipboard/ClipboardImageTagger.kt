package org.futo.inputmethod.latin.uix.actions.clipboard

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal const val ClipboardImageTagModelRevision =
    "wd-convnext-tagger-v3-mobile-e504d4ed-policy1"

private const val ModelAssetPath = "image-tagger/model.quant.preproc.onnx"
private const val LabelsAssetPath = "image-tagger/selected_tags.csv"
private const val ModelOutputCount = 10_861
private const val GeneralThreshold = 0.35f
private const val CharacterThreshold = 0.80f
private const val MaxGeneralTags = 64
private const val MaxCharacterTags = 16

internal data class ClipboardImageTagLabel(
    val name: String,
    val category: Int
)

internal fun parseClipboardImageTagLabels(csv: String): List<ClipboardImageTagLabel> =
    csv.lineSequence()
        .drop(1)
        .filter { it.isNotBlank() }
        .map { line ->
            val columns = line.split(',')
            ClipboardImageTagLabel(
                name = columns[1],
                category = columns[2].toInt()
            )
        }
        .toList()

internal fun selectClipboardImageTags(
    labels: List<ClipboardImageTagLabel>,
    probabilities: FloatArray
): List<ClipboardImageTag> {
    require(labels.size == probabilities.size)

    val candidates = labels.mapIndexedNotNull { index, label ->
        val probability = probabilities[index]
        val category = when(label.category) {
            0 -> ClipboardImageTagCategory.General
            4 -> ClipboardImageTagCategory.Character
            else -> return@mapIndexedNotNull null
        }
        val threshold = when(category) {
            ClipboardImageTagCategory.General -> GeneralThreshold
            ClipboardImageTagCategory.Character -> CharacterThreshold
        }
        if(probability < threshold) return@mapIndexedNotNull null
        ClipboardImageTag(label.name, probability, category)
    }

    val general = candidates
        .filter { it.category == ClipboardImageTagCategory.General }
        .sortedWith(compareByDescending<ClipboardImageTag> { it.probability }.thenBy { it.name })
        .take(MaxGeneralTags)
    val characters = candidates
        .filter { it.category == ClipboardImageTagCategory.Character }
        .sortedWith(compareByDescending<ClipboardImageTag> { it.probability }.thenBy { it.name })
        .take(MaxCharacterTags)
    return (general + characters)
        .sortedWith(compareByDescending<ClipboardImageTag> { it.probability }.thenBy { it.name })
}

internal interface ClipboardImageTagger : AutoCloseable {
    fun tag(file: File, attemptedAtEpochMs: Long): ClipboardImageTaggingResult
}

internal class OnnxClipboardImageTagger(context: Context) : ClipboardImageTagger {
    private val environment = OrtEnvironment.getEnvironment()
    private val labels = context.assets.open(LabelsAssetPath).bufferedReader().use { reader ->
        parseClipboardImageTagLabels(reader.readText())
    }.also { require(it.size == ModelOutputCount) }
    private val session = createSession(context)

    override fun tag(file: File, attemptedAtEpochMs: Long): ClipboardImageTaggingResult =
        try {
            val bytes = file.readBytes()
            val inputBuffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }
            val probabilities = OnnxTensor.createTensor(
                environment,
                inputBuffer,
                longArrayOf(bytes.size.toLong()),
                OnnxJavaType.UINT8
            ).use { input ->
                session.run(mapOf("image" to input)).use { result ->
                    val output = result[0] as OnnxTensor
                    val outputBuffer = output.floatBuffer
                    FloatArray(outputBuffer.remaining()).also(outputBuffer::get)
                }
            }
            require(probabilities.size == labels.size)
            ClipboardImageTaggingResult(
                modelRevision = ClipboardImageTagModelRevision,
                attemptedAtEpochMs = attemptedAtEpochMs,
                tags = selectClipboardImageTags(labels, probabilities)
            )
        } catch(e: Exception) {
            Log.w("ClipboardImageTagger", "Image tagging failed", e)
            ClipboardImageTaggingResult(
                modelRevision = ClipboardImageTagModelRevision,
                attemptedAtEpochMs = attemptedAtEpochMs,
                failure = ClipboardImageTaggingFailure.InferenceFailed
            )
        }

    override fun close() {
        session.close()
    }

    private fun createSession(context: Context): OrtSession {
        val descriptor = context.assets.openFd(ModelAssetPath)
        val startOffset = descriptor.startOffset
        val declaredLength = descriptor.declaredLength
        val modelBuffer = descriptor.createInputStream().use { stream ->
            stream.channel.use { channel ->
                channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    startOffset,
                    declaredLength
                )
            }
        }
        return OrtSession.SessionOptions().use { options ->
            options.registerCustomOpLibrary(OrtxPackage.getLibraryPath())
            environment.createSession(modelBuffer, options)
        }
    }
}
