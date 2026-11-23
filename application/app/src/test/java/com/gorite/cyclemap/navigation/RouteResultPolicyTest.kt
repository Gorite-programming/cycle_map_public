package com.gorite.cyclemap.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteResultPolicyTest {
    private val centers = mapOf(
        "山口県" to CrossGeoPoint(34.18, 131.47),
        "広島県" to CrossGeoPoint(34.40, 132.46),
        "岡山県" to CrossGeoPoint(34.66, 133.93),
        "島根県" to CrossGeoPoint(35.47, 133.05),
        "鳥取県" to CrossGeoPoint(35.50, 134.23),
    )
    private val allPrefs = setOf("山口県", "広島県", "岡山県", "島根県", "鳥取県")

    @Test
    fun findMissingPrefecturesIdentifiesDestinationAndIntermediatePrefectures() {
        assertEquals(
            listOf("広島県"),
            findMissingPrefecturesForRoute(
                stopPrefectures = listOf("山口県", "広島県"),
                availablePrefectures = setOf("山口県"),
                allPrefectures = allPrefs,
                centers = centers,
            ),
        )
        assertEquals(
            listOf("広島県"),
            findMissingPrefecturesForRoute(
                stopPrefectures = listOf("山口県", "岡山県"),
                availablePrefectures = setOf("山口県", "岡山県"),
                allPrefectures = allPrefs,
                centers = centers,
            ),
        )
        assertEquals(
            listOf("広島県", "岡山県"),
            findMissingPrefecturesForRoute(
                stopPrefectures = listOf("山口県", "広島県", "岡山県"),
                availablePrefectures = setOf("山口県"),
                allPrefectures = allPrefs,
                centers = centers,
            ),
        )
        assertEquals(
            emptyList<String>(),
            findMissingPrefecturesForRoute(
                stopPrefectures = listOf("山口県", "広島県", "岡山県"),
                availablePrefectures = setOf("山口県", "広島県", "岡山県"),
                allPrefectures = allPrefs,
                centers = centers,
            ),
        )
    }

    @Test
    fun classifyRouteCompletenessDistinguishesCompleteAndPartial() {
        assertEquals(
            RouteCompleteness.COMPLETE,
            classifyRouteCompleteness(needsCrossPlanning = true, usedCrossRoute = true, singleGraphReachable = false),
        )
        assertEquals(
            RouteCompleteness.COMPLETE,
            classifyRouteCompleteness(needsCrossPlanning = false, usedCrossRoute = false, singleGraphReachable = true),
        )
        assertEquals(
            RouteCompleteness.PARTIAL,
            classifyRouteCompleteness(needsCrossPlanning = true, usedCrossRoute = false, singleGraphReachable = true),
        )
        assertNull(
            classifyRouteCompleteness(needsCrossPlanning = true, usedCrossRoute = false, singleGraphReachable = false),
        )
        assertNull(
            classifyRouteCompleteness(needsCrossPlanning = false, usedCrossRoute = false, singleGraphReachable = false),
        )
    }

    @Test
    fun autoRerouteNeverFallsBackToSingleGraphWhenCrossPlanningFails() {
        assertFalse(
            shouldAttemptSingleGraphFallback(
                isReroute = true,
                usedCrossRoute = false,
                crossOutcome = CrossPlanningOutcome.MissingPrefectureData(listOf("広島県")),
                needsCrossPlanning = true,
            ),
        )
        assertFalse(
            shouldAttemptSingleGraphFallback(
                isReroute = true,
                usedCrossRoute = false,
                crossOutcome = CrossPlanningOutcome.Failed,
                needsCrossPlanning = true,
            ),
        )
        assertTrue(
            shouldAttemptSingleGraphFallback(
                isReroute = false,
                usedCrossRoute = false,
                crossOutcome = CrossPlanningOutcome.MissingPrefectureData(listOf("広島県")),
                needsCrossPlanning = true,
            ),
        )
        assertFalse(
            shouldAttemptSingleGraphFallback(
                isReroute = false,
                usedCrossRoute = false,
                crossOutcome = CrossPlanningOutcome.WaypointUnreachable(1),
                needsCrossPlanning = true,
            ),
        )
        assertTrue(
            shouldAttemptSingleGraphFallback(
                isReroute = true,
                usedCrossRoute = false,
                crossOutcome = CrossPlanningOutcome.NotNeeded,
                needsCrossPlanning = false,
            ),
        )
    }

    @Test
    fun autoRerouteKeepsExistingGuidanceAndShowsMissingPrefectureWhenIncomplete() {
        val partialDecision = decideRouteApplication(
            isReroute = true,
            completeness = RouteCompleteness.PARTIAL,
            crossOutcome = CrossPlanningOutcome.MissingPrefectureData(listOf("広島県")),
        )
        assertFalse(partialDecision.shouldReplaceRoute)
        assertEquals("隣の県のデータが無いため、再探索できません (広島県)", partialDecision.warningMessage)

        val unreachableDecision = decideRouteApplication(
            isReroute = true,
            completeness = null,
            crossOutcome = CrossPlanningOutcome.MissingPrefectureData(listOf("広島県")),
        )
        assertFalse(unreachableDecision.shouldReplaceRoute)
        assertEquals("隣の県のデータが無いため、再探索できません (広島県)", unreachableDecision.warningMessage)

        val failedWithoutMissingDecision = decideRouteApplication(
            isReroute = true,
            completeness = RouteCompleteness.PARTIAL,
            crossOutcome = CrossPlanningOutcome.Failed,
        )
        assertFalse(failedWithoutMissingDecision.shouldReplaceRoute)
        assertEquals("再探索で有効なルートが見つかりませんでした。元のルートを維持します", failedWithoutMissingDecision.warningMessage)
    }

    @Test
    fun manualRouteShowsWarningWithMissingPrefectureWhenReturningPartialRoute() {
        val missingPrefDecision = decideRouteApplication(
            isReroute = false,
            completeness = RouteCompleteness.PARTIAL,
            crossOutcome = CrossPlanningOutcome.MissingPrefectureData(listOf("広島県")),
        )
        assertTrue(missingPrefDecision.shouldReplaceRoute)
        assertEquals(RouteCompleteness.PARTIAL, missingPrefDecision.completeness)
        assertTrue(missingPrefDecision.warningMessage!!.contains("広島県"))

        val genericPartialDecision = decideRouteApplication(
            isReroute = false,
            completeness = RouteCompleteness.PARTIAL,
            crossOutcome = CrossPlanningOutcome.Failed,
        )
        assertTrue(genericPartialDecision.shouldReplaceRoute)
        assertEquals(CROSS_PREFECTURE_UNSUPPORTED_MESSAGE, genericPartialDecision.warningMessage)
    }

    @Test
    fun completeCrossAndSamePrefectureRoutesReplaceWithoutUnexpectedWarnings() {
        val crossComplete = decideRouteApplication(
            isReroute = true,
            completeness = RouteCompleteness.COMPLETE,
            crossOutcome = CrossPlanningOutcome.Succeeded,
        )
        assertTrue(crossComplete.shouldReplaceRoute)
        assertEquals(CROSS_PREFECTURE_ROUTE_MESSAGE, crossComplete.warningMessage)

        val sameComplete = decideRouteApplication(
            isReroute = true,
            completeness = RouteCompleteness.COMPLETE,
            crossOutcome = CrossPlanningOutcome.NotNeeded,
        )
        assertTrue(sameComplete.shouldReplaceRoute)
        assertNull(sameComplete.warningMessage)
    }

    @Test
    fun truncatedCrossPlanningSkipsFallbackAndKeepsTheExistingRoute() {
        for (isReroute in listOf(false, true)) {
            assertFalse(
                shouldAttemptSingleGraphFallback(
                    isReroute = isReroute,
                    usedCrossRoute = false,
                    crossOutcome = CrossPlanningOutcome.Truncated,
                    needsCrossPlanning = true,
                ),
            )
            val decision = decideRouteApplication(
                isReroute = isReroute,
                completeness = RouteCompleteness.PARTIAL,
                crossOutcome = CrossPlanningOutcome.Truncated,
            )
            assertFalse(decision.shouldReplaceRoute)
            assertNull(decision.completeness)
            assertEquals("探索範囲が大きすぎて打ち切りました。近めの目的地でお試しください", decision.warningMessage)
        }
    }

    @Test
    fun waypointFailureAlwaysPreservesRouteAndNamesFailedWaypoint() {
        val crossWp = decideRouteApplication(
            isReroute = false,
            completeness = null,
            crossOutcome = CrossPlanningOutcome.WaypointUnreachable(2),
        )
        assertFalse(crossWp.shouldReplaceRoute)
        assertEquals("経由地 2 に寄れません", crossWp.warningMessage)

        val singleWp = decideRouteApplication(
            isReroute = true,
            completeness = null,
            crossOutcome = CrossPlanningOutcome.NotNeeded,
            singleGraphWaypointFailure = 1,
        )
        assertFalse(singleWp.shouldReplaceRoute)
        assertEquals("経由地 1 に寄れません", singleWp.warningMessage)
    }
}
