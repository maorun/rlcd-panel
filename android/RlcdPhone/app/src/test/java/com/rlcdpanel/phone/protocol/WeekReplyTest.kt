package com.rlcdpanel.phone.protocol

import com.rlcdpanel.phone.WeekChartModel
import com.rlcdpanel.phone.WeekSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WeekReplyTest {
    private fun fixtureLines(): List<String> {
        val reply = ReplyAssembler().append(Fixtures.file("week_reply_sample.txt").readBytes())
        return (reply as? ConsoleReply.Frame)?.lines ?: throw AssertionError("fixture is no valid frame")
    }

    private fun bad(vararg lines: String) {
        try {
            WeekReply.parse(lines.toList())
            fail("expected ParseException")
        } catch (_: ParseException) {
        }
    }

    private val h = WeekReply.LEGACY_HEADER // 7-column rows; the 8-column tests use WeekReply.HEADER

    @Test
    fun sharedFixtureGivesTheSwiftValues() {
        val rows = WeekReply.parse(fixtureLines())
        assertEquals(42, rows.size)
        assertEquals(WeekRow(0, 0, "2026-11-17", 15.0, 19.5, 40.0, 55.0, 0, 0), rows[0])
        assertEquals(WeekRow(5, 6, "2026-11-11", 23.0, 27.5, 56.0, 71.0, 24, 3), rows[41])
        val gap = rows.single { it.sensor == 2 && it.day == 3 }
        assertEquals("2026-11-14", gap.date)
        assertNull(gap.temperatureMinC)
        assertNull(gap.temperatureMaxC)
        assertNull(gap.humidityMinPct)
        assertNull(gap.humidityMaxPct)
        assertNull(gap.moldHours)
        assertNull(gap.dryHours)
        assertTrue(gap.isGap)
        assertEquals(1, rows.count { it.isGap })
    }

    @Test
    fun emptyFieldsAndDashesGiveNull() {
        val rows = WeekReply.parse(listOf("BEGIN WEEK", h, "0,1,2026-11-13,,,,", "1,0,,10.0,20.0,40.0,60.0", "2,0,,--,--,--,--"))
        assertNull(rows[0].temperatureMinC)
        assertNull(rows[0].humidityMaxPct)
        assertEquals("", rows[1].date)
        assertEquals(20.0, rows[1].temperatureMaxC!!, 0.0)
        assertNull(rows[2].temperatureMinC)
    }

    @Test
    fun wrongColumnCountThrows() {
        bad("BEGIN WEEK", h, "0,0,2026-11-14,1,2,3")
        bad("BEGIN WEEK", h, "0,0,2026-11-14,1,2,3,4,5,6")
    }

    @Test
    fun eightColumnsGiveMoldHours() {
        val rows = WeekReply.parse(listOf("BEGIN WEEK", WeekReply.MOLD_HEADER, "0,0,2026-11-14,19.5,22.0,40.5,55.0,6", "0,1,2026-11-13,,,,,", "1,0,,10.0,20.0,40.0,60.0,"))
        assertEquals(6, rows[0].moldHours)
        assertNull(rows[1].moldHours)
        assertNull(rows[2].moldHours)
    }

    @Test
    fun sevenColumnsGiveNullMoldHours() {
        val rows = WeekReply.parse(listOf("BEGIN WEEK", WeekReply.LEGACY_HEADER, "0,0,2026-11-14,19.5,22.0,40.5,55.0"))
        assertNull(rows[0].moldHours)
        assertEquals(55.0, rows[0].humidityMaxPct!!, 0.0)
    }

    @Test
    fun badMoldHoursThrow() {
        bad("BEGIN WEEK", WeekReply.MOLD_HEADER, "0,0,,1,2,3,4,25")
        bad("BEGIN WEEK", WeekReply.MOLD_HEADER, "0,0,,1,2,3,4,-1")
        bad("BEGIN WEEK", WeekReply.MOLD_HEADER, "0,0,,1,2,3,4,x")
        bad("BEGIN WEEK", WeekReply.MOLD_HEADER, "0,0,,1,2,3,4,+5")
        bad("BEGIN WEEK", WeekReply.MOLD_HEADER, "0,0,,1,2,3,4")
        bad("BEGIN WEEK", WeekReply.LEGACY_HEADER, "0,0,,1,2,3,4,5")
    }

    @Test
    fun nineColumnsGiveDryHours() {
        val rows = WeekReply.parse(listOf("BEGIN WEEK", WeekReply.HEADER, "0,0,2026-11-14,19.5,22.0,40.5,55.0,6,5", "0,1,2026-11-13,,,,,,", "1,0,,10.0,20.0,40.0,60.0,3,"))
        assertEquals(6, rows[0].moldHours)
        assertEquals(5, rows[0].dryHours)
        assertNull(rows[1].dryHours)
        assertEquals(3, rows[2].moldHours)
        assertNull(rows[2].dryHours)
    }

    @Test
    fun sevenAndEightColumnsGiveNullDryHours() {
        val seven = WeekReply.parse(listOf("BEGIN WEEK", WeekReply.LEGACY_HEADER, "0,0,2026-11-14,19.5,22.0,40.5,55.0"))
        assertNull(seven[0].dryHours)
        val eight = WeekReply.parse(listOf("BEGIN WEEK", WeekReply.MOLD_HEADER, "0,0,2026-11-14,19.5,22.0,40.5,55.0,6", "0,1,2026-11-13,,,,,"))
        assertEquals(6, eight[0].moldHours)
        assertNull(eight[0].dryHours)
        assertNull(eight[1].dryHours)
    }

    @Test
    fun badDryHoursThrow() {
        bad("BEGIN WEEK", WeekReply.HEADER, "0,0,,1,2,3,4,5,25")
        bad("BEGIN WEEK", WeekReply.HEADER, "0,0,,1,2,3,4,5,-1")
        bad("BEGIN WEEK", WeekReply.HEADER, "0,0,,1,2,3,4,5,x")
        bad("BEGIN WEEK", WeekReply.HEADER, "0,0,,1,2,3,4,5")
        bad("BEGIN WEEK", WeekReply.MOLD_HEADER, "0,0,,1,2,3,4,5,6")
    }

    @Test
    fun otherBadInputThrows() {
        bad("wrong")
        bad("BEGIN WEEK", "wrong")
        bad("BEGIN WEEK", h, "0,7,,,,,")
        bad("BEGIN WEEK", h, "11,0,,,,,")
        bad("BEGIN WEEK", h, "0,0,14.11.2026,,,,")
        bad("BEGIN WEEK", h, "0,0,,1,2,3,4", "0,0,,1,2,3,4")
        bad("BEGIN WEEK", h, "0,0,,5,2,3,4")
        bad("BEGIN WEEK", h, "0,0,,1,2,9,4")
        bad("BEGIN WEEK", h, "0,0,,NaN,2,3,4")
        bad("BEGIN WEEK", h, "0,0,,1,Infinity,3,4")
        bad("BEGIN WEEK", h, "0,0,, 1.0,2,3,4")
        bad("BEGIN WEEK", h, "0,0,,1f,2,3,4")
        assertTrue(WeekReply.parse(listOf("BEGIN WEEK", h)).isEmpty())
    }

    @Test
    fun chartShowsGapDayAsNullBar() {
        val rows = WeekReply.parse(fixtureLines())
        assertEquals(listOf(0, 1, 2, 3, 4, 5), WeekChartModel.sensors(rows))
        val t = WeekChartModel.temperature(rows, 2)
        // Day 3 is the fourth bar from the right, so index 3 of the oldest-first list.
        assertNull(t.low[3])
        assertNull(t.high[3])
        assertNotNull(t.low[2])
        assertTrue(t.hasData)
        assertEquals("11-14", WeekChartModel.dayLabel(rows, 2, 3))
    }

    @Test
    fun scaleKeepsTheMinimumSpan() {
        val flat = WeekSeries(listOf(20.0, null), listOf(20.5, null))
        val (lo, hi) = flat.scale(WeekChartModel.TEMP_MIN_SPAN)!!
        assertEquals(2.0, hi - lo, 1e-9)
        assertEquals(20.25, (lo + hi) / 2, 1e-9)
        val high = WeekSeries(listOf(98.0), listOf(99.5))
        val (hl, hh) = WeekChartModel.humidityScale(high)!!
        assertEquals(100.0, hh, 1e-9)
        assertEquals(5.0, hh - hl, 1e-9)
        val dry = WeekChartModel.humidityScale(WeekSeries(listOf(0.5), listOf(1.0)))!!
        assertEquals(0.0, dry.first, 1e-9)
        assertEquals(5.0, dry.second, 1e-9)
        assertNull(WeekSeries(listOf(null), listOf(null)).scale(2.0))
        assertFalse(WeekSeries(listOf(null), listOf(null)).hasData)
    }

    @Test
    fun sensorCommandGivesWeekWithNumber() {
        assertEquals("WEEK 0", WeekReply.sensorCommand(0))
        assertEquals("WEEK 5", WeekReply.sensorCommand(5))
    }

    @Test
    fun sensorCommandRejectsOtherNumbers() {
        for (n in listOf(-1, 6)) {
            try {
                WeekReply.sensorCommand(n)
                fail("expected IllegalArgumentException for $n")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun oneSensorReplyOfSevenRowsParsesWithAndWithoutMoldColumn() {
        val withMold = (0..6).map { "2,$it,2026-11-${17 - it},15.0,19.5,40.0,55.0,$it" }
        val rows = WeekReply.parse(listOf("BEGIN WEEK", WeekReply.MOLD_HEADER) + withMold)
        assertEquals(7, rows.size)
        assertEquals((0..6).toList(), rows.map { it.day })
        assertTrue(rows.all { it.sensor == 2 })
        assertEquals(6, rows[6].moldHours)
        val legacy = (0..6).map { "2,$it,2026-11-${17 - it},15.0,19.5,40.0,55.0" }
        val old = WeekReply.parse(listOf("BEGIN WEEK", WeekReply.LEGACY_HEADER) + legacy)
        assertEquals(7, old.size)
        assertTrue(old.all { it.moldHours == null })
    }
}
