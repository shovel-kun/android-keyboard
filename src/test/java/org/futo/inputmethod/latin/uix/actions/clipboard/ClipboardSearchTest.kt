package org.futo.inputmethod.latin.uix.actions.clipboard

import org.junit.Assert.*
import org.junit.Test

class ClipboardSearchTest {
    @Test
    fun archiveOcrMatchesEnglishAndJapaneseWithoutAddingTags() {
        val media = media(0, "solo").copy(fileName = "one.png", status = ClipboardArchiveMediaStatus.Saved)
        val result = ClipboardOcrResult(media.ocrInput()!!, "model1", 20L, regions = listOf(
            ClipboardOcrRegion("ＨＥＬＬＯ 世界の猫 ｶﾀｶﾅ", 0.9f, emptyList())))
        val archive = archive(listOf(media.copy(ocr = result)))
        val index = buildClipboardSearchIndex(clipboardSearchSource(listOf(archive), emptyList()))
        assertTrue(index.matches(archive, parseClipboardSearch("hello tag:solo")))
        assertTrue(index.matches(archive, parseClipboardSearch("世界の猫")))
        assertTrue(index.matches(archive, parseClipboardSearch("カタカナ")))
        assertFalse(index.matches(archive, parseClipboardSearch("hello -tag:solo")))
        assertEquals(setOf("solo"), index.tagsForArchive(archive.key))
        assertFalse(index.matches(archive.copy(deletedMediaKeys = setOf(media.archiveMediaKey())), parseClipboardSearch("hello")))
        assertFalse(index.matches(archive.copy(media = listOf(media.copy(ocr = result.copy(failed = true)))), parseClipboardSearch("hello")))
    }

    @Test
    fun query_preservesOrdinaryTextAndUrls() {
        val text = "  hello  world https://example.org/tag:solo  "
        assertEquals(text, parseClipboardSearch(text).text)
        assertTrue(parseClipboardSearch(text).included.isEmpty())
    }

    @Test
    fun query_combinesExactTagsAndExclusionsWithRemainingPhrase() {
        val query = parseClipboardSearch("Some tag:\"BLUE hair\" words tag:solo -tag:hat tag:solo")
        assertEquals("Some words", query.text)
        assertEquals(setOf("blue_hair", "solo"), query.included)
        assertEquals(setOf("hat"), query.excluded)
        assertTrue(query.matchesTags(setOf("blue_hair", "solo")))
        assertFalse(query.matchesTags(setOf("blue_hair", "solo", "hat")))
        assertFalse(query.matchesTags(setOf("blue_hair", "solo_pose")))
        assertTrue(parseClipboardSearch("-tag:hat").matchesTags(emptySet()))
        assertFalse(parseClipboardSearch("tag:unknown").matchesTags(emptySet()))
    }

    @Test
    fun query_ignoresIncompleteClausesUntilTheyHaveValues() {
        assertEquals(emptySet<String>(), parseClipboardSearch("tag:").included)
        assertEquals("hello", parseClipboardSearch("hello tag:\"blue ha").text)
        assertEquals(emptySet<String>(), parseClipboardSearch("tag:\"blue ha").included)
        assertEquals(setOf("solo"), parseClipboardSearch("TAG:SOLO").included)
    }

    @Test
    fun completion_replacesSelectedTokenAndPreservesFollowingText() {
        val text = "hello -tag:bl tag:solo"
        val token = clipboardSearchToken(text, 12, 12)!!
        val replacement = clipboardTagReplacement(text, token, "blue_hair")
        assertEquals("hello -tag:blue_hair tag:solo", text.replaceRange(replacement.start, replacement.end, replacement.text))
        assertEquals(21, replacement.cursor)
        assertNull(clipboardSearchToken(text, 2, text.length))
        assertNull(clipboardSearchToken("https://example.org", 8, 8))
    }

    @Test
    fun completionContext_joinsRemainingPhraseAndKeepsOtherTagClauses() {
        val text = "hello bl world tag:solo"
        val token = clipboardSearchToken(text, 8, 8)!!
        val context = clipboardSearchContext(text, token)
        assertEquals("hello world", context.text)
        assertEquals(setOf("solo"), context.included)
        val plainText = "hello bl world"
        assertEquals("hello world", clipboardSearchContext(plainText, clipboardSearchToken(plainText, 8, 8)!!).text)
    }

