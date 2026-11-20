package com.gorite.cyclemap.ui.layout

import com.gorite.cyclemap.utils.MapLayer
import org.junit.Assert.*
import org.junit.Test

class MapAttributionTest {
    @Test
    fun sourcesAreCreditedForEveryLayer() {
        MapLayer.entries.forEach { assertTrue(mapAttributionText(it).contains("OpenStreetMap contributors")) }
        listOf(MapLayer.GSI, MapLayer.TERRAIN, MapLayer.VECTOR_LIBERTY).forEach {
            assertTrue(mapAttributionText(it).contains("国土地理院"))
        }
        assertTrue(mapAttributionText(MapLayer.VECTOR_LIBERTY).contains("Protomaps"))
        assertFalse(mapAttributionText(MapLayer.OSM).contains("国土地理院"))
    }
}
