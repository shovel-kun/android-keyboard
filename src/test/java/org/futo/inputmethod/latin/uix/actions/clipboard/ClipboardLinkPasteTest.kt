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
    fun formatterCleansSplitsAndRewritesLinks() {
        val formatter = ClipboardLinkPasteFormatter("www.phixiv.net", "fixupx.com", "fxmas.to", cleanLinks = true)
        assertEquals(
            "https://fixupx.com/a/status/1\nhttps://www.phixiv.net/en/artworks/39832455\nhttps://example.com/",
            formatter.textForPaste("https://x.com/a/status/1?s=20 https://www.pixiv.net/artworks/39832455 https://example.com/?utm_source=x")
        )
        assertEquals(
            "||https://fixupx.com/a/status/1||\n||https://example.com/||",
            formatter.wrappedTextForPaste("https://x.com/a/status/1?t=abc\nhttps://example.com/?fbclid=x")
        )
        assertEquals("call me https://example.com/", formatter.textForPaste("call me https://example.com/?fbclid=x"))
        assertEquals(
            "https://example.com/?utm_source=x",
            ClipboardLinkPasteFormatter("www.phixiv.net", "fixupx.com", "fxmas.to", cleanLinks = false)
                .textForPaste("https://example.com/?utm_source=x")
        )
    }
}
