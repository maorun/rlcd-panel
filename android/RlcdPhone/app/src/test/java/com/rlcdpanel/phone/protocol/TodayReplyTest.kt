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
        bad("BEGIN TODAY", h, "0,onboard,5,13.0,17.0,15.0,48.0,47.0,45.0")
        bad("BEGIN TODAY", h, "+1,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0")
        bad("BEGIN TODAY", h, "0,onboard,5,1e3,17.0,15.0,43.0,47.0,45.0")
    }

    @Test
    fun wrongHeaderThrows() {
        bad("wrong")
        bad("BEGIN TODAY")
        bad("BEGIN TODAY", "sensor,name,hours")
        bad("BEGIN TODAY", "sensor,day,date,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct,mold_h")
    }

    @Test
    fun textShowsDashesForNull() {
        val row = TodayRow(0, "onboard", 5, 13.0, null, 15.0, null, null, 45.0)
        assertEquals("Temperatur: min 13.0 °C • max -- • Ø 15.0 °C", com.rlcdpanel.phone.TodayText.temperature(row))
        assertEquals("Feuchte: min -- • max -- • Ø 45.0 %rF", com.rlcdpanel.phone.TodayText.humidity(row))
    }

    @Test
    fun noTimeGivesText() {
        assertEquals("Panel clock not set", TodayReply.statusText("ERR NO_TIME"))
        assertNull(TodayReply.statusText("ERR BAD_ARG"))
    }

    private val h2 = TodayReply.HEADER_V2

    @Test
    fun oldHeaderGivesNullHours() {
        val rows = TodayReply.parse(fixtureLines())
        assertNull(rows[0].moldHours)
        assertNull(rows[0].dryHours)
        assertNull(rows[1].moldHours)
        assertNull(rows[1].dryHours)
    }

    @Test
    fun newHeaderGivesHours() {
        val rows = TodayReply.parse(listOf("BEGIN TODAY", h2, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,3,0"))
        assertEquals(3, rows[0].moldHours)
        assertEquals(0, rows[0].dryHours)
    }

    @Test
    fun emptyHourFieldsGiveNull() {
        val rows = TodayReply.parse(listOf("BEGIN TODAY", h2, "2,slot2,0,,,,,,,,", "3,slot3,4,10.0,12.0,11.0,,,,,2"))
        assertNull(rows[0].moldHours)
        assertNull(rows[0].dryHours)
        assertNull(rows[1].moldHours)
        assertEquals(2, rows[1].dryHours)
    }

    @Test
    fun badHourColumnsThrow() {
        bad("BEGIN TODAY", h2, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,3")
        bad("BEGIN TODAY", h2, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0")
        bad("BEGIN TODAY", h, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,3,0")
        bad("BEGIN TODAY", h2, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,3,7")
        bad("BEGIN TODAY", h2, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,6,0")
        bad("BEGIN TODAY", h2, "0,onboard,24,13.0,17.0,15.0,43.0,47.0,45.0,25,0")
        bad("BEGIN TODAY", h2, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,x,0")
        bad("BEGIN TODAY", h2, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,-1,0")
    }

    @Test
    fun hourTextShowsWarningAboveZero() {
        val row = TodayRow(0, "onboard", 5, 1.0, 2.0, 1.5, 1.0, 2.0, 1.5, 3, 0)
        assertEquals("Schimmel 3 h ⚠", com.rlcdpanel.phone.TodayText.mold(row))
        assertEquals("Trocken 0 h", com.rlcdpanel.phone.TodayText.dry(row))
        assertEquals("Schimmel --", com.rlcdpanel.phone.TodayText.mold(row.copy(moldHours = null)))
        assertEquals("Trocken 2 h ⚠", com.rlcdpanel.phone.TodayText.dry(row.copy(dryHours = 2)))
    }

    private fun hoursFixture(name: String): List<TodayRow> {
        val reply = ReplyAssembler().append(Fixtures.file(name).readBytes())
        val lines = (reply as? ConsoleReply.Frame)?.lines ?: throw AssertionError("$name is no valid frame")
        return TodayReply.parse(lines)
    }

    @Test
    fun hoursFixtureGivesMoldAndDry() {
        val rows = hoursFixture("today_hours_reply_sample.txt")
        assertEquals(2, rows.size)
        assertEquals(listOf(0, 1), rows.map { it.sensor })
        assertEquals(listOf("onboard", "slot1"), rows.map { it.name })
        assertEquals(listOf(5, 2), rows.map { it.hours })
        assertEquals(listOf(2, 2), rows.map { it.moldHours })
        assertEquals(listOf(3, 0), rows.map { it.dryHours })
    }

    @Test
    fun hoursOneFixtureGivesMoldAndDry() {
        val rows = hoursFixture("today_hours_one_reply_sample.txt")
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].sensor)
        assertEquals("slot1", rows[0].name)
        assertEquals(2, rows[0].hours)
        assertEquals(2, rows[0].moldHours)
        assertEquals(0, rows[0].dryHours)
    }
}
