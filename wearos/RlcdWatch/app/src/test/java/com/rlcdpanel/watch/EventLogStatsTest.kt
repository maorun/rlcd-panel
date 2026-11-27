package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.AlertTimeline
import com.rlcdpanel.watch.protocol.EventRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class EventLogStatsTest {
    private val zone = ZoneId.of("UTC")
    private val now = AlertTimeline.parse("2026-03-10 00:00:00", zone)!!

    @Test
    fun linesListNewestMonthFirstThenBadges() {
        val rows = listOf(
            EventRow("2026-01-05 08:00:00", "ALERT_RISE", 1),
            EventRow("2026-01-05 10:00:00", "ALERT_FALL", 1),
            EventRow("2026-02-02 12:00:00", "ALERT_RISE", 128),
            EventRow("2026-02-02 13:00:00", "ALERT_FALL", 128),
            EventRow("2026-02-03 08:00:00", "DRY_AIR", 0x0100),
            EventRow("2026-02-03 10:00:00", "DRY_AIR", 0x0000)
        )
        assertEquals(
            listOf("2026-02 HEAT: 1 x, 1.0 h", "2026-01 FREEZER: 1 x, 2.0 h", "2026-02 D Onboard: 1 x, 2.0 h"),
            EventLogStats.lines(rows, now, zone)
        )
    }

    @Test
    fun openIntervalCountsHoursUntilNow() {
        val rows = listOf(EventRow("2026-03-09 12:00:00", "ALERT_RISE", 128))
        assertEquals(listOf("2026-03 HEAT: 1 x, 12.0 h"), EventLogStats.lines(rows, now, zone))
    }

    @Test
    fun noRowsGiveNoLines() {
        assertTrue(EventLogStats.lines(emptyList(), now, zone).isEmpty())
    }
}
