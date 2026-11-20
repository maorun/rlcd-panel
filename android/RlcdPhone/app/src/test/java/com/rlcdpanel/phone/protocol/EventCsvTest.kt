package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class EventCsvTest {
    private fun fixtureRows(): List<EventRow> {
        val lines = Fixtures.file("events_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }
        return EventsReply.parse(lines).rows
    }

    @Test
    fun headerIsFirstLine() {
        val lines = EventCsv.format(fixtureRows()).lines()
        assertEquals("time,event,detail,text", lines[0])
    }

    @Test
    fun oneLinePerRowInOrder() {
        val rows = fixtureRows()
        val lines = EventCsv.format(rows).trimEnd('\n').lines()
        assertEquals(rows.size + 1, lines.size)
        assertEquals("2026-11-18 09:01:00", lines[1].substringBefore(","))
    }

    @Test
    fun slotStaleRowHasText() {
        val lines = EventCsv.format(fixtureRows()).lines()
        assertEquals("2026-11-18 09:03:00,SLOT_STALE,3,Slot 3 lost", lines.first { it.contains(",SLOT_STALE,3,") })
    }

    @Test
    fun fieldWithCommaIsQuoted() {
        val csv = EventCsv.format(listOf(EventRow("", "FUTURE,EVENT\"x", 7)))
        val line = csv.lines()[1]
        assertEquals(true, line.startsWith(",\"FUTURE,EVENT\"\"x\",7,"))
    }

    @Test
    fun fieldWithNewlineIsQuoted() {
        val csv = EventCsv.format(listOf(EventRow("", "A\nB", 1)))
        assertEquals(true, csv.startsWith("time,event,detail,text\n,\"A\nB\",1,"))
    }

    @Test
    fun emptyListGivesOnlyHeader() {
        assertEquals("time,event,detail,text\n", EventCsv.format(emptyList()))
    }
}
