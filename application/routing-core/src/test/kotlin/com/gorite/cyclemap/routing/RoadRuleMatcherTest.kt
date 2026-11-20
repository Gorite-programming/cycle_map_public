package com.gorite.cyclemap.routing

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.test.*

class RoadRuleMatcherTest {
    private val allowed = classifyBicycleRoad(mapOf("highway" to "residential"), false)
    private val forbidden = allowed.copy(roadway = BikeAccess.FORBIDDEN)
    private val metersPerDegree = Math.PI * 6_371_000.0 / 180.0

    // 赤道付近の南北の線分。offset は東西方向の距離 (m)。
    private fun road(ordinal: Int = 1, offset: Double = 0.0, info: BicycleRoadInfo? = allowed): RoadCandidate =
        RoadCandidate(ordinal, -.001, offset / metersPerDegree, .001, offset / metersPerDegree, info)

    private fun reverse(road: RoadCandidate) = RoadCandidate(
        road.ordinal + 1, road.toLat, road.toLon, road.fromLat, road.fromLon, road.info,
    )

    private fun update(
        matcher: RoadRuleMatcher,
        candidates: List<RoadCandidate>,
        time: Long = 0,
        accuracy: Float = 5f,
        speed: Float? = 4f,
        bearing: Float? = 0f,
        lon: Double = 0.0,
        lat: Double = 0.0,
    ) = matcher.update(time, lat, lon, accuracy, speed, bearing, candidates)

    private fun confirm(matcher: RoadRuleMatcher, candidates: List<RoadCandidate>, bearing: Float = 0f): RoadRuleMatch {
        assertNull(update(matcher, candidates, bearing = bearing))
        assertNull(update(matcher, candidates, time = 1_000, bearing = bearing))
        return assertNotNull(update(matcher, candidates, time = 2_000, bearing = bearing))
    }

    @Test fun choosesDirectionOnThirdUpdateAndIgnoresReverseRuleDifferences() {
        val forward = road()
        val reverse = reverse(forward).copy(info = forbidden)
        val match = confirm(RoadRuleMatcher(), listOf(reverse, forward))
        assertEquals(forward.ordinal, match.ordinal)
        assertFalse(match.wrongWay)
        assertEquals(0.0, match.distanceMeters, 1e-6)
        assertNull(match.kind)
    }

    @Test fun detectsWrongWayAndMotorOnly() {
        val oneWay = road(info = allowed.copy(flags = BicycleRoadInfo.FLAG_ONEWAY))
        val wrong = confirm(RoadRuleMatcher(), listOf(oneWay), 180f)
        assertTrue(wrong.wrongWay)
        assertEquals(RoadRuleKind.WRONG_WAY, wrong.kind)
        assertFalse(confirm(RoadRuleMatcher(), listOf(oneWay)).wrongWay)
        val motorOnly = road(info = forbidden.copy(flags = BicycleRoadInfo.FLAG_MOTORROAD))
        assertEquals(RoadRuleKind.MOTOR_ONLY, confirm(RoadRuleMatcher(), listOf(motorOnly)).kind)
    }

    @Test fun rejectsParallelRoadsWithDifferentRulesButAllowsSameRules() {
        val near = road()
        val other = road(3, 3.0, forbidden)
        val matcher = RoadRuleMatcher()
        repeat(5) { assertNull(update(matcher, listOf(near, other), it * 1_000L)) }
        assertEquals(near.ordinal, confirm(RoadRuleMatcher(), listOf(near, road(3, 11.0, forbidden))).ordinal)
        assertEquals(near.ordinal, confirm(RoadRuleMatcher(), listOf(near, other.copy(info = allowed))).ordinal)
        // 同じルールの候補が2位でも、僅差に異なるルールがあれば確定しない。
        repeat(3) { assertNull(update(matcher, listOf(near, road(4, 1.0), other))) }
    }

