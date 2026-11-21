package com.gorite.cyclemap.hazard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class HazardFileTest {

    @Test
    fun parseAndRead_roundTripCrossingsAndBridgesWithJapaneseAndNullNames() {
        val crossings = listOf(
            34.1665432 to 132.1783456,
            34.3910000 to 132.4610000,
            34.3920000 to 132.4620000,
        )
        val bridges = listOf(
            BridgeSpec(
                name = "錦帯橋",
                points = listOf(
                    34.1676100 to 132.1783900,
                    34.1679500 to 132.1794500,
                    34.1683000 to 132.1805000,
                ),
            ),
            BridgeSpec(
                name = null,
                points = listOf(
                    34.3910000 to 132.4613000,
                    34.3910000 to 132.4618000,
                ),
            ),
            BridgeSpec(
                name = "見本橋",
                points = listOf(
                    34.3903000 to 132.4600000,
                    34.3906000 to 132.4600000,
                ),
            ),
        )
        val bytes = HazardTestWriter.buildBytes(crossings, bridges)

        val parsed = HazardFile.parse(bytes)
        assertNotNull(parsed)
        parsed!!
        assertEquals(crossings.size, parsed.crossingCount)
        for (i in crossings.indices) {
            assertEquals(crossings[i].first, parsed.crossingLatitudes[i], 1e-7)
            assertEquals(crossings[i].second, parsed.crossingLongitudes[i], 1e-7)
        }
        assertEquals(bridges.size, parsed.bridges.size)
        for (i in bridges.indices) {
            val expected = bridges[i]
            val actual = parsed.bridges[i]
            assertEquals(expected.name, actual.name)
            assertEquals(expected.points.size, actual.pointCount)
            for (k in expected.points.indices) {
                assertEquals(expected.points[k].first, actual.latitudes[k], 1e-7)
                assertEquals(expected.points[k].second, actual.longitudes[k], 1e-7)
            }
        }

        val tempFile = File.createTempFile("hazards_test_", ".bin")
        try {
            tempFile.writeBytes(bytes)
            val readData = HazardFile.read(tempFile)
            assertNotNull(readData)
            assertEquals(parsed.crossingCount, readData!!.crossingCount)
            assertEquals(parsed.bridges.size, readData.bridges.size)
            assertEquals("錦帯橋", readData.bridges[0].name)
            assertNull(readData.bridges[1].name)
            assertEquals("見本橋", readData.bridges[2].name)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun parseAndRead_corruptedOrMissingInputsReturnNullWithoutThrowing() {
        val valid = HazardTestWriter.buildBytes(
            crossings = listOf(34.3901 to 132.4600),
            bridges = listOf(
                BridgeSpec("テスト橋", listOf(34.3903 to 132.4600, 34.3906 to 132.4600)),
            ),
        )

        // 空のバイト列・存在しないファイル
        assertNull(HazardFile.parse(ByteArray(0)))
        assertNull(HazardFile.read(File("nonexistent_hazards_file_123456.bin")))

        // magic 違い
        val badMagic = valid.copyOf().apply { this[0] = 'X'.code.toByte() }
        assertNull(HazardFile.parse(badMagic))

        // version 違い (CRC は正しく再計算してバージョン検証が効くことも確認)
        val badVersion = recomputeCrc(
            valid.copyOf().apply {
                ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putShort(4, 2)
            },
        )
        assertNull(HazardFile.parse(badVersion))

        // reserved != 0
        val badReserved = recomputeCrc(
            valid.copyOf().apply {
                ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putShort(6, 1)
            },
        )
        assertNull(HazardFile.parse(badReserved))

        // 途中で切れた
        assertNull(HazardFile.parse(valid.copyOf(10)))
        assertNull(HazardFile.parse(valid.copyOf(valid.size - 1)))
        assertNull(HazardFile.parse(valid.copyOf(valid.size - 8)))

        // CRC 違い
        val badCrc = valid.copyOf().apply { this[size - 1] = (this[size - 1].toInt() xor 0xFF).toByte() }
        assertNull(HazardFile.parse(badCrc))

        // 橋の点数が 2 未満 (改ざん + CRC 再計算)
        val singlePointBridge = recomputeCrc(
            valid.copyOf().apply {
                // header(20) + crossing(8) + nameOffset(4) -> pointCount at offset 32
                ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putShort(32, 1)
            },
        )
        assertNull(HazardFile.parse(singlePointBridge))

        // 存在しない名前オフセット (改ざん + CRC 再計算)
        val badNameOffset = recomputeCrc(
            valid.copyOf().apply {
                // header(20) + crossing(8) -> nameOffset at offset 28
                ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(28, 9999)
            },
        )
        assertNull(HazardFile.parse(badNameOffset))
    }

    @Test
    fun parse_dropsOutOfBoundsCoordinates() {
        val crossings = listOf(
            19.9999999 to 135.0, // 南限未満 -> 除外
            46.0000001 to 135.0, // 北限超過 -> 除外
            35.0 to 121.9999999, // 西限未満 -> 除外
            35.0 to 154.0000001, // 東限超過 -> 除外
            20.0 to 122.0,       // 南西境界 -> 保持
            46.0 to 154.0,       // 北東境界 -> 保持
            34.39 to 132.46,     // 通常 -> 保持
        )
        val bridges = listOf(
            BridgeSpec(
                name = "範囲外を含む橋",
                points = listOf(34.39 to 132.46, 47.0 to 132.46),
            ),
            BridgeSpec(
                name = "範囲内の橋",
                points = listOf(34.3903 to 132.4600, 34.3906 to 132.4600),
            ),
        )
        val parsed = HazardFile.parse(HazardTestWriter.buildBytes(crossings, bridges))
        assertNotNull(parsed)
        parsed!!

        assertEquals(3, parsed.crossingCount)
        assertEquals(20.0, parsed.crossingLatitudes[0], 1e-7)
        assertEquals(122.0, parsed.crossingLongitudes[0], 1e-7)
        assertEquals(46.0, parsed.crossingLatitudes[1], 1e-7)
        assertEquals(154.0, parsed.crossingLongitudes[1], 1e-7)
        assertEquals(34.39, parsed.crossingLatitudes[2], 1e-7)
        assertEquals(132.46, parsed.crossingLongitudes[2], 1e-7)

        assertEquals(1, parsed.bridges.size)
        assertEquals("範囲内の橋", parsed.bridges[0].name)
    }

    @Test
    fun readSampleResourceIfPresent() {
        val stream = javaClass.classLoader?.getResourceAsStream("hazards_small.bin")
        val fallbackFile = File("src/test/resources/hazards_small.bin")
        val bytes = stream?.use { it.readBytes() } ?: fallbackFile.takeIf { it.isFile }?.readBytes()
        assumeTrue("hazards_small.bin が存在しないためスキップ", bytes != null)

        val data = HazardFile.parse(bytes!!)
        assertNotNull(data)
        data!!
        assertTrue(data.crossingCount >= 1)
        assertTrue(data.bridges.isNotEmpty())
        assertTrue(data.bridges.any { it.name == "見本橋" })
    }

    private fun recomputeCrc(bytes: ByteArray): ByteArray {
        val payloadEnd = bytes.size - 4
        val crc = CRC32().apply { update(bytes, 0, payloadEnd) }.value
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(payloadEnd, crc.toInt())
        return bytes
    }
}
