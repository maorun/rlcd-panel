package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.ConsoleReply
import com.rlcdpanel.watch.protocol.Fixtures
import com.rlcdpanel.watch.protocol.HistoryReply
import com.rlcdpanel.watch.protocol.PanelHistoryRow
import com.rlcdpanel.watch.protocol.ReplyAssembler
import com.rlcdpanel.watch.protocol.ReplyError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTextTest {
    private fun rows(): List<PanelHistoryRow> {
        val reply = ReplyAssembler().append(Fixtures.file("history_reply_sample.txt").readBytes())
        return HistoryReply.parse((reply as ConsoleReply.Frame).lines)
    }

    @Test
    fun minimumAndMaximumText() {
        assertEquals("Temp 20.5 .. 21.4 °C", HistoryText.temperatureRange(rows(), 0))
        assertEquals("RH 45 .. 47 %", HistoryText.humidityRange(rows(), 0))
        assertEquals("Temp --", HistoryText.temperatureRange(rows(), 3))
        assertEquals("RH --", HistoryText.humidityRange(rows(), 7))
    }

    @Test
    fun newestRowsInAgeOrder() {
        val many = (23 downTo 0).map { PanelHistoryRow(2, it, 20.0 + it, 50.0, null) }
        assertEquals(
            listOf("-0 h 20.0 °C 50 %", "-1 h 21.0 °C 50 %", "-2 h 22.0 °C 50 %", "-3 h 23.0 °C 50 %", "-4 h 24.0 °C 50 %", "-5 h 25.0 °C 50 %"),
            HistoryText.newestRows(many, 2)
        )
        assertEquals("-0 h -- °C 70 %", HistoryText.newestRows(rows(), 3).single())
        assertEquals("-0 h 20.0 °C -- %", HistoryText.rowText(PanelHistoryRow(1, 0, 20.0, null, null)))
    }

    @Test
    fun noRowsGiveNoData() {
        assertTrue(HistoryText.newestRows(rows(), 7).isEmpty())
        assertEquals("Keine Daten", HistoryText.NO_DATA)
    }

    @Test
    fun fallbackFilterKeepsOnlyTheRoom() {
        val room = HistoryText.rowsOf(rows(), 1)
        assertEquals(listOf(0, 1), room.map { it.ageHours })
        assertTrue(room.all { it.sensor == 1 })
    }

    @Test
    fun errLineStartsFallbackButLinkFaultsDoNot() {
        assertTrue(HistoryText.needsFallback(ConsoleReply.Status("ERR BAD_ARG")))
        assertFalse(HistoryText.needsFallback(ConsoleReply.Status("ERR BLUETOOTH_OFF")))
        assertFalse(HistoryText.needsFallback(ConsoleReply.Status("ERR PERMISSION")))
        assertFalse(HistoryText.needsFallback(ConsoleReply.Status("ERR SCAN_FAILED")))
        assertFalse(HistoryText.needsFallback(ConsoleReply.Status("ERR BAD_COMMAND")))
        assertFalse(HistoryText.needsFallback(ConsoleReply.Status("ERR READ_ONLY")))
        assertTrue(HistoryText.needsFallback(ConsoleReply.Status("ERR UNKNOWN")))
        assertFalse(HistoryText.needsFallback(ConsoleReply.Corrupt(ReplyError.Timeout)))
    }

    @Test
    fun timeoutShowsPanelNotInRange() {
        assertEquals("Panel not in range", HistoryText.failure(ConsoleReply.Corrupt(ReplyError.Timeout)))
    }

    @Test
    fun otherFailuresUseTheConsoleText() {
        assertEquals("Antwort zu groß", HistoryText.failure(ConsoleReply.Corrupt(ReplyError.TooLarge)))
        assertTrue(HistoryText.failure(ConsoleReply.Status("ERR READ_ONLY")).startsWith("Panel lehnt"))
    }
}
