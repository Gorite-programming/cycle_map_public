package com.gorite.cyclemap.ui.mapview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorMapLogicTest {
    private val eps = 1e-9

    @Test
    fun `表示領域が確定していれば上下左右に20パーセントの余白を足す`() {
        val b = poiFetchBounds(34.4, 132.4, 14.0, south = 34.39, north = 34.41, west = 132.39, east = 132.41)
        assertEquals(34.39 - 0.02 * 0.20, b.minLat, eps)
        assertEquals(34.41 + 0.02 * 0.20, b.maxLat, eps)
        assertEquals(132.39 - 0.02 * 0.20, b.minLon, eps)
        assertEquals(132.41 + 0.02 * 0.20, b.maxLon, eps)
    }

    @Test
    fun `表示領域が未確定なら中心基準でズーム応じた最小幅を確保する`() {
        // z14: 360/2^14 = 0.02197 度。半幅は 0.60 倍
        val b = poiFetchBounds(34.4, 132.4, 14.0, 34.4, 34.4, 132.4, 132.4)
        val half = (360.0 / Math.pow(2.0, 14.0)) * 0.60
        assertEquals(34.4 - half, b.minLat, eps)
        assertEquals(34.4 + half, b.maxLat, eps)
        assertEquals(132.4 - half, b.minLon, eps)
        assertEquals(132.4 + half, b.maxLon, eps)
    }

    @Test
    fun `最小幅はズームに依らず0_008から2度に収まる`() {
        val far = poiFetchBounds(0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals(2.0 * 0.60, far.maxLat, eps)
        val near = poiFetchBounds(0.0, 0.0, 22.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals(0.008 * 0.60, near.maxLat, eps)
    }

    @Test
    fun `幅0_002度ちょうどは確定扱い`() {
        val b = poiFetchBounds(10.0, 10.0, 14.0, 10.0, 10.002, 10.0, 10.002)
        assertEquals(10.0 - 0.002 * 0.20, b.minLat, eps)
    }

    @Test
    fun `再取得を省くのは20m未満かつズーム差0_2未満のときだけ`() {
        assertTrue(shouldSkipPoiRefetch(34.0, 132.0, 14.0, 34.0, 132.0, 14.1))
        assertFalse(shouldSkipPoiRefetch(34.0, 132.0, 14.0, 34.0, 132.0, 14.3))
        assertFalse(shouldSkipPoiRefetch(34.0, 132.0, 14.0, 34.001, 132.0, 14.0)) // 約111m
    }

    @Test
    fun `前回位置が無いか現在位置が不明なら再取得する`() {
        assertFalse(shouldSkipPoiRefetch(null, null, -1.0, 34.0, 132.0, 14.0))
        assertFalse(shouldSkipPoiRefetch(34.0, 132.0, 14.0, null, null, 14.0))
    }
}
