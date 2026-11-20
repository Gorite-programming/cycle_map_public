package com.gorite.cyclemap.weather

import com.gorite.cyclemap.data.AddressDisplayController
import org.junit.Assert.*
import org.junit.Test

class WeatherLocationTest {
    @Test fun distanceIsFiniteAtCoincidentAndAntipodalPoints() {
        assertEquals(0.0, WeatherRepository.distanceMeters(35.0, 135.0, 35.0, 135.0), 0.0)
        for (lat in -90..90) {
            val distance = WeatherRepository.distanceMeters(lat.toDouble(), 0.0, -lat.toDouble(), 180.0)
            assertTrue("antipodal latitude $lat", distance.isFinite())
            assertEquals(Math.PI * 6371000.0, distance, 0.2)
        }
        assertEquals(111194.93, WeatherRepository.distanceMeters(0.0, 0.0, 0.0, 1.0), 0.01)
    }

    @Test fun cityHintRejectsPlaceholdersAndKeepsResolvedNames() {
        for (label in listOf(null, "", "  ", AddressDisplayController.ADDRESS_LOADING,
            AddressDisplayController.ADDRESS_UNKNOWN, "名称なし")) {
            assertNull(weatherCityHint(label))
        }
        assertEquals("山口県山口市", weatherCityHint(" 山口県山口市 "))
        assertEquals("岩国市", weatherCityHint("岩国市"))
    }
}
