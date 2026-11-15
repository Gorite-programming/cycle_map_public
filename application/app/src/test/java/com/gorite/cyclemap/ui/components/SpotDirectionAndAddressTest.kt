package com.gorite.cyclemap.ui.components

import com.gorite.cyclemap.contract.NearbySpot
import com.gorite.cyclemap.search.nearbySpotSelectColumns
import com.gorite.cyclemap.utils.compassLabel8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpotDirectionAndAddressTest {

    private val origin = 34.3965 to 132.4550

    @Test
    fun directionBearingDeg_returnsNullWhenOriginMissingOrInvalid() {
        assertNull(directionBearingDeg(null, 34.40, 132.46, 500.0))
        assertNull(directionBearingDeg(Double.NaN to 132.45, 34.40, 132.46, 500.0))
        assertNull(directionBearingDeg(origin, 34.40, Double.POSITIVE_INFINITY, 500.0))
    }

    @Test
    fun directionBearingDeg_returnsNullUnderThirtyMeters() {
        // 座標は離れていても表示距離が 30m 未満なら方角を出さない
        assertNull(directionBearingDeg(origin, 34.40, 132.46, distanceMeters = 29.9))
        // 座標間距離そのものが 30m 未満なら、distanceMeters 未指定でも方角を出さない
        assertNull(directionBearingDeg(origin, origin.first + 0.0001, origin.second))
        assertNull(directionBearingDeg(origin, origin.first, origin.second, distanceMeters = 100.0))
    }

    @Test
    fun directionBearingDeg_returnsCompassDirectionAtThirtyMetersOrMore() {
        // 北東方向 (~300m)
        val bearingNe = directionBearingDeg(origin, origin.first + 0.002, origin.second + 0.002, distanceMeters = 281.0)
        assertNotNull(bearingNe)
        assertEquals("北東", compassLabel8(bearingNe!!))

        // ちょうど 30m 以上 (北方向 ~1.1km)
        val bearingN = directionBearingDeg(origin, origin.first + 0.01, origin.second, distanceMeters = 30.0)
        assertNotNull(bearingN)
        assertEquals("北", compassLabel8(bearingN!!))

        // 南西方向
        val bearingSw = directionBearingDeg(origin, origin.first - 0.005, origin.second - 0.005)
        assertNotNull(bearingSw)
        assertEquals("南西", compassLabel8(bearingSw!!))
    }

    @Test
    fun formatShortDistance_formatsMetersAndKilometers() {
        assertEquals("0 m", formatShortDistance(-5.0))
        assertEquals("0 m", formatShortDistance(0.0))
        assertEquals("281 m", formatShortDistance(281.4))
        assertEquals("999 m", formatShortDistance(999.4))
        assertEquals("1.0 km", formatShortDistance(1000.0))
        assertEquals("1.3 km", formatShortDistance(1250.0))
    }

    @Test
    fun formatSpotSubtitle_prefersShortAddressAndFallsBackToCategory() {
        assertEquals(
            "広島市西区井口5丁目20-14",
            formatSpotSubtitle("広島県広島市西区井口5丁目20-14", "shop:convenience"),
        )
        assertEquals(
            "広島市中区袋町5-7",
            formatSpotSubtitle("広島市中区 中区袋町5-7", "amenity:cafe"),
        )
        assertEquals("コンビニ", formatSpotSubtitle(null, "shop:convenience"))
        assertEquals("コンビニ", formatSpotSubtitle("", "shop:convenience"))
        assertEquals("コンビニ", formatSpotSubtitle("   ", "shop:convenience"))
        assertEquals("コンビニ", formatSpotSubtitle("周辺エリア", "shop:convenience"))
        assertEquals("コンビニ", formatSpotSubtitle("〒730-0036", "shop:convenience"))
        assertEquals("トイレ", formatSpotSubtitle("7300036", "amenity:toilets"))
    }

    @Test
    fun nearbySpotSelectColumns_handlesModernAndLegacySchemas() {
        assertEquals(
            "name, category, lat, lon, address",
            nearbySpotSelectColumns(setOf("name", "category", "lat", "lon", "address")),
        )
        assertEquals(
            "name, category, lat, lon, NULL",
            nearbySpotSelectColumns(setOf("name", "category", "lat", "lon")),
        )
    }

    @Test
    fun nearbySpot_addressDefaultsToNull() {
        val withoutAddress = NearbySpot("ローソン", "shop:convenience", 34.39, 132.45, 281.0)
        assertNull(withoutAddress.address)

        val withAddress = NearbySpot("ローソン", "shop:convenience", 34.39, 132.45, 281.0, address = "広島市西区")
        assertEquals("広島市西区", withAddress.address)
    }
}
