package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RejectsReplyTest {
    private fun fixtureLines(): List<String> =
        Fixtures.file("rejects_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }

    private fun reply(vararg rows: String) = listOf("BEGIN REJECTS", RejectsReply.HEADER) + rows

    @Test
    fun sharedFixtureParses() {
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
    fun emptyListGivesNoRows() {
        val log = RejectsReply.parse(reply())
        assertEquals(0, log.rows.size)
        assertEquals(0, log.dropped)
    }

    @Test
    fun fixTextOfEachReason() {
        assertEquals("Free a slot", RejectReason.FULL.fix)
        assertEquals("Assign the MAC to a slot", RejectReason.UNASSIGNED.fix)
        assertEquals("Enable the slot in slot_enabled_mask", RejectReason.DISABLED.fix)
        assertEquals("Check the sensor position", RejectReason.JUMP.fix)
        assertEquals("Move the sensor nearer or lower auto_assign_min_rssi", RejectReason.WEAK.fix)
        val row = RejectsReply.parse(reply("AA:00:00:00:00:02,BTHOME,WEAK,3")).rows.single()
        assertEquals(RejectReason.WEAK.fix, row.fix)
    }

    @Test
    fun unknownReasonThrows() {
        assertThrows(ParseException::class.java) { RejectsReply.parse(reply("AA:00:00:00:00:02,ATC,SLOT_OFF,1")) }
    }

    @Test
    fun unknownFormatThrows() {
        assertThrows(ParseException::class.java) { RejectsReply.parse(reply("AA:00:00:00:00:02,XYZ,FULL,1")) }
    }

    @Test
    fun countZeroOrAbove255Throws() {
        assertThrows(ParseException::class.java) { RejectsReply.parse(reply("AA:00:00:00:00:02,ATC,FULL,0")) }
        assertThrows(ParseException::class.java) { RejectsReply.parse(reply("AA:00:00:00:00:02,ATC,FULL,256")) }
        assertThrows(ParseException::class.java) { RejectsReply.parse(reply("AA:00:00:00:00:02,ATC,FULL,-1")) }
    }

    @Test
    fun wrongColumnCountThrows() {
        assertThrows(ParseException::class.java) { RejectsReply.parse(reply("AA:00:00:00:00:02,ATC,FULL")) }
        assertThrows(ParseException::class.java) { RejectsReply.parse(reply("AA:00:00:00:00:02,ATC,FULL,1,9")) }
    }

    @Test
    fun invalidMacThrows() {
        assertThrows(ParseException::class.java) { RejectsReply.parse(reply("AA:00:00:00:02,ATC,FULL,1")) }
        assertThrows(ParseException::class.java) { RejectsReply.parse(reply("ZZ:00:00:00:00:02,ATC,FULL,1")) }
    }

    @Test
    fun repeatedMacThrows() {
        assertThrows(ParseException::class.java) {
            RejectsReply.parse(reply("AA:00:00:00:00:02,ATC,FULL,1", "aa:00:00:00:00:02,ATC,JUMP,1"))
        }
    }

    @Test
    fun badDroppedRowThrows() {
        assertThrows(ParseException::class.java) { RejectsReply.parse(reply("DROPPED,0")) }
        assertThrows(ParseException::class.java) { RejectsReply.parse(reply("DROPPED,x")) }
        assertThrows(ParseException::class.java) { RejectsReply.parse(reply("DROPPED,256")) }
        assertThrows(ParseException::class.java) { RejectsReply.parse(reply("DROPPED,1,2")) }
        assertThrows(ParseException::class.java) { RejectsReply.parse(reply("DROPPED,1", "DROPPED,2")) }
        assertThrows(ParseException::class.java) { RejectsReply.parse(reply("DROPPED,1", "AA:00:00:00:00:02,ATC,FULL,1")) }
    }

    @Test
    fun missingBeginOrHeaderThrows() {
        assertThrows(ParseException::class.java) { RejectsReply.parse(listOf(RejectsReply.HEADER)) }
        assertThrows(ParseException::class.java) { RejectsReply.parse(listOf("BEGIN REJECTS", "a,b,c,d")) }
        assertThrows(ParseException::class.java) { RejectsReply.parse(emptyList()) }
    }

    @Test
    fun actionOfEachFixtureRow() {
        val actions = RejectsReply.parse(fixtureLines()).rows.map { it.reason to it.action }
        assertEquals(
            listOf(
                RejectReason.FULL to RejectAction.FREE,
                RejectReason.UNASSIGNED to RejectAction.ASSIGN,
                RejectReason.JUMP to null,
                RejectReason.DISABLED to null
            ),
            actions
        )
    }

    @Test
    fun weakGivesNoAction() {
        assertNull(RejectsReply.parse(reply("AA:00:00:00:00:02,BTHOME,WEAK,3")).rows.single().action)
    }
}
