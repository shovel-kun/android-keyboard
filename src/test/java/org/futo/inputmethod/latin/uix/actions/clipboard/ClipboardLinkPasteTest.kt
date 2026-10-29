package org.futo.inputmethod.latin.uix.actions.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardLinkPasteTest {
    @Test
    fun splitsLinkOnlyClipsAndRejectsProse() {
        assertEquals(
            listOf("https://a.example/1", "||https://b.example/2||"),
            clipboardPasteLinks("  https://a.example/1 \n ||https://b.example/2||  ")
        )
        assertNull(clipboardPasteLinks("look at https://a.example/1"))
        assertNull(clipboardPasteLinks("   "))
    }

    @Test
    fun spoilerWrapsEachLinkLine() {
        assertEquals(
            "||https://a.example/1||\n||https://b.example/2||",
            spoilerWrapPasteLinks("https://a.example/1\n||https://b.example/2||")
        )
    }

    @Test
    fun startsNewLineOnlyAfterAnExistingLink() {
        val link = "https://b.example/2"
        assertEquals("\n$link", separatedLinkPaste("see https://a.example/1", link))
        assertEquals("\n||$link||", separatedLinkPaste("||https://a.example/1|| ", "||$link||"))
        assertEquals(link, separatedLinkPaste("see this: ", link))
        assertEquals(link, separatedLinkPaste("https://a.example/1\n", link))
        assertEquals(link, separatedLinkPaste(null, link))
        assertEquals("not a link", separatedLinkPaste("https://a.example/1", "not a link"))
    }

    @Test
    fun quickClipCleansSplitsAndSeparatesLinks() {
        assertEquals(
            "\nhttps://x.com/a/status/1\nhttps://example.com/",
            quickClipPasteText("https://x.com/a/status/1?s=20 https://example.com/?utm_source=x", true, "https://a.example/1")
        )
        assertEquals("https://example.com/?utm_source=x", quickClipPasteText("https://example.com/?utm_source=x", false, ""))
        assertEquals("call me https://example.com/", quickClipPasteText("call me https://example.com/?fbclid=x", true, "https://a.example/1"))
    }
}
