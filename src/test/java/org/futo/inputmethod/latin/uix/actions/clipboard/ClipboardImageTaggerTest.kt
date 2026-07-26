package org.futo.inputmethod.latin.uix.actions.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardImageTaggerTest {
    @Test
    fun parsesModelLabelOrderAndSelectsOnlySearchableCategories() {
        val labels = parseClipboardImageTagLabels(
            """tag_id,name,category,count
                |1,explicit,9,1
                |2,blue_hair,0,1
                |3,character_name,4,1
                |4,low_score,0,1
            """.trimMargin()
        )

        val tags = selectClipboardImageTags(
            labels,
            floatArrayOf(0.99f, 0.91f, 0.85f, 0.34f)
        )

        assertEquals(listOf("blue_hair", "character_name"), tags.map { it.name })
        assertEquals(
            listOf(ClipboardImageTagCategory.General, ClipboardImageTagCategory.Character),
            tags.map { it.category }
        )
    }

    @Test
    fun appliesPerCategoryCaps() {
        val general = (0 until 70).map { ClipboardImageTagLabel("general_$it", 0) }
        val characters = (0 until 20).map { ClipboardImageTagLabel("character_$it", 4) }

        val tags = selectClipboardImageTags(
            general + characters,
            FloatArray(general.size + characters.size) { 0.95f }
        )

        assertEquals(64, tags.count { it.category == ClipboardImageTagCategory.General })
        assertEquals(16, tags.count { it.category == ClipboardImageTagCategory.Character })
    }
}
