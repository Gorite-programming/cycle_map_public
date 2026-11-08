package com.gorite.cyclemap.search

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

enum class OpenStatus {
    Open,
    Closed,
    Unknown
}

object OpeningHoursParser {

    private val dayAbbrs = mapOf(
        "Mo" to DayOfWeek.MONDAY,
        "Tu" to DayOfWeek.TUESDAY,
        "We" to DayOfWeek.WEDNESDAY,
        "Th" to DayOfWeek.THURSDAY,
        "Fr" to DayOfWeek.FRIDAY,
        "Sa" to DayOfWeek.SATURDAY,
        "Su" to DayOfWeek.SUNDAY
    )

    private val dayOrder = listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY
    )

    /** 祝日 (PH)・学校休暇 (SH) は日付の判定を持たないので、この規則は無視する (曜日の規則だけで判定する)。 */
    private val ignorableDayTokens = setOf("PH", "SH")

    /** 月・週・日の出日の入り・n 番目の曜日・年などを含む規則。解釈できないので、判定は Unknown に倒す。 */
    private val unsupportedPattern = Regex(
        "(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|week|sunrise|sunset|dawn|dusk|easter)\\b|\\[|\\+|\\|\\||\\d{4}"
    )

    data class DayRule(
        val days: Set<DayOfWeek>,
        val isOff: Boolean,
        val timeRanges: List<Pair<Int, Int>> // (startMinutes, endMinutes), e.g. 09:00 = 540
    )

    /**
     * 解析結果。[rules] は文中の順序のまま (後ろの規則が前の規則を上書きする)。
     * [unsupported] は解釈できなかった規則の原文。空でなければ営業状況を断定しない。
     */
    data class Parsed(val rules: List<DayRule>, val unsupported: List<String>) {
        val isFullySupported: Boolean get() = unsupported.isEmpty() && rules.isNotEmpty()
    }

    /** 文字列がこのパーサで完全に解釈できるか (実データでの対応率の測定にも使う)。 */
    fun isFullySupported(raw: String?): Boolean = parse(raw).isFullySupported

    /**
     * OSM opening_hours 文字列を解析し、指定時刻の営業状況を返す。
     * 解析不能・空文字列・解釈できない規則を含む場合は安全側に倒して [OpenStatus.Unknown] を返す。
     */
    fun evaluate(raw: String?, dateTime: LocalDateTime = LocalDateTime.now()): OpenStatus {
        if (raw.isNullOrBlank()) return OpenStatus.Unknown
        val parsed = parse(raw)
        if (!parsed.isFullySupported) return OpenStatus.Unknown
        val rules = parsed.rules

        return try {
            val currentDay = dateTime.dayOfWeek
            val prevDay = currentDay.minus(1)
            val currentMinutes = dateTime.hour * 60 + dateTime.minute

            // 1. 前日深夜からの日またぎ営業の判定 (例: 22:00-02:00)。前日に効いている規則だけを見る
            effectiveRule(rules, prevDay)?.let { rule ->
                if (!rule.isOff) {
                    for ((start, end) in rule.timeRanges) {
                        if (end < start && currentMinutes < end) return OpenStatus.Open
                    }
                }
            }

            // 2. 当日に効いている規則 (後ろの規則が前の規則を上書きする)。曜日の指定が無い日は断定しない
            val rule = effectiveRule(rules, currentDay) ?: return OpenStatus.Unknown
            if (rule.isOff) return OpenStatus.Closed
            for ((start, end) in rule.timeRanges) {
                if (end < start) {
                    // 日またぎ: start以降なら本日営業中
                    if (currentMinutes >= start) return OpenStatus.Open
                } else if (currentMinutes in start..end) {
                    return OpenStatus.Open
                }
            }
            OpenStatus.Closed
        } catch (_: Exception) {
            OpenStatus.Unknown
        }
    }

    /** [day] に効いている規則。曜日の指定が無い規則は毎日に効く。後ろにあるものが優先。 */
    private fun effectiveRule(rules: List<DayRule>, day: DayOfWeek): DayRule? =
        rules.lastOrNull { it.days.isEmpty() || day in it.days }

    /** 文字列を規則の並びに解析する。 */
    fun parse(raw: String?): Parsed {
        if (raw.isNullOrBlank()) return Parsed(emptyList(), emptyList())
        val clean = normalize(raw)
        if (clean.equals("24/7", ignoreCase = true)) {
            return Parsed(listOf(DayRule(emptySet(), false, listOf(0 to 1440))), emptyList())
        }
        if (clean.equals("closed", ignoreCase = true) || clean.equals("off", ignoreCase = true)) {
            return Parsed(listOf(DayRule(emptySet(), true, emptyList())), emptyList())
        }

        val rules = mutableListOf<DayRule>()
        val unsupported = mutableListOf<String>()
        for (stmt in clean.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
            when (val result = parseStatement(stmt)) {
                is Statement.Rule -> rules += result.rule
                Statement.Ignored -> Unit
                Statement.Unsupported -> unsupported += stmt
            }
        }
        return Parsed(rules, unsupported)
    }

    private sealed interface Statement {
        data class Rule(val rule: DayRule) : Statement
        data object Ignored : Statement
        data object Unsupported : Statement
    }

    /** 全角・ダッシュ・引用符コメントなど、日本の入力で多い揺れをそろえる。 */
    private fun normalize(raw: String): String =
        java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)
            .replace(Regex("\"[^\"]*\""), " ")      // "定休日あり" のようなコメント
            .replace(Regex("[\\u2010-\\u2015\\u2212\\u301C~]"), "-") // ‐ – — ― − 〜 ~ を - に
            .replace(Regex("\\b(Mo|Tu|We|Th|Fr|Sa|Su|PH|SH)(?=\\d)"), "$1 ")  // "PH10:30-23:00" → "PH 10:30-23:00"
            .replace(Regex("(?<=\\d|off|closed|open),\\s*(?=(?:Mo|Tu|We|Th|Fr|Sa|Su|PH|SH)\\b)"), ";")  // 規則どうしを区切るカンマ
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun parseStatement(stmt: String): Statement {
        if (unsupportedPattern.containsMatchIn(stmt)) return Statement.Unsupported

        // 曜日の部分と時刻の部分に分ける。時刻の部分は、数字で始まる語 / off / closed / open / 24/7 から
        val parts = stmt.split(" ").filter { it.isNotEmpty() }
        val timeStart = parts.indexOfFirst { token ->
            token[0].isDigit() || token.equals("off", true) || token.equals("closed", true) ||
                token.equals("open", true) || token.equals("24/7", true)
        }
        val dayTokens = if (timeStart < 0) parts.joinToString(",") else parts.take(timeStart).joinToString(",")
        val timeTokens = if (timeStart < 0) "" else parts.drop(timeStart).joinToString(" ")
        if (timeStart < 0) return Statement.Unsupported

        val dayParse = parseDays(dayTokens) ?: return Statement.Unsupported
        // 祝日だけの規則 (PH off など) は日付を判定できないので無視する
        if (dayTokens.isNotEmpty() && dayParse.days.isEmpty() && dayParse.onlyIgnorable) return Statement.Ignored

        val lower = timeTokens.lowercase()
        return when {
            lower == "off" || lower == "closed" -> Statement.Rule(DayRule(dayParse.days, true, emptyList()))
            lower == "24/7" || lower == "open" -> Statement.Rule(DayRule(dayParse.days, false, listOf(0 to 1440)))
            else -> {
                val ranges = parseTimeRanges(timeTokens.removeSuffix(" open").trim()) ?: return Statement.Unsupported
                Statement.Rule(DayRule(dayParse.days, false, ranges))
            }
        }
    }

    private data class DayParse(val days: Set<DayOfWeek>, val onlyIgnorable: Boolean)

    /** 曜日の指定を解析する。解釈できない語があれば null。空文字列は「毎日」。 */
    private fun parseDays(token: String): DayParse? {
        if (token.isEmpty()) return DayParse(emptySet(), false)
        val days = mutableSetOf<DayOfWeek>()
        var sawIgnorable = false
        for (segRaw in token.split(",")) {
            val seg = segRaw.trim()
            if (seg.isEmpty()) continue
            if (seg in ignorableDayTokens) { sawIgnorable = true; continue }
            if (seg.contains("-")) {
                val sub = seg.split("-")
                if (sub.size != 2) return null
                val start = dayAbbrs[sub[0].trim()] ?: return null
                val end = dayAbbrs[sub[1].trim()] ?: return null
                val sIdx = dayOrder.indexOf(start)
                val eIdx = dayOrder.indexOf(end)
                if (sIdx <= eIdx) {
                    for (i in sIdx..eIdx) days.add(dayOrder[i])
                } else {
                    for (i in sIdx until dayOrder.size) days.add(dayOrder[i])
                    for (i in 0..eIdx) days.add(dayOrder[i])
                }
            } else {
                days.add(dayAbbrs[seg] ?: return null)
            }
        }
        return DayParse(days, onlyIgnorable = days.isEmpty() && sawIgnorable)
    }

    /** "09:00-12:00,13:00-17:00" を分の組にする。1つでも解釈できなければ null。 */
    private fun parseTimeRanges(token: String): List<Pair<Int, Int>>? {
        val list = mutableListOf<Pair<Int, Int>>()
        for (range in token.split(",")) {
            val parts = range.trim().split("-")
            if (parts.size != 2) return null
            val startMin = parseMinute(parts[0].trim()) ?: return null
            var endMin = parseMinute(parts[1].trim()) ?: return null
            // 「18:00-27:30」のような 25 時以降の表記は、翌日にまたがる営業 (18:00-03:30) として扱う
            if (endMin > 1440) endMin -= 1440
            list.add(Pair(startMin, endMin))
        }
        return list.ifEmpty { null }
    }

    private fun parseMinute(timeStr: String): Int? {
        val parts = timeStr.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..30 || m !in 0..59) return null
        val minutes = h * 60 + m
        // 24:00 ちょうどは 1440 に収める。それより後は呼び出し側が翌日分として扱う
        return if (h == 24 && m == 0) 1440 else minutes
    }
}
