package com.gorite.cyclemap.ui.hazard

import com.gorite.cyclemap.hazard.HazardFile
import com.gorite.cyclemap.hazard.HazardIndex
import com.gorite.cyclemap.hazard.HazardKind
import com.gorite.cyclemap.routing.RoutePoint
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class RouteHazardsStateTest {

    @Before
    @After
    fun resetCache() {
        HazardIndexCache.clearForTest()
    }

    @Test
    fun computeRouteHazards_returnsEmptyWhenIndexMissingOrRouteTooShort() = runBlocking {
        val route = listOf(
            RoutePoint(34.3900, 132.4600),
            RoutePoint(34.3910, 132.4600),
        )
        assertTrue(computeRouteHazards(route, indexProvider = { null }).isEmpty())
        assertTrue(computeRouteHazards(route, indexProvider = { error("boom") }).isEmpty())

        var providerCalled = false
        val probeProvider: suspend () -> HazardIndex? = {
            providerCalled = true
            null
        }
        assertTrue(computeRouteHazards(emptyList(), probeProvider).isEmpty())
        assertTrue(computeRouteHazards(listOf(RoutePoint(34.3900, 132.4600)), probeProvider).isEmpty())
        assertEquals(false, providerCalled)
    }

    @Test
    fun computeRouteHazards_matchesSampleHazardsBinAlongHandCraftedRoute() = runBlocking {
        val bytes = javaClass.classLoader?.getResourceAsStream("hazards_small.bin")?.use { it.readBytes() }
            ?: File("src/test/resources/hazards_small.bin").takeIf { it.isFile }?.readBytes()
        assumeTrue("hazards_small.bin が存在しないためスキップ", bytes != null)

        val index = HazardIndex.build(HazardFile.parse(bytes!!)!!)
        val route = listOf(
            RoutePoint(34.3900, 132.4600),
            RoutePoint(34.3901, 132.4600), // 踏切 (34.3901, 132.4600)
            RoutePoint(34.3903, 132.4600), // 見本橋 始点
            RoutePoint(34.3906, 132.4600), // 見本橋 中点
            RoutePoint(34.3909, 132.4600), // 見本橋 終点
            RoutePoint(34.3910, 132.4600),
        )

        val hazards = computeRouteHazards(route, indexProvider = { index })
        assertEquals(2, hazards.size)
        assertEquals(HazardKind.CROSSING, hazards[0].kind)
        assertNull(hazards[0].name)
        assertEquals(HazardKind.BRIDGE, hazards[1].kind)
        assertEquals("見本橋", hazards[1].name)
        assertTrue(hazards[0].startMeters < hazards[1].startMeters)
    }

    @Test
    fun hazardIndexCache_reusesLoadedIndexAndClearsWhenFileMissing() = runBlocking {
        val missing = File("nonexistent_hazard_cache_file.bin")
        assertNull(HazardIndexCache.loadOrReuse(missing))

        val bytes = javaClass.classLoader?.getResourceAsStream("hazards_small.bin")?.use { it.readBytes() }
            ?: File("src/test/resources/hazards_small.bin").takeIf { it.isFile }?.readBytes()
        assumeTrue("hazards_small.bin が存在しないためスキップ", bytes != null)

        val tempFile = File.createTempFile("hazard_cache_", ".bin")
        try {
            tempFile.writeBytes(bytes!!)
            var loadCount = 0
            val countingLoader: (File) -> HazardIndex? = { f ->
                loadCount++
                HazardFile.read(f)?.let(HazardIndex::build)
            }

            val first = HazardIndexCache.loadOrReuse(tempFile, countingLoader)
            val second = HazardIndexCache.loadOrReuse(tempFile, countingLoader)
            assertSame(first, second)
            assertEquals(1, loadCount)

            assertTrue(tempFile.delete())
            assertNull(HazardIndexCache.loadOrReuse(tempFile, countingLoader))
            assertEquals(1, loadCount)
        } finally {
            tempFile.delete()
        }
    }
}
