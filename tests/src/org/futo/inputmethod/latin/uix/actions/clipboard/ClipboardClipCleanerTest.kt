package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.PersistableBundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.URLSpan
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.futo.inputmethod.latin.uix.DataStoreHelper
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardClipCleanerTest {
    @Test
    fun listenerCleansSystemClipboardIndependentlyOfHistoryAndHonorsToggle() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Normally initialized by LatinIME.onCreate before the clipboard manager.
        DataStoreHelper.init(context)
        val settings = listOf(ClipboardCleanLinks, ClipboardHistoryEnabled, ClipboardIncognitoMode)
        val originalSettings = settings.associateWith { context.getSettingBlocking(it) }
        ActivityScenario.launch(SettingsActivity::class.java).use {
            val manager = withContext(Dispatchers.Main) { ClipboardHistoryManager.getInstance(context) }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            suspend fun awaitCondition(message: String, condition: () -> Boolean) {
                val completed = withTimeoutOrNull(10_000) {
                    while(!withContext(Dispatchers.Main) { condition() }) delay(20)
                    true
                }
                assertTrue(message, completed == true)
            }
            try {
                awaitCondition("Clipboard manager loaded") { manager.clipboardLoaded }
                val initialSize = withContext(Dispatchers.Main) { manager.clipboardHistory.size }
                // Both history-off and incognito still clean the system clip without saving it.
                for(historyEnabled in listOf(false, true)) {
                    context.setSetting(ClipboardCleanLinks, true)
                    context.setSetting(ClipboardHistoryEnabled, historyEnabled)
                    context.setSetting(ClipboardIncognitoMode, historyEnabled)
                    awaitCondition("Settings applied for history=$historyEnabled") {
                        context.getSettingBlocking(ClipboardCleanLinks) &&
                            context.getSettingBlocking(ClipboardHistoryEnabled) == historyEnabled &&
                            context.getSettingBlocking(ClipboardIncognitoMode) == historyEnabled
                    }
                    val expected = "https://example.com/?history=$historyEnabled"
                    withContext(Dispatchers.Main) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("test", "$expected&utm_source=test"))
                    }
                    awaitCondition("System clipboard cleaned for history=$historyEnabled") {
                        clipboard.primaryClip?.getItemAt(0)?.text?.toString() == expected
                    }
                    instrumentation.waitForIdleSync()
                    assertEquals(initialSize, withContext(Dispatchers.Main) { manager.clipboardHistory.size })
                }

                context.setSetting(ClipboardCleanLinks, false)
                awaitCondition("Cleaning disabled") { !context.getSettingBlocking(ClipboardCleanLinks) }
                val original = "https://example.com/?utm_source=disabled"
                withContext(Dispatchers.Main) { clipboard.setPrimaryClip(ClipData.newPlainText("test", original)) }
                instrumentation.waitForIdleSync()
                assertEquals(original, clipboard.primaryClip!!.getItemAt(0).text.toString())

                context.setSetting(ClipboardCleanLinks, true)
                context.setSetting(ClipboardIncognitoMode, false)
                awaitCondition("Cleaning and history enabled") {
                    context.getSettingBlocking(ClipboardCleanLinks) && !context.getSettingBlocking(ClipboardIncognitoMode)
                }
                val saved = "https://example.com/?id=cleaned-history"
                withContext(Dispatchers.Main) { clipboard.setPrimaryClip(ClipData.newPlainText("test", "$saved&utm_source=test")) }
                awaitCondition("Cleaned link saved to history") { manager.clipboardHistory.any { it.text == saved } }
                assertEquals(saved, clipboard.primaryClip!!.getItemAt(0).text.toString())
                assertFalse(withContext(Dispatchers.Main) { manager.clipboardHistory.any { it.text == "$saved&utm_source=test" } })
            } finally {
                originalSettings.forEach { (setting, value) -> context.setSetting(setting, value) }
            }
        }
    }

    @Test
    fun preservesDescriptionExtrasAndEveryItemWhileCleaningTextHtmlAndUris() {
        val clip = ClipData.newHtmlText(
            "label", "https://example.com/?utm_source=x&id=42",
            "<b><a href=\"https://example.com/?utm_source=x&amp;id=42\">Link</a></b>"
        )
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            putString("app.extra", "keep")
        }
        clip.addItem(ClipData.Item(Uri.parse("content://example.com/image/1")))
        clip.addItem(ClipData.Item(Uri.parse("https://youtu.be/abc?si=x&t=30")))

        val cleaned = cleanClipboardClip(clip)!!
        assertEquals("label", cleaned.description.label)
        assertEquals(clip.description.mimeTypeCount, cleaned.description.mimeTypeCount)
        assertTrue(cleaned.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        assertEquals("keep", cleaned.description.extras!!.getString("app.extra"))
        assertEquals(3, cleaned.itemCount)
        assertEquals("https://example.com/?id=42", cleaned.getItemAt(0).text.toString())
        assertEquals("<b><a href=\"https://example.com/?id=42\">Link</a></b>", cleaned.getItemAt(0).htmlText)
        assertEquals(clip.getItemAt(1).uri, cleaned.getItemAt(1).uri)
        assertEquals("https://youtu.be/abc?t=30", cleaned.getItemAt(2).uri.toString())
        assertNull(cleanClipboardClip(cleaned))
    }

    @Test
    fun retainsStylesAndCleansHiddenUrlSpans() {
        val text = SpannableString("Read this link")
        text.setSpan(URLSpan("https://example.com/?utm_source=x"), 5, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val cleaned = cleanClipboardClip(ClipData.newPlainText("label", text))!!
        val result = cleaned.getItemAt(0).text as Spanned
        val span = result.getSpans(0, result.length, URLSpan::class.java).single()
        assertEquals("https://example.com/", span.url)
        assertEquals(5, result.getSpanStart(span))
        assertEquals(text.length, result.getSpanEnd(span))
        assertEquals(Typeface.BOLD, result.getSpans(0, result.length, StyleSpan::class.java).single().style)
    }

    @Test
    fun retainsExclusiveSpansCoveringAWholeVisibleUrl() {
        val text = SpannableString("https://example.com/?utm_source=x")
        text.setSpan(URLSpan(text.toString()), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val result = cleanClipboardClip(ClipData.newPlainText("label", text))!!.getItemAt(0).text as Spanned
        assertEquals("https://example.com/", result.toString())
        val link = result.getSpans(0, result.length, URLSpan::class.java).single()
        assertEquals(result.toString(), link.url)
        assertEquals(result.length, result.getSpanEnd(link))
        assertEquals(Typeface.BOLD, result.getSpans(0, result.length, StyleSpan::class.java).single().style)
    }

    @Test
    fun refusesToOverwriteDifferentOrClearedClipboardContents() {
        val original = ClipData.newPlainText("label", "https://example.com/?utm_source=x")
        assertTrue(sameClipboardContents(original, ClipData(original)))
        assertFalse(sameClipboardContents(original, ClipData.newPlainText("label", "new copy")))
        assertFalse(sameClipboardContents(original, null))
        val sensitive = ClipData.newPlainText("label", original.getItemAt(0).text)
        sensitive.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        assertFalse(sameClipboardContents(original, sensitive))
    }
}
