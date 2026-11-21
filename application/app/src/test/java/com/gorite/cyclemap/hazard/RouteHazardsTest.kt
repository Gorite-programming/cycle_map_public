package com.gorite.cyclemap.hazard

import com.gorite.cyclemap.routing.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class RouteHazardsTest {

    private val baseLat = 34.0
    private val baseLon = 132.0

    private fun pointAt(eastMeters: Double, northMeters: Double = 0.0): RoutePoint =
        RoutePoint(offsetLat(baseLat, northMeters), offsetLon(baseLat, baseLon, eastMeters))

    private fun coordAt(eastMeters: Double, northMeters: Double = 0.0): Pair<Double, Double> =
        offsetLat(baseLat, northMeters) to offsetLon(baseLat, baseLon, eastMeters)

    private fun indexOf(
        crossings: List<Pair<Double, Double>> = emptyList(),
        bridges: List<BridgeSpec> = emptyList(),
    ): HazardIndex {
        val bytes = HazardTestWriter.buildBytes(crossings, bridges)
        val data = HazardFile.parse(bytes)!!
        return HazardIndex.build(data)
    }

    @Test
    fun findRouteHazards_crossingDistanceThreshold() {
        val route = listOf(pointAt(0.0), pointAt(300.0))
        val index = indexOf(
            crossings = listOf(
                coordAt(100.0, 5.0),  // 5 m 離れ -> 採用
                coordAt(200.0, 30.0), // 30 m 離れ -> 不採用
            ),
        )

        val result = findRouteHazards(route, index)
        assertEquals(1, result.size)
        assertEquals(HazardKind.CROSSING, result[0].kind)
        assertNull(result[0].name)
        assertEquals(100.0, result[0].startMeters, 2.0)
        assertEquals(result[0].startMeters, result[0].endMeters, 1e-6)
    }

    @Test
    fun findRouteHazards_bridgeMatchingAndUnderpassRejection() {
        val route = listOf(pointAt(0.0), pointAt(600.0))
        val index = indexOf(
            bridges = listOf(
                // 経路と重なる橋 (頂点 3 点が 4 m 以内、重なり 80 m)
                BridgeSpec(
                    name = "錦帯橋",
                    points = listOf(
                        coordAt(200.0, 4.0),
                        coordAt(240.0, 3.0),
                        coordAt(280.0, 4.0),
                    ),
                ),
                // 頂点順が進行方向と逆向きの橋でも始点 < 終点で正しく採択される
                BridgeSpec(
                    name = "逆向き橋",
                    points = listOf(
                        coordAt(560.0, 3.0),
                        coordAt(520.0, 3.0),
                    ),
                ),
                // 経路と直交して跨ぐだけの橋 (橋の下を通るケース: 経路上の頂点 1 点のみ近い)
                BridgeSpec(
                    name = "立体交差の橋",
                    points = listOf(
                        coordAt(380.0, -50.0),
                        coordAt(380.0, 0.0),
                        coordAt(380.0, 50.0),
                    ),
                ),
                // 2 頂点とも 5 m 以内だが重なりが 15 m (< 20 m) なので不採用
                BridgeSpec(
                    name = "短い重なり橋",
                    points = listOf(
                        coordAt(420.0, 2.0),
                        coordAt(435.0, 2.0),
                    ),
                ),
            ),
        )

        val result = findRouteHazards(route, index)
        assertEquals(2, result.size)
        val first = result[0]
        assertEquals(HazardKind.BRIDGE, first.kind)
        assertEquals("錦帯橋", first.name)
        assertEquals(200.0, first.startMeters, 2.0)
        assertEquals(280.0, first.endMeters, 2.0)

        val second = result[1]
        assertEquals(HazardKind.BRIDGE, second.kind)
        assertEquals("逆向き橋", second.name)
        assertEquals(520.0, second.startMeters, 2.0)
        assertEquals(560.0, second.endMeters, 2.0)
    }

    @Test
    fun findRouteHazards_mergesDoubleTrackCrossingsAndSplitBridgesWithin40Meters() {
        val route = listOf(pointAt(0.0), pointAt(1000.0))
        val index = indexOf(
            crossings = listOf(
                // 複線 (20 m 離れ) -> 1 つにまとまる
                coordAt(100.0, 0.0),
                coordAt(120.0, 0.0),
                // 50 m 離れた次の踏切 -> 別になる
                coordAt(170.0, 0.0),
            ),
            bridges = listOf(
                // 30 m 空けて分かれた橋 (後続片のみ名前つき) -> 1 つにまとまり名前引き継ぎ
                BridgeSpec(null, listOf(coordAt(400.0, 0.0), coordAt(450.0, 0.0))),
                BridgeSpec("分割橋", listOf(coordAt(480.0, 0.0), coordAt(550.0, 0.0))),
            ),
        )

        val result = findRouteHazards(route, index)
        assertEquals(3, result.size)

        // 昇順であることを確認
        assertTrue(result[0].startMeters < result[1].startMeters)
        assertTrue(result[1].startMeters < result[2].startMeters)

        assertEquals(HazardKind.CROSSING, result[0].kind)
        assertEquals(100.0, result[0].startMeters, 2.0)
        assertEquals(120.0, result[0].endMeters, 2.0)

        assertEquals(HazardKind.CROSSING, result[1].kind)
        assertEquals(170.0, result[1].startMeters, 2.0)

        assertEquals(HazardKind.BRIDGE, result[2].kind)
        assertEquals("分割橋", result[2].name)
        assertEquals(400.0, result[2].startMeters, 2.0)
        assertEquals(550.0, result[2].endMeters, 2.0)
    }

    @Test
    fun findRouteHazards_emptyAndSinglePointRoutesReturnEmpty() {
        val index = indexOf(crossings = listOf(coordAt(0.0, 0.0)))
        assertTrue(findRouteHazards(emptyList(), index).isEmpty())
        assertTrue(findRouteHazards(listOf(pointAt(0.0)), index).isEmpty())
    }

    @Test
    fun findRouteHazards_largeRouteAndLargeIndexCompletesWithoutError() {
        val routePointCount = 50_000
        val bridgeCount = 100_000

        // 5 万点の経路 (約 90 km)
        val route = ArrayList<RoutePoint>(routePointCount)
        val routeLat = 34.50
        val startLon = 132.00
        for (i in 0 until routePointCount) {
            route.add(RoutePoint(routeLat, startLon + i * 0.00002))
        }

        // 10 万件の橋 (大部分は別緯度、一部を経路近傍に配置、1 本だけ経路上に配置)
        val bridges = ArrayList<BridgeHazard>(bridgeCount)
        for (i in 0 until bridgeCount - 101) {
            val lat = 33.0 + (i / 1000) * 0.01
            val lon = 132.0 + (i % 1000) * 0.002
            bridges.add(
                BridgeHazard(
                    name = null,
                    latitudes = doubleArrayOf(lat, lat),
                    longitudes = doubleArrayOf(lon, lon + 0.0005),
                ),
            )
        }
        for (i in 0 until 100) {
            val lon = startLon + i * 0.005
            bridges.add(
                BridgeHazard(
                    name = "近傍非一致橋$i",
                    latitudes = doubleArrayOf(routeLat + 0.0005, routeLat + 0.0005),
                    longitudes = doubleArrayOf(lon, lon + 0.001),
                ),
            )
        }
        val targetStartLon = startLon + 25_000 * 0.00002
        bridges.add(
            BridgeHazard(
                name = "大規模検証橋",
                latitudes = doubleArrayOf(routeLat, routeLat),
                longitudes = doubleArrayOf(targetStartLon, targetStartLon + 0.001),
            ),
        )

        val index = HazardIndex.build(HazardData(DoubleArray(0), DoubleArray(0), bridges))
        val result = findRouteHazards(route, index)

        assertEquals(1, result.size)
        assertEquals(HazardKind.BRIDGE, result[0].kind)
        assertEquals("大規模検証橋", result[0].name)
        assertTrue(result[0].endMeters - result[0].startMeters > 50.0)
    }

    private fun offsetLat(baseLat: Double, northMeters: Double): Double =
        baseLat + northMeters / METERS_PER_DEG

    private fun offsetLon(baseLat: Double, baseLon: Double, eastMeters: Double): Double =
        baseLon + eastMeters / (METERS_PER_DEG * cos(Math.toRadians(baseLat)))

    private companion object {
        const val METERS_PER_DEG = 6_371_000.0 * Math.PI / 180.0
    }
}
