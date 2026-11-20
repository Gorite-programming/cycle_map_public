package com.gorite.cyclemap.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AreaSelectionBoundsTest {
    @Test
    fun includesOtherTwoCornersAtFortyFiveDegreeRotation() {
        val bounds = projectAreaSelectionBounds(-1f, -1f, 1f, 1f) { x, y ->
            // 対角二点だけでは経度がどちらも 135 となり範囲が潰れる。
            (35.0 + x + y) to (135.0 + x - y)
        }
        assertEquals(AreaSelectionBounds(37.0, 137.0, 33.0, 133.0), bounds)
    }

    @Test
    fun includesAllCornersOfPitchedTrapezoid() {
        val bounds = projectAreaSelectionBounds(-1f, -1f, 1f, 1f) { x, y ->
            (35.0 - y) to (135.0 + x * if (y < 0) 3.0 else 1.0)
        }
        assertEquals(AreaSelectionBounds(36.0, 138.0, 34.0, 132.0), bounds)
    }

    @Test
    fun rejectsMissingNonFiniteAndOutOfRangeProjections() {
        assertNull(projectAreaSelectionBounds(0f, 0f, 1f, 1f) { _, _ -> null })
        assertNull(projectAreaSelectionBounds(0f, 0f, 1f, 1f) { x, y ->
            if (x == 1f && y == 0f) Double.NaN to 135.0 else 35.0 to 135.0
        })
        assertNull(projectAreaSelectionBounds(0f, 0f, 1f, 1f) { _, _ -> 91.0 to 135.0 })
        assertNull(projectAreaSelectionBounds(0f, 0f, 1f, 1f) { _, _ -> 35.0 to Double.POSITIVE_INFINITY })
    }

    @Test
    fun ignoresSelectionWhenProjectionThrows() {
        assertNull(projectAreaSelectionBounds(0f, 0f, 1f, 1f) { _, _ ->
            throw IllegalArgumentException("逆投影できません")
        })
    }
}
