package com.gorite.cyclemap.ui.mapview

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 同梱スタイル (protomaps_light/dark.json) の整合性。scripts/patch_map_style.py の調整が残っていること、
 * 参照する標識画像がコード側の登録名と一致することを確認する。
 */
class MapStyleAssetsTest {
    private fun loadStyle(theme: String): JsonObject {
        val name = "protomaps_$theme.json"
        val file = listOf(File("src/main/assets/styles/$name"), File("app/src/main/assets/styles/$name"))
            .firstOrNull { it.exists() } ?: error("style asset not found: $name")
        return JsonParser.parseString(file.readText()).asJsonObject
    }

    private fun layers(style: JsonObject): Map<String, JsonObject> =
        style.getAsJsonArray("layers").associate { it.asJsonObject.get("id").asString to it.asJsonObject }

    private fun JsonArray.strings(): List<String> = map { it.asString }

    @Test
    fun shieldLayerUsesJapaneseNetworksAndRegisteredImageNames() {
        for (theme in listOf("light", "dark")) {
            val shields = layers(loadStyle(theme)).getValue("roads_shields")
            val text = shields.toString()
            assertTrue("$theme: national shield image", text.contains(SHIELD_NATIONAL_PREFIX))
            assertTrue("$theme: prefectural shield image", text.contains(SHIELD_PREFECTURAL_PREFIX))
            assertTrue("$theme: JP:national", text.contains("JP:national"))
            assertTrue("$theme: JP:prefectural", text.contains("JP:prefectural"))
            // 高速道路 (kind=highway) は関連路線の番号が混ざるため標識の対象外
            assertFalse("$theme: highway shields are excluded", shields.get("filter").toString().contains("\"highway\""))
        }
    }

    @Test
    fun shieldImageNamesCoverEveryDigitCountTheStyleCanRequest() {
        // スタイルは shield_text が 4 文字以下のときだけ標識を出す。コードは 1〜4 桁すべての画像を登録する。
        assertEquals(listOf(1, 2, 3, 4), SHIELD_DIGIT_COUNTS.toList())
        for (digits in SHIELD_DIGIT_COUNTS.drop(1)) assertTrue(shieldWidthDp(digits) > shieldWidthDp(digits - 1))
        for (theme in listOf("light", "dark")) {
            val filter = layers(loadStyle(theme)).getValue("roads_shields").get("filter").toString()
            assertTrue("$theme: shield_text length limit is 4", filter.contains("[\"get\",\"shield_text\"]],4]"))
        }
    }

    @Test
    fun railwayUsesBlackAndWhiteZebra() {
        for (theme in listOf("light", "dark")) {
            val all = layers(loadStyle(theme))
            val base = all.getValue("roads_rail_base").getAsJsonObject("paint")
            val hatch = all.getValue("roads_rail_hatch").getAsJsonObject("paint")
            assertTrue("$theme: tick is a dash pattern", hatch.has("line-dasharray"))
            assertTrue(
                "$theme: tick colour differs from the base colour",
                base.get("line-color").asString != hatch.get("line-color").asString,
            )
        }
    }

    @Test
    fun tilePoisCoveredByDynamicPinsAreRemovedFromTheStyle() {
        val covered = listOf("restaurant", "fast_food", "cafe", "convenience", "toilets", "drinking_water", "bus_stop")
        for (theme in listOf("light", "dark")) {
            val kinds = layers(loadStyle(theme)).getValue("pois").getAsJsonArray("filter")
                .get(1).asJsonArray.get(2).asJsonArray.get(1).asJsonArray.strings()
            for (kind in covered) assertFalse("$theme: $kind should be drawn by the dynamic pins", kinds.contains(kind))
            // 駅・公園などタイル由来のラベルは残す
            assertTrue("$theme: station stays", kinds.contains("station"))
            assertTrue("$theme: park stays", kinds.contains("park"))
        }
    }

    @Test
    fun landuseIsSplitIntoDistinctKindsWithDistinctColours() {
        val ids = listOf(
            "landuse_park", "landuse_wood", "landuse_farmland", "landuse_cemetery",
            "landuse_residential", "landuse_commercial", "landuse_pitch", "landuse_military",
        )
        for (theme in listOf("light", "dark")) {
            val all = layers(loadStyle(theme))
            val colours = ids.map { id ->
                val layer = all[id] ?: error("$theme: $id missing")
                layer.getAsJsonObject("paint").get("fill-color").asString
            }
            assertEquals("$theme: every landuse class has its own colour", colours.size, colours.toSet().size)
            // 同じ kind を 2 つのレイヤーが塗らない (重なると上のレイヤーの色で隠れてしまう)
            val kinds = ids.flatMap { id -> all.getValue(id).getAsJsonArray("filter").strings().drop(2) }
            assertEquals("$theme: a kind is painted by one layer only", kinds.size, kinds.toSet().size)
            assertTrue("$theme: forest is no longer painted as a park", !all.getValue("landuse_park").toString().contains("\"forest\""))
        }
    }

    @Test
    fun placeLabelLayersExistSoDynamicPinsCanBeInsertedBelowThem() {
        // setupDynamicPoiLayers は places_subplace → places_region → places_locality の順で直下を探す
        for (theme in listOf("light", "dark")) {
            assertTrue("$theme: places_subplace", layers(loadStyle(theme)).containsKey("places_subplace"))
        }
    }
}
