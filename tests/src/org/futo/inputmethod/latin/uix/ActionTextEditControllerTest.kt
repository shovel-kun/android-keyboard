package org.futo.inputmethod.latin.uix

import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionTextEditControllerTest {
    @Test
    fun completion_replacesOnlyTokenAndKeepsInputConnectionOnSearchEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, androidx.appcompat.R.style.Theme_AppCompat)
            val view = ActionEditText(context)
            view.setText("hello bl tag:solo")
            view.setSelection(6, 8)
            val connection = view.onCreateInputConnection(EditorInfo())!!
            val controller = ActionTextEditController()
            controller.attach(view)
            connection.setComposingRegion(6, 8)
            var reported = ""
            view.setTextChangeCallback { reported = it }

            controller.replace("hello bl tag:solo", 6, 9, "tag:blue_hair ")

            assertEquals("hello tag:blue_hair tag:solo", view.text.toString())
            assertEquals(view.text.toString(), reported)
            assertEquals(20, controller.selectionStart)
            assertEquals(controller.selectionStart, controller.selectionEnd)
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(view.editableText))
            connection.commitText("new ", 1)
            assertEquals("hello tag:blue_hair new tag:solo", view.text.toString())
            controller.replace("outdated query", 0, 99, "tag:hat ")
            assertEquals("hello tag:blue_hair new tag:solo", view.text.toString())
            assertEquals(1, controller.completionVersion)
            controller.detach()
        }
    }

    @Test
    fun back_dismissesSuggestionsBeforeNormalEditorHandlingAndDetachClearsCallbacks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, androidx.appcompat.R.style.Theme_AppCompat)
            val view = ActionEditText(context)
            val controller = ActionTextEditController()
            controller.attach(view)
            var dismissed = false
            controller.onKey = { key ->
                if(key == KeyEvent.KEYCODE_BACK && !dismissed) { dismissed = true; true } else false
            }
            assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
            assertTrue(view.onKeyPreIme(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
            assertTrue(dismissed)
            controller.detach()
            assertNull(view.completionKey)
            assertNull(view.selectionChanged)
        }
    }
}
