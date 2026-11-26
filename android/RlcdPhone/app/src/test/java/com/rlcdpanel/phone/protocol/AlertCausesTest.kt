package com.rlcdpanel.phone.protocol

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
