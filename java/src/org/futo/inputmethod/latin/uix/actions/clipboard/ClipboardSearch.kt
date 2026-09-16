package org.futo.inputmethod.latin.uix.actions.clipboard

internal data class ClipboardSearchQuery(
    val text: String,
    val included: Set<String>,
    val excluded: Set<String>
) {
    val normalizedText = text.trim().lowercase()

    fun matchesTags(tags: Set<String>): Boolean =
        tags.containsAll(included) && excluded.none { it in tags }
}

private val ClipboardSearchTokens = Regex("""(?<!\S)-?tag:(?:"[^"]*(?:"|$)|[^\s]*)|\S+""", RegexOption.IGNORE_CASE)
private val ClipboardTagWhitespace = Regex("\\s+")
private val ClipboardTagPrefix = Regex("^-?tag:", RegexOption.IGNORE_CASE)

internal fun normalizeClipboardTag(value: String): String =
    value.trim().lowercase().replace(ClipboardTagWhitespace, "_")

internal fun parseClipboardSearch(text: String): ClipboardSearchQuery {
    val included = mutableSetOf<String>()
    val excluded = mutableSetOf<String>()
    var hasTags = false
    val freeText = ClipboardSearchTokens.findAll(text).mapNotNull { match ->
        val prefix = ClipboardTagPrefix.find(match.value) ?: return@mapNotNull match.value
        hasTags = true
        val value = match.value.substring(prefix.value.length)
        if(value.startsWith('"') && (value.length < 2 || !value.endsWith('"'))) return@mapNotNull null
        val tag = normalizeClipboardTag(value.removeSurrounding("\""))
        if(tag.isNotEmpty()) {
            if(prefix.value.startsWith('-')) excluded.add(tag) else included.add(tag)
        }
        null
    }.toList()
    return ClipboardSearchQuery(if(hasTags) freeText.joinToString(" ") else text, included, excluded)
}

internal data class ClipboardSearchToken(
    val start: Int,
    val end: Int,
    val prefix: String,
    val negative: Boolean
)

internal fun clipboardSearchToken(text: String, selectionStart: Int, selectionEnd: Int): ClipboardSearchToken? {
    val start = minOf(selectionStart, selectionEnd)
    val end = maxOf(selectionStart, selectionEnd)
    if(start < 0 || end > text.length) return null
    val match = ClipboardSearchTokens.findAll(text).firstOrNull {
        start >= it.range.first && start <= it.range.last + 1 && end <= it.range.last + 1
    }
    if(match == null) return null
    val raw = match.value
    val prefix = ClipboardTagPrefix.find(raw)?.value
    val value = if(prefix != null) raw.substring(prefix.length) else raw.removePrefix("-")
    // URLs and other operators are ordinary text, not tag completion candidates.
    if(prefix == null && ':' in raw) return null
    return ClipboardSearchToken(
        match.range.first, match.range.last + 1,
        normalizeClipboardTag(value.removePrefix("\"").removeSuffix("\"")), raw.startsWith('-')
    )
}

// Only the token being completed is a draft. Selecting a suggestion or finishing commits it.
internal fun clipboardEditingSearchText(text: String, token: ClipboardSearchToken?): String =
    if(token == null) text else listOf(text.substring(0, token.start).trim(), text.substring(token.end).trim())
        .filter { it.isNotEmpty() }.joinToString(" ")

internal fun clipboardSearchContext(text: String, token: ClipboardSearchToken): ClipboardSearchQuery =
    parseClipboardSearch(clipboardEditingSearchText(text, token))

internal data class ClipboardSearchReplacement(val start: Int, val end: Int, val text: String) {
    val cursor: Int get() = start + text.length
}

internal fun clipboardTagReplacement(text: String, token: ClipboardSearchToken, tag: String): ClipboardSearchReplacement {
    val end = if(token.end < text.length && text[token.end] == ' ') token.end + 1 else token.end
    return ClipboardSearchReplacement(token.start, end, "${if(token.negative) "-" else ""}tag:$tag ")
}

internal data class ClipboardTagSuggestion(
    val name: String,
    val category: ClipboardImageTagCategory,
    val count: Int
)

