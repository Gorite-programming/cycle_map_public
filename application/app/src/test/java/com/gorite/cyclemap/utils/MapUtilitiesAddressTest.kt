package com.gorite.cyclemap.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MapUtilitiesAddressTest {
    @Test
    fun removesDuplicatedMunicipalitySuffixAtAddressStart() {
        assertEquals("広島市南区 松原町2-37", normalizePlaceAddress("広島市南区 南区松原町2-37"))
    }

    @Test
    fun keepsAddressWhenThereIsNoPrefixOverlap() {
        assertEquals("広島市南区 比治山本町", normalizePlaceAddress("広島市南区 比治山本町"))
    }
}
