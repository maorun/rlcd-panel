package com.rlcdpanel.phone

import com.rlcdpanel.phone.protocol.ConsoleReply
import com.rlcdpanel.phone.protocol.Fixtures
import com.rlcdpanel.phone.protocol.ReplyAssembler
import com.rlcdpanel.phone.protocol.WeekReply
import org.junit.Assert.assertEquals
import org.junit.Test

class WeekChartModelTest {
    private fun fixtureRows() = WeekReply.parse(
        (ReplyAssembler().append(Fixtures.file("week_reply_sample.txt").readBytes()) as ConsoleReply.Frame).lines
    )

    @Test
    fun moldGivesSevenValuesOldestFirst() {
        // Day 0 to 6 of sensor 0 in the fixture: 0, 7, 2, 3, 4, 0, 1.
        assertEquals(listOf<Int?>(1, 0, 4, 3, 2, 7, 0), WeekChartModel.mold(fixtureRows(), 0))
    }

    @Test
    fun moldGivesNullForAGapDayAndAnEmptyField() {
        val rows = fixtureRows()
        // Sensor 2 day 3 is a gap day: position 3 from the left.
        assertEquals(null, WeekChartModel.mold(rows, 2)[3])
        // Sensor 1 day 2 has an empty mold field: position 4.
        assertEquals(null, WeekChartModel.mold(rows, 1)[4])
        assertEquals(24, WeekChartModel.mold(rows, 5)[0])
    }

    @Test
    fun moldIsNullForAReplyWithTheSevenColumnHeader() {
        val rows = WeekReply.parse(
            listOf("BEGIN WEEK", WeekReply.LEGACY_HEADER) + (0..6).map { "0,$it,2026-11-1$it,15.0,19.5,40.0,55.0" }
        )
        assertEquals(List<Int?>(7) { null }, WeekChartModel.mold(rows, 0))
    }
}
