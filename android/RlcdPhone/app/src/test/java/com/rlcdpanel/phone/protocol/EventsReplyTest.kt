package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EventsReplyTest {
    private fun fixtureLines(): List<String> =
        Fixtures.file("events_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }

    @Test
    fun sharedFixtureParses() {
        val log = EventsReply.parse(fixtureLines())
        assertEquals(23, log.rows.size)
        assertEquals(EventRow("2026-11-18 09:21:00", "SCAN_FAULT", 1), log.rows[20])
        assertEquals(EventRow("2026-11-18 09:22:00", "WINDOW_OPEN", 257), log.rows[21])
        assertEquals(EventRow("2026-11-18 09:01:00", "RESET", 4), log.rows[0])
        assertEquals(0, log.dropped)
    }

    @Test
    fun sharedFixtureCrcMatches() {
        val text = Fixtures.file("events_reply_sample.txt").readText()
        val assembler = ReplyAssembler()
        val reply = assembler.append(text.toByteArray())
        assertEquals(ConsoleReply.Frame(fixtureLines()), reply)
    }

    @Test
    fun droppedRowGivesCountAndIsNoRow() {
        val log = EventsReply.parse(
            listOf("BEGIN EVENTS", EventsReply.HEADER, "2026-11-18 09:00:00,EVENTS_DROPPED,3", ",RESET,4")
        )
        assertEquals(3, log.dropped)
        assertEquals(listOf(EventRow("", "RESET", 4)), log.rows)
    }

    @Test
    fun emptyRingGivesNoRows() {
        val log = EventsReply.parse(listOf("BEGIN EVENTS", EventsReply.HEADER))
        assertEquals(0, log.rows.size)
    }

    @Test
    fun rowWithTwoColumnsThrows() {
        assertThrows(ParseException::class.java) {
            EventsReply.parse(listOf("BEGIN EVENTS", EventsReply.HEADER, "x,y"))
        }
    }

    @Test
    fun negativeDroppedCountThrows() {
        assertThrows(ParseException::class.java) {
            EventsReply.parse(listOf("BEGIN EVENTS", EventsReply.HEADER, "t,EVENTS_DROPPED,-3"))
        }
    }

    @Test
    fun badDetailThrows() {
        assertThrows(ParseException::class.java) {
            EventsReply.parse(listOf("BEGIN EVENTS", EventsReply.HEADER, "t,RESET,x"))
        }
    }

    @Test
    fun missingBeginOrHeaderThrows() {
        assertThrows(ParseException::class.java) { EventsReply.parse(listOf(EventsReply.HEADER)) }
        assertThrows(ParseException::class.java) { EventsReply.parse(listOf("BEGIN EVENTS", "a,b,c")) }
        assertThrows(ParseException::class.java) { EventsReply.parse(emptyList()) }
    }
}
