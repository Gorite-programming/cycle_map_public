package com.gorite.cyclemap.hazard

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * 注意地点データファイル内の 1 本の橋の座標列。
 */
class BridgeHazard(
    val name: String?,
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
) {
    val pointCount: Int get() = latitudes.size

    init {
        require(latitudes.size == longitudes.size) {
            "latitudes.size (${latitudes.size}) != longitudes.size (${longitudes.size})"
        }
        require(latitudes.size >= 2) {
            "Bridge must have at least 2 points, got ${latitudes.size}"
        }
    }
}

/**
 * `hazards.bin` から読み込んだ踏切・橋の一式。
 */
class HazardData(
    val crossingLatitudes: DoubleArray,
    val crossingLongitudes: DoubleArray,
    val bridges: List<BridgeHazard>,
) {
    val crossingCount: Int get() = crossingLatitudes.size

    init {
        require(crossingLatitudes.size == crossingLongitudes.size) {
            "crossingLatitudes.size (${crossingLatitudes.size}) != crossingLongitudes.size (${crossingLongitudes.size})"
        }
    }
}

/**
 * 注意地点バイナリ `hazards.bin` (版 1、リトルエンディアン) のリーダー。
 *
 * 形式・範囲・CRC-32 が合わない場合やファイルが読めない場合は、例外を投げずに `null` を返す。
 */
object HazardFile {
    const val MAGIC_STRING = "CMHZ"
    const val VERSION = 1
    const val NO_NAME_OFFSET = 0xFFFFFFFFL

    const val MIN_LAT_E7 = 200_000_000
    const val MAX_LAT_E7 = 460_000_000
    const val MIN_LON_E7 = 1_220_000_000
    const val MAX_LON_E7 = 1_540_000_000

    private const val HEADER_BYTES = 20
    private const val CRC_BYTES = 4
    private const val POINT_BYTES = 8
    private const val BRIDGE_HEADER_BYTES = 8
    private const val E7_SCALE = 10_000_000.0
    private const val MAX_FILE_BYTES = 256L shl 20

    private val MAGIC_BYTES = MAGIC_STRING.toByteArray(StandardCharsets.US_ASCII)

    /** ファイルから `hazards.bin` を読み込む。読めない・不正な場合は `null`。 */
    fun read(file: File): HazardData? {
        return try {
            if (!file.isFile) return null
            val length = file.length()
            if (length < (HEADER_BYTES + CRC_BYTES).toLong() || length > MAX_FILE_BYTES) return null
            val bytes = file.readBytes()
            if (bytes.size.toLong() != length) return null
            parse(bytes)
        } catch (_: Exception) {
            null
        }
    }

    /** バイト配列から `hazards.bin` を解析する。不正な場合は `null`。 */
    fun parse(bytes: ByteArray): HazardData? = try {
        parseOrNull(bytes)
    } catch (_: Exception) {
        null
    }

