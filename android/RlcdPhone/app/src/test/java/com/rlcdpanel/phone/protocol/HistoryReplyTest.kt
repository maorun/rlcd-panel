package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class HistoryReplyTest {
    private fun fixtureLines(): List<String> {
        val reply = ReplyAssembler().append(Fixtures.file("history_reply_sample.txt").readBytes())
        return (reply as? ConsoleReply.Frame)?.lines ?: throw AssertionError("fixture is no valid frame")
    }

    private fun bad(vararg lines: String) {
        try {
            HistoryReply.parse(lines.toList())
            fail("expected ParseException")
        } catch (_: ParseException) {
        }
    }

    private val h = HistoryReply.HEADER

    @Test
    fun sharedFixtureGivesTheSwiftValues() {
        val rows = HistoryReply.parse(fixtureLines())
        assertEquals(6, rows.size)
        assertEquals(PanelHistoryRow(0, 0, 21.4, 45.0, 38.0), rows[0])
        assertNull(rows[1].noiseDb)
        assertEquals(46.5, rows[1].humidityPct!!, 0.0)
        assertNull(rows[5].temperatureC)
        assertEquals(70.0, rows[5].humidityPct!!, 0.0)
    }

    @Test
    fun dashesGiveNull() {
        val rows = HistoryReply.parse(listOf("BEGIN HISTORY", h, "10,23,--,--,--"))
        assertEquals(PanelHistoryRow(10, 23, null, null, null), rows[0])
    }

    @Test
    fun negativeDecimalParsesAndBareMinusThrows() {
        val rows = HistoryReply.parse(listOf("BEGIN HISTORY", h, "0,0,-1.5,2,3"))
        assertEquals(-1.5, rows[0].temperatureC!!, 0.0)
        bad("BEGIN HISTORY", h, "0,0,-,2,3")
    }

    @Test
    fun badInputThrows() {
        bad("wrong")
        bad("BEGIN HISTORY", "wrong")
        bad("BEGIN HISTORY", h, "0,0,1,2")
        bad("BEGIN HISTORY", h, "0,0,1,2,3,4")
        bad("BEGIN HISTORY", h, "11,0,1,2,3")
        bad("BEGIN HISTORY", h, "0,24,1,2,3")
        bad("BEGIN HISTORY", h, "0,x,1,2,3")
        bad("BEGIN HISTORY", h, "0,0,NaN,2,3")
        bad("BEGIN HISTORY", h, "0,0, 1.0,2,3")
        assertEquals(0, HistoryReply.parse(listOf("BEGIN HISTORY", h)).size)
    }
}
