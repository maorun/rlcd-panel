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

    @Test
    fun sharedFixtureGivesRows() {
        val rows = ReceptionReply.parse(fixtureLines())
        assertEquals(
            listOf(
                ReceptionRow(1, "A1:A1:A1:A1:A1:A1", "", 24, 12, "110100101101001011010010"),
                ReceptionRow(2, "A2:A2:A2:A2:A2:A2", "Cellar", 6, 4, "101101")
            ),
            rows
        )
    }

    @Test
    fun sharedFixtureCrcMatches() {
        val text = Fixtures.file("reception_reply_sample.txt").readText()
        assertEquals(ConsoleReply.Frame(fixtureLines()), ReplyAssembler().append(text.toByteArray()))
    }

    @Test
    fun maskOf24CharactersGivesNewest12Cells() {
        val row = ReceptionReply.parse(fixtureLines())[0]
        assertEquals("001011010010", row.newestCells())
        assertEquals(12, row.newestCells().length)
        assertEquals("101101", ReceptionReply.parse(fixtureLines())[1].newestCells())
    }

    @Test
    fun maskGivesCellsAndPercent() {
        val row = ReceptionReply.parse(reply("2,A2:A2:A2:A2:A2:A2,,6,4,101101")).single()
        assertEquals(listOf(true, false, true, true, false, true), row.cells)
        assertEquals(66, row.percent)
        assertFalse(row.isLow)
        assertEquals(24, ReceptionReply.parse(fixtureLines())[0].cells.size)
    }

    @Test
    fun slotWithoutScanHasNoRate() {
        val row = ReceptionReply.parse(reply("1,A1:A1:A1:A1:A1:A1,,0,0,")).single()
        assertEquals(0, row.cells.size)
        assertNull(row.percent)
        assertEquals("", row.newestCells())
        assertFalse(row.isLow)
    }

    @Test
    fun commandNameIsReception() {
        assertEquals("RECEPTION", ReceptionReply.COMMAND)
    }

    @Test
    fun warnsBelowHalf() {
        val rows = ReceptionReply.parse(
            reply("1,A1:A1:A1:A1:A1:A1,,4,1,0001", "2,A2:A2:A2:A2:A2:A2,,4,2,0011", "3,A3:A3:A3:A3:A3:A3,,0,0,")
        )
        assertTrue(rows[0].isLow)
        assertFalse(rows[1].isLow)
        assertFalse(rows[2].isLow)
    }

    @Test
    fun exactlyHalfIsNotLow() {
        val rows = ReceptionReply.parse(reply("1,A1:A1:A1:A1:A1:A1,,4,2,1010"))
        assertFalse(rows[0].isLow)
    }

    @Test
    fun badSlotThrows() {
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("0,A1:A1:A1:A1:A1:A1,,2,1,10")) }
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("11,A1:A1:A1:A1:A1:A1,,2,1,10")) }
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("01,A1:A1:A1:A1:A1:A1,,2,1,10")) }
    }

    @Test
    fun badMacThrows() {
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("1,A1:A1:A1:A1:A1,,2,1,10")) }
    }

    @Test
    fun wrongColumnCountThrows() {
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("1,A1:A1:A1:A1:A1:A1,2,1,10")) }
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("1,A1:A1:A1:A1:A1:A1,,2,1,10,0")) }
    }

    @Test
    fun scansAbove24Throws() {
        val mask = "1".repeat(25)
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("1,A1:A1:A1:A1:A1:A1,,25,25,$mask")) }
    }

    @Test
    fun badMaskCharacterThrows() {
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("1,A1:A1:A1:A1:A1:A1,,4,2,10a1")) }
    }

    @Test
    fun wrongMaskLengthThrows() {
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("1,A1:A1:A1:A1:A1:A1,,4,2,101")) }
    }

    @Test
    fun receivedNotMatchingMaskThrows() {
        assertThrows(ParseException::class.java) { ReceptionReply.parse(reply("1,A1:A1:A1:A1:A1:A1,,4,3,1010")) }
    }

    @Test
    fun repeatedSlotThrows() {
        assertThrows(ParseException::class.java) {
            ReceptionReply.parse(reply("1,A1:A1:A1:A1:A1:A1,,2,1,10", "1,A2:A2:A2:A2:A2:A2,,2,1,01"))
        }
    }

    @Test
    fun missingBeginOrHeaderThrows() {
        assertThrows(ParseException::class.java) { ReceptionReply.parse(listOf("BEGIN RECEPTION", "a,b")) }
        assertThrows(ParseException::class.java) { ReceptionReply.parse(emptyList()) }
    }

    @Test
    fun headerOnlyGivesEmptyList() {
        assertEquals(0, ReceptionReply.parse(reply()).size)
    }
}
