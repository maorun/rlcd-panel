package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeekMoldTextTest {
    private fun fixtureRows(): List<WeekRow> {
        val reply = ReplyAssembler().append(Fixtures.file("week_reply_sample.txt").readBytes())
        val lines = (reply as? ConsoleReply.Frame)?.lines ?: throw AssertionError("fixture is no valid frame")
        return WeekReply.parse(lines)
    }

    @Test
    fun fixtureSumOfSensorZero() {
        assertEquals("Schimmel 17 h", WeekMoldText.summary(fixtureRows().filter { it.sensor == 0 }))
    }

    @Test
    fun emptyFieldDayDoesNotAddToTheSum() {
        // Sensor 1 has an empty mold field on day 2; the other six days give 1 + 2 + 4 + 0 + 1 + 2.
        assertEquals("Schimmel 10 h", WeekMoldText.summary(fixtureRows().filter { it.sensor == 1 }))
    }

    @Test
    fun gapDaysDoNotAddToTheSum() {
        val rows = listOf(
            WeekRow(0, 0, "2026-11-17", 15.0, 19.5, 40.0, 55.0, 3),
            WeekRow(0, 1, "2026-11-16", null, null, null, null, null),
            WeekRow(0, 2, "2026-11-15", 15.0, 19.5, 40.0, 55.0, 4)
        )
        assertEquals("Schimmel 7 h", WeekMoldText.summary(rows))
    }

    @Test
    fun sevenColumnReplyGivesNull() {
        val lines = listOf(
            "BEGIN WEEK",
            WeekReply.LEGACY_HEADER,
            "0,0,2026-11-17,15.0,19.5,40.0,55.0",
            "0,1,2026-11-16,15.5,20.0,41.0,56.0"
        )
        assertNull(WeekMoldText.summary(WeekReply.parse(lines)))
    }

    @Test
    fun noRowsGiveNull() {
        assertNull(WeekMoldText.summary(emptyList()))
    }
}