    private fun parseOrNull(bytes: ByteArray): HazardData? {
        if (bytes.size < HEADER_BYTES + CRC_BYTES) return null
        for (i in MAGIC_BYTES.indices) {
            if (bytes[i] != MAGIC_BYTES[i]) return null
        }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(MAGIC_BYTES.size)

        val version = buf.short.toInt() and 0xFFFF
        val reserved = buf.short.toInt() and 0xFFFF
        if (version != VERSION || reserved != 0) return null

        val crossingCount = buf.int.toLong() and 0xFFFFFFFFL
        val bridgeCount = buf.int.toLong() and 0xFFFFFFFFL
        val nameTableBytes = buf.int.toLong() and 0xFFFFFFFFL

        val payloadEnd = (bytes.size - CRC_BYTES).toLong()
        if (nameTableBytes > payloadEnd - HEADER_BYTES.toLong()) return null
        val namesStart = payloadEnd - nameTableBytes

        if (crossingCount > (namesStart - HEADER_BYTES.toLong()) / POINT_BYTES.toLong()) return null
        val bridgesStart = HEADER_BYTES.toLong() + crossingCount * POINT_BYTES.toLong()
        val minBridgeBytes = (BRIDGE_HEADER_BYTES + 2 * POINT_BYTES).toLong()
        if (bridgeCount > (namesStart - bridgesStart) / minBridgeBytes) return null

        val expectedCrc = buf.getInt(payloadEnd.toInt()).toLong() and 0xFFFFFFFFL
        val actualCrc = CRC32().apply { update(bytes, 0, payloadEnd.toInt()) }.value
        if (actualCrc != expectedCrc) return null

        val names = parseNameTable(bytes, namesStart.toInt(), payloadEnd.toInt()) ?: return null

        val rawCrossingCount = crossingCount.toInt()
        val crossingLats = DoubleArray(rawCrossingCount)
        val crossingLons = DoubleArray(rawCrossingCount)
        var keptCrossings = 0
        for (i in 0 until rawCrossingCount) {
            val latE7 = buf.int
            val lonE7 = buf.int
            if (isInBounds(latE7, lonE7)) {
                crossingLats[keptCrossings] = latE7 / E7_SCALE
                crossingLons[keptCrossings] = lonE7 / E7_SCALE
                keptCrossings++
            }
        }

        val rawBridgeCount = bridgeCount.toInt()
        val bridges = ArrayList<BridgeHazard>(rawBridgeCount)
        for (i in 0 until rawBridgeCount) {
            if (buf.position().toLong() + BRIDGE_HEADER_BYTES.toLong() > namesStart) return null
            val nameOffset = buf.int.toLong() and 0xFFFFFFFFL
            val pointCount = buf.short.toInt() and 0xFFFF
            val bridgeReserved = buf.short.toInt() and 0xFFFF
            if (bridgeReserved != 0 || pointCount < 2) return null

            val pointsBytes = pointCount.toLong() * POINT_BYTES.toLong()
            if (buf.position().toLong() + pointsBytes > namesStart) return null

            val name = if (nameOffset == NO_NAME_OFFSET) {
                null
            } else {
                if (nameOffset > Int.MAX_VALUE) return null
                names[nameOffset.toInt()] ?: return null
            }

            val bLats = DoubleArray(pointCount)
            val bLons = DoubleArray(pointCount)
            var allInBounds = true
            for (k in 0 until pointCount) {
                val latE7 = buf.int
                val lonE7 = buf.int
                if (isInBounds(latE7, lonE7)) {
                    bLats[k] = latE7 / E7_SCALE
                    bLons[k] = lonE7 / E7_SCALE
                } else {
                    allInBounds = false
                }
            }
            if (allInBounds) {
                bridges.add(BridgeHazard(name, bLats, bLons))
            }
        }

        if (buf.position().toLong() != namesStart) return null

        val finalCrossingLats = if (keptCrossings == rawCrossingCount) crossingLats else crossingLats.copyOf(keptCrossings)
        val finalCrossingLons = if (keptCrossings == rawCrossingCount) crossingLons else crossingLons.copyOf(keptCrossings)
        return HazardData(finalCrossingLats, finalCrossingLons, bridges)
    }

    private fun parseNameTable(bytes: ByteArray, start: Int, end: Int): Map<Int, String>? {
        if (start == end) return emptyMap()
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val names = HashMap<Int, String>()
        var pos = start
        while (pos < end) {
            var nul = -1
            for (i in pos until end) {
                if (bytes[i].toInt() == 0) {
                    nul = i
                    break
                }
            }
            if (nul <= pos) return null
            decoder.reset()
            val decoded = decoder.decode(ByteBuffer.wrap(bytes, pos, nul - pos))
            names[pos - start] = decoded.toString()
            pos = nul + 1
        }
        return names
    }

    private fun isInBounds(latE7: Int, lonE7: Int): Boolean =
        latE7 in MIN_LAT_E7..MAX_LAT_E7 && lonE7 in MIN_LON_E7..MAX_LON_E7
}