    @Test fun invalidInputsHoldForFourSecondsAndExpireAtFiveSeconds() {
        for ((accuracy, speed, bearing) in listOf(
            Triple(30f, 4f, 0f), Triple(5f, .5f, 0f), Triple(5f, 4f, null), Triple(5f, null, 0f),
            Triple(Float.NaN, 4f, 0f), Triple(5f, Float.NaN, 0f), Triple(5f, 4f, Float.NaN),
        )) {
            val matcher = RoadRuleMatcher()
            val candidates = listOf(road())
            val match = confirm(matcher, candidates)
            for (time in listOf(3_000L, 7_000L)) {
                assertEquals(match, update(matcher, candidates, time, accuracy, speed, bearing))
            }
            assertNull(update(matcher, candidates, 8_000, accuracy, speed, bearing))
            assertNull(update(matcher, candidates, 9_000))
            assertNull(update(matcher, candidates, 10_000))
            assertNotNull(update(matcher, candidates, 11_000))
        }
    }

    @Test fun switchesOnlyAfterThreeConsecutiveUpdatesAndResetForgetsEverything() {
        val matcher = RoadRuleMatcher()
        val first = road()
        val next = road(3, 1.0, forbidden)
        val match = confirm(matcher, listOf(first))
        assertEquals(match, update(matcher, listOf(next), 3_000))
        assertEquals(match, update(matcher, listOf(next), 4_000))
        assertEquals(next.ordinal, assertNotNull(update(matcher, listOf(next), 5_000)).ordinal)
        matcher.reset()
        assertNull(update(matcher, listOf(next), 6_000))
        assertNull(update(matcher, listOf(next), 7_000))
        assertNotNull(update(matcher, listOf(next), 8_000))
    }

    @Test fun ambiguityAndMissingCandidatesWithdrawConfirmationAndBreakStreak() {
        for (uncertain in listOf(emptyList(), listOf(road(), road(3, 3.0, forbidden)))) {
            val matcher = RoadRuleMatcher()
            val candidates = listOf(road())
            confirm(matcher, candidates)
            assertNull(update(matcher, uncertain, 3_000))
            assertNull(update(matcher, candidates, 4_000))
            assertNull(update(matcher, candidates, 5_000))
            assertNotNull(update(matcher, candidates, 6_000))
        }
    }

    @Test fun directionChangesDoNotChangeTheStableSegment() {
        val matcher = RoadRuleMatcher()
        val forward = road()
        val reverse = reverse(forward)
        val candidates = listOf(forward, reverse)
        assertNull(update(matcher, candidates))
        assertNull(update(matcher, candidates, 1_000, bearing = 180f))
        assertEquals(forward.ordinal, assertNotNull(update(matcher, candidates, 2_000)).ordinal)
        assertEquals(reverse.ordinal, assertNotNull(update(matcher, candidates, 3_000, bearing = 180f)).ordinal)
    }

    @Test fun usesSegmentDistanceAndAccuracyCutoffAndSkipsMissingOrDegenerateRules() {
        assertEquals(3.0, confirm(RoadRuleMatcher(), listOf(road(offset = 3.0))).distanceMeters, 1e-6)
        val matcher = RoadRuleMatcher()
        repeat(3) {
            assertNull(update(matcher, listOf(road(offset = 16.0), road(info = null), road().copy(toLat = -.001))))
        }
        repeat(2) { assertNull(update(matcher, listOf(road(offset = 25.0)), accuracy = 20f)) }
        assertEquals(25.0, assertNotNull(update(matcher, listOf(road(offset = 25.0)), accuracy = 20f)).distanceMeters, 1e-6)
        // 線分の延長上でも、端点から離れていれば候補にならない。
        repeat(3) { assertNull(update(matcher, listOf(road()), lat = .002)) }
    }

