package com.rlcdpanel.watch.protocol

import com.rlcdpanel.watch.EventLogState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EventsReplyTest {
    private fun eightRows(): List<String> =
        (1..8).map { "2026-11-18 09:0$it:00,SLOT_STALE,$it" }

    private fun reply(vararg extra: String): List<String> =
        listOf("BEGIN EVENTS", EventsReply.HEADER) + extra.toList() + eightRows()

    @Test
    fun newestCommandGivesEventsAndCount() {
        assertEquals("EVENTS 8", EventsReply.newestCommand(EventLogState.SHORT_COUNT))
        assertEquals("EVENTS 1", EventsReply.newestCommand(1))
        assertEquals("EVENTS 16", EventsReply.newestCommand(16))
    }

    @Test
    fun newestCommandRejectsCountOutsideOneToSixteen() {
        assertThrows(IllegalArgumentException::class.java) { EventsReply.newestCommand(0) }
        assertThrows(IllegalArgumentException::class.java) { EventsReply.newestCommand(17) }
        assertThrows(IllegalArgumentException::class.java) { EventsReply.newestCommand(-1) }
    }

    @Test
    fun parsesEightRowReplyWithoutDroppedRow() {
        val log = EventsReply.parse(reply())
        assertEquals(8, log.rows.size)
        assertEquals(0, log.dropped)
        assertEquals("2026-11-18 09:01:00", log.rows.first().time)
        assertEquals(8, log.rows.last().detail)
    }

    @Test
    fun parsesEightRowReplyWithDroppedRow() {
        val log = EventsReply.parse(reply("2026-11-18 09:00:00,EVENTS_DROPPED,5"))
        assertEquals(8, log.rows.size)
        assertEquals(5, log.dropped)
        assertEquals(1, log.rows.first().detail)
        assertEquals(8, log.rows.last().detail)
    }

    @Test
    fun shortReadShowsEightRowsAndFullReadShowsAll() {
        val rows = (1..12).map { EventRow("2026-11-18 09:00:00", "SLOT_STALE", it) }
        val short = EventLogState(rows = rows, loaded = true).visibleRows
        assertEquals(8, short.size)
        assertEquals(12, short.first().detail)
        assertEquals(5, short.last().detail)
        assertEquals(12, EventLogState(rows = rows, loaded = true, all = true).visibleRows.size)
        assertEquals(12, EventLogState(rows = rows, loaded = true, all = true).visibleRows.first().detail)
    }
}
