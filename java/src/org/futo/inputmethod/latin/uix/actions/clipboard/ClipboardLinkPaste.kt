package org.futo.inputmethod.latin.uix.actions.clipboard

private val PasteLinkToken = Regex("""(?:\|\|)?https?://\S+?(?:\|\|)?""", RegexOption.IGNORE_CASE)
private val PasteWhitespace = Regex("""\s+""")

private fun String.isPasteLinkToken(): Boolean = PasteLinkToken.matches(this)

/** The links of a clip made only of whitespace-separated (optionally spoilered) links, or null. */
internal fun clipboardPasteLinks(text: String): List<String>? {
    val trimmed = text.trim()
    if(trimmed.isEmpty()) return null
    return trimmed.split(PasteWhitespace).takeIf { tokens -> tokens.all { it.isPasteLinkToken() } }
}

/** Spoiler-wraps each link of a link-only paste separately so every link keeps its own line. */
internal fun spoilerWrapPasteLinks(text: String): String =
    text.lines().joinToString("\n") { line ->
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> line
            trimmed.startsWith("||") && trimmed.endsWith("||") && trimmed.length > 4 -> line
            else -> "||$trimmed||"
        }
    }

/** Starts a link-only paste on a new line when the text before the cursor already ends in a link. */
internal fun separatedLinkPaste(textBeforeCursor: CharSequence?, pasteText: String): String {
    if(clipboardPasteLinks(pasteText) == null) return pasteText
    val previousToken = textBeforeCursor?.toString()
        ?.trimEnd(' ', '\t')
        ?.takeLastWhile { !it.isWhitespace() }
    if(previousToken.isNullOrEmpty() || !previousToken.isPasteLinkToken()) return pasteText
    return "\n" + pasteText.trimStart('\n')
}

/** Turns clipboard text into pasted text: tracking removed, links rewritten to the paste domains. */
internal class ClipboardLinkPasteFormatter(
    pixivDomain: String,
    xDomain: String,
    mastodonDomain: String,
    private val cleanLinks: Boolean
) {
    private val phixivSession = PhixivArtworkPasteSession(pixivDomain)
    private val xSession = XLinkPasteSession(xDomain)
    private val mastodonSession = MastodonLinkPasteSession(mastodonDomain)

    private fun linkText(link: String, imageCount: Int?): String = xSession.textForPaste(
        mastodonSession.textForPaste(phixivSession.textForPaste(link, imageCount))
    )

    /** [imageCount] is the clip's pixiv image count; it only applies when the clip is a single link. */
    fun textForPaste(text: String, imageCount: Int? = null): String {
        // Clips saved before a cleaning rule existed (or copied elsewhere) still carry tracking.
        val cleaned = if(cleanLinks) cleanClipboardUrls(text) else text
        val links = clipboardPasteLinks(cleaned)
        return when {
            links == null -> linkText(cleaned, imageCount)
            links.size == 1 -> linkText(links.single(), imageCount)
            else -> links.joinToString("\n") { linkText(it, imageCount = null) }
        }
    }

    fun wrappedTextForPaste(text: String, imageCount: Int? = null): String {
        val pasteText = textForPaste(text, imageCount)
        return if(clipboardPasteLinks(pasteText) != null) {
            spoilerWrapPasteLinks(pasteText)
        } else {
            xSession.wrappedTextForPaste(pasteText)
        }
    }
}
