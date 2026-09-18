package org.futo.inputmethod.latin.uix.actions.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ClipboardThumbnailTest {
    @Test
    fun videoPreviewUsesProviderThumbnailWhenLocalExtractionFails() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "thumbnail-fallback-test-${System.nanoTime()}")
        directory.mkdirs()
        try {
            val video = File(directory, "unsupported.mp4").apply { writeText("unsupported container") }
            var recoveries = 0
            val loaded = loadClipboardBitmap(video) { file ->
                recoveries++
                val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
                try {
                    ClipboardUtil.thumbnailFor(file).outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            assertNotNull(loaded)
            assertEquals(1, recoveries)
            assertNotNull(loadClipboardBitmap(video) { recoveries++ })
            assertEquals("A cached preview must not fetch again", 1, recoveries)

            val unavailable = File(directory, "unavailable.mp4").apply { writeText("unsupported container") }
            assertNull(loadClipboardBitmap(unavailable) { })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun videoPreviewRecoversMissingAndUnreadableThumbnails() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val resource = context.resources.getIdentifier(
            "clipboard_thumbnail_video", "raw", context.packageName
        )
        val directory = File(instrumentation.targetContext.cacheDir, "thumbnail-test-${System.nanoTime()}")
        directory.mkdirs()
        try {
            for(contents in listOf(null, byteArrayOf(), "broken jpeg".toByteArray())) {
                val video = File(directory, "video-${contents?.size}.mp4")
                context.resources.openRawResource(resource).use { input ->
                    video.outputStream().use { input.copyTo(it) }
                }
                val thumbnail = ClipboardUtil.thumbnailFor(video)
                contents?.let { thumbnail.writeBytes(it) }

                assertNotNull("Preview for thumbnail bytes=${contents?.size}", loadClipboardBitmap(video) {
                    throw AssertionError("Locally decodable video must not need a provider thumbnail")
                })
                val bitmap = BitmapFactory.decodeFile(thumbnail.absolutePath)
                assertNotNull("Repaired thumbnail must be saved", bitmap)
                bitmap?.recycle()
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
