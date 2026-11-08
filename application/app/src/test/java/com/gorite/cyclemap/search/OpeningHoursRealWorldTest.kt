package com.gorite.cyclemap.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 実データ (OSM 5県) に出てくる表記の扱い。解釈できない表記は営業状況を断定しない (Unknown)。 */
class OpeningHoursRealWorldTest {
    // 2026-10-12 は月曜、13 火、14 水、15 木、16 金、17 土、18 日
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)

    @Test
    fun theMostCommonRealWorldStringsAreAllSupported() {
        val lines = javaClass.getResourceAsStream("/opening_hours_real_world.txt")!!
            .bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("sample file should have many patterns, was ${lines.size}", lines.size >= 100)
        val unsupported = lines.filterNot { OpeningHoursParser.isFullySupported(it) }
        // 月・第n曜日・日の出などを含む少数だけが未対応でよい
        assertTrue("too many unsupported real-world strings: $unsupported", unsupported.size <= lines.size / 25)
    }

    @Test
    fun laterRulesOverrideEarlierOnes() {
        // 毎日営業だが水曜だけ休み (OSM では後ろの規則が前を上書きする)
        val rule = "Mo-Su 10:00-20:00; We off"
        assertEquals(OpenStatus.Closed, OpeningHoursParser.evaluate(rule, at(14, 12)))
        assertEquals(OpenStatus.Open, OpeningHoursParser.evaluate(rule, at(15, 12)))
    }

    @Test
    fun commaBetweenRulesSeparatesThem() {
        val rule = "Tu-Fr 09:00-19:00, Sa-Su 10:00-18:00"
        assertEquals(OpenStatus.Open, OpeningHoursParser.evaluate(rule, at(14, 12)))   // 水
        assertEquals(OpenStatus.Closed, OpeningHoursParser.evaluate(rule, at(17, 19))) // 土 19:00 は閉店後
        assertEquals(OpenStatus.Open, OpeningHoursParser.evaluate(rule, at(17, 17)))
    }

    @Test
    fun holidayTokenGluedToTimesAndHolidayOnlyRulesAreHarmless() {
        assertEquals(OpenStatus.Open, OpeningHoursParser.evaluate("Mo-Fr 11:00-23:00; Sa 10:30-23:00; PH10:30-23:00", at(12, 12)))
        assertEquals(OpenStatus.Open, OpeningHoursParser.evaluate("Mo-Fr 09:00-18:00; PH off", at(12, 10)))
    }

    @Test
    fun hoursPast24AreTreatedAsOvernight() {
        assertEquals(OpenStatus.Open, OpeningHoursParser.evaluate("18:00-27:30", at(12, 23)))
        assertEquals(OpenStatus.Open, OpeningHoursParser.evaluate("18:00-27:30", at(13, 2)))
        assertEquals(OpenStatus.Closed, OpeningHoursParser.evaluate("18:00-27:30", at(13, 4)))
    }

    @Test
    fun fullWidthAndDashVariantsAreNormalized() {
        assertEquals(OpenStatus.Open, OpeningHoursParser.evaluate("Mo-Fr 09:00–17:00", at(12, 10)))   // en dash
        assertEquals(OpenStatus.Open, OpeningHoursParser.evaluate("Ｍo-Fr ０９：００－１７：００", at(12, 10)))
        assertEquals(OpenStatus.Open, OpeningHoursParser.evaluate("Mo-Fr 09:00〜17:00", at(12, 10)))
    }

    @Test
    fun quotedCommentsAreIgnored() {
        assertEquals(OpenStatus.Open, OpeningHoursParser.evaluate("Mo-Fr 09:00-17:00 \"要予約\"", at(12, 10)))
    }

    @Test
    fun ruleswithMonthsOrNthWeekdaysAreNotGuessed() {
        // 誤って「営業中」と出さないよう、解釈できない規則を含むときは Unknown
        for (raw in listOf(
            "Oct-Mar Mo-Su 09:00-17:00; Apr-Sep Mo-Su 09:00-17:30",
            "10:00-19:00; We[2-3] off",
            "Mo-Fr 09:00-17:00; sunrise-sunset",
            "17:00+",
        )) {
            assertFalse(raw, OpeningHoursParser.isFullySupported(raw))
            assertEquals(raw, OpenStatus.Unknown, OpeningHoursParser.evaluate(raw, at(12, 12)))
        }
    }

    @Test
    fun twentyFourSevenAndDayOnlyEdgeCases() {
        assertTrue(OpeningHoursParser.isFullySupported("24/7"))
        assertFalse(OpeningHoursParser.isFullySupported("Mo-Fr"))  // 時刻が無い
        assertFalse(OpeningHoursParser.isFullySupported("???"))
        assertFalse(OpeningHoursParser.isFullySupported(null))
    }
}
