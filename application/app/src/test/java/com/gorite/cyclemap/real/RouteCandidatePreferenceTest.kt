package com.gorite.cyclemap.real

import com.gorite.cyclemap.navigation.displayedRouteDistance
import com.gorite.cyclemap.navigation.connectRouteEndpoints
import com.gorite.cyclemap.navigation.trimSingleGraphRouteSafe
import com.gorite.cyclemap.routing.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File

class RouteCandidatePreferenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val points = listOf(0.0 to 0.0, 0.001 to 0.001, -0.001 to 0.001,
        0.002 to 0.001, 0.0 to 0.002)

    // Three separate paths: steep and short, flat and longer, and a supplied primary.
    private fun graph(): LazyMappedRoadGraph {
        val file = File(temporary.root, "test.graph")
        val index = File(temporary.root, "test.graph.idx")
        val edges = listOf(listOf(1 to 1000.0, 2 to 1400.0, 3 to 10000.0),
            listOf(4 to 1000.0), listOf(4 to 1400.0), listOf(4 to 10000.0), emptyList())
        val starts = LongArray(points.size)
        DataOutputStream(file.outputStream()).use { out ->
            out.writeUTF("CYCLEMAP_GRAPH_V1")
            out.writeInt(points.size)
            points.forEachIndexed { i, (lat, lon) ->
                out.writeLong(i.toLong() + 1); out.writeDouble(lat); out.writeDouble(lon)
            }
            edges.forEachIndexed { from, outgoing ->
                starts[from] = out.size().toLong()
                outgoing.forEach { (to, distance) ->
                    out.writeLong(from.toLong() + 1); out.writeLong(to.toLong() + 1)
                    out.writeDouble(distance); out.writeUTF("residential"); out.writeByte(1)
                    out.writeByte(1); out.writeDouble(if (from == 1 || to == 1) 12.0 else 0.0)
                }
            }
        }
        DataOutputStream(index.outputStream()).use { out ->
            out.writeUTF("CYCLEMAP_INDEX_V2"); out.writeInt(points.size)
            starts.forEach(out::writeLong)
            var ordinal = 0
            edges.forEach { out.writeInt(ordinal); ordinal += it.size }
            edges.forEach { out.writeInt(it.size) }
            out.writeInt(ordinal)
            edges.flatten().forEach { out.writeInt(it.first) }
        }
        return LazyMappedRoadGraph.load(file, index)
    }

    private fun primary() = MappedRouteResult(
        nodeIds = listOf(1L, 4L, 5L), totalDistanceMeters = 20000.0, totalCost = 20000.0,
        coordinates = listOf(points[0], points[3], points[4]), nodeIndices = listOf(0, 3, 4),
    )

    @Test fun alternativesFollowSelectedPreference() {
        graph().use { graph ->
            val planner = RealRoutePlanner({ graph })
            val flat = planner.buildOptionsFromPrimary(graph, 0, 4, primary(), preference = RoutePreference.FLAT)
            val hills = planner.buildOptionsFromPrimary(graph, 0, 4, primary(), preference = RoutePreference.HILL_CLIMB)
            assertEquals(listOf(1L, 3L, 5L), flat.last().nodeIds)
            assertEquals(listOf(1L, 2L, 5L), hills.last().nodeIds)
            assertFalse(flat.last().isPrimary)
            assertFalse(hills.last().isPrimary)
            assertEquals("astar", graph.lastEngine)
            for (option in flat + hills) {
                assertEquals(displayedRouteDistance(option.points), option.distanceMeters, 1e-6)
            }
        }
    }

    @Test fun primaryIncludesEndpointConnectionsInBothCardAndDisplayedLine() {
        graph().use { graph ->
            val primary = primary()
            val line = listOf(-0.0001 to 0.0) + primary.coordinates + listOf(0.0001 to 0.002)
            val option = RealRoutePlanner({ graph }).buildOptionsFromPrimary(
                graph, -1, -1, primary, primaryDisplayPoints = line, primaryNodePointOffset = 1,
            ).single()
            assertEquals(line, option.points)
            assertEquals(displayedRouteDistance(line), option.distanceMeters, 1e-6)
            assertEquals(primary.coordinates,
                option.points.drop(option.nodePointOffset).take(option.nodeIds.size))
            assertTrue(option.distanceMeters < primary.totalDistanceMeters)
        }
    }

    @Test fun alternativeContainingAllTrimmedPrimaryNodesIsExcluded() {
        graph().use { graph ->
            // The primary has lost endpoint nodes; the untrimmed alternative contains it entirely.
            val trimmedPrimary = primary().copy(
                nodeIds = listOf(3L, 5L), coordinates = listOf(points[2], points[4]), nodeIndices = listOf(2, 4),
            )
            val options = RealRoutePlanner({ graph }).buildOptionsFromPrimary(
                graph, 0, 4, trimmedPrimary, preference = RoutePreference.FLAT,
            )
            assertEquals(1, options.size)
        }
    }

    @Test fun alternativeAndPrimaryIncludeTheSameEndpointConnections() {
        graph().use { graph ->
            val stops = listOf(0.0 to -0.0001, 0.0 to 0.0021)
            val primary = primary()
            val display = connectRouteEndpoints(primary, null, stops)
            val options = RealRoutePlanner({ graph }).buildOptionsFromPrimary(
                graph, 0, 4, primary, preference = RoutePreference.FLAT,
                primaryDisplayPoints = display.points, primaryNodePointOffset = display.nodePointOffset,
                endpointStops = stops,
            )
            assertEquals(2, options.size)
            val connectorDistance = displayedRouteDistance(listOf(stops.first(), points.first())) +
                displayedRouteDistance(listOf(points.last(), stops.last()))
            for (option in options) {
                val nodes = option.points.drop(option.nodePointOffset).take(option.nodeIds.size)
                assertEquals(stops.first(), option.points.first())
                assertEquals(stops.last(), option.points.last())
                assertEquals(1, option.nodePointOffset)
                assertEquals(displayedRouteDistance(nodes) + connectorDistance, option.distanceMeters, 1e-6)
            }
        }
    }

    @Test fun alternativeIsTrimmedBeforeItsDisplayGeometryIsBuilt() {
        graph().use { graph ->
            val stops = listOf(-0.0005 to 0.0005, points.last())
            val primaryTrim = trimSingleGraphRouteSafe(primary(), stops)!!
            val display = connectRouteEndpoints(primaryTrim.result, primaryTrim, stops)
            val options = RealRoutePlanner({ graph }).buildOptionsFromPrimary(
                graph, 0, 4, primaryTrim.result, preference = RoutePreference.FLAT,
                primaryDisplayPoints = display.points, primaryNodePointOffset = display.nodePointOffset,
                endpointStops = stops,
            )
            assertEquals(2, options.size)
            val alternative = options.last()
            assertEquals(listOf(3L, 5L), alternative.nodeIds)
            assertEquals(stops.first(), alternative.points.first())
            assertEquals(listOf(points[2], points[4]), alternative.points.drop(alternative.nodePointOffset))
            assertEquals(displayedRouteDistance(alternative.points), alternative.distanceMeters, 1e-6)
        }
    }
}
