package com.gorite.cyclemap.ui.cycling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiCategoryPickerTest {
    @Test
    fun groupsContainEveryPoiCategoryExactlyOnce() {
        val grouped = POI_CATEGORY_GROUPS.flatMap { it.categories }
        assertEquals(
            "All 31 PoiCategory entries must appear once across POI_CATEGORY_GROUPS",
            PoiCategory.entries.size,
            grouped.size,
        )
        assertEquals(
            "No duplicate categories allowed across POI_CATEGORY_GROUPS",
            grouped.size,
            grouped.toSet().size,
        )
        assertEquals(PoiCategory.entries.toSet(), grouped.toSet())
    }

    @Test
    fun everyGroupHasANonBlankLabelAndAtLeastOneCategory() {
        for (group in POI_CATEGORY_GROUPS) {
            assertTrue("Group label should not be blank", group.label.isNotBlank())
            assertTrue("Group ${group.label} should not be empty", group.categories.isNotEmpty())
        }
    }
}
