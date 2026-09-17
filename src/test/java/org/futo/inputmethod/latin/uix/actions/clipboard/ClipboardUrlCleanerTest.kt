package org.futo.inputmethod.latin.uix.actions.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardUrlCleanerTest {
    @Test
    fun removesKnownTrackersAndPreservesRawFunctionalParameters() {
        assertEquals(
            "https://example.com/a%2fb?q=a+b&q=%2f&empty=&flag#section",
            cleanClipboardUrl("https://example.com/a%2fb?utm_source=mail&q=a+b&q=%2f&fbclid=x&empty=&flag#section")
        )
        assertEquals("https://example.com/", cleanClipboardUrl("https://example.com/?utm_source=x&gclid=y"))
        assertEquals("https://example.com/?id=42", cleanClipboardUrl("https://example.com/?%75tm_source=x&id=42&UTM_MEDIUM=email"))
    }

    @Test
    fun cleansMultipleLinksWithoutChangingProseOrMarkdown() {
        assertEquals(
            "See [this](https://example.com/a_(b)) and https://example.org/?id=42.\nDone!",
            cleanClipboardUrls("See [this](https://example.com/a_(b)?utm_source=x) and https://example.org/?id=42&fbclid=x.\nDone!")
        )
    }

    @Test
    fun siteSpecificRulesKeepFunctionalParametersAndRespectDomainBoundaries() {
        val cases = mapOf(
            "https://youtu.be/abc?si=tracking&t=30" to "https://youtu.be/abc?t=30",
            "https://www.youtube.com/watch?v=abc&si=tracking&list=xyz&t=30" to "https://www.youtube.com/watch?v=abc&list=xyz&t=30",
            "https://open.spotify.com/track/123?si=x" to "https://open.spotify.com/track/123",
            "https://www.instagram.com/p/123/?igsh=abc" to "https://www.instagram.com/p/123/",
            "https://www.reddit.com/r/a/comments/123/title/?share_id=x&sort=new" to "https://www.reddit.com/r/a/comments/123/title/?sort=new",
            "https://x.com/alice/status/123?s=20&t=abc" to "https://x.com/alice/status/123",
            "https://x.com/search?q=abc&t=video" to "https://x.com/search?q=abc&t=video",
            "https://notyoutube.com/?si=keep" to "https://notyoutube.com/?si=keep",
            "https://youtube.com.example.org/?si=keep" to "https://youtube.com.example.org/?si=keep"
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, cleanClipboardUrl(input)) }
    }

    @Test
    fun leavesUnknownSignedMalformedAndNonWebLinksUntouched() {
        listOf(
            "https://example.com/?ref=keep&source=keep&si=keep&t=30#utm_source=fragment",
            "https://example.com/?utm_source=x&X-Amz-Signature=signature",
            "https://example.com/?token=secret&utm_source=x",
            "https://example.com/?sig=secret&utm_source=x",
            "https://example.com/?q=%zz&utm_source=x",
            "https:///?utm_source=x",
            "mailto:a@example.com?utm_source=x",
            "content://example.com/?utm_source=x",
            "just some text"
        ).forEach { assertEquals(it, it, cleanClipboardUrl(it)) }
    }

    @Test
    fun htmlLinksKeepEscapingFormattingAndFunctionalParameters() {
        assertEquals(
            "<a href=\"https://example.com/?id=42&amp;q=a%26b\"><b>Read</b></a>",
            cleanClipboardUrls("<a href=\"https://example.com/?utm_source=x&amp;id=42&#38;q=a%26b\"><b>Read</b></a>", html = true)
        )
    }

    @Test
    fun cleaningIsIdempotent() {
        val cleaned = cleanClipboardUrls("https://youtu.be/abc?si=x&t=30 https://example.com/?utm_source=x#section")
        assertEquals(cleaned, cleanClipboardUrls(cleaned))
    }
}
