package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WeekReplyTest {
    private val h = WeekReply.MOLD_HEADER // 8-column rows; the 9-column tests use WeekReply.HEADER

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

    @Test
    fun sharedFixtureGivesFortyTwoRows() {
        val rows = WeekReply.parse(fixtureLines())
        assertEquals(42, rows.size)
        assertEquals(WeekRow(0, 0, "2026-11-17", 15.0, 19.5, 40.0, 55.0, 0, 0), rows[0])
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
    fun wrongColumnCountThrows() {
        bad("BEGIN WEEK", h, "0,0,2026-11-14,1,2,3")
        bad("BEGIN WEEK", h, "0,0,2026-11-14,1,2,3,4,5,6")
    }

    @Test
    fun eightColumnsGiveMoldHours() {
        val rows = WeekReply.parse(listOf("BEGIN WEEK", h, "0,0,2026-11-14,19.5,22.0,40.5,55.0,6", "0,1,2026-11-13,,,,,", "1,0,,10.0,20.0,40.0,60.0,"))
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
        bad("BEGIN WEEK", h, "0,0,,1,2,3,4,25")
        bad("BEGIN WEEK", h, "0,0,,1,2,3,4,-1")
        bad("BEGIN WEEK", h, "0,0,,1,2,3,4,x")
        bad("BEGIN WEEK", h, "0,0,,1,2,3,4,+5")
        bad("BEGIN WEEK", h, "0,0,,1,2,3,4")
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
    fun badHeaderThrows() {
        bad("wrong")
        bad("BEGIN WEEK", "wrong")
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
