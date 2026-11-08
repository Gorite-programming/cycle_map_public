package com.gorite.cyclemap.ui.cycling

enum class GradeKind { CLIMB, DESCENT }

/**
 * ルート前方の目立つ坂の予告。
 *
 * @property kind 上りか下りか。
 * @property gradePct 区間の平均勾配 (%、常に正の値。向きは [kind])。
 * @property startsInM 現在位置から坂が始まるまでの距離 (m)。すでに坂の途中なら 0。
 * @property lengthM 坂が続く長さ (m)。先読み範囲の終端まで続くときは null。
 */
data class GradeAhead(
    val kind: GradeKind,
    val gradePct: Double,
    val startsInM: Double,
    val lengthM: Double?,
)

private enum class Slope { FLAT, CLIMB, DESCENT }

/**
 * 標高プロファイルから、[progressM] より前方 [lookaheadM] 以内にある最初の目立つ坂を返す。
 * 目立つ坂がなければ null (この先は平坦)。
 *
 * 勾配は [stepM] 刻みで前方 [probeM] の傾きを測り、絶対値が [thresholdPct] 以上を坂とみなす。
 */
fun gradeAhead(
    profile: List<ElevationSample>,
    progressM: Double,
    lookaheadM: Double = 400.0,
    stepM: Double = 25.0,
    probeM: Double = 50.0,
    thresholdPct: Double = 3.0,
): GradeAhead? {
    if (profile.size < 2) return null
    val total = profile.last().distanceM
    val start = progressM.coerceIn(0.0, total)
    val end = minOf(total, start + lookaheadM)
    if (end - start < stepM) return null

    class Sample(val distance: Double, val grade: Double, val slope: Slope)

    val samples = ArrayList<Sample>()
    var d = start
    while (d < end - 1.0) {
        val g = forwardGradeAt(profile, d, probeM) ?: break
        val slope = when {
            g >= thresholdPct -> Slope.CLIMB
            g <= -thresholdPct -> Slope.DESCENT
            else -> Slope.FLAT
        }
        samples += Sample(d, g, slope)
        d += stepM
    }
    val first = samples.indexOfFirst { it.slope != Slope.FLAT }
    if (first < 0) return null

    val slope = samples[first].slope
    var last = first
    while (true) {
        val next = last + 1
        if (next < samples.size && samples[next].slope == slope) {
            last = next
        } else if (next + 1 < samples.size && samples[next].slope == Slope.FLAT && samples[next + 1].slope == slope) {
            // 1サンプルだけの凹凸 (標高ノイズ) では坂を切らない
            last = next + 1
        } else {
            break
        }
    }
    val run = samples.subList(first, last + 1)
    val endsWithinRange = last + 1 < samples.size
    val length = if (endsWithinRange) samples[last + 1].distance - samples[first].distance else null
    val meanGrade = run.map { it.grade }.average()
    val startsIn = (samples[first].distance - start).coerceAtLeast(0.0)

    return GradeAhead(
        kind = if (slope == Slope.CLIMB) GradeKind.CLIMB else GradeKind.DESCENT,
        gradePct = kotlin.math.abs(meanGrade),
        startsInM = startsIn,
        lengthM = length,
    )
}

/** [progressM] からゴールまでの残り獲得標高 (上り分の積算、m)。標高が得られなければ null。 */
fun remainingElevationGain(profile: List<ElevationSample>, progressM: Double): Double? {
    if (profile.size < 2) return null
    val total = profile.last().distanceM
    val all = elevationGain(profile, total) ?: return null
    val done = elevationGain(profile, progressM.coerceIn(0.0, total)) ?: return null
    return (all - done).coerceAtLeast(0.0)
}