    @Test
    fun completion_handlesQuotesUnicodeAndTrailingSpace() {
        val text = "猫 tag:\"blue ha"
        val token = clipboardSearchToken(text, text.length, text.length)!!
        assertEquals("blue_ha", token.prefix)
        assertEquals("猫 tag:blue_hair ", text.replaceRange(token.start, token.end, clipboardTagReplacement(text, token, "blue_hair").text))
        assertNull(clipboardSearchToken("tag:solo ", 9, 9))
        assertNull(clipboardSearchToken("", 0, 0))
    }

    @Test
    fun editingSearch_keepsCommittedFiltersUntilCompletionOrSubmit() {
        val text = "tag:solo tag:blu"
        val token = clipboardSearchToken(text, text.length, text.length)!!
        assertEquals("tag:solo", clipboardEditingSearchText(text, token))
        assertEquals(text, clipboardEditingSearchText(text, null))
        val replacement = clipboardTagReplacement(text, token, "blue_hair")
        val completed = text.replaceRange(replacement.start, replacement.end, replacement.text)
        assertNull(clipboardSearchToken(completed, replacement.cursor, replacement.cursor))
        assertEquals(setOf("solo", "blue_hair"), parseClipboardSearch(completed).included)
    }

    @Test
    fun editingSearch_preservesTextWhenFinishedAndOtherTermsWhileCompleting() {
        val text = "hello  world"
        val token = clipboardSearchToken(text, text.length, text.length)!!
        assertEquals("hello", clipboardEditingSearchText(text, token))
        assertEquals(text, clipboardEditingSearchText(text, null))
        val middle = "hello -tag:bl world tag:solo"
        val middleToken = clipboardSearchToken(middle, 12, 12)!!
        assertEquals("hello world tag:solo", clipboardEditingSearchText(middle, middleToken))
    }

    @Test
    fun suggestions_countCardsRankDeterministicallyAndExcludeUsedTags() {
        val general = ClipboardImageTagCategory.General
        val index = ClipboardSearchIndex(mapOf(
            "a" to mapOf("blue_hair" to general, "dark_blue_hair" to general, "solo" to general),
            "b" to mapOf("blue_eyes" to general, "blue_hair" to general)
        ))
        assertEquals(listOf("blue_hair", "blue_eyes", "dark_blue_hair"), index.suggestions("blue", emptySet(), listOf("a", "b")).map { it.name })
        assertEquals(3, index.suggestions("blue_hair", emptySet(), listOf("a", "a", "b")).first().count)
        assertEquals(listOf("blue_eyes", "dark_blue_hair"), index.suggestions("blue", setOf("blue_hair"), listOf("a", "b")).map { it.name })
        assertTrue(index.suggestions("blue", emptySet(), emptyList()).isEmpty())
    }
    @Test
    fun index_unionsMediaTagsButCountsEachArchiveOnce() {
        val archive = archive(listOf(media(0, "blue_hair"), media(1, "solo", "blue_hair")))
        val clip = entry()
        val index = buildClipboardSearchIndex(clipboardSearchSource(listOf(archive), listOf(clip)))
        val query = parseClipboardSearch("tag:blue_hair tag:solo")
        assertTrue(index.matches(archive, query))
        assertTrue(index.matches(clip, query))
        assertFalse(index.matches(archive, parseClipboardSearch("-tag:solo")))
        assertEquals(1, index.suggestions("blue", emptySet(), listOf(archive.key)).single().count)
        assertFalse(index.matches(clip.copy(text = "unrelated", previewMetadata = null), query))
        assertEquals(listOf("blue_hair"), archive.media[0].imageTagging!!.tags.map { it.name })
    }

    @Test
    fun index_excludesFailuresAndDeletedMediaButRetainsMissingFileMetadata() {
        val failed = media(2, "hat").let { it.copy(imageTagging = it.imageTagging!!.copy(failure = ClipboardImageTaggingFailure.InferenceFailed)) }
        val archive = archive(listOf(media(0, "blue_hair"), media(1, "solo"), failed))
            .copy(deletedMediaKeys = setOf("index:1"))
        val source = clipboardSearchSource(listOf(archive), emptyList())
        assertEquals(setOf("blue_hair"), buildClipboardSearchIndex(source).tagsForArchive(archive.key))
    }

