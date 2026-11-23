package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.LazyMappedRoadGraph
import com.gorite.cyclemap.routing.RoutePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock

class MultiLegCrossRouteTruncationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun reportsTruncationForTwoPrefectures() {
        val names = mapOf("山口県" to "Yamaguchi.graph", "広島県" to "Hiroshima.graph")
        writeLinearGraph(names.getValue("山口県"), 1L..2L)
        writeLinearGraph(names.getValue("広島県"), 2L..3L)

        assertTrue(plan(names, goal = 3, maxExpandedNodes = 20) is MultiLegCrossOutcome.Success)
        assertEquals(MultiLegCrossOutcome.Truncated, plan(names, goal = 3, maxExpandedNodes = 1))
    }

    @Test
    fun reportsTruncationAfterTryingBothThreePrefectureChains() {
        val names = mapOf(
            "山口県" to "Yamaguchi.graph",
            "広島県" to "Hiroshima.graph",
            "島根県" to "Shimane.graph",
            "鳥取県" to "Tottori.graph",
        )
        writeLinearGraph(names.getValue("山口県"), 1L..2L)
        writeLinearGraph(names.getValue("広島県"), 2L..4L)
        writeLinearGraph(names.getValue("島根県"), 2L..4L)
        writeLinearGraph(names.getValue("鳥取県"), 4L..5L)

        assertTrue(plan(names, goal = 5, maxExpandedNodes = 20) is MultiLegCrossOutcome.Success)
        val openedFiles = mutableSetOf<String>()
        val outcome = plan(names, goal = 5, maxExpandedNodes = 2, openedFiles = openedFiles)
        assertTrue(openedFiles.containsAll(listOf("Hiroshima.graph", "Shimane.graph")))
        assertEquals(MultiLegCrossOutcome.Truncated, outcome)
    }

    @Test
    fun reusesLoadedGraphWhenFilenameCaseDiffers() {
        val names = mapOf("山口県" to "yamaguchi.graph", "広島県" to "Hiroshima.graph")
        writeLinearGraph("Yamaguchi.graph", 1L..2L)
        writeLinearGraph(names.getValue("広島県"), 2L..3L)

        val openedFiles = mutableSetOf<String>()
        LazyMappedRoadGraph.load(File(tempFolder.root, "Yamaguchi.graph")).use { loaded ->
            val outcome = plan(names, goal = 3, maxExpandedNodes = 20, openedFiles = openedFiles, loadedGraph = loaded)
            assertTrue(outcome is MultiLegCrossOutcome.Success)
        }
        assertEquals(setOf("Hiroshima.graph"), openedFiles)
    }

    private fun plan(
        names: Map<String, String>,
        goal: Long,
        maxExpandedNodes: Int,
        openedFiles: MutableSet<String> = mutableSetOf(),
        loadedGraph: LazyMappedRoadGraph? = null,
    ): MultiLegCrossOutcome = calculateMultiLegCrossRouteWithEnv(
        dataDir = tempFolder.root,
        graphLock = ReentrantReadWriteLock(),
        jobGraph = loadedGraph,
        jobGraphName = if (loadedGraph != null) "Yamaguchi.graph" else null,
        stops = listOf(CrossGeoPoint(34.0, longitude(1)), CrossGeoPoint(34.0, longitude(goal))),
        preference = RoutePreference.FLAT,
        isCancelled = { false },
        namesByPref = names,
        centers = emptyMap(),
        prefectureLookup = { _, lon -> if (lon == longitude(1)) names.keys.first() else names.keys.last() },
        nowMs = { 0L },
        logGraphInfo = {},
        logRouteInfo = {},
        logRouteWarn = {},
        logRouteError = {},
        attachCh = { _, graphFile -> openedFiles += graphFile.name },
        enablePrefetch = false,
        maxExpandedNodes = maxExpandedNodes,
    )

    private fun longitude(nodeId: Long) = 132.0 + nodeId * 0.001

    private fun writeLinearGraph(fileName: String, ids: LongRange) {
        val offsets = mutableListOf<Long>()
        val counts = mutableListOf<Int>()
        val targets = mutableListOf<Int>()
        val graphFile = File(tempFolder.root, fileName)
        DataOutputStream(graphFile.outputStream().buffered()).use { out ->
            out.writeUTF("CYCLEMAP_GRAPH_V1")
            out.writeInt(ids.count())
            for (id in ids) {
                out.writeLong(id)
                out.writeDouble(34.0)
                out.writeDouble(longitude(id))
            }
            for (id in ids) {
                val neighbors = listOf(id - 1, id + 1).filter { it in ids }
                offsets += out.size().toLong()
                counts += neighbors.size
                for (neighbor in neighbors) {
                    out.writeLong(id)
                    out.writeLong(neighbor)
                    out.writeDouble(100.0)
                    out.writeUTF("residential")
                    out.writeByte(0) // 双方向
                    out.writeByte(0) // 勾配なし
                    targets += (neighbor - ids.first).toInt()
                }
            }
        }
        DataOutputStream(File(tempFolder.root, "$fileName.idx").outputStream().buffered()).use { out ->
            out.writeUTF("CYCLEMAP_INDEX_V2")
            out.writeInt(ids.count())
            offsets.forEach { out.writeLong(it) }
            var ordinal = 0
            counts.forEach { count ->
                out.writeInt(ordinal)
                ordinal += count
            }
            counts.forEach { out.writeInt(it) }
            out.writeInt(targets.size)
            targets.forEach { out.writeInt(it) }
        }
    }
}
