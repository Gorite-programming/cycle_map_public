package com.gorite.cyclemap.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossRoutePlanningTest {
    @Test fun plansShortestAvailableChainsAndOrdersByCenterDistance() {
        val centers = mapOf(
            "山口県" to CrossGeoPoint(34.0, 131.0), "広島県" to CrossGeoPoint(34.4, 132.5),
            "島根県" to CrossGeoPoint(35.0, 132.5), "岡山県" to CrossGeoPoint(34.7, 133.9),
        )
        val candidates = planPrefectureChains("山口県", "岡山県", centers.keys, centers)
        assertEquals(listOf("山口県", "広島県", "岡山県"), candidates.first())
        assertTrue(candidates.all { it.size == 3 })
        assertTrue(planPrefectureChains("山口県", "岡山県", setOf("山口県", "岡山県"), centers).isEmpty())
    }

    @Test fun classifiesLegAndMergesThreeRoutePartsByIdOrNearCoordinate() {
        assertEquals(CrossLegKind.SAME, classifyLeg("広島県", "広島県"))
        assertEquals(CrossLegKind.CROSS, classifyLeg("広島県", "岡山県"))
        assertEquals(CrossLegKind.UNKNOWN, classifyLeg(null, "岡山県"))
        val merged = mergeRoutePathParts(listOf(
            CrossRoutePathPart(listOf(1, 2), listOf(0.0 to 0.0, 0.0 to 0.01), 100.0, 90.0),
            CrossRoutePathPart(listOf(99, 3), listOf(0.0 to 0.010001, 0.0 to 0.02), 120.0, 110.0),
            CrossRoutePathPart(listOf(3, 4), listOf(0.0 to 0.02, 0.0 to 0.03), 80.0, 70.0),
        ))
        assertEquals(listOf(1L, 2L, 3L, 4L), merged.nodeIds)
        assertEquals(300.0, merged.distanceMeters, 0.0)
        assertEquals(270.0, merged.cost, 0.0)
    }

    @Test fun plansFourPrefectureChainFromYamaguchiToHyogoOverAllPrefectures() {
        val centers = com.gorite.cyclemap.data.PrefectureData.ALL.associate { pref ->
            val b = pref.bounds
            pref.name to CrossGeoPoint((b.latNorth + b.latSouth) / 2.0, (b.lonEast + b.lonWest) / 2.0)
        }
        val available = setOf("山口県", "広島県", "岡山県", "兵庫県", "島根県", "鳥取県")
        val chains = planPrefectureChains("山口県", "兵庫県", available, centers)
        assertEquals(listOf("山口県", "広島県", "岡山県", "兵庫県"), chains.first())
        // 途中の県にデータが無ければ (広島県なし) 島根・鳥取経由に回る
        val viaSanin = planPrefectureChains("山口県", "兵庫県", available - "広島県", centers)
        assertEquals(listOf("山口県", "島根県", "鳥取県", "兵庫県"), viaSanin.first())
    }
}
