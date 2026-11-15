package com.gorite.cyclemap.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CompassLabelsTest {
    private val labels = listOf("北", "北東", "東", "南東", "南", "南西", "西", "北西")

    @Test
    fun sectorCentersAndEveryBoundary() {
        labels.forEachIndexed { index, label ->
            assertEquals(label, compassLabel8(index * 45.0))
            val boundary = index * 45.0 + 22.5
            val nextLabel = labels[(index + 1) % 8]
            assertEquals("before $boundary", label, compassLabel8(boundary - 0.000001))
            assertEquals("at $boundary", nextLabel, compassLabel8(boundary))
            assertEquals("after $boundary", nextLabel, compassLabel8(boundary + 0.000001))
        }
    }

    @Test
    fun negativeAndMultipleRevolutions() {
        val angles = listOf(0.0, 22.499999, 22.5, 45.0, 67.5, 112.5, 157.5, 202.5, 247.5, 292.5, 337.5, 359.999999)
        angles.forEach { angle ->
            for (turns in -3..3) {
                assertEquals("angle $angle turns $turns", compassLabel8(angle), compassLabel8(angle + turns * 360.0))
            }
        }
        assertEquals("北西", compassLabel8(-45.0))
        assertEquals("北", compassLabel8(360.0))
        assertEquals("北東", compassLabel8(765.0))
    }
}
