package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TodayReplyTest {
    private fun fixtureLines(): List<String> {
        val reply = ReplyAssembler().append(Fixtures.file("today_reply_sample.txt").readBytes())
        return (reply as? ConsoleReply.Frame)?.lines ?: throw AssertionError("fixture is no valid frame")
    }

    private fun bad(vararg lines: String) {
        try {
            TodayReply.parse(lines.toList())
            fail("expected ParseException")
        } catch (_: ParseException) {
        }
    }

    private val h = TodayReply.HEADER

    @Test
    fun sharedFixtureGivesTheRows() {
        val rows = TodayReply.parse(fixtureLines())
        assertEquals(2, rows.size)
        assertEquals(TodayRow(0, "onboard", 5, 13.0, 17.0, 15.0, 43.0, 47.0, 45.0), rows[0])
        assertEquals(TodayRow(1, "slot1", 2, -3.5, -2.5, -3.0, 80.0, 81.0, 80.5), rows[1])
    }

    @Test
    fun emptyFieldsGiveNull() {
        val rows = TodayReply.parse(listOf("BEGIN TODAY", h, "2,slot2,0,,,,,,", "3,slot3,4,10.0,12.0,11.0,,,"))
        assertEquals(TodayRow(2, "slot2", 0, null, null, null, null, null, null), rows[0])
        assertNull(rows[1].humidityMeanPct)
        assertEquals(11.0, rows[1].temperatureMeanC!!, 0.0)
    }

    @Test
    fun noRowsIsEmpty() {
        assertTrue(TodayReply.parse(listOf("BEGIN TODAY", h)).isEmpty())
    }

    @Test
    fun eightColumnsThrow() {
        bad("BEGIN TODAY", h, "0,onboard,5,13.0,17.0,15.0,43.0,47.0")
        bad("BEGIN TODAY", h, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,1")
    }

    @Test
    fun badNumberThrows() {
        bad("BEGIN TODAY", h, "0,onboard,5,temp_min_c=x,17.0,15.0,43.0,47.0,45.0")
        bad("BEGIN TODAY", h, "0,onboard,5,NaN,17.0,15.0,43.0,47.0,45.0")
        bad("BEGIN TODAY", h, "0,onboard,x,13.0,17.0,15.0,43.0,47.0,45.0")
        bad("BEGIN TODAY", h, "0,onboard,25,13.0,17.0,15.0,43.0,47.0,45.0")
        bad("BEGIN TODAY", h, "11,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0")
        bad("BEGIN TODAY", h, "0,onboard,5,18.0,17.0,15.0,43.0,47.0,45.0")
        bad("BEGIN TODAY", h, "0,a,1,1,2,1,1,2,1", "0,b,1,1,2,1,1,2,1")
    }

    @Test
    fun wrongHeaderThrows() {
        bad("wrong")
        bad("BEGIN TODAY")
        bad("BEGIN TODAY", "sensor,name,hours")
        bad("BEGIN TODAY", "sensor,day,date,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct,mold_h")
    }

    @Test
    fun noTimeGivesText() {
        assertEquals("Panel clock not set", TodayReply.statusText("ERR NO_TIME"))
        assertNull(TodayReply.statusText("ERR BAD_ARG"))
    }
}
