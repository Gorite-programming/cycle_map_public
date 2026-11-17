package com.gorite.cyclemap.routing

import java.io.*
import kotlin.test.*

class ExcludedRoadIndexTest {
    private val info = classifyBicycleRoad(mapOf("highway" to "motorway", "bicycle" to "no"), false)
    private fun temporary() = File.createTempFile("excluded-road", ".bikerules").apply { deleteOnExit() }
    private fun segment(a: Double = 34.0, b: Double = 132.0, c: Double = 34.001, d: Double = 132.001) =
        ExcludedSegment(a, b, c, d, info)

    @Test fun roundTripPreservesDirectionsInfoAndUniqueNegativeOrdinals() {
        val file = temporary()
        val forward = segment()
        val reverse = ExcludedSegment(forward.toLat, forward.toLon, forward.fromLat, forward.fromLon,
            classifyBicycleRoad(mapOf("highway" to "trunk", "motorroad" to "yes", "sidewalk:left" to "yes"), true))
        ExcludedRoadIndex.write(file, listOf(forward, reverse))
        val index = assertNotNull(ExcludedRoadIndex.load(file))
        val candidates = index.candidatesNear(34.0005, 132.0005)
        assertEquals(listOf(-1, -2), candidates.map { it.ordinal })
        assertEquals(listOf(forward, reverse), candidates.map { ExcludedSegment(it.fromLat, it.fromLon, it.toLat, it.toLon, it.info!!) })
        ExcludedRoadIndex.write(file, emptyList())
        assertTrue(assertNotNull(ExcludedRoadIndex.load(file)).candidatesNear(34.0, 132.0).isEmpty())
    }

    @Test fun rejectsMissingWrongMagicTruncatedAndMismatchedCounts() {
        val file = temporary()
        assertTrue(file.delete())
        assertNull(ExcludedRoadIndex.load(file))
        DataOutputStream(file.outputStream()).use { it.writeUTF("WRONG"); it.writeInt(0) }
        assertNull(ExcludedRoadIndex.load(file))
        ExcludedRoadIndex.write(file, listOf(segment()))
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 1))
        assertNull(ExcludedRoadIndex.load(file))
        for (count in listOf(-1, 0, 2, Int.MAX_VALUE)) {
            file.writeBytes(bytes)
            RandomAccessFile(file, "rw").use { it.readUTF(); it.writeInt(count) }
            assertNull(ExcludedRoadIndex.load(file))
        }
        file.writeBytes(bytes + byteArrayOf(0))
        assertNull(ExcludedRoadIndex.load(file))
    }

    @Test fun usesDistanceToWholeSegmentAcrossGridCells() {
        val file = temporary()
        ExcludedRoadIndex.write(file, listOf(segment(34.0, 131.8, 34.0, 132.2), segment(34.01, 132.0, 34.02, 132.0)))
        val index = assertNotNull(ExcludedRoadIndex.load(file))
        assertEquals(listOf(-1), index.candidatesNear(34.0001, 132.0, 20.0).map { it.ordinal })
        assertTrue(index.candidatesNear(34.0003, 132.0, 20.0).isEmpty())
        assertTrue(index.candidatesNear(34.0, 132.21, 100.0).isEmpty())
        assertEquals(1, index.candidatesNear(34.0, 132.0, 0.0).size)
    }

    @Test fun handlesGridBoundariesNegativeCoordinatesAndVeryLongSegments() {
        val file = temporary()
        ExcludedRoadIndex.write(file, listOf(segment(-34.01, -132.01, -34.0, -132.0), segment(-50.0, -150.0, 50.0, 150.0)))
        val index = assertNotNull(ExcludedRoadIndex.load(file))
        assertEquals(listOf(-1), index.candidatesNear(-34.0, -132.0, 1.0).map { it.ordinal })
        assertEquals(listOf(-2), index.candidatesNear(0.0, 0.0, 1.0).map { it.ordinal })
        assertTrue(index.candidatesNear(Double.NaN, 0.0).isEmpty())
        assertTrue(index.candidatesNear(0.0, 0.0, -1.0).isEmpty())
    }

    @Test fun roundsCoordinatesToMicrodegreesAndPreservesPreviousFileOnFailure() {
        val file = temporary()
        ExcludedRoadIndex.write(file, listOf(segment(34.0000006)))
        val original = file.readBytes()
        assertEquals(34.000001, assertNotNull(ExcludedRoadIndex.load(file)).candidatesNear(34.0, 132.0).single().fromLat)
        assertFails { ExcludedRoadIndex.write(file, listOf(segment(Double.NaN))) }
        assertContentEquals(original, file.readBytes())
    }

    @Test fun indexesTwoHundredThousandSyntheticSegments() {
        val file = temporary()
        ExcludedRoadIndex.write(file, List(200_000) { i ->
            val lat = 34.0 + (i / 1000) * 0.001
            val lon = 132.0 + (i % 1000) * 0.001
            segment(lat, lon, lat + 0.0001, lon + 0.0001)
        })
        val start = System.nanoTime()
        val index = assertNotNull(ExcludedRoadIndex.load(file))
        val loaded = System.nanoTime()
        repeat(1000) {
            val nearby = index.candidatesNear(34.1, 132.5)
            assertTrue(nearby.isNotEmpty() && nearby.size < 30)
        }
        println("synthetic segments=200000; loadMs=${(loaded - start) / 1_000_000}; queries1000Ms=${(System.nanoTime() - loaded) / 1_000_000}")
    }
}
