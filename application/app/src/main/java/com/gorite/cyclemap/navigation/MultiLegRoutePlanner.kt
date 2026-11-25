package com.gorite.cyclemap.navigation

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.gorite.cyclemap.data.PrefectureData
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

internal sealed interface MultiLegCrossOutcome {
    data object NotCrossPrefecture : MultiLegCrossOutcome
    data class Success(val result: MultiLegRouteResult) : MultiLegCrossOutcome
    data class MissingPrefectureData(val missingPrefectures: List<String>) : MultiLegCrossOutcome
    data class WaypointUnreachable(val waypointNumber: Int) : MultiLegCrossOutcome
    data object Cancelled : MultiLegCrossOutcome
    data object Truncated : MultiLegCrossOutcome
    data object Failed : MultiLegCrossOutcome
}

private data class SolvedCrossLeg(
    val chain: List<String>,
    val parts: List<CrossRoutePart>,
    val distanceMeters: Double,
    val cost: Double,
    val chainLabel: String,
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
): MultiLegCrossOutcome {
    val centers = PrefectureData.ALL.associate { pref ->
        val b = pref.bounds
        pref.name to CrossGeoPoint((b.latNorth + b.latSouth) / 2.0, (b.lonEast + b.lonWest) / 2.0)
    }
    val namesByPref = PrefectureData.ALL.associate {
        it.name to RoutingGraphSelector.SUPPORTED_FILES.getValue(it.id)
    }
    return calculateMultiLegCrossRouteWithEnv(
        dataDir = cycleMapDataDir(context),
        graphLock = graphLock,
        jobGraph = jobGraph,
        jobGraphName = jobGraphName,
        stops = stops.map { CrossGeoPoint(it.latitude, it.longitude) },
        preference = preference,
        isCancelled = isCancelled,
        namesByPref = namesByPref,
        centers = centers,
    )
}