internal data class ClipboardSearchSource(
    val archiveTags: Map<String, Map<String, ClipboardImageTagCategory>>,
    val entries: Map<String, ClipboardEntrySearchSource>
)

internal data class ClipboardEntrySearchSource(val text: String?, val metadataArchiveKey: String?)

internal fun clipboardSearchSource(
    archives: Collection<ClipboardLinkArchive>,
    entries: List<ClipboardEntry>
): ClipboardSearchSource = ClipboardSearchSource(
    archives.associate { archive ->
        archive.key to buildMap {
            archive.media.filterNot {
                it.archiveMediaKey() in archive.deletedMediaKeys || "${it.sourceIndex}:${it.sourceUrl}" in archive.deletedMediaKeys
            }.forEach { media ->
                media.imageTagging?.takeIf { it.failure == null }?.tags?.forEach { tag ->
                    put(tag.name, tag.category)
                }
            }
        }
    },
    entries.associate { it.selectionKey() to ClipboardEntrySearchSource(it.text, it.previewMetadata?.archiveKey()) }
)

internal class ClipboardSearchIndex(
    val archiveTags: Map<String, Map<String, ClipboardImageTagCategory>> = emptyMap(),
    val archiveByEntry: Map<String, String> = emptyMap()
) {
    private val tagArchives = buildMap<String, MutableSet<String>> {
        archiveTags.forEach { (archive, tags) ->
            tags.keys.forEach { name -> getOrPut(name) { mutableSetOf() }.add(archive) }
        }
    }
    private val categories = buildMap { archiveTags.values.forEach { putAll(it) } }

    fun tagsForArchive(key: String): Set<String> = archiveTags[key]?.keys.orEmpty()
    fun tagsForEntry(entry: ClipboardEntry): Set<String> =
        archiveByEntry[entry.selectionKey()]?.let(::tagsForArchive).orEmpty()

    fun matches(entry: ClipboardEntry, query: ClipboardSearchQuery): Boolean =
        query.matchesTags(tagsForEntry(entry)) && entry.matchesNormalizedQuery(query.normalizedText)

    fun matches(archive: ClipboardLinkArchive, query: ClipboardSearchQuery): Boolean =
        query.matchesTags(tagsForArchive(archive.key)) && archive.matchesArchiveQuery(query.text)

    // Repeated archive keys represent different clip cards and count separately.
    fun suggestions(
        prefix: String,
        used: Set<String>,
        archiveKeys: List<String>,
        limit: Int = 8
    ): List<ClipboardTagSuggestion> {
        if(archiveKeys.isEmpty()) return emptyList()
        val cardCounts = archiveKeys.groupingBy { it }.eachCount()
        val counts = tagArchives.mapNotNull { (name, archives) ->
            if(name in used || tagMatchRank(name, prefix) == 3) return@mapNotNull null
            val count = archives.sumOf { cardCounts[it] ?: 0 }
            if(count == 0) null else ClipboardTagSuggestion(name, categories.getValue(name), count)
        }
        return counts.sortedWith(
            compareBy<ClipboardTagSuggestion> { tagMatchRank(it.name, prefix) }
                .thenByDescending { it.count }.thenBy { it.name }
        ).take(limit)
    }
}

private fun tagMatchRank(name: String, prefix: String): Int = when {
    name == prefix -> 0
    name.startsWith(prefix) -> 1
    name.contains("_$prefix") -> 2
    else -> 3
}

internal fun buildClipboardSearchIndex(source: ClipboardSearchSource): ClipboardSearchIndex = ClipboardSearchIndex(
    source.archiveTags.mapValues { (_, tags) -> tags.mapKeys { normalizeClipboardTag(it.key) } },
    source.entries.mapNotNull { (entryKey, entry) ->
        // Use the same URL parser as archive backfill; no filename-based associations.
        val archiveKey = entry.metadataArchiveKey ?: entry.text?.let { text ->
            ClipboardLinkPreviewFetcher.previewCandidateFor(text)?.metadata?.archiveKey()
        }
        archiveKey?.takeIf { it in source.archiveTags }?.let { entryKey to it }
    }.toMap()
)
