package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.URLSpan

/** Returns null when no item needs changing, so writeback cannot trigger a loop. */
internal fun cleanClipboardClip(clip: ClipData): ClipData? {
    val items = (0 until clip.itemCount).map { index ->
        val item = clip.getItemAt(index)
        val text = item.text?.let(::cleanClipboardText)
        val html = item.htmlText?.let { cleanClipboardUrls(it, html = true) }
        val uri = item.uri?.let { original ->
            val cleaned = cleanClipboardUrl(original.toString())
            if(cleaned == original.toString()) original else Uri.parse(cleaned)
        }
        if(text === item.text && html == item.htmlText && uri == item.uri) item else {
            ClipData.Item(text, html, item.intent, uri)
        }
    }
    if(items.indices.all { items[it] === clip.getItemAt(it) }) return null
    // ClipDescription's copy constructor does NOT copy extras (including sensitive).
    val description = ClipDescription(clip.description).apply {
        clip.description.extras?.let { extras = it }
    }
    return ClipData(description, items.first()).apply { items.drop(1).forEach(::addItem) }
}

private fun cleanClipboardText(text: CharSequence): CharSequence {
    val replacements = clipboardUrlReplacements(text.toString())
    val spans = (text as? Spanned)?.getSpans(0, text.length, URLSpan::class.java).orEmpty()
    val changedSpans = spans.mapNotNull { span ->
        cleanClipboardUrl(span.url).takeIf { it != span.url }?.let { span to it }
    }
    if(replacements.isEmpty() && changedSpans.isEmpty()) return text
    return SpannableStringBuilder(text).apply {
        changedSpans.forEach { (span, url) ->
            val start = getSpanStart(span)
            val end = getSpanEnd(span)
            val flags = getSpanFlags(span)
            removeSpan(span)
            setSpan(URLSpan(url), start, end, flags)
        }
        replacements.asReversed().forEach { replacement ->
            val start = replacement.range.first
            val end = replacement.range.last + 1
            // Android removes exclusive spans when their entire text is replaced.
            // Carry those spans onto the shorter link so rich paste retains styling.
            val coveringSpans = getSpans(start, end, Any::class.java).filter {
                getSpanStart(it) == start && getSpanEnd(it) == end
            }.associateWith { getSpanFlags(it) }
            replace(start, end, replacement.text)
            coveringSpans.forEach { (span, flags) ->
                if(getSpanStart(span) < 0) setSpan(span, start, start + replacement.text.length, flags)
            }
        }
    }
}

/** Check again after background cleaning, before replacing the system clipboard. */
internal fun sameClipboardContents(expected: ClipData, current: ClipData?): Boolean {
    if(current == null || expected.itemCount != current.itemCount) return false
    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        expected.description.timestamp != current.description.timestamp) return false
    if(expected.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) !=
        current.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)) return false
    return (0 until expected.itemCount).all { index ->
        val a = expected.getItemAt(index)
        val b = current.getItemAt(index)
        a.text?.toString() == b.text?.toString() && a.htmlText == b.htmlText &&
            a.uri == b.uri && a.intent?.toUri(0) == b.intent?.toUri(0)
    }
}