    @Test
    fun source_ignoresPinConfidenceAndProgressChangesButTracksRetaggingAndDeletion() {
        val archive = archive(listOf(media(0, "blue_hair")))
        val clip = entry()
        val source = clipboardSearchSource(listOf(archive), listOf(clip))
        val changedProbability = archive.copy(media = archive.media.map { media ->
            media.copy(lastAttemptAtEpochMs = 99L, imageTagging = media.imageTagging!!.copy(
                tags = media.imageTagging.tags.map { it.copy(probability = 0.99f) }
            ))
        })
        assertEquals(source, clipboardSearchSource(listOf(changedProbability), listOf(clip.copy(pinned = true, timestamp = 99L))))
        val retagged = buildClipboardSearchIndex(clipboardSearchSource(listOf(archive.copy(media = listOf(media(0, "solo")))), listOf(clip)))
        assertEquals(setOf("solo"), retagged.tagsForEntry(clip))
        assertTrue(buildClipboardSearchIndex(clipboardSearchSource(emptyList(), listOf(clip))).tagsForEntry(clip).isEmpty())
        assertNotEquals(source, clipboardSearchSource(listOf(archive), emptyList()))
    }

    @Test
    fun index_resolvesLegacyClipUrlsThroughExistingArchiveResolver() {
        val archive = archive(listOf(media(0, "solo")))
        val clip = entry().copy(previewMetadata = null)
        val index = buildClipboardSearchIndex(clipboardSearchSource(listOf(archive), listOf(clip)))
        assertEquals(setOf("solo"), index.tagsForEntry(clip))
        assertTrue(index.suggestions("", emptySet(), listOf(archive.key)).isNotEmpty())
    }

    @Test
    fun suggestions_matchExhaustiveReferenceForLargeVocabulary() {
        val general = ClipboardImageTagCategory.General
        val tags = (0 until 10000).associate { "archive:$it" to mapOf("tag_$it" to general) }
        repeat(3) { ClipboardSearchIndex(tags).suggestions("tag_1", emptySet(), tags.keys.toList()) }
        val start = System.nanoTime()
        val index = ClipboardSearchIndex(tags)
        val buildMs = (System.nanoTime() - start) / 1_000_000.0
        val prefixes = listOf("tag_1", "tag_99", "unknown", "")
        val queryStart = System.nanoTime()
        val actual = prefixes.associateWith { prefix -> index.suggestions(prefix, emptySet(), tags.keys.toList()).map { it.name } }
        val queryMs = (System.nanoTime() - queryStart) / 1_000_000.0
        for(prefix in prefixes) {
            val expected = tags.values.flatMap { it.keys }.filter { it.startsWith(prefix) }
                .sortedWith(compareBy<String> { if(it == prefix) 0 else 1 }.thenBy { it }).take(8)
            assertEquals(expected, actual.getValue(prefix))
        }
        println("Clipboard tag workload: build=$buildMs ms, four queries=$queryMs ms")
    }

    private fun entry() = ClipboardEntry(
        timestamp = 1L, pinned = false, text = "https://www.pixiv.net/en/artworks/123", uri = null,
        mimeTypes = listOf("text/plain"),
        previewMetadata = ClipboardPreviewMetadata(ClipboardPreviewProvider.PIXIV, sourceId = "123")
    )

    private fun archive(media: List<ClipboardArchiveMedia>) = ClipboardLinkArchive(
        key = "pixiv:123", provider = ClipboardPreviewProvider.PIXIV,
        sourceUrl = "https://www.pixiv.net/en/artworks/123", media = media,
        createdAtEpochMs = 1L, updatedAtEpochMs = 1L
    )

    private fun media(index: Int, vararg tags: String) = ClipboardArchiveMedia(
        sourceUrl = "https://example.com/$index.jpg", sourceIndex = index,
        imageTagging = ClipboardImageTaggingResult("test", 1L, tags.map {
            ClipboardImageTag(it, 0.8f, ClipboardImageTagCategory.General)
        })
    )

}
