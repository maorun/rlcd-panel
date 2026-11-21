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
    fun jumpDropGivesSlotText() {
        assertEquals("Slot 3 reading jump dropped", EventText.describe(row("JUMP_DROP", 3)))
        assertEquals("Slot 10 reading jump dropped", EventText.describe(row("JUMP_DROP", 10)))
        assertEquals("JUMP_DROP,0", EventText.describe(row("JUMP_DROP", 0)))
        assertEquals("JUMP_DROP,11", EventText.describe(row("JUMP_DROP", 11)))
        assertEquals("JUMP_DROP,259", EventText.describe(row("JUMP_DROP", 259)))
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
    fun clockSetGivesActionAndTransport() {
        assertEquals("Clock set (USB)", EventText.describe(row("CLOCK_SET", 0x0000)))
        assertEquals("RTC trimmed (BLE)", EventText.describe(row("CLOCK_SET", 0x0101)))
        assertEquals("CLOCK_SET,2", EventText.describe(row("CLOCK_SET", 0x0002)))
        assertEquals("Onboard sensor fault (NACK)", EventText.describe(row("SHTC3_FAULT", 0x0101)))
        assertEquals("Onboard sensor back", EventText.describe(row("SHTC3_FAULT", 0x0000)))
        assertEquals("SHTC3_FAULT,1025", EventText.describe(row("SHTC3_FAULT", 0x0401)))
        assertEquals("SHTC3_FAULT,1", EventText.describe(row("SHTC3_FAULT", 0x0001)))
        assertEquals("SHTC3_FAULT,256", EventText.describe(row("SHTC3_FAULT", 0x0100)))
        assertEquals("SHTC3_FAULT,2", EventText.describe(row("SHTC3_FAULT", 0x0002)))
        assertEquals("Slot 4 battery low", EventText.describe(row("SENSOR_BATTERY", 260)))
        assertEquals("Slot 4 battery OK", EventText.describe(row("SENSOR_BATTERY", 4)))
        assertEquals("SENSOR_BATTERY,11", EventText.describe(row("SENSOR_BATTERY", 11)))
        assertEquals("SENSOR_BATTERY,516", EventText.describe(row("SENSOR_BATTERY", 516)))
    }

    @Test
    fun screenModelKeepsNewestEightRowsNewestFirst() {
        val log = sampleLog()
        val state = EventLogState(rows = log.rows, dropped = log.dropped, loaded = true)
        assertEquals(8, state.visibleRows.size)
        assertEquals(log.rows.last(), state.visibleRows.first())
        assertEquals(log.rows[log.rows.size - 8], state.visibleRows.last())
        assertEquals(listOf("FUTURE_EVENT", "JUMP_DROP", "SENSOR_BATTERY"), state.visibleRows.take(3).map { it.event })
    }

    @Test
    fun timeShowsHoursAndMinutes() {
        assertEquals("09:01", EventLogState.formatTime("2026-11-18T09:01:30"))
        assertEquals("09:01", EventLogState.formatTime("2026-11-18 09:01:30"))
        assertEquals(EventLogState.UNKNOWN_TIME, EventLogState.formatTime(""))
    }
}