internal fun calculateMultiLegCrossRouteWithEnv(
    dataDir: File,
    graphLock: ReentrantReadWriteLock,
    jobGraph: LazyMappedRoadGraph?,
    jobGraphName: String?,
    stops: List<CrossGeoPoint>,
    preference: RoutePreference,
    isCancelled: () -> Boolean,
    namesByPref: Map<String, String>,
    centers: Map<String, CrossGeoPoint>,
    prefectureLookup: (Double, Double) -> String? = ::findPrefectureName,
    nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    logGraphInfo: (String) -> Unit = { Log.i("CycleMapGraph", it) },
    logRouteInfo: (String) -> Unit = { Log.i("CycleMapRoute", it) },
    logRouteWarn: (String) -> Unit = { Log.w("CycleMapRoute", it) },
    logRouteError: (String) -> Unit = { Log.e("CycleMapRoute", it) },
    attachCh: (LazyMappedRoadGraph, File) -> Unit = { g, f -> loadContractionHierarchy(g, f) },
    enablePrefetch: Boolean = true,
    maxExpandedNodes: Int = ROUTE_MAX_EXPANDED_NODES,
): MultiLegCrossOutcome {
    if (stops.size < 2) return MultiLegCrossOutcome.NotCrossPrefecture
    val prefectures = stops.map { prefectureLookup(it.latitude, it.longitude) }
    if (prefectures.zipWithNext().none { classifyLeg(it.first, it.second) == CrossLegKind.CROSS }) {
        return MultiLegCrossOutcome.NotCrossPrefecture
    }

    // 全県候補から端末にグラフのある県だけを抽出し、不足県があれば早期に特定して返す。
    val available = namesByPref.filterValues { RoutingGraphSelector.resolveGraphFiles(dataDir, it) != null }.keys
    val missing = findMissingPrefecturesForRoute(prefectures, available, namesByPref.keys, centers)
    if (missing.isNotEmpty()) {
        return MultiLegCrossOutcome.MissingPrefectureData(missing)
    }
    if (prefectures.any { it == null }) {
        return MultiLegCrossOutcome.Failed
    }

    val routeParts = ArrayList<CrossRoutePathPart>()
    val instructionParts = ArrayList<CrossInstructionPart>()
    val elevationParts = ArrayList<CrossElevationPart>()
    var totalLoadMs = 0L
    var portalMs = 0L
    var aMs = 0L
    var bMs = 0L
    var endpointSnapMs = 0L
    var guidanceElevationMs = 0L
    var crossLegTruncated = false
    val graphFingerprints = HashMap<File, GraphFingerprint>()
    val routePrefetch = if (enablePrefetch && preference == RoutePreference.RECOMMENDED) {
        RouteFilePrefetch(isCancelled)
    } else {
        null
    }

    fun openOwned(pref: String): LazyMappedRoadGraph? {
        val fileName = namesByPref[pref] ?: return null
        val files = RoutingGraphSelector.resolveGraphFiles(dataDir, fileName) ?: return null
        val started = nowMs()
        loadTimingSink = logGraphInfo
        val graph = LazyMappedRoadGraph.load(files.first, files.second)
        attachCh(graph, files.first)
        totalLoadMs += nowMs() - started
        return graph
    }

    fun <T> withGraph(pref: String, block: (LazyMappedRoadGraph) -> T): T? {
        if (sameGraphFile(namesByPref[pref], jobGraphName) && jobGraph != null) {
            return graphLock.read { block(jobGraph) }
        }
        val graph = openOwned(pref) ?: return null
        return try {
            block(graph)
        } finally {
            graph.close()
        }
    }

    fun solveSameLeg(pref: String, startIdx: Int, goalIdx: Int): SolvedCrossLeg? {
        var solved: SolvedCrossLeg? = null
        val ok = withGraph(pref) { graph ->
            val r = graph.route(
                startIdx,
                goalIdx,
                isCancelled = isCancelled,
                maxExpandedNodes = maxExpandedNodes,
                preference = preference,
            )
            // 同じ県の区間が探索の上限で打ち切られたら、経路なしと区別して伝える。
            if (r.wasTruncated) crossLegTruncated = true
            if (r.isReachable && !r.wasTruncated) {
                solved = SolvedCrossLeg(
                    chain = listOf(pref),
                    parts = listOf(CrossRoutePart(r.nodeIds, r.coordinates, r.nodeIndices)),
                    distanceMeters = r.totalDistanceMeters,
                    cost = r.totalCost,
                    chainLabel = pref,
                )
            }
        }
        return if (ok != null) solved else null
    }

    fun solveCrossLeg(fromPref: String, toPref: String, startIdx: Int, goalIdx: Int): SolvedCrossLeg? {
        val chains = planPrefectureChains(fromPref, toPref, available, centers)
        var wasTruncated = false
        for (chain in chains) {
            if (isCancelled()) return null
            val sources = chain.mapIndexed { stage, pref ->
                val fileName = namesByPref[pref] ?: return null
                val files = RoutingGraphSelector.resolveGraphFiles(dataDir, fileName) ?: return null
                val nextGraphFile = chain.getOrNull(stage + 1)?.let { next ->
                    namesByPref[next]?.let { RoutingGraphSelector.resolveGraphFiles(dataDir, it)?.first }
                }
                val portalsToNext: (() -> CrossPortals?)? = nextGraphFile?.let { nextFile ->
                    { loadCrossPortals(files.first, nextFile, dataDir) { f -> graphFingerprints.getOrPut(f) { GraphFingerprint.fromFile(f) } } }
                }
                if (sameGraphFile(fileName, jobGraphName) && jobGraph != null) {
                    ChainGraph(
                        open = { jobGraph },
                        borrowed = true,
                        openIdIndex = { jobGraph.sortedNodeIdIndex() },
                        stageName = pref,
                        portalsToNext = portalsToNext,
                    )
                } else {
                    ChainGraph(
                        open = {
                            val loadStarted = nowMs()
                            loadTimingSink = logGraphInfo
                            LazyMappedRoadGraph.load(files.first, files.second, readRichSections = false).also {
                                attachCh(it, files.first)
                                totalLoadMs += nowMs() - loadStarted
                            }
                        },
                        openIdIndex = {
                            loadTimingSink = logGraphInfo
                            LazyMappedRoadGraph.loadSortedNodeIdIndex(files.first)
                        },
                        stageName = pref,
                        portalsToNext = portalsToNext,
                    )
                }
            }
            if (routePrefetch != null) {
                val prefetch = ArrayList<File>()
                for (pref in chain) {
                    val fileName = namesByPref[pref] ?: continue
                    if (sameGraphFile(fileName, jobGraphName)) continue
                    val gf = RoutingGraphSelector.resolveGraphFiles(dataDir, fileName)?.first ?: continue
                    findContractionHierarchyFile(gf)?.let { prefetch += it }
                    prefetch += gf
                }
                routePrefetch.enqueue(prefetch)
            }

            try {
                val chCross = if (preference == RoutePreference.RECOMMENDED) {
                    if (sources.any { it.borrowed }) {
                        graphLock.read {
                            routeAcrossChainCh(sources, startIdx, goalIdx, isCancelled, stageTimingSink = logGraphInfo)
                        }
                    } else {
                        routeAcrossChainCh(sources, startIdx, goalIdx, isCancelled, stageTimingSink = logGraphInfo)
                    }
                } else null
                if (chCross != null) {
                    if (!chCross.isReachable) continue
                    logRouteInfo("CROSS_ENGINE ch graphs=${chain.size}")
                    return SolvedCrossLeg(
                        chain = chain,
                        parts = chCross.parts,
                        distanceMeters = chCross.totalDistanceMeters,
                        cost = chCross.totalCost,
                        chainLabel = chain.joinToString(">"),
                    )
                } else if (chain.size == 2) {
                    var ga: LazyMappedRoadGraph? = null
                    var gb: LazyMappedRoadGraph? = null
                    try {
                        val graphA = sources[0].open().also { ga = it }
                        val graphB = sources[1].open().also { gb = it }
                        val portalStarted = nowMs()
                        val portals = if (sources.any { it.borrowed }) {
                            graphLock.read { portalsBetween(graphA, graphB) }
                        } else {
                            portalsBetween(graphA, graphB)
                        }
                        portalMs += nowMs() - portalStarted
                        val cross = if (sources.any { it.borrowed }) {
                            graphLock.read {
                                routeAcrossGraphs(graphA, startIdx, graphB, goalIdx, portals, preference, isCancelled, maxExpandedNodes)
                            }
                        } else {
                            routeAcrossGraphs(graphA, startIdx, graphB, goalIdx, portals, preference, isCancelled, maxExpandedNodes)
                        }
                        if (!cross.isReachable) {
                            wasTruncated = wasTruncated || cross.wasTruncated
                            continue
                        }
                        aMs += cross.aSearchMs
                        bMs += cross.bSearchMs
                        return SolvedCrossLeg(
                            chain = chain,
                            parts = listOf(cross.aPart, cross.bPart),
                            distanceMeters = cross.totalDistanceMeters,
                            cost = cross.totalCost,
                            chainLabel = chain.joinToString(">"),
                        )
                    } finally {
                        if (gb != null && !sources[1].borrowed) sources[1].close(gb)
                        if (ga != null && !sources[0].borrowed) sources[0].close(ga)
                    }
                } else {
                    val cross = if (sources.any { it.borrowed }) {
                        graphLock.read {
                            routeAcrossChain(
                                sources, startIdx, goalIdx, preference, isCancelled, maxExpandedNodes,
                                stageTimingSink = logGraphInfo,
                            )
                        }
                    } else {
                        routeAcrossChain(
                            sources, startIdx, goalIdx, preference, isCancelled, maxExpandedNodes,
                            stageTimingSink = logGraphInfo,
                        )
                    }
                    if (!cross.isReachable) {
                        wasTruncated = wasTruncated || cross.wasTruncated
                        continue
                    }
                    return SolvedCrossLeg(
                        chain = chain,
                        parts = cross.parts,
                        distanceMeters = cross.totalDistanceMeters,
                        cost = cross.totalCost,
                        chainLabel = chain.joinToString(">"),
                    )
                }
            } catch (t: Throwable) {
                if (isCancelled()) return null
                if (t is OutOfMemoryError) {
                    logRouteError("ROUTE_FAILURE reason=out_of_memory")
                    throw t
                }
                logRouteWarn("CROSS_PREFECTURE chain failed ${chain.joinToString(">")}: ${t.message}")
                continue
            }
        }
        // 別のチェーンで同じ区間を解決できた場合は打ち切りとして扱わない。
        crossLegTruncated = crossLegTruncated || wasTruncated
        return null
    }

    val solvedLegs: List<SolvedCrossLeg> = if (stops.size == 2) {
        val fromPref = prefectures[0]!!
        val toPref = prefectures[1]!!
        val snapStarted = nowMs()
        val startIdx = withGraph(fromPref) { it.nearestWellConnectedNodeIndex(stops[0].latitude, stops[0].longitude) } ?: -1
        val goalIdx = withGraph(toPref) { it.nearestWellConnectedNodeIndex(stops[1].latitude, stops[1].longitude) } ?: -1
        endpointSnapMs += nowMs() - snapStarted
        if (isCancelled()) return MultiLegCrossOutcome.Cancelled
        if (startIdx < 0 || goalIdx < 0) return MultiLegCrossOutcome.Failed
        val solved = solveCrossLeg(fromPref, toPref, startIdx, goalIdx)
        if (isCancelled()) return MultiLegCrossOutcome.Cancelled
        if (solved == null) {
            return if (crossLegTruncated) MultiLegCrossOutcome.Truncated else MultiLegCrossOutcome.Failed
        }
        listOf(solved)
    } else {
        // 経由地ごとに候補ノード列を 1 度だけ解決し、前区間の終端ノードを次区間の始端ノードとしてそのまま使う。
        val stopCandidates = arrayOfNulls<IntArray>(stops.size)
        val snapStarted = nowMs()
        val indicesByPref = (0 until stops.size).groupBy { prefectures[it]!! }
        for ((pref, indices) in indicesByPref) {
            if (isCancelled()) return MultiLegCrossOutcome.Cancelled
            val ok = withGraph(pref) { graph ->
                for (k in indices) {
                    val stop = stops[k]
                    val touchesCross = (k > 0 && classifyLeg(prefectures[k - 1], prefectures[k]) == CrossLegKind.CROSS) ||
                        (k < stops.lastIndex && classifyLeg(prefectures[k], prefectures[k + 1]) == CrossLegKind.CROSS)
                    stopCandidates[k] = if (k == 0 || k == stops.lastIndex) {
                        val endpoint = graph.nearestWellConnectedNodeIndex(stop.latitude, stop.longitude)
                        if (endpoint >= 0) intArrayOf(endpoint) else IntArray(0)
                    } else {
                        val raw = graph.nearestNodeCandidates(stop.latitude, stop.longitude, radiusMeters = 400.0, maxCount = 24)
                        val filtered = filterWaypointCandidates(graph, stop.latitude, stop.longitude, raw)
                        if (touchesCross && filtered.isNotEmpty()) {
                            val well = graph.nearestWellConnectedNodeIndex(stop.latitude, stop.longitude)
                            val pos = filtered.indexOf(well)
                            if (pos > 0) filtered.copyOfRange(pos, filtered.size) else filtered
                        } else {
                            filtered
                        }
                    }
                }
            }
            if (ok == null) return MultiLegCrossOutcome.Failed
        }
        endpointSnapMs += nowMs() - snapStarted
        if (isCancelled()) return MultiLegCrossOutcome.Cancelled

        val cands = stopCandidates.map { it ?: IntArray(0) }
        for (i in 1 until stops.lastIndex) {
            if (cands[i].isEmpty()) return MultiLegCrossOutcome.WaypointUnreachable(i)
        }
        if (cands.first().isEmpty() || cands.last().isEmpty()) return MultiLegCrossOutcome.Failed

        val legSolutions = arrayOfNulls<SolvedCrossLeg>(stops.size - 1)
        val failedFrom = Array(stops.size) { HashSet<Int>() }
        val reachedAtLeg = Array(stops.size) { LinkedHashSet<Int>() }
        var maxReachedLeg = -1

        fun solveDirect(fromIdx: Int, toIdx: Int): SolvedCrossLeg? {
            val fromPref = prefectures.first()!!
            val toPref = prefectures.last()!!
            return if (classifyLeg(fromPref, toPref) == CrossLegKind.SAME) {
                solveSameLeg(fromPref, fromIdx, toIdx)
            } else {
                solveCrossLeg(fromPref, toPref, fromIdx, toIdx)
            }
        }

        fun searchFrom(leg: Int, startNode: Int): Int {
            if (isCancelled()) return 1
            if (startNode in failedFrom[leg]) return -1
            val nextStop = leg + 1
            val fromPref = prefectures[leg]!!
            val toPref = prefectures[nextStop]!!
            val kind = classifyLeg(fromPref, toPref)
            for (nextNode in cands[nextStop]) {
                if (isCancelled()) return 1
                if (nextStop < stops.lastIndex && nextNode in failedFrom[nextStop]) continue
                val solved = if (kind == CrossLegKind.SAME) {
                    solveSameLeg(fromPref, startNode, nextNode)
                } else {
                    solveCrossLeg(fromPref, toPref, startNode, nextNode)
                }
                if (isCancelled()) return 1
                if (solved == null) continue
                legSolutions[leg] = solved
                reachedAtLeg[nextStop].add(nextNode)
                if (leg > maxReachedLeg) maxReachedLeg = leg
                if (nextStop == stops.lastIndex) return 0
                val sub = searchFrom(leg + 1, nextNode)
                if (sub != -1) return sub
            }
            failedFrom[leg].add(startNode)
            return -1
        }

        var found = false
        for (startNode in cands[0]) {
            if (isCancelled()) return MultiLegCrossOutcome.Cancelled
            val status = searchFrom(0, startNode)
            if (status == 1) return MultiLegCrossOutcome.Cancelled
            if (status == 0) {
                found = true
                break
            }
        }
        if (!found) {
            if (isCancelled()) return MultiLegCrossOutcome.Cancelled
            if (crossLegTruncated) return MultiLegCrossOutcome.Truncated
            val directSolved = solveDirect(cands.first().first(), cands.last().first()) != null
            if (isCancelled()) return MultiLegCrossOutcome.Cancelled
            if (crossLegTruncated) return MultiLegCrossOutcome.Truncated
            if (!directSolved) return MultiLegCrossOutcome.Failed
            if (maxReachedLeg < 0) return MultiLegCrossOutcome.WaypointUnreachable(1)
            val stalledWaypoint = maxReachedLeg + 1
            if (stalledWaypoint >= stops.lastIndex - 1) {
                return MultiLegCrossOutcome.WaypointUnreachable(stalledWaypoint)
            }
            val stalledPref = prefectures[stalledWaypoint]!!
            val goalPref = prefectures.last()!!
            val canLeaveStalled = reachedAtLeg[stalledWaypoint].any { wIdx ->
                if (classifyLeg(stalledPref, goalPref) == CrossLegKind.SAME) {
                    solveSameLeg(stalledPref, wIdx, cands.last().first()) != null
                } else {
                    solveCrossLeg(stalledPref, goalPref, wIdx, cands.last().first()) != null
                }
            }
            if (isCancelled()) return MultiLegCrossOutcome.Cancelled
            if (!canLeaveStalled && crossLegTruncated) return MultiLegCrossOutcome.Truncated
            return MultiLegCrossOutcome.WaypointUnreachable(
                if (canLeaveStalled) stalledWaypoint + 1 else stalledWaypoint,
            )
        }
        legSolutions.map { it!! }
    }

    val partsByPref = solvedLegs.flatMap { leg ->
        leg.parts.mapIndexed { index, part -> leg.chain[index] to part }
    }
    var groupStart = 0
    while (groupStart < partsByPref.size) {
        if (isCancelled()) return MultiLegCrossOutcome.Cancelled
        val pref = partsByPref[groupStart].first
        var groupEnd = groupStart + 1
        while (groupEnd < partsByPref.size &&
            partsByPref[groupEnd].first == pref &&
            partsByPref[groupEnd - 1].second.nodeIds.lastOrNull() == partsByPref[groupEnd].second.nodeIds.firstOrNull()
        ) {
            groupEnd++
        }
        val ok = withGraph(pref) { graph ->
            val started = nowMs()
            val pathStart = routeParts.size
            for (i in groupStart until groupEnd) {
                val part = partsByPref[i].second
                if (part.nodeIndices.isEmpty()) return@withGraph false
                val grades = graph.segmentGradesAlongIndices(part.nodeIndices)
                val elevations = graph.nodeElevationsAt(part.nodeIndices)
                routeParts += CrossRoutePathPart(
                    part.nodeIds, part.coordinates, grades.sumOf { it.distanceMeters }, 0.0, part.nodeIndices,
                )
                elevationParts += CrossElevationPart(grades, elevations)
            }
            instructionParts += buildCrossInstructionPart(graph, routeParts.subList(pathStart, routeParts.size))
            guidanceElevationMs += nowMs() - started
            true
        }
        if (ok != true) return MultiLegCrossOutcome.Failed
        groupStart = groupEnd
    }

    if (isCancelled()) return MultiLegCrossOutcome.Cancelled
    logGraphInfo("CHAIN_STAGE name=endpoint_snap ms=$endpointSnapMs")
    logGraphInfo("CHAIN_STAGE name=guidance_elevation ms=$guidanceElevationMs")
    val merged = mergeRoutePathParts(routeParts)
    return MultiLegCrossOutcome.Success(
        MultiLegRouteResult(
            MappedRouteResult(
                merged.nodeIds, solvedLegs.sumOf { it.distanceMeters }, solvedLegs.sumOf { it.cost },
                merged.coordinates, nodeIndices = emptyList(),
            ),
            mergeInstructionParts(instructionParts),
            mergeElevationParts(elevationParts),
            totalLoadMs,
            solvedLegs.joinToString("|") { it.chainLabel },
            portalMs,
            aMs,
            bMs,
        ),
    )
}
