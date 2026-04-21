package org.futo.inputmethod.latin.uix.actions.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardBackupTest {
    @Test
    fun decodeClipboardEntries_ignoresUnknownFieldsFromNewerBackups() {
        val decoded = decodeClipboardEntries(
            """
            [
              {
                "timestamp": 123,
                "pinned": false,
                "text": "hello",
                "uri": null,
                "mimeTypes": ["text/plain"],
                "brandNewField": "future"
              }
            ]
            """.trimIndent()
        )

        assertEquals(1, decoded.size)
        assertEquals(123L, decoded.single().timestamp)
        assertEquals("hello", decoded.single().text)
        assertNull(decoded.single().uri)
    }
}
