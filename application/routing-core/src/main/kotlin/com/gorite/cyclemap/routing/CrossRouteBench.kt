package com.gorite.cyclemap.routing

import java.io.File
import java.util.Locale

/** ./gradlew :routing-core:crossRouteBench -PgraphA=... -PgraphB=... -Pod="lat,lon,lat,lon[;...]" */
fun main(args: Array<String>) {
    require(args.size == 3) { "Expected graphA graphB od" }
    val fileA = File(args[0]); val fileB = File(args[1])
    val ods = args[2].split(';').map { row ->
        val values = row.split(',').map(String::trim).map(String::toDouble)
        require(values.size == 4) { "Each OD must be lat,lon,lat,lon" }
        values
    }
    LazyMappedRoadGraph.load(fileA).use { a -> LazyMappedRoadGraph.load(fileB).use { b ->
        val portalStarted = System.nanoTime()
        val portals = portalsBetween(a, b)
        val portalMs = (System.nanoTime() - portalStarted) / 1e6
        println("portals=${portals.size} portalMatchingMs=${fmt(portalMs)}")
        for ((index, od) in ods.withIndex()) {
            val start = a.nearestNodeIndex(od[0], od[1]); val goal = b.nearestNodeIndex(od[2], od[3])
            val tA = System.nanoTime()
            val reach = a.routeToPortals(start, portals.aIndices)
            val aMs = (System.nanoTime() - tA) / 1e6
            if (!reach.wasCancelled && !reach.wasTruncated) {
                val costs = reach.portals.associateBy { it.aIndex }
                val sources = portals.nodeIds.indices.mapNotNull { k -> costs[portals.aIndices[k]]?.let { PortalSource(it.nodeId, it.aIndex, portals.bIndices[k], it.cost, it.distanceMeters) } }
                val tB = System.nanoTime()
                val routed = b.routeFromPortals(sources, goal)
                val bMs = (System.nanoTime() - tB) / 1e6
                val total = aMs + bMs + portalMs
                val portalNode = routed.seedPortalId?.let { id -> (0 until portals.size).firstOrNull { portals.nodeIds[it] == id }?.let { b.nodeAt(portals.bIndices[it]) } }
                println(String.format(Locale.US,
                    "OD%d A(start=%d,expanded=%d,ms=%.1f) B(goal=%d,expanded=%d,ms=%.1f) totalMs=%.1f reachable=%s distanceM=%.1f cost=%.3f portalId=%s portalCoord=%s aNodes=%d bNodes=%d cancelled=%s truncated=%s",
                    index + 1, start, reach.expandedNodes, aMs, goal, routed.expandedNodes, bMs, total, routed.isReachable,
                    routed.totalDistanceMeters, routed.totalCost, routed.seedPortalId ?: "-",
                    portalNode?.let { "${it.latitude},${it.longitude}" } ?: "-", a.nodeCount, b.nodeCount, routed.wasCancelled, routed.wasTruncated))
            } else println("OD${index + 1} A expanded=${reach.expandedNodes} ms=${fmt(aMs)} cancelled=${reach.wasCancelled} truncated=${reach.wasTruncated}")
        }
    } }
}

private fun fmt(value: Double) = String.format(Locale.US, "%.1f", value)
