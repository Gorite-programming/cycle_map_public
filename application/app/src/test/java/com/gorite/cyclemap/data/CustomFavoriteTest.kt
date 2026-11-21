package com.gorite.cyclemap.data

import com.gorite.cyclemap.search.formatCategoryLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CustomFavoriteTest {

    @Test
    fun nameIsTrimmedAndFallsBackToTheDefault() {
        assertEquals("自宅", normalizeFavoriteName("  自宅 "))
        assertEquals(DEFAULT_FAVORITE_NAME, normalizeFavoriteName(""))
        assertEquals(DEFAULT_FAVORITE_NAME, normalizeFavoriteName("   "))
    }

    @Test
    fun longNamesAreCut() {
        val long = "あ".repeat(100)
        assertEquals(30, normalizeFavoriteName(long).length)
    }

    @Test
    fun customFavoriteKeepsTheCoordinatesAndUsesTheCustomCategory() {
        val spot = customFavorite("自宅", 34.36687, 132.37799, now = 123L)
        assertEquals("自宅", spot.name)
        assertEquals(CUSTOM_FAVORITE_CATEGORY, spot.category)
        assertEquals(34.36687, spot.latitude, 0.0)
        assertEquals(132.37799, spot.longitude, 0.0)
        assertEquals(123L, spot.addedAt)
        assertEquals("登録した地点", formatCategoryLabel(spot.category))
    }

    @Test
    fun theIdIsDecidedByThePlaceSoSavingAgainRenames() {
        val a = customFavorite("自宅", 34.36687, 132.37799)
        val b = customFavorite("実家", 34.36687, 132.37799)
        val c = customFavorite("自宅", 34.40000, 132.40000)
        assertEquals(a.id, b.id)
        assertNotEquals(a.id, c.id)
    }
}
