package com.gorite.cyclemap.tracking

import java.util.Locale

/** サイコン画面の数値の文字列化。純粋な関数で、単体テストできる。 */
object RideFormat {
    /** 速度。小数 1 桁 (例 "23.4")。負・NaN は "0.0"。 */
    fun speed(kmh: Double): String =
        String.format(Locale.US, "%.1f", if (kmh.isFinite() && kmh > 0.0) kmh else 0.0)

    /** 距離。1 km 未満は "850 m"、以上は小数 2 桁の "12.34 km"。 */
    fun distance(meters: Double): String {
        val m = if (meters.isFinite() && meters > 0.0) meters else 0.0
        return if (m < 1000.0) String.format(Locale.US, "%d m", Math.round(m))
        else String.format(Locale.US, "%.2f km", m / 1000.0)
    }

    /** 時間。1 時間未満は "m:ss"、以上は "h:mm:ss"。 */
    fun duration(seconds: Double): String {
        val total = if (seconds.isFinite() && seconds > 0.0) seconds.toLong() else 0L
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** 獲得標高。例 "+123 m"。 */
    fun elevationGain(meters: Double): String =
        String.format(Locale.US, "+%d m", Math.round(if (meters.isFinite() && meters > 0.0) meters else 0.0))
}
