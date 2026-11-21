package com.gorite.cyclemap.hazard

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import kotlin.math.roundToInt

/**
 * テスト用の `hazards.bin` (版 1) バイナリ組み立てヘルパー。
 */
data class BridgeSpec(
    val name: String?,
    val points: List<Pair<Double, Double>>,
)

object HazardTestWriter {
    private const val E7_SCALE = 10_000_000.0
    private const val NO_NAME_U32 = -1 // 0xFFFFFFFF

    fun buildBytes(
        crossings: List<Pair<Double, Double>>,
        bridges: List<BridgeSpec>,
    ): ByteArray {
        val nameTable = ByteArrayOutputStream()
        val nameOffsets = HashMap<String, Int>()
        val recordsSize = crossings.size * 8 + bridges.sumOf { 8 + it.points.size * 8 }
        val records = ByteBuffer.allocate(recordsSize).order(ByteOrder.LITTLE_ENDIAN)

        for ((lat, lon) in crossings) {
            records.putInt(toE7(lat))
            records.putInt(toE7(lon))
        }

        for (bridge in bridges) {
            val nameOffset = if (bridge.name.isNullOrEmpty()) {
                NO_NAME_U32
            } else {
                nameOffsets.getOrPut(bridge.name) {
                    val offset = nameTable.size()
                    nameTable.write(bridge.name.toByteArray(StandardCharsets.UTF_8))
                    nameTable.write(0)
                    offset
                }
            }
            records.putInt(nameOffset)
            records.putShort(bridge.points.size.toShort())
            records.putShort(0)
            for ((lat, lon) in bridge.points) {
                records.putInt(toE7(lat))
                records.putInt(toE7(lon))
            }
        }

        val namesBytes = nameTable.toByteArray()
        val payloadSize = 20 + recordsSize + namesBytes.size
        val full = ByteBuffer.allocate(payloadSize + 4).order(ByteOrder.LITTLE_ENDIAN)
        full.put("CMHZ".toByteArray(StandardCharsets.US_ASCII))
        full.putShort(1)
        full.putShort(0)
        full.putInt(crossings.size)
        full.putInt(bridges.size)
        full.putInt(namesBytes.size)
        full.put(records.array())
        full.put(namesBytes)

        val payload = full.array()
        val crc = CRC32().apply { update(payload, 0, payloadSize) }.value
        full.putInt(crc.toInt())
        return payload
    }

    fun writeFile(
        file: File,
        crossings: List<Pair<Double, Double>>,
        bridges: List<BridgeSpec>,
    ): File {
        file.parentFile?.mkdirs()
        file.writeBytes(buildBytes(crossings, bridges))
        return file
    }

    private fun toE7(degrees: Double): Int = (degrees * E7_SCALE).roundToInt()
}
