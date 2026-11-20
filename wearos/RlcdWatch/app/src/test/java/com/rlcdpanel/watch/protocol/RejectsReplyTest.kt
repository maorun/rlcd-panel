package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RejectsReplyTest {
    private fun fixtureLines(): List<String> =
        Fixtures.file("rejects_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }

    private fun reply(vararg rows: String) = listOf("BEGIN REJECTS", RejectsReply.HEADER) + rows

    @Test
    fun sharedFixtureGivesRowsReasonsAndDropped() {
        val log = RejectsReply.parse(fixtureLines())
        assertEquals(
            listOf(
                RejectRow("AA:00:00:00:00:13", "METER", RejectReason.FULL, 2),
                RejectRow("AA:00:00:00:00:14", "ATC", RejectReason.UNASSIGNED, 1),
                RejectRow("AA:00:00:00:00:01", "WOIO", RejectReason.JUMP, 1),
                RejectRow("AA:00:00:00:00:15", "THERMOPRO", RejectReason.DISABLED, 255)
            ),
            log.rows
        )
        assertEquals(1, log.dropped)
    }

    @Test
    fun sharedFixtureCrcMatches() {
        val text = Fixtures.file("rejects_reply_sample.txt").readText()
        assertEquals(ConsoleReply.Frame(fixtureLines()), ReplyAssembler().append(text.toByteArray()))
    }

    @Test
    fun headerOnlyGivesEmptyList() {
        val log = RejectsReply.parse(reply())
        assertEquals(0, log.rows.size)
        assertEquals(0, log.dropped)
    }

    @Test
    fun reasonLostThrows() {
        assertThrows(ParseException::class.java) { RejectsReply.parse(reply("AA:00:00:00:00:02,ATC,LOST,1")) }
    }

    @Test
    fun missingBeginOrHeaderThrows() {
        assertThrows(ParseException::class.java) { RejectsReply.parse(listOf("BEGIN REJECTS", "a,b,c,d")) }
        assertThrows(ParseException::class.java) { RejectsReply.parse(emptyList()) }
    }
}