    @Test fun outgoingBranchesExposeBikeInfoAndCandidateCoordinates() {
        val file = File.createTempFile("road-rule-matcher", ".graph").apply { deleteOnExit() }
        val index = File(file.path + ".idx").apply { deleteOnExit() }
        val bytes = ByteArrayOutputStream()
        val starts = LongArray(3) { -1 }
        // 通常の道の符号化が0になるため、設備のあるルールを索引に入れる。
        val info = allowed.copy(flags = BicycleRoadInfo.FLAG_ONEWAY)
        DataOutputStream(bytes).use { out ->
            out.writeUTF("CYCLEMAP_GRAPH_V1")
            out.writeInt(3)
            repeat(3) { out.writeLong(10L + it); out.writeDouble(0.0); out.writeDouble(it * .0001) }
            out.writeInt(3)
            listOf(0 to 1, 0 to 2, 1 to 0).forEach { (from, to) ->
                if (starts[from] < 0) starts[from] = bytes.size().toLong()
                out.writeLong(10L + from); out.writeLong(10L + to); out.writeDouble(10.0)
                out.writeUTF("residential"); out.writeBoolean(false); out.writeBoolean(false)
            }
        }
        file.writeBytes(bytes.toByteArray())
        DataOutputStream(index.outputStream()).use { out ->
            out.writeUTF("CYCLEMAP_INDEX_V2"); out.writeInt(3)
            starts.forEach(out::writeLong)
            intArrayOf(0, 2, -1).forEach(out::writeInt)
            intArrayOf(2, 1, 0).forEach(out::writeInt)
            out.writeInt(3); intArrayOf(1, 2, 0).forEach(out::writeInt)
            out.writeUTF("CYCLEMAP_ELEVATION_V1"); out.writeInt(3)
            repeat(3) { out.writeShort(0) }
            for (magic in listOf("CYCLEMAP_ROADNAME_V1", "CYCLEMAP_TURNLANES_V1")) {
                out.writeUTF(magic); out.writeInt(0); out.writeInt(3)
                repeat(3) { out.writeInt(-1) }
            }
            out.writeUTF("CYCLEMAP_BIKEINFO_V1"); out.writeInt(3)
            intArrayOf(info.encode(), 0, info.encode()).forEach(out::writeInt)
        }
        LazyMappedRoadGraph.load(file, index).use { graph ->
            assertEquals(listOf(0, 1), graph.outgoingBranches(0).map { it.ordinal })
            assertEquals(info, graph.outgoingBranches(0)[0].bicycleInfo)
            assertNull(graph.outgoingBranches(0)[1].bicycleInfo)
            assertEquals(2, graph.outgoingBranches(1).single().ordinal)
            assertEquals(info, graph.outgoingBranches(1).single().bicycleInfo)
            val candidates = graph.roadCandidatesNear(0.0, 0.0)
            assertEquals(setOf(0, 1, 2), candidates.map { it.ordinal }.toSet())
            assertEquals(RoadCandidate(0, 0.0, 0.0, 0.0, .0001, info), candidates.single { it.ordinal == 0 })
            assertEquals(RoadCandidate(1, 0.0, 0.0, 0.0, .0002, null), candidates.single { it.ordinal == 1 })
            assertEquals(listOf(0, 1), graph.roadCandidatesNear(0.0, 0.0, maxNodes = 1).map { it.ordinal })
            assertEquals(setOf(0, 2), graph.roadCandidatesNear(0.0, 0.0, includeOrdinary = false).map { it.ordinal }.toSet())
            assertEquals(listOf(0), graph.roadCandidatesNear(0.0, 0.0, maxNodes = 1, includeOrdinary = false).map { it.ordinal })
        }
    }

