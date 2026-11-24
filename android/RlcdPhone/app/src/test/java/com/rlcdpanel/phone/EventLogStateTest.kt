package com.rlcdpanel.phone

import com.rlcdpanel.phone.protocol.EventRow
import org.junit.Assert.assertEquals
import org.junit.Test

class EventLogStateTest {
    private val rows = (1..5).map { EventRow("t$it", "RESET", it) }

    @Test
    fun newestGivesLastRowsNewestFirst() {
        val state = EventLogState(rows = rows, loaded = true)
        assertEquals(listOf(rows[4], rows[3], rows[2]), state.newest(3))
    }

    @Test
    fun newestGivesAllRowsWhenFewerExist() {
        val state = EventLogState(rows = rows.take(2), loaded = true)
        assertEquals(listOf(rows[1], rows[0]), state.newest(3))
    }

    @Test
    fun newestGivesNoRowsForEmptyState() {
        assertEquals(emptyList<EventRow>(), EventLogState().newest(3))
    }

    @Test
    fun newestGivesNoRowsForZeroOrNegativeCount() {
        val state = EventLogState(rows = rows, loaded = true)
        assertEquals(emptyList<EventRow>(), state.newest(0))
        assertEquals(emptyList<EventRow>(), state.newest(-1))
    }
}
