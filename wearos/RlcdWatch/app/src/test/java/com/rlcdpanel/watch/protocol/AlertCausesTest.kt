package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AlertCausesTest {
    private val names = listOf("Wohnzimmer", "Bad", "Küche", "Flur", "Keller", "Garage")
    private fun fixtureLines(): List<String> =
        Fixtures.file("alerts_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }

    @Test
    fun frameGivesOneLineForEachActiveAlert() {
        val state = AlertCausesState.of(ConsoleReply.Frame(fixtureLines()), names)
        assertEquals(
            listOf("FREEZER: Küche", "HUMIDITY: Bad, Keller", "NOISE", "SENSOR_BATTERY: Küche", "HEAT: Flur"),
            state.lines
        )
        assertNull(state.message)
        assertFalse(state.isReading)
    }

    @Test
    fun causesMatchOnlyTheAlertsOfTheFrame() {
        val state = AlertCausesState.of(ConsoleReply.Frame(fixtureLines()), names)
        assertEquals(1 or 2 or 4 or 64 or 128, state.mask)
        assertEquals(true, state.matches(1 or 2 or 4 or 64 or 128))
        assertEquals(false, state.matches(2))
    }

    @Test
    fun cardLinesFollowTheState() {
        val mask = 1 or 2 or 4 or 64 or 128
        val read = AlertCausesState.of(ConsoleReply.Frame(fixtureLines()), names)
        assertEquals(read.lines, read.cardLines(mask))
        // A changed alert set hides the old causes.
        assertEquals(listOf(AlertCausesState.TAP_TEXT), read.cardLines(2))
        assertEquals(listOf(AlertCausesState.READING_TEXT), AlertCausesState(isReading = true).cardLines(mask))
        assertEquals(listOf("Update the panel firmware"), AlertCausesState.of(ConsoleReply.Status("ERR UNKNOWN"), names).cardLines(mask))
        assertEquals(listOf(AlertCausesState.TAP_TEXT), AlertCausesState().cardLines(mask))
    }

    @Test
    fun corruptReplyGivesMessage() {
        val state = AlertCausesState.of(ConsoleReply.Corrupt(ReplyError.Timeout), names)
        assertNull(state.lines)
        assertEquals("Bad reply from the panel", state.message)
        assertEquals("Keine Antwort", AlertCausesState.of(ConsoleReply.Corrupt(ReplyError.Timeout), names, "Keine Antwort").message)
    }

    @Test
    fun oldFirmwareGivesUpdateHint() {
        val state = AlertCausesState.of(ConsoleReply.Status("ERR UNKNOWN"), names)
        assertNull(state.lines)
        assertEquals("Update the panel firmware", state.message)
    }

    @Test
    fun unreadableFrameGivesMessage() {
        val state = AlertCausesState.of(ConsoleReply.Frame(listOf("BEGIN ALERTS", AlertsReply.HEADER, "HUMIDITY,1,0,0G1,70")), names)
        assertNull(state.lines)
        assertEquals("Antwort nicht lesbar: HUMIDITY,1,0,0G1,70", state.message)
    }
}

class AlertCausesMaskTest {
    @Test
    fun maskSixGivesTwoTileNames() {
        val names = listOf("Wohnzimmer", "Bad", "Küche", "Flur")
        val log = AlertsReply.parse(listOf("BEGIN ALERTS", AlertsReply.HEADER, "FREEZER,1,0,006,0.0"))
        assertEquals(listOf("FREEZER: Bad, Küche"), log.causeLines(names))
    }

    @Test
    fun noActiveAlertGivesEmptyLines() {
        val rows = AlertsReply.NAME_LIST.map { "$it,0,0,," }
        val state = AlertCausesState.of(ConsoleReply.Frame(listOf("BEGIN ALERTS", AlertsReply.HEADER) + rows), emptyList())
        assertEquals(emptyList<String>(), state.lines)
        assertEquals(0, state.mask)
    }
}

class AlertCauseRowsTest {
    private val names = listOf("Wohnzimmer", "Bad", "Küche", "Flur", "Keller")
    private fun log() = AlertsReply.parse(Fixtures.file("alerts_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") })

    @Test
    fun rowsShowTilesLimitAndAck() {
        assertEquals(
            listOf(
                "FREEZER: Küche · 0.0 °C",
                "HUMIDITY: Bad, Keller · 70 %",
                "NOISE · 60 dB · Acked",
                "SENSOR_BATTERY: Küche · 10 %",
                "HEAT: Flur · 30 °C"
            ),
            AlertCauseRows.lines(log(), names)
        )
    }

    @Test
    fun emptyReplyGivesNoLines() {
        val rows = AlertsReply.NAME_LIST.map { "$it,0,0,," }
        assertEquals(emptyList<String>(), AlertCauseRows.lines(AlertsReply.parse(listOf("BEGIN ALERTS", AlertsReply.HEADER) + rows), names))
    }

    @Test
    fun heldAlertWithoutTileKeepsLimit() {
        val log = AlertsReply.parse(listOf("BEGIN ALERTS", AlertsReply.HEADER, "HEAT,1,0,000,30"))
        assertEquals(listOf("HEAT: no tile above the limit · 30 °C"), AlertCauseRows.lines(log, names))
    }

    @Test
    fun shortTileNameListFallsBackToTileNumber() {
        val log = AlertsReply.parse(listOf("BEGIN ALERTS", AlertsReply.HEADER, "HUMIDITY,1,0,404,70"))
        assertEquals(listOf("HUMIDITY: Tile 2, Tile 10 · 70 %"), AlertCauseRows.lines(log, emptyList()))
    }
}
