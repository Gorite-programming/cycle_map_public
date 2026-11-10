package com.gorite.cyclemap.navigation

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.gorite.cyclemap.data.PrefectureData
import com.gorite.cyclemap.data.RoutingGraphSelection
import com.gorite.cyclemap.data.RoutingGraphSelector
import com.gorite.cyclemap.data.findPrefectureName
import com.gorite.cyclemap.routing.*
import com.gorite.cyclemap.utils.ROUTE_MAX_EXPANDED_NODES
import com.gorite.cyclemap.utils.cycleMapDataDir
import org.osmdroid.util.GeoPoint
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read

internal data class MultiLegRouteResult(
    val route: MappedRouteResult,
    val instructions: List<RouteInstruction>,
    val elevation: CrossRouteElevationData,
    val loadMs: Long,
    val chain: String,
    val portalMs: Long,
    val aMs: Long,
    val bMs: Long,
)

/** Cross-prefecture path. Same-prefecture-only requests stay on the legacy path. */
internal fun calculateMultiLegCrossRoute(
    context: Context,
    graphLock: ReentrantReadWriteLock,
    jobGraph: LazyMappedRoadGraph,
    jobGraphName: String?,
    stops: List<GeoPoint>,
    preference: RoutePreference,
    isCancelled: () -> Boolean,
): MultiLegRouteResult? {
    val prefectures = stops.map { findPrefectureName(it.latitude, it.longitude) }
    if (prefectures.zipWithNext().none { classifyLeg(it.first, it.second) != CrossLegKind.SAME }) return null
    val dataDir = cycleMapDataDir(context)
    val supported = listOf("山口県", "島根県", "広島県", "岡山県", "鳥取県")
    val namesByPref: Map<String, String> = mapOf(
        "山口県" to "yamaguchi.graph", "島根県" to "Shimane.graph", "広島県" to "Hiroshima.graph",
        "岡山県" to "Okayama.graph", "鳥取県" to "Tottori.graph",
    )
    val available = namesByPref.filterValues { RoutingGraphSelector.resolveGraphFiles(dataDir, it) != null }.keys
    val centers = supported.associateWith { name ->
        val b = PrefectureData.ALL.first { it.name == name }.bounds
        CrossGeoPoint((b.latNorth + b.latSouth) / 2.0, (b.lonEast + b.lonWest) / 2.0)
    }
    val routeParts = ArrayList<CrossRoutePathPart>()
    val instructionParts = ArrayList<CrossInstructionPart>()
    val elevationParts = ArrayList<CrossElevationPart>()
    var totalDistance = 0.0
    var totalCost = 0.0
    var totalLoadMs = 0L
    var portalMs = 0L
    var aMs = 0L
    var bMs = 0L
    var endpointSnapMs = 0L
    var guidanceElevationMs = 0L
    val usedChains = ArrayList<String>()

    fun open(pref: String): LazyMappedRoadGraph? {
        val fileName = namesByPref[pref] ?: return null
        if (fileName == jobGraphName) return jobGraph
        val files = RoutingGraphSelector.resolveGraphFiles(dataDir, fileName) ?: return null
        val started = SystemClock.elapsedRealtime()
        loadTimingSink = { Log.i("CycleMapGraph", it) }
        val graph = LazyMappedRoadGraph.load(files.first, files.second)
        totalLoadMs += SystemClock.elapsedRealtime() - started
        return graph
    }
    fun withGraph(pref: String, block: (LazyMappedRoadGraph) -> Unit): Boolean {
        if (namesByPref[pref] == jobGraphName) {
            graphLock.read { block(jobGraph) }
            return true
        }
        val graph = open(pref) ?: return false
        try { block(graph) } finally { graph.close() }
        return true
    }
    fun addPart(graph: LazyMappedRoadGraph, indices: List<Int>, ids: List<Long>, coords: List<Pair<Double, Double>>, distance: Double, cost: Double) {
        val started = SystemClock.elapsedRealtime()
        val grades = graph.segmentGradesAlongIndices(indices)
        val elevations = graph.nodeElevationsAt(indices)
        val actualDistance = if (grades.isNotEmpty()) grades.sumOf { it.distanceMeters } else distance
        routeParts += CrossRoutePathPart(ids, coords, actualDistance, cost, indices)
        instructionParts += CrossInstructionPart(buildRouteInstructions(graph, ids, coords), ids.size, actualDistance)
        elevationParts += CrossElevationPart(grades, elevations)
        guidanceElevationMs += SystemClock.elapsedRealtime() - started
    }

    for (leg in 0 until stops.lastIndex) {
        if (isCancelled()) return null
        val fromPref = prefectures[leg]; val toPref = prefectures[leg + 1]
        val kind = classifyLeg(fromPref, toPref)
        val from = stops[leg]; val to = stops[leg + 1]
        if (kind == CrossLegKind.UNKNOWN || fromPref == null || toPref == null) return null
        if (kind == CrossLegKind.SAME) {
            var success = false
            val ok = withGraph(fromPref) { graph ->
                val outcome = graph.routeWithSnapFallback(from.latitude, from.longitude, to.latitude, to.longitude,
                    isCancelled = isCancelled, maxExpandedNodes = ROUTE_MAX_EXPANDED_NODES, preference = preference)
                if (outcome.result.isReachable && !outcome.result.wasTruncated) {
                    val r = outcome.result
                    addPart(graph, r.nodeIndices, r.nodeIds, r.coordinates, r.totalDistanceMeters, r.totalCost)
                    totalDistance += r.totalDistanceMeters; totalCost += r.totalCost; success = true
                }
            }
            if (!ok || !success) return null
            usedChains.add(fromPref)
        } else {
            val chains = planPrefectureChains(fromPref, toPref, available, centers)
            var reached = false
            for (chain in chains) {
                if (isCancelled()) return null
                val sources = chain.map { pref ->
                    val fileName = namesByPref[pref] ?: return null
                    val files = RoutingGraphSelector.resolveGraphFiles(dataDir, fileName) ?: return null
                    if (fileName == jobGraphName) ChainGraph(open = { jobGraph }, borrowed = true,
                        openIdIndex = { jobGraph.sortedNodeIdIndex() }, stageName = pref)
                    else ChainGraph(open = {
                        val loadStarted = SystemClock.elapsedRealtime()
                        loadTimingSink = { Log.i("CycleMapGraph", it) }
                        LazyMappedRoadGraph.load(files.first, files.second).also {
                            totalLoadMs += SystemClock.elapsedRealtime() - loadStarted
                        }
                    }, openIdIndex = {
                        loadTimingSink = { Log.i("CycleMapGraph", it) }
                        LazyMappedRoadGraph.loadSortedNodeIdIndex(files.first)
                    }, stageName = pref)
                }
                val indices = IntArray(chain.size)
                val crossParts: List<CrossRoutePart>
                val routeDistance: Double
                val routeCost: Double
                try {
                    val firstPref = chain.first(); val lastPref = chain.last()
                    val endpointSnapStarted = SystemClock.elapsedRealtime()
                    val startIdx = if (namesByPref[firstPref] == jobGraphName) graphLock.read { jobGraph.nearestNodeIndex(from.latitude, from.longitude) }
                        else sources.first().open().let { g -> try { g.nearestNodeIndex(from.latitude, from.longitude) } finally { sources.first().close(g) } }
                    val goalIdx = if (namesByPref[lastPref] == jobGraphName) graphLock.read { jobGraph.nearestNodeIndex(to.latitude, to.longitude) }
                        else sources.last().open().let { g -> try { g.nearestNodeIndex(to.latitude, to.longitude) } finally { sources.last().close(g) } }
                    endpointSnapMs += SystemClock.elapsedRealtime() - endpointSnapStarted
                    if (startIdx < 0 || goalIdx < 0) continue
                    chain.forEachIndexed { i, pref ->
                        if (namesByPref[pref] == jobGraphName) indices[i] = if (i == 0) startIdx else goalIdx
                    }
                    if (chain.size == 2) {
                        var ga: LazyMappedRoadGraph? = null
                        var gb: LazyMappedRoadGraph? = null
                        try {
                            val graphA = sources[0].open().also { ga = it }
                            val graphB = sources[1].open().also { gb = it }
                            val portalStarted = SystemClock.elapsedRealtime()
                            val portals = if (sources.any { it.borrowed }) graphLock.read { portalsBetween(graphA, graphB) }
                                else portalsBetween(graphA, graphB)
                            portalMs += SystemClock.elapsedRealtime() - portalStarted
                            val cross = if (sources.any { it.borrowed }) graphLock.read { routeAcrossGraphs(graphA, startIdx, graphB, goalIdx, portals, preference, isCancelled, ROUTE_MAX_EXPANDED_NODES) }
                                else routeAcrossGraphs(graphA, startIdx, graphB, goalIdx, portals, preference, isCancelled, ROUTE_MAX_EXPANDED_NODES)
                            if (!cross.isReachable) continue
                            aMs += cross.aSearchMs; bMs += cross.bSearchMs
                            crossParts = listOf(cross.aPart, cross.bPart); routeDistance = cross.totalDistanceMeters; routeCost = cross.totalCost
                        } finally {
                            if (gb != null && !sources[1].borrowed) sources[1].close(gb)
                            if (ga != null && !sources[0].borrowed) sources[0].close(ga)
                        }
                    } else {
                        val cross = if (sources.any { it.borrowed }) graphLock.read {
                            routeAcrossChain(sources, startIdx, goalIdx, preference, isCancelled, ROUTE_MAX_EXPANDED_NODES,
                                stageTimingSink = { Log.i("CycleMapGraph", it) })
                        } else routeAcrossChain(sources, startIdx, goalIdx, preference, isCancelled, ROUTE_MAX_EXPANDED_NODES,
                            stageTimingSink = { Log.i("CycleMapGraph", it) })
                        if (!cross.isReachable) continue
                        crossParts = cross.parts; routeDistance = cross.totalDistanceMeters; routeCost = cross.totalCost
                    }
                } catch (t: Throwable) {
                    if (isCancelled()) return null
                    if (t is OutOfMemoryError) {
                        Log.e("CycleMapRoute", "ROUTE_FAILURE reason=out_of_memory")
                        throw t
                    }
                    Log.w("CycleMapRoute", "CROSS_PREFECTURE chain failed ${chain.joinToString(">")}: ${t.message}")
                    continue
                } finally { /* Each owned graph is closed by routeAcrossChain or the pair block. */ }
                // Reopen each graph one at a time to materialize rich data while its local indices are valid.
                var partOk = true
                crossParts.forEachIndexed { i, part ->
                    if (part.nodeIndices.isEmpty()) { partOk = false; return@forEachIndexed }
                    val ok = withGraph(chain[i]) { graph ->
                        addPart(graph, part.nodeIndices, part.nodeIds, part.coordinates,
                            graph.segmentGradesAlongIndices(part.nodeIndices).sumOf { it.distanceMeters }, 0.0)
                    }
                    if (!ok) partOk = false
                }
                if (!partOk) return null
                totalDistance += routeDistance; totalCost += routeCost
                usedChains += chain.joinToString(">")
                reached = true
                break
            }
            if (!reached) return null
        }
    }
    if (isCancelled()) return null
    Log.i("CycleMapGraph", "CHAIN_STAGE name=endpoint_snap ms=$endpointSnapMs")
    Log.i("CycleMapGraph", "CHAIN_STAGE name=guidance_elevation ms=$guidanceElevationMs")
    val merged = mergeRoutePathParts(routeParts)
    return MultiLegRouteResult(
        MappedRouteResult(merged.nodeIds, totalDistance, totalCost, merged.coordinates, nodeIndices = emptyList()),
        mergeInstructionParts(instructionParts), mergeElevationParts(elevationParts), totalLoadMs,
        usedChains.joinToString("|"), portalMs, aMs, bMs
    )
}
