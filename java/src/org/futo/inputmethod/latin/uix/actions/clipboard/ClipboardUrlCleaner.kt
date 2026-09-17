package org.futo.inputmethod.latin.uix.actions.clipboard

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Locale

// Deliberately limited to known tracking fields. Unknown fields (including ref, q,
// v, t and list) can affect the destination and must survive unless scoped below.
private val trackingParameters = setOf(
    "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid",
    "srsltid", "mc_cid", "mc_eid", "_ga", "_gl", "mkt_tok", "_openstat",
    "__hsfp", "__hssc", "__hstc", "_hsenc", "_hsmi"
)
private val signedParameters = setOf(
    "signature", "sig", "x-amz-signature", "x-goog-signature", "key-pair-id",
    "token", "access_token"
)
private val clipboardUrlPattern = Regex("https?://[^\\s<>\"'`]+", RegexOption.IGNORE_CASE)
private val htmlAmpersand = Regex("&(?:amp|#0*38|#x0*26);", RegexOption.IGNORE_CASE)
private val twitterStatusPath = Regex("/[^/]+/status/[0-9]+/?")
private val redditTrackingParameters = setOf("share_id", "share", "ref_source", "rdt")
private val closingBrackets = mapOf(')' to '(', ']' to '[', '}' to '{')

internal data class ClipboardUrlReplacement(val range: IntRange, val text: String)

internal fun clipboardUrlReplacements(text: String, html: Boolean = false): List<ClipboardUrlReplacement> =
    clipboardUrlPattern.findAll(text).mapNotNull { match ->
        val original = match.value.trimUrlPunctuation(html)
        val decoded = if(html) original.replace(htmlAmpersand, "&") else original
        val cleaned = cleanClipboardUrl(decoded)
        if(cleaned == decoded) null else ClipboardUrlReplacement(
            match.range.first until match.range.first + original.length,
            if(html) cleaned.replace("&", "&amp;") else cleaned
        )
    }.toList()

private fun String.trimUrlPunctuation(html: Boolean): String {
    val unmatchedClosings = closingBrackets.mapValues { (close, open) ->
        count { it == close } - count { it == open }
    }.toMutableMap()
    var end = length
    // Keep sentence punctuation and enclosing Markdown brackets outside the URL.
    while(end > 0) {
        val last = this[end - 1]
        when {
            (unmatchedClosings[last] ?: 0) > 0 -> {
                unmatchedClosings[last] = unmatchedClosings.getValue(last) - 1
                end--
            }
            last in ".,!?;:" && !(html && last == ';') -> end--
            else -> break
        }
    }
    return substring(0, end)
}

internal fun cleanClipboardUrls(text: String, html: Boolean = false): String {
    val replacements = clipboardUrlReplacements(text, html)
    if(replacements.isEmpty()) return text
    return StringBuilder(text).apply {
        replacements.asReversed().forEach { replace(it.range.first, it.range.last + 1, it.text) }
    }.toString()
}

internal fun cleanClipboardUrl(url: String): String {
    val uri = try {
        URI(url)
    } catch(_: URISyntaxException) {
        return url
    }
    if(!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return url
    val host = uri.host?.lowercase(Locale.ROOT) ?: return url
    val query = uri.rawQuery ?: return url
    val parameters = query.split('&')
    val names = parameters.map {
        URLDecoder.decode(it.substringBefore('='), "UTF-8").lowercase(Locale.ROOT)
    }
    // Even a tracking field can be covered by a signature or authentication token.
    if(names.any { it in signedParameters }) return url
    val siteParameters = when {
        host.isDomain("youtube.com") || host == "youtu.be" || host == "open.spotify.com" -> setOf("si")
        host.isDomain("instagram.com") -> setOf("igshid", "igsh")
        host.isDomain("reddit.com") -> redditTrackingParameters
        (host.isDomain("twitter.com") || host.isDomain("x.com")) &&
            twitterStatusPath.matches(uri.path.orEmpty()) -> setOf("s", "t")
        else -> emptySet()
    }
    val kept = parameters.filterIndexed { index, _ ->
        val name = names[index]
        !(name.startsWith("utm_") || name.startsWith("mtm_") || name in trackingParameters ||
            name in siteParameters)
    }
    if(kept.size == parameters.size) return url
    // Splice the raw query instead of rebuilding the URI: retain encoding, duplicate
    // parameters, their order, the fragment, and the original spelling of the URL.
    return url.substringBefore('?') +
        (if(kept.isEmpty()) "" else "?" + kept.joinToString("&")) +
        (if(uri.rawFragment == null) "" else "#" + uri.rawFragment)
}

private fun String.isDomain(domain: String): Boolean = this == domain || endsWith(".$domain")
