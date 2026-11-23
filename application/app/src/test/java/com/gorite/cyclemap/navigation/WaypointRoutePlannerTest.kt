package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.InstructionType
import com.gorite.cyclemap.routing.LazyMappedRoadGraph
import com.gorite.cyclemap.routing.RoutePreference
import com.gorite.cyclemap.routing.haversineMeters
import com.gorite.cyclemap.routing.nodeElevationsAlong
import com.gorite.cyclemap.routing.nodeElevationsAt
import com.gorite.cyclemap.routing.routeWithSnapFallback
import com.gorite.cyclemap.routing.segmentGradesAlong
import com.gorite.cyclemap.routing.segmentGradesAlongIndices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.roundToInt

class WaypointRoutePlannerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private data class SynNode(
        val id: Long,
        val lat: Double,
        val lon: Double,
        val elevationMeters: Double,
    )

    private data class SynEdge(
        val toId: Long,
        val distanceMeters: Double,
        val roadType: String = "residential",
        val oneWay: Boolean = false,
        val gradePercent: Double = Double.NaN,
    )

    private fun writeGraphFiles(
        dir: File,
        fileName: String,
        nodes: List<SynNode>,
        edges: Map<Long, List<SynEdge>>,
    ): Pair<File, File> {
        val graphFile = File(dir, fileName)
        val indexFile = File(dir, "$fileName.idx")
        val indexById = nodes.withIndex().associate { it.value.id to it.index }
        val starts = LongArray(nodes.size)
        val firstOrdinals = IntArray(nodes.size)
        val counts = IntArray(nodes.size)
        val targets = ArrayList<Int>()

        DataOutputStream(BufferedOutputStream(FileOutputStream(graphFile))).use { out ->
            out.writeUTF("CYCLEMAP_GRAPH_V1")
            out.writeInt(nodes.size)
            for (node in nodes) {
                out.writeLong(node.id)
                out.writeDouble(node.lat)
                out.writeDouble(node.lon)
            }
            nodes.forEachIndexed { idx, node ->
                starts[idx] = out.size().toLong()
                firstOrdinals[idx] = targets.size
                val outgoing = edges[node.id].orEmpty()
                counts[idx] = outgoing.size
                for (edge in outgoing) {
                    out.writeLong(node.id)
                    out.writeLong(edge.toId)
                    out.writeDouble(edge.distanceMeters)
                    out.writeUTF(edge.roadType)
                    out.writeByte(if (edge.oneWay) 1 else 0)
                    if (edge.gradePercent.isNaN()) {
                        out.writeByte(0)
                    } else {
                        out.writeByte(1)
                        out.writeDouble(edge.gradePercent)
                    }
                    targets += indexById.getValue(edge.toId)
                }
            }
        }

        DataOutputStream(BufferedOutputStream(FileOutputStream(indexFile))).use { out ->
            out.writeUTF("CYCLEMAP_INDEX_V2")
            out.writeInt(nodes.size)
            starts.forEach { out.writeLong(it) }
            firstOrdinals.forEach { out.writeInt(it) }
            counts.forEach { out.writeInt(it) }
            out.writeInt(targets.size)
            targets.forEach { out.writeInt(it) }
            out.writeUTF("CYCLEMAP_ELEVATION_V1")
            out.writeInt(nodes.size)
            for (node in nodes) {
                out.writeShort((node.elevationMeters * 10.0).roundToInt())
            }
        }
        return graphFile to indexFile
    }

    private fun loadSyntheticGraph(
        fileName: String,
        nodes: List<SynNode>,
        edges: Map<Long, List<SynEdge>>,
    ): LazyMappedRoadGraph {
        val (gf, idx) = writeGraphFiles(tempFolder.root, fileName, nodes, edges)
        return LazyMappedRoadGraph.load(gf, idx)
    }

    @Test
    fun oneWayDeadEndWithParallelRoadConnectsAtSharedNodeAndPreservesLineGuidanceElevation() {
        // 1(A) -> 2(袋小路終端・出辺なし) と、平行道路 1 <-> 5 <-> 3 <-> 4(B)
        // 経由地 W(34.00105, 132.0010) は 2 に最も近く(約5.5m)、3 に 2番目に近い(約27.8m)。
        val nodes = listOf(
            SynNode(1L, 34.0000, 132.0000, 10.0),
            SynNode(2L, 34.0010, 132.0010, 25.0),
            SynNode(3L, 34.0013, 132.0010, 15.0),
            SynNode(4L, 34.0013, 132.0020, 18.0),
            SynNode(5L, 34.0013, 132.0000, 12.0),
        )
        val edges = mapOf(
            1L to listOf(
                SynEdge(2L, 140.0, "residential", oneWay = true, gradePercent = 10.7),
                SynEdge(5L, 145.0, "secondary", oneWay = false, gradePercent = 1.4),
            ),
            2L to emptyList(),
            5L to listOf(
                SynEdge(1L, 145.0, "secondary", oneWay = false, gradePercent = -1.4),
                SynEdge(3L, 92.0, "secondary", oneWay = false, gradePercent = 3.3),
            ),
            3L to listOf(
                SynEdge(5L, 92.0, "secondary", oneWay = false, gradePercent = -3.3),
                SynEdge(4L, 92.0, "secondary", oneWay = false, gradePercent = 3.3),
            ),
            4L to listOf(
                SynEdge(3L, 92.0, "secondary", oneWay = false, gradePercent = -3.3),
            ),
        )
        val stops = listOf(
            34.0000 to 132.0000, // A (node 1)
            34.00105 to 132.0010, // W (nearest=2, fallback=3)
            34.0013 to 132.0020, // B (node 4)
        )

        loadSyntheticGraph("parallel.graph", nodes, edges).use { graph ->
            // 旧来の区間別探索では A->W が 2 で終わり、W->B が 3 から始まるため接続点が食い違うことを確認。
            val legacyLeg0 = graph.routeWithSnapFallback(stops[0].first, stops[0].second, stops[1].first, stops[1].second)
            val legacyLeg1 = graph.routeWithSnapFallback(stops[1].first, stops[1].second, stops[2].first, stops[2].second)
            assertEquals(listOf(1L, 2L), legacyLeg0.result.nodeIds)
            assertEquals(listOf(3L, 4L), legacyLeg1.result.nodeIds)

            // 修正版では共通ノード 3 で接続し、連続した経路 [1, 5, 3, 4] になる。
            val plan = planSingleGraphRoute(graph, stops, RoutePreference.RECOMMENDED)
            assertTrue(plan is SingleGraphRoutePlan.Success)
            val success = plan as SingleGraphRoutePlan.Success
            val result = success.result

            assertEquals(listOf(1L, 5L, 3L, 4L), result.nodeIds)
            assertEquals(listOf(0, 4, 2, 3), result.nodeIndices)
            assertEquals(result.nodeIds.size, result.coordinates.size)
            assertEquals(listOf(3L), success.waypointNodeIds)

            // すべての隣接ノード間に実在の辺があること。
            for (i in 0 until result.nodeIndices.lastIndex) {
                val u = result.nodeIndices[i]
                val v = result.nodeIndices[i + 1]
                assertNotNull("Missing edge $u -> $v", graph.edgeDistanceAndGrade(u, v))
            }

            // 案内: 全区間が既知道路で、曲がり角 (1->5->3 で右折) が正しく生成されること。
            val instructions = buildRouteInstructions(graph, result.nodeIds, result.coordinates)
            assertTrue(instructions.any { it.nodeId == 5L && it.type == InstructionType.RIGHT_TURN })
            assertTrue(instructions.none { it.incomingRoadType == "unknown" || it.outgoingRoadType == "unknown" })

            // 標高・勾配: 座標ベースとインデックスベースが同一の連続経路を参照して一致すること。
            val gradesByCoord = graph.segmentGradesAlong(result.coordinates)
            val gradesByIdx = graph.segmentGradesAlongIndices(result.nodeIndices)
            assertEquals(gradesByIdx, gradesByCoord)
            assertEquals(3, gradesByIdx.size)
            listOf(1.4, 3.3, 3.3).forEachIndexed { idx, expected ->
                assertEquals(expected, gradesByIdx[idx].gradePercent!!, 1e-4)
            }

            val elevByCoord = graph.nodeElevationsAlong(result.coordinates)
            val elevByIdx = graph.nodeElevationsAt(result.nodeIndices)
            assertEquals(elevByIdx, elevByCoord)
            assertEquals(listOf(10.0, 12.0, 15.0, 18.0), elevByIdx)

            // 端辺トリミングでも経由地ノード 3 が保持されること。
            val trimmed = trimSingleGraphRouteSafe(result, stops, success.waypointNodeIds)
            assertNotNull(trimmed)
            assertTrue(trimmed!!.result.nodeIds.contains(3L))
        }
    }

    @Test
    fun unreachableOneWayDeadEndReportsSpecificWaypointNumber() {
        // 1(A) -> 2(袋小路・出辺なし), 1 <-> 4(B)。W(node 2) へは行けるが B へ抜けられず平行道路もない。
        val nodes = listOf(
            SynNode(1L, 34.0000, 132.0000, 10.0),
            SynNode(2L, 34.0010, 132.0010, 20.0),
            SynNode(4L, 34.0000, 132.0020, 15.0),
        )
        val edges = mapOf(
            1L to listOf(
                SynEdge(2L, 140.0, "residential", oneWay = true, gradePercent = 5.0),
                SynEdge(4L, 180.0, "secondary", oneWay = false, gradePercent = 2.0),
            ),
            2L to emptyList(),
            4L to listOf(
                SynEdge(1L, 180.0, "secondary", oneWay = false, gradePercent = -2.0),
            ),
        )
        val stops = listOf(
            34.0000 to 132.0000,
            34.0010 to 132.0010,
            34.0000 to 132.0020,
        )

        loadSyntheticGraph("deadend.graph", nodes, edges).use { graph ->
            val plan = planSingleGraphRoute(graph, stops)
            assertEquals(SingleGraphRoutePlan.WaypointUnreachable(1), plan)
            assertEquals("経由地 1 に寄れません", waypointUnreachableMessage(1))
        }
    }

    @Test
    fun secondWaypointUnreachableReportsWaypointTwo() {
        // 1(A) <-> 2(W1) <-> 4(B)、さらに 2 -> 3(W2袋小路・出辺なし)。W1 は通れるが W2 に寄れない。
        val nodes = listOf(
            SynNode(1L, 34.0000, 132.0000, 10.0),
            SynNode(2L, 34.0000, 132.0010, 12.0),
            SynNode(3L, 34.0050, 132.0010, 22.0),
            SynNode(4L, 34.0000, 132.0020, 14.0),
        )
        val edges = mapOf(
            1L to listOf(SynEdge(2L, 92.0, "secondary")),
            2L to listOf(
                SynEdge(1L, 92.0, "secondary"),
                SynEdge(3L, 550.0, "residential", oneWay = true),
                SynEdge(4L, 92.0, "secondary"),
            ),
            3L to emptyList(),
            4L to listOf(SynEdge(2L, 92.0, "secondary")),
        )
        val stops = listOf(
            34.0000 to 132.0000,
            34.0000 to 132.0010,
            34.0050 to 132.0010,
            34.0000 to 132.0020,
        )

        loadSyntheticGraph("two_wp.graph", nodes, edges).use { graph ->
            val plan = planSingleGraphRoute(graph, stops)
            assertEquals(SingleGraphRoutePlan.WaypointUnreachable(2), plan)
        }
    }

    @Test
    fun crossPrefectureRouteGuidesRightTurnAtSamePrefectureWaypoint() {
        val result = planRouteWithJunctions(turnAtPortal = false)
        assertEquals(listOf(1L, 2L, 3L, 4L), result.route.nodeIds)
        val waypointTurn = result.instructions.single { it.nodeId == 2L }
        assertEquals(InstructionType.RIGHT_TURN, waypointTurn.type)
        assertEquals(1, waypointTurn.routeIndex)
        assertEquals(haversineMeters(34.0, 132.0, 34.001, 132.0), waypointTurn.distanceFromStartMeters, 0.01)
    }

    @Test
    fun crossPrefectureRouteGuidesRightTurnAtSharedPortal() {
        val result = planRouteWithJunctions(turnAtPortal = true)
        assertEquals(listOf(1L, 2L, 3L, 4L), result.route.nodeIds)
        val portalTurn = result.instructions.single { it.nodeId == 3L }
        assertEquals(InstructionType.RIGHT_TURN, portalTurn.type)
        assertEquals(2, portalTurn.routeIndex)
        assertEquals(
            haversineMeters(34.001, 132.0, 34.001, 132.00075) + haversineMeters(34.001, 132.00075, 34.001, 132.0015),
            portalTurn.distanceFromStartMeters,
            0.01,
        )
        assertTrue(result.instructions.none { it.nodeId == 2L })
    }

    @Test
    fun crossPrefectureRouteOmitsInstructionAtStraightPortal() {
        val result = planRouteWithJunctions(turnAtPortal = false)
        assertEquals(listOf(1L, 2L, 3L, 4L), result.route.nodeIds)
        assertTrue(result.instructions.none { it.nodeId == 3L })
    }

    private fun planRouteWithJunctions(turnAtPortal: Boolean): MultiLegRouteResult {
        val start = SynNode(1L, if (turnAtPortal) 34.001 else 34.0, 132.0, 0.0)
        val waypoint = SynNode(2L, 34.001, if (turnAtPortal) 132.00075 else 132.0, 0.0)
        val portal = SynNode(3L, 34.001, 132.0015, 0.0)
        val end = if (turnAtPortal) SynNode(4L, 34.0, 132.0015, 0.0) else SynNode(4L, 34.001, 132.003, 0.0)
        fun edge(from: SynNode, to: SynNode) =
            SynEdge(to.id, haversineMeters(from.lat, from.lon, to.lat, to.lon))
        writeGraphFiles(
            tempFolder.root, "yamaguchi.graph", listOf(start, waypoint, portal),
            mapOf(
                start.id to listOf(edge(start, waypoint)),
                waypoint.id to listOf(edge(waypoint, start), edge(waypoint, portal)),
                portal.id to listOf(edge(portal, waypoint)),
            ),
        )
        writeGraphFiles(
            tempFolder.root, "hiroshima.graph", listOf(portal, end),
            mapOf(portal.id to listOf(edge(portal, end)), end.id to listOf(edge(end, portal))),
        )
        val outcome = calculateMultiLegCrossRouteWithEnv(
            dataDir = tempFolder.root,
            graphLock = ReentrantReadWriteLock(),
            jobGraph = null,
            jobGraphName = null,
            stops = listOf(start, waypoint, end).map { CrossGeoPoint(it.lat, it.lon) },
            preference = RoutePreference.RECOMMENDED,
            isCancelled = { false },
            namesByPref = mapOf("山口県" to "yamaguchi.graph", "広島県" to "hiroshima.graph"),
            centers = emptyMap(),
            prefectureLookup = { _, lon -> if (lon < portal.lon) "山口県" else "広島県" },
            nowMs = { 0L },
            logGraphInfo = {},
            logRouteInfo = {},
            logRouteWarn = {},
            logRouteError = {},
            attachCh = { _, _ -> },
            enablePrefetch = false,
        )
        assertTrue(outcome.toString(), outcome is MultiLegCrossOutcome.Success)
        val result = (outcome as MultiLegCrossOutcome.Success).result
        assertEquals(
            edge(start, waypoint).distanceMeters + edge(waypoint, portal).distanceMeters + edge(portal, end).distanceMeters,
            result.route.totalDistanceMeters,
            0.01,
        )
        return result
    }

    @Test
    fun crossPrefectureWaypointConnectsAtSamePortalAwareNodeAndDetectsMissingPrefecture() {
        // 山口県 (lon < 132.0010): 10(A) <-> 100(県境ポータル)
        // 広島県 (lon >= 132.0010): 100(県境ポータル) -> 200(袋小路), 100 <-> 500 <-> 300(平行道路) <-> 400(B)
        // 経由地 W は 200(最寄り) と 300(平行道路) の間。
        val yamaguchiNodes = listOf(
            SynNode(10L, 34.0000, 132.0000, 10.0),
            SynNode(100L, 34.0000, 132.0010, 15.0),
        )
        val yamaguchiEdges = mapOf(
            10L to listOf(SynEdge(100L, 92.0, "secondary", gradePercent = 5.4)),
            100L to listOf(SynEdge(10L, 92.0, "secondary", gradePercent = -5.4)),
        )
        writeGraphFiles(tempFolder.root, "yamaguchi.graph", yamaguchiNodes, yamaguchiEdges)

        val hiroshimaNodes = listOf(
            SynNode(100L, 34.0000, 132.0010, 15.0),
            SynNode(200L, 34.0010, 132.0020, 30.0),
            SynNode(500L, 34.0013, 132.0010, 17.0),
            SynNode(300L, 34.0013, 132.0020, 20.0),
            SynNode(400L, 34.0013, 132.0030, 23.0),
        )
        val hiroshimaEdges = mapOf(
            100L to listOf(
                SynEdge(200L, 140.0, "residential", oneWay = true, gradePercent = 10.7),
                SynEdge(500L, 145.0, "secondary", oneWay = false, gradePercent = 1.4),
            ),
            200L to emptyList(),
            500L to listOf(
                SynEdge(100L, 145.0, "secondary", oneWay = false, gradePercent = -1.4),
                SynEdge(300L, 92.0, "secondary", oneWay = false, gradePercent = 3.3),
            ),
            300L to listOf(
                SynEdge(500L, 92.0, "secondary", oneWay = false, gradePercent = -3.3),
                SynEdge(400L, 92.0, "secondary", oneWay = false, gradePercent = 3.3),
            ),
            400L to listOf(
                SynEdge(300L, 92.0, "secondary", oneWay = false, gradePercent = -3.3),
            ),
        )
        writeGraphFiles(tempFolder.root, "Hiroshima.graph", hiroshimaNodes, hiroshimaEdges)

        val namesByPref = mapOf("山口県" to "yamaguchi.graph", "広島県" to "Hiroshima.graph")
        val centers = mapOf(
            "山口県" to CrossGeoPoint(34.0000, 132.0005),
            "広島県" to CrossGeoPoint(34.0010, 132.0020),
        )
        val lookup: (Double, Double) -> String? = { _, lon ->
            if (lon < 132.0010) "山口県" else "広島県"
        }

        val lock = ReentrantReadWriteLock()
        val yGraph = LazyMappedRoadGraph.load(
            File(tempFolder.root, "yamaguchi.graph"),
            File(tempFolder.root, "yamaguchi.graph.idx"),
        )
        yGraph.use {
            val stops = listOf(
                CrossGeoPoint(34.0000, 132.0000), // 山口県 A (10)
                CrossGeoPoint(34.00105, 132.0020), // 広島県 W (200 に最寄り、300 が平行)
                CrossGeoPoint(34.0013, 132.0030), // 広島県 B (400)
            )
            val outcome = calculateMultiLegCrossRouteWithEnv(
                dataDir = tempFolder.root,
                graphLock = lock,
                jobGraph = yGraph,
                jobGraphName = "yamaguchi.graph",
                stops = stops,
                preference = RoutePreference.RECOMMENDED,
                isCancelled = { false },
                namesByPref = namesByPref,
                centers = centers,
                prefectureLookup = lookup,
                nowMs = { 0L },
                logGraphInfo = {},
                logRouteInfo = {},
                logRouteWarn = {},
                logRouteError = {},
                attachCh = { _, _ -> },
                enablePrefetch = false,
            )

            assertTrue(outcome is MultiLegCrossOutcome.Success)
            val result = (outcome as MultiLegCrossOutcome.Success).result
            assertEquals(listOf(10L, 100L, 500L, 300L, 400L), result.route.nodeIds)
            assertEquals(listOf(10.0, 15.0, 17.0, 20.0, 23.0), result.elevation.nodeElevations)
            assertEquals(4, result.elevation.segments.size)
            listOf(5.4, 1.4, 3.3, 3.3).forEachIndexed { idx, expected ->
                assertEquals(expected, result.elevation.segments[idx].gradePercent!!, 1e-4)
            }
            assertTrue(result.instructions.none { it.incomingRoadType == "unknown" || it.outgoingRoadType == "unknown" })

            // 広島県のグラフが無いディレクトリを用意し、県境上の目的地 (経度 1.0 度先も含む) の欠落検出と部分経路ガードを検証する。
            val dirWithoutHiroshima = tempFolder.newFolder("without_hiroshima")
            writeGraphFiles(dirWithoutHiroshima, "yamaguchi.graph", yamaguchiNodes, yamaguchiEdges)

            // 1) 経度 1.0 度先の目的地: 欠落県 (広島県) を検出し、単一グラフは 400m 外なので Unreachable。
            val farBorderStops = listOf(
                CrossGeoPoint(34.0000, 132.0000), // 山口県
                CrossGeoPoint(34.0000, 133.0000), // 広島県 (経度1度先)
            )
            val missingFarOutcome = calculateMultiLegCrossRouteWithEnv(
                dataDir = dirWithoutHiroshima,
                graphLock = lock,
                jobGraph = yGraph,
                jobGraphName = "yamaguchi.graph",
                stops = farBorderStops,
                preference = RoutePreference.RECOMMENDED,
                isCancelled = { false },
                namesByPref = namesByPref,
                centers = centers,
                prefectureLookup = lookup,
                nowMs = { 0L },
                logGraphInfo = {},
                logRouteInfo = {},
                logRouteWarn = {},
                logRouteError = {},
                attachCh = { _, _ -> },
                enablePrefetch = false,
            )
            assertEquals(MultiLegCrossOutcome.MissingPrefectureData(listOf("広島県")), missingFarOutcome)
            assertEquals(
                SingleGraphRoutePlan.Unreachable,
                planSingleGraphRoute(yGraph, farBorderStops.map { it.latitude to it.longitude }),
            )

            // 2) 県境上の目的地 (ポータル 100 から約 18m 先の広島県側):
            //    単一グラフは部分経路 [10, 100] を返すが、自動再探索ではフォールバックせず既存案内を保持する。
            val nearBorderStops = listOf(
                CrossGeoPoint(34.0000, 132.0000), // 山口県
                CrossGeoPoint(34.0000, 132.0012), // 広島県 (県境直近)
            )
            val missingNearOutcome = calculateMultiLegCrossRouteWithEnv(
                dataDir = dirWithoutHiroshima,
                graphLock = lock,
                jobGraph = yGraph,
                jobGraphName = "yamaguchi.graph",
                stops = nearBorderStops,
                preference = RoutePreference.RECOMMENDED,
                isCancelled = { false },
                namesByPref = namesByPref,
                centers = centers,
                prefectureLookup = lookup,
                nowMs = { 0L },
                logGraphInfo = {},
                logRouteInfo = {},
                logRouteWarn = {},
                logRouteError = {},
                attachCh = { _, _ -> },
                enablePrefetch = false,
            )
            assertEquals(MultiLegCrossOutcome.MissingPrefectureData(listOf("広島県")), missingNearOutcome)

            val singleFallback = planSingleGraphRoute(
                yGraph,
                nearBorderStops.map { it.latitude to it.longitude },
            )
            assertTrue("singleFallback was $singleFallback", singleFallback is SingleGraphRoutePlan.Success)
            assertFalse(
                shouldAttemptSingleGraphFallback(
                    isReroute = true,
                    usedCrossRoute = false,
                    crossOutcome = CrossPlanningOutcome.MissingPrefectureData(listOf("広島県")),
                    needsCrossPlanning = true,
                ),
            )
            val rerouteDecision = decideRouteApplication(
                isReroute = true,
                completeness = classifyRouteCompleteness(
                    needsCrossPlanning = true,
                    usedCrossRoute = false,
                    singleGraphReachable = false,
                ),
                crossOutcome = CrossPlanningOutcome.MissingPrefectureData(listOf("広島県")),
            )
            assertFalse(rerouteDecision.shouldReplaceRoute)
            assertEquals("隣の県のデータが無いため、再探索できません (広島県)", rerouteDecision.warningMessage)

            // 手動探索では部分経路を出す場合に必ず広島県を含む警告を表示する。
            val manualDecision = decideRouteApplication(
                isReroute = false,
                completeness = classifyRouteCompleteness(
                    needsCrossPlanning = true,
                    usedCrossRoute = false,
                    singleGraphReachable = true,
                ),
                crossOutcome = CrossPlanningOutcome.MissingPrefectureData(listOf("広島県")),
            )
            assertTrue(manualDecision.shouldReplaceRoute)
            assertEquals(RouteCompleteness.PARTIAL, manualDecision.completeness)
            assertTrue(manualDecision.warningMessage!!.contains("広島県"))

            // 県またぎでも経由地が一方通行の袋小路で平行道路がないときは WaypointUnreachable(1) を返す。
            val dirDeadEnd = tempFolder.newFolder("cross_deadend")
            writeGraphFiles(dirDeadEnd, "yamaguchi.graph", yamaguchiNodes, yamaguchiEdges)
            val deadEndHiroshimaNodes = listOf(
                SynNode(100L, 34.0000, 132.0010, 15.0),
                SynNode(200L, 34.0010, 132.0020, 30.0),
                SynNode(400L, 34.0000, 132.0030, 23.0),
            )
            val deadEndHiroshimaEdges = mapOf(
                100L to listOf(
                    SynEdge(200L, 140.0, "residential", oneWay = true, gradePercent = 10.7),
                    SynEdge(400L, 180.0, "secondary", oneWay = false, gradePercent = 4.4),
                ),
                200L to emptyList(),
                400L to listOf(
                    SynEdge(100L, 180.0, "secondary", oneWay = false, gradePercent = -4.4),
                ),
            )
            writeGraphFiles(dirDeadEnd, "Hiroshima.graph", deadEndHiroshimaNodes, deadEndHiroshimaEdges)

            val deadEndOutcome = calculateMultiLegCrossRouteWithEnv(
                dataDir = dirDeadEnd,
                graphLock = lock,
                jobGraph = yGraph,
                jobGraphName = "yamaguchi.graph",
                stops = listOf(
                    CrossGeoPoint(34.0000, 132.0000), // 山口県 A
                    CrossGeoPoint(34.0010, 132.0020), // 広島県 W (袋小路 200)
                    CrossGeoPoint(34.0000, 132.0030), // 広島県 B (400)
                ),
                preference = RoutePreference.RECOMMENDED,
                isCancelled = { false },
                namesByPref = namesByPref,
                centers = centers,
                prefectureLookup = lookup,
                nowMs = { 0L },
                logGraphInfo = {},
                logRouteInfo = {},
                logRouteWarn = {},
                logRouteError = {},
                attachCh = { _, _ -> },
                enablePrefetch = false,
            )
            assertEquals(MultiLegCrossOutcome.WaypointUnreachable(1), deadEndOutcome)
        }
    }
}
