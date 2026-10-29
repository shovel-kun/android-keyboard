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
