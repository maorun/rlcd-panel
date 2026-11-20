package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WeekReplyTest {
    private val h = WeekReply.HEADER

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
        assertEquals(WeekRow(0, 0, "2026-11-17", 15.0, 19.5, 40.0, 55.0), rows[0])
        val gap = rows.single { it.sensor == 2 && it.day == 3 }
        assertEquals("2026-11-14", gap.date)
        assertNull(gap.temperatureMinC)
        assertNull(gap.temperatureMaxC)
        assertNull(gap.humidityMinPct)
        assertNull(gap.humidityMaxPct)
        assertTrue(gap.isGap)
        assertEquals(1, rows.count { it.isGap })
    }

    @Test
    fun wrongColumnCountThrows() {
        bad("BEGIN WEEK", h, "0,0,2026-11-14,1,2,3")
        bad("BEGIN WEEK", h, "0,0,2026-11-14,1,2,3,4,5")
    }

    @Test
    fun badHeaderThrows() {
        bad("wrong")
        bad("BEGIN WEEK", "wrong")
    }
}
