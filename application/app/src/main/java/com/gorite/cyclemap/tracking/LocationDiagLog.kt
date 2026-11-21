package com.gorite.cyclemap.tracking

import java.io.File
import java.io.IOException
import java.io.Writer
import java.util.Locale

/** 診断ログの 1 行ぶんの測位。 */
internal data class DiagFix(
    val elapsedMillis: Long,
    val epochMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val speedAccuracyMps: Float?,
    val bearingDegrees: Float?,
    val provider: String?,
    val satellites: Int?,
)

/**
 * GPX の記録中に、届いた測位のすべて (捨てたものも含む) を、端末内の CSV に残す診断ログ。
 * 実走で「位置が 7 秒おきにしか届かない」「停止中に位置が往復する」といったぶれの原因を、
 * 精度・速度・取得元・衛星数から後で調べるためのもの。端末の外には出さない (他の GPX と同じ場所に置く)。
 * 書き込みに失敗しても、位置情報の処理は止めない。
 */
internal class LocationDiagLog(
    private val writer: Writer,
    private val flushEveryLines: Int = DEFAULT_FLUSH_LINES,
) {
    private var pending = 0
    private var failed = false

    init {
        write(HEADER)
    }

    @Synchronized
    fun append(fix: DiagFix, verdict: GateVerdict) {
        write(format(fix, verdict))
        if (++pending >= flushEveryLines) flush()
    }

    @Synchronized
    fun close() {
        try {
            writer.flush()
            writer.close()
        } catch (_: IOException) {
            failed = true
        }
    }

    private fun write(line: String) {
        if (failed) return
        try {
            writer.write(line)
            writer.write("\n")
        } catch (_: IOException) {
            failed = true
        }
    }

    private fun flush() {
        pending = 0
        if (failed) return
        try {
            writer.flush()
        } catch (_: IOException) {
            failed = true
        }
    }

    companion object {
        const val HEADER = "elapsed_ms,epoch_ms,lat,lon,accuracy_m,speed_mps,speed_acc_mps,bearing_deg,provider,satellites,verdict"
        private const val DEFAULT_FLUSH_LINES = 10

        internal fun format(fix: DiagFix, verdict: GateVerdict): String = buildString {
            append(fix.elapsedMillis).append(',')
            append(fix.epochMillis).append(',')
            append(String.format(Locale.US, "%.7f,%.7f", fix.latitude, fix.longitude)).append(',')
            append(optional(fix.accuracyMeters)).append(',')
            append(optional(fix.speedMps)).append(',')
            append(optional(fix.speedAccuracyMps)).append(',')
            append(optional(fix.bearingDegrees)).append(',')
            append(fix.provider?.replace(',', ' ') ?: "").append(',')
            append(fix.satellites?.toString() ?: "").append(',')
            append(verdict.name)
        }

        private fun optional(value: Float?): String = if (value == null || !value.isFinite()) "" else String.format(Locale.US, "%.2f", value)

        /** 診断ログを開く。開けないときは null (記録は診断ログなしで続ける)。 */
        fun open(file: File): LocationDiagLog? = try {
            file.parentFile?.mkdirs()
            LocationDiagLog(file.bufferedWriter(Charsets.UTF_8))
        } catch (_: IOException) {
            null
        }
    }
}
