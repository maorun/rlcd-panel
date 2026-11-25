package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceptionReplyTest {
    private fun fixtureLines(): List<String> =
        Fixtures.file("reception_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }

    private fun reply(vararg rows: String) = listOf("BEGIN RECEPTION", ReceptionReply.HEADER) + rows

    private val mac = "A1:A1:A1:A1:A1:A1"

    @Test
    fun sharedFixtureParses() {
        val rows = ReceptionReply.parse(fixtureLines())
        assertEquals(2, rows.size)
        assertEquals(1, rows[0].slot)
        assertEquals("", rows[0].name)
        assertEquals(24, rows[0].scans)
        assertEquals(12, rows[0].received)
        assertEquals(24, rows[0].cells.size)
        assertEquals(2, rows[1].slot)
        assertEquals("Cellar", rows[1].name)
        assertEquals(6, rows[1].scans)
        assertEquals(4, rows[1].received)
    }

    @Test
    fun sharedFixtureCrcMatches() {
        val text = Fixtures.file("reception_reply_sample.txt").readText()
        assertEquals(ConsoleReply.Frame(fixtureLines()), ReplyAssembler().append(text.toByteArray()))
    }

    @Test
    fun maskGivesCellsOldestFirst() {
        val row = ReceptionReply.parse(reply("2,$mac,,6,4,101101")).single()
        assertEquals(listOf(true, false, true, true, false, true), row.cells)
        assertEquals(66, row.percent)
        assertFalse(row.isLow)
    }

    @Test
    fun lowRateIsBelowFiftyPercent() {
        assertTrue(ReceptionReply.parse(reply("1,$mac,,6,2,100100")).single().isLow)
        assertFalse(ReceptionReply.parse(reply("1,$mac,,6,3,101010")).single().isLow)
    }

    @Test
    fun slotWithoutScanHasNoRate() {
        val row = ReceptionReply.parse(reply("1,$mac,,0,0,")).single()
        assertEquals(0, row.cells.size)
        assertNull(row.percent)
        assertFalse(row.isLow)
    }

    @Test
    fun emptyReplyGivesNoRows() {
        assertEquals(0, ReceptionReply.parse(reply()).size)
    }

    @Test
    fun badMaskCharacterThrows() {
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("1,$mac,,4,2,10a1")) }
    }

    @Test
    fun wrongMaskLengthThrows() {
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("1,$mac,,6,3,10101")) }
    }

    @Test
    fun wrongReceivedValueThrows() {
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("1,$mac,,6,5,101101")) }
    }

    @Test
    fun repeatedSlotThrows() {
        assertThrows(ParseException::class.java) {
            ReceptionReply.parse(reply("1,$mac,,6,4,101101", "1,A2:A2:A2:A2:A2:A2,,6,4,101101"))
        }
    }

    @Test
    fun wrongColumnCountThrows() {
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("1,$mac,,6,4")) }
    }

    @Test
    fun scanCountAbove24Throws() {
        val mask = "1".repeat(25)
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("1,$mac,,25,25,$mask")) }
    }

    @Test
    fun slotOutsideRangeThrows() {
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("0,$mac,,6,4,101101")) }
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("11,$mac,,6,4,101101")) }
    }

    @Test
    fun badMacThrows() {
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("1,A1:A1:A1,,6,4,101101")) }
    }

    @Test
    fun missingBeginOrHeaderThrows() {
        assertThrows(ParseException::class.java) { ReceptionReply.parse(listOf(ReceptionReply.HEADER)) }
        assertThrows(ParseException::class.java) { ReceptionReply.parse(listOf("BEGIN RECEPTION", "slot")) }
    }
}