    @Test fun parallelOrdinarySideRoadSuppressesFalseMotorOnlyWarningWhileBypassStillWarns() {
        val motorOnly = classifyBicycleRoad(mapOf("highway" to "trunk", "motorroad" to "yes"), false)

        fun buildTwoWaySideRoadGraph(includeBikeInfo: Boolean): Pair<File, File> {
            val file = File.createTempFile("side-road", ".graph").apply { deleteOnExit() }
            val index = File(file.path + ".idx").apply { deleteOnExit() }
            val bytes = ByteArrayOutputStream()
            val starts = LongArray(2) { -1 }
            DataOutputStream(bytes).use { out ->
                out.writeUTF("CYCLEMAP_GRAPH_V1")
                out.writeInt(2)
                out.writeLong(1L); out.writeDouble(-.001); out.writeDouble(0.0)
                out.writeLong(2L); out.writeDouble(.001); out.writeDouble(0.0)
                out.writeInt(2)
                listOf(0 to 1, 1 to 0).forEach { (from, to) ->
                    starts[from] = bytes.size().toLong()
                    out.writeLong(1L + from); out.writeLong(1L + to); out.writeDouble(222.0)
                    out.writeUTF("residential"); out.writeBoolean(false); out.writeBoolean(false)
                }
            }
            file.writeBytes(bytes.toByteArray())
            DataOutputStream(index.outputStream()).use { out ->
                out.writeUTF("CYCLEMAP_INDEX_V2"); out.writeInt(2)
                starts.forEach(out::writeLong)
                intArrayOf(0, 1).forEach(out::writeInt)
                intArrayOf(1, 1).forEach(out::writeInt)
                out.writeInt(2); intArrayOf(1, 0).forEach(out::writeInt)
                if (includeBikeInfo) {
                    out.writeUTF("CYCLEMAP_BIKEINFO_V1"); out.writeInt(2)
                    intArrayOf(0, 0).forEach(out::writeInt)
                }
            }
            return file to index
        }

        for (includeBikeInfo in listOf(true, false)) {
            val (graphFile, indexFile) = buildTwoWaySideRoadGraph(includeBikeInfo)
            LazyMappedRoadGraph.load(graphFile, indexFile).use { graph ->
                assertNull(graph.bicycleInfoOfEdge(0))
                assertNull(graph.bicycleInfoOfEdge(1))

                // 1) 僅差 (3m) で並走する自動車専用バイパス: 側道上でもGPSがバイパス寄りでも誤警告を出さない。
                val closeBypassFile = File.createTempFile("bypass-3m", ".bikerules").apply { deleteOnExit() }
                val closeLon = 3.0 / metersPerDegree
                ExcludedRoadIndex.write(closeBypassFile, listOf(ExcludedSegment(-.001, closeLon, .001, closeLon, motorOnly)))
                val closeBypass = assertNotNull(ExcludedRoadIndex.load(closeBypassFile))

                val closeMatcher = RoadRuleMatcher()
                repeat(4) { i ->
                    val lon = if (i % 2 == 0) 0.0 else 2.0 / metersPerDegree
                    val candidates = graph.roadCandidatesNear(0.0, lon) + closeBypass.candidatesNear(0.0, lon)
                    assertNull(update(closeMatcher, candidates, time = i * 1_000L, lon = lon))
                }

                // 2) 8m 離れた並走バイパス: 側道を走るときは警告せず、バイパスを走るときは3回目で自動車専用を警告する。
                val separatedBypassFile = File.createTempFile("bypass-8m", ".bikerules").apply { deleteOnExit() }
                val bypassLon = 8.0 / metersPerDegree
                ExcludedRoadIndex.write(separatedBypassFile, listOf(ExcludedSegment(-.001, bypassLon, .001, bypassLon, motorOnly)))
                val separatedBypass = assertNotNull(ExcludedRoadIndex.load(separatedBypassFile))

                val sideMatcher = RoadRuleMatcher()
                repeat(4) { i ->
                    val candidates = graph.roadCandidatesNear(0.0, 0.0) + separatedBypass.candidatesNear(0.0, 0.0)
                    assertNull(update(sideMatcher, candidates, time = i * 1_000L, lon = 0.0))
                }

                val bypassMatcher = RoadRuleMatcher()
                repeat(2) { i ->
                    val candidates = graph.roadCandidatesNear(0.0, bypassLon) + separatedBypass.candidatesNear(0.0, bypassLon)
                    assertNull(update(bypassMatcher, candidates, time = i * 1_000L, lon = bypassLon))
                }
                val warnCandidates = graph.roadCandidatesNear(0.0, bypassLon) + separatedBypass.candidatesNear(0.0, bypassLon)
                val match = assertNotNull(update(bypassMatcher, warnCandidates, time = 2_000L, lon = bypassLon))
                assertEquals(RoadRuleKind.MOTOR_ONLY, match.kind)

                // 3) バイパスから側道へ移ったら、3回連続で側道が勝った時点で警告が消える。
                val sideCandidates = graph.roadCandidatesNear(0.0, 0.0) + separatedBypass.candidatesNear(0.0, 0.0)
                assertEquals(RoadRuleKind.MOTOR_ONLY, update(bypassMatcher, sideCandidates, time = 3_000L, lon = 0.0)?.kind)
                assertEquals(RoadRuleKind.MOTOR_ONLY, update(bypassMatcher, sideCandidates, time = 4_000L, lon = 0.0)?.kind)
                assertNull(update(bypassMatcher, sideCandidates, time = 5_000L, lon = 0.0))
            }
        }
    }
}
