package com.gorite.cyclemap.ui.cycling

import com.gorite.cyclemap.routing.RoadRuleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadRuleTextTest {

    @Test
    fun allEntriesHaveNonEmptyUniqueTextWithoutForbiddenWords() {
        val entries = RoadRuleKind.entries
        assertEquals(7, entries.size)

        val forbiddenWords = listOf("違法", "禁止")
        val texts = entries.map { kind ->
            val text = roadRuleText(kind)
            assertTrue("$kind の文言が空です", text.isNotBlank())
            for (word in forbiddenWords) {
                assertFalse(
                    "$kind の文言「$text」に禁止語「$word」が含まれています",
                    text.contains(word),
                )
            }
            text
        }

        assertEquals(
            "7 種の文言に重複があります: $texts",
            entries.size,
            texts.toSet().size,
        )
    }
}
