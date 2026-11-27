package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AlertsReplyTest {
    private val names = listOf("Wohnzimmer", "Bad", "Küche", "Flur", "Keller", "Garage")
    private fun head(vararg rows: String) = listOf("BEGIN ALERTS", AlertsReply.HEADER) + rows
    private fun fixtureLines(): List<String> =
        Fixtures.file("alerts_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }

    @Test
    fun sharedFixtureParses() {
        val r = AlertsReply.parse(fixtureLines())
        assertEquals(8, r.rows.size)
        assertEquals(listOf("FREEZER", "HUMIDITY", "NOISE", "SENSOR_BATTERY", "HEAT"), r.rows.filter { it.active }.map { it.name })
        val humidity = r.rows[1]
        assertEquals(0x012, humidity.tiles)
        assertEquals(listOf(1, 4), humidity.tileIndices)
        assertEquals("70", humidity.limit)
        assertEquals(true, r.rows[2].acked)
        assertNull(r.rows[2].tiles)
    }

    @Test
    fun causeTextNamesTheTiles() {
        val r = AlertsReply.parse(fixtureLines())
        assertEquals("HUMIDITY: Bad, Keller", r.causeText("HUMIDITY", names))
        assertEquals("FREEZER: Küche", r.causeText("FREEZER", names))
        assertEquals("HEAT: Flur", r.causeText("HEAT", names))
        assertEquals("NOISE", r.causeText("NOISE", names))
        assertNull(r.causeText("VENTILATE", names))
    }

    @Test
    fun heldAlertWithoutTileSaysSo() {
        val r = AlertsReply.parse(head("HUMIDITY,1,0,000,70"))
        assertEquals("HUMIDITY: no tile above the limit", r.causeText("HUMIDITY", names))
    }

    @Test
    fun causeLinesFollowTheAlertMask() {
        val r = AlertsReply.parse(fixtureLines())
        assertEquals(
            listOf("FREEZER: Küche", "HUMIDITY: Bad, Keller", "NOISE", "SENSOR_BATTERY: Küche", "HEAT: Flur"),
            r.causeLines(names)
        )
    }

    @Test
    fun missingTileNameFallsBackToTileNumber() {
        val r = AlertsReply.parse(head("HUMIDITY,1,0,400,70"))
        assertEquals("HUMIDITY: Tile 10", r.causeText("HUMIDITY", listOf("A")))
    }

    @Test
    fun badMaskThrows() {
        val e = assertThrows(ParseException::class.java) { AlertsReply.parse(head("HUMIDITY,1,0,0G1,70")) }
        assertEquals("HUMIDITY,1,0,0G1,70", e.line)
    }

    @Test
    fun otherBadLinesThrow() {
        for (line in listOf(
            "BOGUS,1,0,000,70", "HUMIDITY,2,0,000,70", "HUMIDITY,1,0,0012,70",
            "HUMIDITY,1,0,800,70", "HUMIDITY,1,0,000", "HUMIDITY,1,0,000,7x"
        )) {
            assertThrows(line, ParseException::class.java) { AlertsReply.parse(head(line)) }
        }
    }

    @Test
    fun repeatedAlertThrows() {
        val e = assertThrows(ParseException::class.java) {
            AlertsReply.parse(head("HUMIDITY,1,0,002,70", "HUMIDITY,0,0,000,70"))
        }
        assertEquals("HUMIDITY,0,0,000,70", e.line)
    }

    @Test
    fun missingHeaderThrows() {
        assertThrows(ParseException::class.java) { AlertsReply.parse(emptyList()) }
        assertThrows(ParseException::class.java) { AlertsReply.parse(listOf("BEGIN ALERTS")) }
    }

    @Test
    fun activeMaskFollowsTheAlertBits() {
        assertEquals(1 or 2 or 4 or 64 or 128, AlertsReply.parse(fixtureLines()).activeMask)
        assertEquals(0, AlertsReply.parse(head()).activeMask)
    }

    @Test
    fun oldFirmwareGivesUpdateHint() {
        assertEquals("Update the panel firmware", AlertsReply.errorText("ERR UNKNOWN"))
        assertEquals("ERR X", AlertsReply.errorText("ERR X"))
    }
}
