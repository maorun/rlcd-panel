package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoldSummaryTest {
    /** First mold column of the onboard sensor in a `DAYS` row. */
    private val onboardMoldColumn = 1 + 6 * DaysReply.SENSOR_COUNT + 5

    private fun fixtureLines(): List<String> =
        Fixtures.file("days_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }

    /** The shared fixture (3 days, onboard sensor) with the given onboard mold hours; null keeps the empty field. */
    private fun rowsWithMold(vararg hours: Int?): List<DaysRow> {
        var day = 0
        val lines = fixtureLines().mapIndexed { i, line ->
            if (i < 2) line else {
                val c = line.split(",").toMutableList()
                hours[day++]?.let { c[onboardMoldColumn] = it.toString() }
                c.joinToString(",")
            }
        }
        return DaysReply.parse(lines)
    }

    @Test
    fun fixtureWithoutMoldValueGivesNullTotal() {
        val room = MoldSummary.build(DaysReply.parse(fixtureLines()), PanelConfig()).single()
        assertEquals(0, room.sensor)
        assertNull(room.totalHours)
        assertEquals(0, room.moldDays)
        assertNull(room.worstDate)
        assertNull(room.worstHours)
    }

    @Test
    fun givesTotalDayCountAndWorstDay() {
        val room = MoldSummary.build(rowsWithMold(2, 7, 0), PanelConfig()).single()
        assertEquals(9, room.totalHours)
        assertEquals(2, room.moldDays)
        assertEquals("2026-01-01", room.worstDate)
        assertEquals(7, room.worstHours)
    }

    @Test
    fun tieSelectsTheNewestDay() {
        val room = MoldSummary.build(rowsWithMold(5, 5, 3), PanelConfig()).single()
        assertEquals(13, room.totalHours)
        assertEquals(3, room.moldDays)
        assertEquals("2026-01-01", room.worstDate)
        assertEquals(5, room.worstHours)
    }

    @Test
    fun roomWithoutMoldHoursHasTotalZeroAndNoWorstDay() {
        val room = MoldSummary.build(rowsWithMold(0, 0, 0), PanelConfig()).single()
        assertEquals(0, room.totalHours)
        assertEquals(0, room.moldDays)
        assertNull(room.worstDate)
    }

    @Test
    fun gapDaysDoNotCount() {
        val room = MoldSummary.build(rowsWithMold(null, 4, null), PanelConfig()).single()
        assertEquals(4, room.totalHours)
        assertEquals(1, room.moldDays)
        assertEquals("2026-01-01", room.worstDate)
    }

    @Test
    fun noRowsGiveNoRoom() {
        assertTrue(MoldSummary.build(emptyList(), PanelConfig()).isEmpty())
    }
}
