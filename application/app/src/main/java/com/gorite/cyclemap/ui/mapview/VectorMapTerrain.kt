package com.gorite.cyclemap.ui.mapview

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * 地形の表現 (等高線・陰影起伏) をベーススタイルに差し込む。
 *
 * データは県のタイル (`<県>.pmtiles`) と同じ場所に置く別ファイルで、`scripts/build_terrain.py` が作る:
 *   `<県小文字>.contours.pmtiles`  等高線 (ベクター, レイヤー "contour", ele=標高m, kind=minor|index)
 *   `<県小文字>.hillshade.pmtiles` 陰影起伏 (ラスター, 半透明のオーバーレイ)
 * 片方または両方が無い県では、その表現だけを出さない (地図そのものは今までどおり動く)。
 */
internal data class TerrainFiles(val hillshade: File?, val contours: File?) {
    val isEmpty: Boolean get() = hillshade == null && contours == null
}

internal const val TERRAIN_HILLSHADE_SOURCE = "terrain_hillshade"
internal const val TERRAIN_CONTOURS_SOURCE = "terrain_contours"

/** 県のタイル [pmtilesPath] に対応する地形ファイルを、同じ階層 (と tiles/ 下) から探す。 */
internal fun terrainFilesFor(pmtilesPath: String): TerrainFiles {
    val base = File(pmtilesPath)
    // 旧名の Hiroshima_osm.pmtiles も hiroshima の地形に対応づける
    val stem = base.name.removeSuffix(".pmtiles").removeSuffix("_osm").lowercase()
    val dirs = listOfNotNull(base.parentFile, base.parentFile?.let { File(it, "tiles") })
    fun find(kind: String): File? =
        dirs.map { File(it, "$stem.$kind.pmtiles") }.firstOrNull { it.isFile && it.length() > 0 }
    return TerrainFiles(hillshade = find("hillshade"), contours = find("contours"))
}

private fun lineWidth(vararg stops: Pair<Int, Double>): JsonArray = JsonArray().apply {
    add("interpolate"); add(JsonArray().apply { add("linear") }); add(JsonArray().apply { add("zoom") })
    stops.forEach { (z, w) -> add(z); add(w) }
}

private fun obj(build: JsonObject.() -> Unit): JsonObject = JsonObject().apply(build)

/** スタイル JSON に地形の source とレイヤーを足して返す。[terrain] が空なら [styleJson] をそのまま返す。 */
internal fun injectTerrain(styleJson: String, isNight: Boolean, terrain: TerrainFiles): String {
    if (terrain.isEmpty) return styleJson
    val style = JsonParser.parseString(styleJson).asJsonObject
    val sources = style.getAsJsonObject("sources")
    val layers = style.getAsJsonArray("layers")

    terrain.hillshade?.let { file ->
        sources.add(TERRAIN_HILLSHADE_SOURCE, obj {
            addProperty("type", "raster")
            addProperty("url", "pmtiles://file://${file.absolutePath}")
            addProperty("tileSize", 256)
        })
    }
    terrain.contours?.let { file ->
        sources.add(TERRAIN_CONTOURS_SOURCE, obj {
            addProperty("type", "vector")
            addProperty("url", "pmtiles://file://${file.absolutePath}")
        })
    }

    // 地面 (土地利用・水域) の上、道路・建物の下に置く。ラベルは最初のシンボルレイヤーの直前に置く
    val belowRoads = JsonArray()
    if (terrain.hillshade != null) {
        belowRoads.add(obj {
            addProperty("id", "terrain_hillshade")
            addProperty("type", "raster")
            addProperty("source", TERRAIN_HILLSHADE_SOURCE)
            add("paint", obj {
                addProperty("raster-opacity", if (isNight) 0.40 else 0.55)
                addProperty("raster-fade-duration", 0)
            })
        })
    }
    if (terrain.contours != null) {
        val minorColor = if (isNight) "rgba(214,184,140,0.30)" else "rgba(150,108,68,0.40)"
        val indexColor = if (isNight) "rgba(222,196,156,0.50)" else "rgba(128,86,48,0.62)"
        belowRoads.add(obj {
            addProperty("id", "terrain_contour_minor")
            addProperty("type", "line")
            addProperty("source", TERRAIN_CONTOURS_SOURCE)
            addProperty("source-layer", "contour")
            add("filter", JsonParser.parseString("[\"==\",[\"get\",\"kind\"],\"minor\"]"))
            addProperty("minzoom", 12.5)
            add("paint", obj {
                addProperty("line-color", minorColor)
                add("line-width", lineWidth(13 to 0.3, 16 to 0.6, 18 to 0.9))
            })
        })
        belowRoads.add(obj {
            addProperty("id", "terrain_contour_index")
            addProperty("type", "line")
            addProperty("source", TERRAIN_CONTOURS_SOURCE)
            addProperty("source-layer", "contour")
            add("filter", JsonParser.parseString("[\"==\",[\"get\",\"kind\"],\"index\"]"))
            addProperty("minzoom", 11.5)
            add("paint", obj {
                addProperty("line-color", indexColor)
                add("line-width", lineWidth(12 to 0.5, 15 to 0.9, 18 to 1.3))
            })
        })
    }
    val labels = JsonArray()
    if (terrain.contours != null) {
        labels.add(obj {
            addProperty("id", "terrain_contour_labels")
            addProperty("type", "symbol")
            addProperty("source", TERRAIN_CONTOURS_SOURCE)
            addProperty("source-layer", "contour")
            add("filter", JsonParser.parseString("[\"==\",[\"get\",\"kind\"],\"index\"]"))
            addProperty("minzoom", 13.5)
            add("layout", obj {
                addProperty("symbol-placement", "line")
                add("text-field", JsonParser.parseString("[\"to-string\",[\"get\",\"ele\"]]"))
                add("text-font", JsonParser.parseString("[\"Noto Sans Regular\"]"))
                addProperty("text-size", 10)
                addProperty("symbol-spacing", 400)
                addProperty("text-max-angle", 30)
            })
            add("paint", obj {
                addProperty("text-color", if (isNight) "#D8C09A" else "#7A5A38")
                addProperty("text-halo-color", if (isNight) "#141210" else "#FFFFFF")
                addProperty("text-halo-width", 1.5)
            })
        })
    }

    fun indexOfFirst(predicate: (String) -> Boolean): Int =
        layers.indexOfFirst { predicate(it.asJsonObject.get("id").asString) }

    val roadsAt = indexOfFirst { it.startsWith("roads_tunnels") }.takeIf { it >= 0 }
        ?: indexOfFirst { it == "buildings-2d" }.takeIf { it >= 0 } ?: layers.size()
    val labelsAt = indexOfFirst { it == "address_label" }.takeIf { it >= 0 } ?: layers.size()

    val result = JsonArray()
    layers.forEachIndexed { i, layer ->
        if (i == roadsAt) belowRoads.forEach { result.add(it) }
        if (i == labelsAt) labels.forEach { result.add(it) }
        result.add(layer)
    }
    if (roadsAt >= layers.size()) belowRoads.forEach { result.add(it) }
    if (labelsAt >= layers.size()) labels.forEach { result.add(it) }
    style.add("layers", result)
    return style.toString()
}
