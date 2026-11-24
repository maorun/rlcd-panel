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

    private fun vectorNumber(text: String): Double = if (text.equals("nan", ignoreCase = true)) Double.NaN else text.toDouble()

    @Test
    fun derivedCurvesMatchSharedClimateVectors() {
        var checked = 0
        for (raw in Fixtures.file("climate_math_vectors.txt").readLines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val c = line.split("|").map { it.trim() }
            val row = PanelHistoryRow(0, 0, vectorNumber(c[0]), vectorNumber(c[1]), null)
            val abs = c[3]
            val heat = c[4]
            if (abs == "-") assertNull(line, row.value(HistoryCurve.ABSOLUTE_HUMIDITY))
            else assertEquals(line, abs.toDouble(), row.value(HistoryCurve.ABSOLUTE_HUMIDITY)!!, 0.05)
            if (heat == "-") assertNull(line, row.value(HistoryCurve.FEELS_LIKE))
            else assertEquals(line, heat.toDouble(), row.value(HistoryCurve.FEELS_LIKE)!!, 0.05)
            checked++
        }
        assertEquals(true, checked >= 15)
    }

    @Test
    fun derivedCurvesOfTheSampleFixtureAndGap() {
        val rows = HistoryReply.parse(fixtureLines())
        // Row 0,0 is 21.4 C / 45 %RH: 8.22 g/m3 at 21.0 C in the vectors, about 8.43 at 21.4 C.
        assertEquals(8.43, rows[0].value(HistoryCurve.ABSOLUTE_HUMIDITY)!!, 0.06)
        assertEquals(21.4, rows[0].value(HistoryCurve.FEELS_LIKE)!!, 0.05)
        assertEquals(21.4, rows[0].value(HistoryCurve.TEMPERATURE)!!, 0.0)
        assertEquals(45.0, rows[0].value(HistoryCurve.HUMIDITY)!!, 0.0)
        // Sensor 3 has no temperature: derived curves have a gap, humidity stays.
        val noTemp = rows.single { it.sensor == 3 }
        assertNull(noTemp.value(HistoryCurve.FEELS_LIKE))
        assertNull(noTemp.value(HistoryCurve.ABSOLUTE_HUMIDITY))
        assertNull(noTemp.value(HistoryCurve.TEMPERATURE))
        assertEquals(70.0, noTemp.value(HistoryCurve.HUMIDITY)!!, 0.0)
        // No humidity: derived curves have a gap, temperature stays.
        val noRh = PanelHistoryRow(1, 2, 20.0, null, null)
        assertNull(noRh.value(HistoryCurve.FEELS_LIKE))
        assertNull(noRh.value(HistoryCurve.ABSOLUTE_HUMIDITY))
        assertNull(noRh.value(HistoryCurve.HUMIDITY))
        assertEquals(20.0, noRh.value(HistoryCurve.TEMPERATURE)!!, 0.0)
    }
}
