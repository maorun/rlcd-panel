package com.rlcdpanel.watch.protocol

import com.rlcdpanel.watch.EventLogState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventTextTest {
    private fun row(event: String, detail: Int) = EventRow("2026-11-18 09:00:00", event, detail)

    private fun sampleLog(): EventsLog {
        val lines = Fixtures.file("events_reply_sample.txt").readLines().filterNot { it.startsWith("CRC32") }
        return EventsReply.parse(lines)
    }

    @Test
    fun resetGivesReasonAndAbnormalMark() {
        assertEquals("reset PANIC (abnormal)", EventText.describe(row("RESET", 4)))
        assertTrue(EventText.isAbnormal(row("RESET", 4)))
        assertFalse(EventText.isAbnormal(row("RESET", 1)))
    }

    @Test
    fun slotStaleUsesHighAndLowByte() {
        assertEquals("Slot 3 back", EventText.describe(row("SLOT_STALE", 259)))
    }

    @Test
    fun powerSourceGivesUsbText() {
        assertEquals("USB power connected", EventText.describe(row("POWER_SOURCE", 1)))
    }

    @Test
    fun screenModelKeepsNewestEightRowsNewestFirst() {
        val log = sampleLog()
        val state = EventLogState(rows = log.rows, dropped = log.dropped, loaded = true)
        assertEquals(8, state.visibleRows.size)
        assertEquals(log.rows.last(), state.visibleRows.first())
        assertEquals(log.rows[log.rows.size - 8], state.visibleRows.last())
        assertEquals(listOf("FUTURE_EVENT", "POWER_SOURCE", "POWER_SOURCE"), state.visibleRows.take(3).map { it.event })
    }

    @Test
    fun timeShowsHoursAndMinutes() {
        assertEquals("09:01", EventLogState.formatTime("2026-11-18T09:01:30"))
        assertEquals("09:01", EventLogState.formatTime("2026-11-18 09:01:30"))
        assertEquals(EventLogState.UNKNOWN_TIME, EventLogState.formatTime(""))
    }
}
