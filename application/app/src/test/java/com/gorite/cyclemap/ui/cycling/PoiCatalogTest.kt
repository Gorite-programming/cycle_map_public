package com.gorite.cyclemap.ui.cycling

import com.gorite.cyclemap.ui.mapview.poiFeatureLimitForZoom
import com.gorite.cyclemap.ui.mapview.poiLabelVisible
import com.gorite.cyclemap.ui.mapview.poiPrefixesForZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiCatalogTest {
    @Test
    fun everyPrefixIsClaimedByExactlyOneCategory() {
        val claimed = PoiCategory.entries.flatMap { cat -> cat.prefixes.map { it to cat } }
        assertEquals("a prefix is declared by two categories", claimed.size, claimed.map { it.first }.toSet().size)
    }

    @Test
    fun forCategoryUsesTheLongestMatchSoSpecificTourismKindsWin() {
        assertEquals(PoiCategory.HOTEL, PoiCategory.forCategory("tourism:hotel"))
        assertEquals(PoiCategory.CAMP, PoiCategory.forCategory("tourism:camp_site"))
        assertEquals(PoiCategory.INFO, PoiCategory.forCategory("tourism:information"))
        // 未知の tourism:* は従来どおり観光に入る
        assertEquals(PoiCategory.TOURISM, PoiCategory.forCategory("tourism:attraction"))
        assertEquals(PoiCategory.TOURISM, PoiCategory.forCategory("tourism:something_new"))
    }

    @Test
    fun databaseCategoriesMapToTheExpectedCategory() {
        val expected = mapOf(
            "highway:bus_stop" to PoiCategory.BUS_STOP,
            "railway:station" to PoiCategory.STATION,
            "amenity:hospital" to PoiCategory.HOSPITAL,
            "amenity:clinic" to PoiCategory.CLINIC,
            "amenity:dentist" to PoiCategory.CLINIC,
            "amenity:pharmacy" to PoiCategory.PHARMACY,
            "shop:supermarket" to PoiCategory.SUPERMARKET,
            "amenity:place_of_worship" to PoiCategory.WORSHIP,
            "amenity:post_office" to PoiCategory.POST,
            "amenity:bank" to PoiCategory.BANK,
            "amenity:school" to PoiCategory.SCHOOL,
            "amenity:bar" to PoiCategory.BAR,
            "amenity:bicycle_rental" to PoiCategory.BIKE_SERVICE,
            "amenity:charging_station" to PoiCategory.EV,
            "amenity:townhall" to PoiCategory.PUBLIC,
            "leisure:sports_centre" to PoiCategory.SPORTS,
        )
        for ((dbCategory, category) in expected) assertEquals(dbCategory, category, PoiCategory.forCategory(dbCategory))
        assertNull(PoiCategory.forCategory("shop:hairdresser"))
    }

    @Test
    fun majorFacilitiesAppearEarlierThanDailyShops() {
        assertTrue(PoiCategory.STATION.minZoom < PoiCategory.CONVENIENCE.minZoom)
        assertTrue(PoiCategory.TOURISM.minZoom < PoiCategory.FOOD.minZoom)
        assertTrue(PoiCategory.HOSPITAL.minZoom < PoiCategory.CLINIC.minZoom)
        assertTrue(PoiCategory.BUS_STOP.minZoom >= 15.5)
    }

    @Test
    fun lowZoomShowsOnlyMajorFacilities() {
        val all = PoiCategory.entries
        val z11 = all.filter { it.minZoom <= 11.0 }
        assertEquals(setOf(PoiCategory.STATION), z11.toSet())
        assertTrue(all.filter { it.minZoom <= 12.0 }.size < all.size / 2)
        assertEquals(all.size, all.filter { it.minZoom <= 16.0 }.size)
        // 低ズームの検索プレフィックスは、そのズームで出すカテゴリのものだけ
        val prefixes = poiPrefixesForZoom(PoiCategory.defaults(), 11.0)
        assertTrue(prefixes.contains("railway:station"))
        assertFalse(prefixes.contains("shop:convenience"))
    }

    @Test
    fun pinCountGrowsWithZoomAndIsZeroWhenZoomedFarOut() {
        assertEquals(0, poiFeatureLimitForZoom(9.0))
        var previous = 0
        for (zoom in listOf(11.0, 12.5, 13.5, 14.5, 15.5, 17.0)) {
            val limit = poiFeatureLimitForZoom(zoom)
            assertTrue("limit at $zoom", limit > previous)
            previous = limit
        }
    }

    @Test
    fun namesAreShownOnlyForProminentFacilitiesWhenZoomedOut() {
        assertFalse(poiLabelVisible(12.0, 960))
        assertTrue(poiLabelVisible(14.0, 960))
        assertFalse(poiLabelVisible(14.0, 700))
        assertTrue(poiLabelVisible(16.5, 700))
        assertTrue(poiLabelVisible(17.5, 100))
    }

    @Test
    fun defaultsExcludeNoisyCategoriesAndKeepCyclingEssentials() {
        val defaults = PoiCategory.defaults()
        for (essential in listOf(PoiCategory.STATION, PoiCategory.CONVENIENCE, PoiCategory.TOILET, PoiCategory.BICYCLE, PoiCategory.WATER, PoiCategory.FOOD)) {
            assertTrue("$essential is on by default", essential in defaults)
        }
        for (noisy in listOf(PoiCategory.BUS_STOP, PoiCategory.PARKING, PoiCategory.BAR, PoiCategory.CLINIC)) {
            assertFalse("$noisy is off by default", noisy in defaults)
        }
    }

    @Test
    fun migrationKeepsTheOldChoiceAndAddsNewDefaultCategories() {
        val oldChoice = setOf(PoiCategory.CONVENIENCE, PoiCategory.TOILET, PoiCategory.STATION, PoiCategory.HOSPITAL)
        val migrated = PoiCategory.migrateSelection(oldChoice, fromVersion = 1)
        assertTrue(migrated.containsAll(oldChoice))
        // 旧版の「病院」は診療所を含み、「駅・バス停」はバス停を含んでいた
        assertTrue(PoiCategory.CLINIC in migrated)
        assertTrue(PoiCategory.BUS_STOP in migrated)
        // 旧版にはなく初期表示のカテゴリが増える
        assertTrue(PoiCategory.WORSHIP in migrated)
        assertTrue(PoiCategory.HOTEL in migrated)
        // 旧版で外していたカテゴリを勝手に復活させない
        assertFalse(PoiCategory.PARKING in migrated)
        assertFalse(PoiCategory.BAKERY in migrated.minus(PoiCategory.defaults()))
        // 既に現行版なら何も変えない
        assertEquals(oldChoice, PoiCategory.migrateSelection(oldChoice, PoiCategory.CATALOG_VERSION))
    }

    @Test
    fun everyCategoryHasAnIconAndAValidColour() {
        for (cat in PoiCategory.entries) {
            assertNotNull(cat.label)
            assertTrue("${cat.name} icon", cat.iconRes != 0)
            assertTrue("${cat.name} colour", Regex("#[0-9A-Fa-f]{6}").matches(cat.colorHex))
        }
    }
}
