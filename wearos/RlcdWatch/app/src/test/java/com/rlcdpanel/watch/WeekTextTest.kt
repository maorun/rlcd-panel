package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.ConsoleReply
import com.rlcdpanel.watch.protocol.Fixtures
import com.rlcdpanel.watch.protocol.ReplyAssembler
import com.rlcdpanel.watch.protocol.WeekReply
import com.rlcdpanel.watch.protocol.WeekRow
import org.junit.Assert.assertEquals
import org.junit.Test

class WeekTextTest {
    private fun rows(): List<WeekRow> {
        val reply = ReplyAssembler().append(Fixtures.file("week_reply_sample.txt").readBytes())
        return WeekReply.parse((reply as ConsoleReply.Frame).lines)
    }

    @Test
    fun gapDayShowsDashes() {
        val gap = rows().single { it.sensor == 2 && it.day == 3 }
        assertEquals("--", WeekText.temperature(gap))
        assertEquals("--", WeekText.humidity(gap))
        assertEquals("--", WeekText.dayLabel(WeekRow(0, 0, "", null, null, null, null)))
    }

    @Test
    fun normalDayShowsRanges() {
        val first = rows()[0]
        assertEquals("15.0 .. 19.5 °C", WeekText.temperature(first))
        assertEquals("40 .. 55 %", WeekText.humidity(first))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), WeekText.daysOf(rows(), 2).map { it.day })
    }

    @Test
    fun allSevenWeekdayLabels() {
        val labels = (13..19).map { WeekText.dayLabel(WeekRow(0, 0, "2026-04-$it", 1.0, 2.0, 3.0, 4.0)) }
        // 2026-04-13 is a Monday.
        assertEquals(listOf("Mo 13.04.", "Di 14.04.", "Mi 15.04.", "Do 16.04.", "Fr 17.04.", "Sa 18.04.", "So 19.04."), labels)
    }

    @Test
    fun moldText() {
        fun row(hours: Int?) = WeekRow(0, 0, "2026-04-13", 1.0, 2.0, 3.0, 4.0, hours)
        assertEquals("Schimmel 0 h", WeekText.mold(row(0)))
        assertEquals("⚠ Schimmel 5 h", WeekText.mold(row(5)))
        assertEquals("", WeekText.mold(row(null)))
    }
}
