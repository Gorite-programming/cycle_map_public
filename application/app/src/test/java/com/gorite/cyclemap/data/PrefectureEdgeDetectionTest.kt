package com.gorite.cyclemap.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 県の矩形が実際の県域より狭い所でも、行政界で正しい県を返す (S21 で岩国が広島県と判定され、地図が真っ黒になった)。 */
class PrefectureEdgeDetectionTest {
    @Test
    fun iwakuniIsYamaguchiEvenThoughTheBoxStopsShort() {
        assertEquals("山口県", findPrefectureName(34.1720, 132.2255)) // 岩国駅
        assertEquals("山口県", findPrefectureName(34.2010, 132.2200)) // 和木町
        assertEquals("広島県", findPrefectureName(34.2385, 132.2228)) // 大竹駅
    }

    @Test
    fun outlyingPartsOutsideTheBoxes() {
        assertEquals("沖縄県", findPrefectureName(24.3400, 124.1550)) // 石垣島
    }
}
