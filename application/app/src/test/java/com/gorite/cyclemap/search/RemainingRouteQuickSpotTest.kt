package com.gorite.cyclemap.search

import com.gorite.cyclemap.contract.NearbySpot
import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.routing.RouteProgress
import com.gorite.cyclemap.utils.haversineMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RemainingRouteQuickSpotTest {
    private fun progressAt(distanceFromStartMeters: Double, totalMeters: Double = 10_000.0) = RouteProgress(
        distanceFromStartMeters = distanceFromStartMeters,
        routeDistanceMeters = totalMeters,
        distanceToRouteMeters = 0.0,
        nextManeuver = null,
        distanceToNextManeuverMeters = null,
    )

    @Test
    fun returnsFullRouteWhenNotNavigatingOrNoProgress() {
        val route = listOf(
            RoutePoint(34.000, 132.000),
            RoutePoint(34.000, 132.010),
            RoutePoint(34.000, 132.020),
        )
        assertEquals(route, activeRemainingRoutePoints(false, route, progressAt(500.0)))
        assertEquals(route, remainingRoutePoints(route, progress = null))
        assertEquals(route, remainingRoutePoints(route, traveledMeters = 0.0))
        assertEquals(route, remainingRoutePoints(route, traveledMeters = Double.NaN))

        val single = listOf(RoutePoint(34.0, 132.0))
        assertEquals(single, remainingRoutePoints(single, traveledMeters = 100.0))
    }

    @Test
    fun slicesRouteAtInterpolatedProgressAndDropsPassedSegments() {
        val p0 = RoutePoint(34.000, 132.000)
        val p1 = RoutePoint(34.000, 132.010)
        val p2 = RoutePoint(34.000, 132.020)
        val seg1Len = haversineMeters(p0.latitude, p0.longitude, p1.latitude, p1.longitude)
        val seg2Len = haversineMeters(p1.latitude, p1.longitude, p2.latitude, p2.longitude)

        // 1区間目の途中 (50%) で分割される
        val midFirst = remainingRoutePoints(listOf(p0, p1, p2), seg1Len * 0.5)
        assertEquals(3, midFirst.size)
        assertEquals(132.005, midFirst[0].longitude, 1e-4)
        assertEquals(p1, midFirst[1])
        assertEquals(p2, midFirst[2])

        // 2区間目の途中まで進んだら p0, p1 は落ちる
        val midSecond = remainingRoutePoints(listOf(p0, p1, p2), seg1Len + seg2Len * 0.5)
        assertEquals(2, midSecond.size)
        assertEquals(132.015, midSecond[0].longitude, 1e-4)
        assertEquals(p2, midSecond[1])

        // 終端以降は終点のみ
        val atEnd = remainingRoutePoints(listOf(p0, p1, p2), seg1Len + seg2Len + 50.0)
        assertEquals(listOf(p2), atEnd)
    }

    @Test
    fun enumeratesPrefecturesAlongRouteWithoutDuplicates() {
        val yamaguchi = RoutePoint(34.185, 131.471)
        val hiroshima = RoutePoint(34.396, 132.459)
        val okayama = RoutePoint(34.661, 133.935)

        val prefs = prefecturesAlongRoute(listOf(yamaguchi, hiroshima, hiroshima, okayama))
        assertEquals(listOf("山口県", "広島県", "岡山県"), prefs)
    }

    @Test
    fun samplesLongDenseRouteAndKeepsEndpoints() {
        // 150点以上の経路でも始点・中間・終点の県を拾える
        val points = (0..150).map { i ->
            RoutePoint(34.0 + i * 0.002, 131.5 + i * 0.01)
        }
        val prefs = prefecturesAlongRoute(
            routePoints = points,
            prefectureLookup = { _, lon ->
                when {
                    lon < 132.0 -> "山口県"
                    lon < 132.7 -> "広島県"
                    else -> "岡山県"
                }
            },
            sampleIntervalMeters = 500.0,
        )
        assertEquals(listOf("山口県", "広島県", "岡山県"), prefs)
    }

    @Test
    fun selectsCurrentAndRemainingRouteDatabasesIncludingNonAdjacentAndLegacyDb() {
        val dir = Files.createTempDirectory("quickspot-route-dbs").toFile()
        try {
            File(dir, "search.db").writeText("")
            File(dir, "Hiroshima.search.db").writeText("")
            File(dir, "Okayama.search.db").writeText("")
            File(dir, "Shimane.search.db").writeText("") // 隣接県だが残りの経路は通らない

            val yamaguchiCenter = RoutePoint(34.185, 131.471)
            val remainingRoute = listOf(
                RoutePoint(34.250, 132.050), // 山口県側
                RoutePoint(34.396, 132.459), // 広島県
                RoutePoint(34.661, 133.935), // 岡山県 (山口と非隣接)
            )

            val selected = selectRouteQuickSpotDatabases(
                dataDir = dir,
                centerLat = yamaguchiCenter.latitude,
                centerLon = yamaguchiCenter.longitude,
                remainingRoute = remainingRoute,
            )
            assertEquals(
                listOf("山口県", "広島県", "岡山県"),
                selected.map { it.prefectureName },
            )
            assertTrue(selected.none { it.prefectureName == "島根県" })
            assertEquals("search.db", selected.first().file.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun selectsRemainingPrefectureDatabaseEvenWhenCurrentPrefectureDbIsMissing() {
        val dir = Files.createTempDirectory("quickspot-missing-current").toFile()
        try {
            File(dir, "Hiroshima.search.db").writeText("")
            val selected = selectRouteQuickSpotDatabases(
                dataDir = dir,
                centerLat = 34.185,
                centerLon = 131.471, // 山口県 (DB なし)
                remainingRoute = listOf(RoutePoint(34.396, 132.459)), // 広島県 (DB あり)
            )
            assertEquals(listOf("広島県"), selected.map { it.prefectureName })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mergesDeduplicatesPrioritizesRouteAndRespectsLimit() {
        val remaining = listOf(
            RoutePoint(34.000, 132.020),
            RoutePoint(34.000, 132.080),
        )
        // 県境付近で両方の DB に重複収録されたスポット (住所あり側を残す)
        val db1OnRoute = NearbySpot("コンビニA", "shop:convenience", 34.0001, 132.040, 2000.0, address = null)
        val db2OnRouteDup = NearbySpot("コンビニA", "shop:convenience", 34.00012, 132.04002, 2002.0, address = "広島県廿日市市")
        // ルートから外れた多数のスポット (現在地からは近いが残りルートからは遠い)
        val offRouteMany = (1..30).map { i ->
            NearbySpot("後方スポット$i", "shop:convenience", 34.020 + i * 0.0001, 132.000, 200.0 + i)
        }

        val merged = mergeQuickSpotResults(
            spotGroups = listOf(listOf(db1OnRoute) + offRouteMany, listOf(db2OnRouteDup)),
            remainingRoute = remaining,
            limit = 30,
        )
        assertEquals(30, merged.size)
        assertEquals("コンビニA", merged.first().name)
        assertEquals("広島県廿日市市", merged.first().address)
        assertEquals(1, merged.count { it.name == "コンビニA" })
    }

    @Test
    fun routeSearchCentersStartAtCurrentPositionAndStepAlongTheRoute() {
        // 東へ約 50km (経度 0.55° ≒ 50km @34°N)
        val route = listOf(RoutePoint(34.0, 132.0), RoutePoint(34.0, 132.30), RoutePoint(34.0, 132.55))
        val centers = routeSearchCenters(route, stepMeters = 12_000.0, maxAheadMeters = 48_000.0)
        assertEquals(route.first(), centers.first())
        assertEquals(5, centers.size) // 0, 12, 24, 36, 48 km
        assertTrue(centers.zipWithNext().all { (a, b) -> b.longitude > a.longitude })
        assertTrue(routeSearchCenters(emptyList()).isEmpty())
        assertEquals(1, routeSearchCenters(listOf(RoutePoint(34.0, 132.0), RoutePoint(34.0, 132.01))).size)
    }
}
