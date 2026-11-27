package com.rlcdpanel.watch.protocol

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
        assertEquals("T 13.0 / -- / Ø 15.0 °C", com.rlcdpanel.watch.TodayScreenText.temperature(row))
        assertEquals("RH -- / -- / Ø 45.0 %", com.rlcdpanel.watch.TodayScreenText.humidity(row))
    }

    @Test
    fun sensorWithoutHoursShowsDashes() {
        val row = TodayRow(2, "slot2", 0, null, null, null, null, null, null)
        assertEquals("--", com.rlcdpanel.watch.TodayScreenText.temperature(row))
        assertEquals("--", com.rlcdpanel.watch.TodayScreenText.humidity(row))
        assertEquals("--", com.rlcdpanel.watch.TodayScreenText.hours(row))
        assertEquals("5 h", com.rlcdpanel.watch.TodayScreenText.hours(TodayRow(0, "onboard", 5, 1.0, 2.0, 1.5, 1.0, 2.0, 1.5)))
    }

    @Test
    fun noTimeGivesText() {
        assertEquals("Panel clock not set", TodayReply.statusText("ERR NO_TIME"))
        assertNull(TodayReply.statusText("ERR BAD_ARG"))
    }

    private val h2 = TodayReply.HEADER_V2

    @Test
    fun oldFixtureHasNullMoldAndDry() {
        TodayReply.parse(fixtureLines()).forEach {
            assertNull(it.moldHours)
            assertNull(it.dryHours)
        }
    }

    @Test
    fun newRowGivesMoldAndDry() {
        val rows = TodayReply.parse(listOf("BEGIN TODAY", h2, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,3,0", "1,slot1,5,1.0,2.0,1.5,40.0,41.0,40.5,,5"))
        assertEquals(3, rows[0].moldHours)
        assertEquals(0, rows[0].dryHours)
        assertNull(rows[1].moldHours)
        assertEquals(5, rows[1].dryHours)
    }

    @Test
    fun newHeaderBadRowsThrow() {
        bad("BEGIN TODAY", h2, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,3")
        bad("BEGIN TODAY", h2, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0")
        bad("BEGIN TODAY", h2, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,7,0")
        bad("BEGIN TODAY", h2, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,0,7")
        bad("BEGIN TODAY", h2, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,x,0")
        bad("BEGIN TODAY", h, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,3,0")
    }

    @Test
    fun zeroHoursAllowOnlyZeroOrEmpty() {
        val rows = TodayReply.parse(listOf("BEGIN TODAY", h2, "2,slot2,0,,,,,,,0,0", "3,slot3,0,,,,,,,,"))
        assertEquals(0, rows[0].moldHours)
        assertNull(rows[1].dryHours)
        bad("BEGIN TODAY", h2, "2,slot2,0,,,,,,,1,0")
    }

    @Test
    fun moldDryLineText() {
        val row = TodayRow(0, "onboard", 5, 1.0, 2.0, 1.5, 1.0, 2.0, 1.5, 3, null)
        assertEquals("Mold 3 h · Dry -- h", com.rlcdpanel.watch.TodayScreenText.moldDry(row))
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

    private val h3 = TodayReply.HEADER_V3

    private fun heatFixture(name: String): List<TodayRow> {
        val reply = ReplyAssembler().append(Fixtures.file(name).readBytes())
        val lines = (reply as? ConsoleReply.Frame)?.lines ?: throw AssertionError("$name is no valid frame")
        return TodayReply.parse(lines)
    }

    @Test
    fun heatFixtureGivesHeatHours() {
        val rows = heatFixture("today_heat_reply_sample.txt")
        assertEquals(listOf(2, 0), rows.map { it.heatHours })
        assertEquals(listOf(0, 0), rows.map { it.moldHours })
    }

    @Test
    fun elevenColumnFixtureGivesNullHeat() {
        assertTrue(heatFixture("today_hours_reply_sample.txt").all { it.heatHours == null })
        assertTrue(heatFixture("today_reply_sample.txt").all { it.heatHours == null })
    }

    @Test
    fun emptyHeatFieldGivesNull() {
        val rows = TodayReply.parse(listOf("BEGIN TODAY", h3, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,2,3,"))
        assertNull(rows[0].heatHours)
        assertEquals(3, rows[0].dryHours)
    }

    @Test
    fun badHeatValuesThrow() {
        for (v in listOf("-1", "x", "6", "25")) bad("BEGIN TODAY", h3, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,2,3,$v")
        bad("BEGIN TODAY", h3, "0,onboard,5,13.0,17.0,15.0,43.0,47.0,45.0,2,3")
    }

    @Test
    fun heatText() {
        val row = TodayRow(0, "onboard", 5, 1.0, 2.0, 1.5, 1.0, 2.0, 1.5, 3, 0, 2)
        assertEquals("Heat 2 h", com.rlcdpanel.watch.TodayScreenText.heat(row))
        assertEquals("Heat -- h", com.rlcdpanel.watch.TodayScreenText.heat(row.copy(heatHours = null)))
    }
}
