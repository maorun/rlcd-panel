package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HistoryCommandTest {
    @Test
    fun commandLine() {
        assertEquals("HISTORY 3", HistoryCommand.line(3))
        assertEquals("HISTORY 10", HistoryCommand.line(10))
        assertEquals("HISTORY", HistoryCommand.line(null))
        assertThrows(IllegalArgumentException::class.java) { HistoryCommand.line(11) }
        assertThrows(IllegalArgumentException::class.java) { HistoryCommand.line(-1) }
    }

    @Test
    fun filterKeepsOneSensor() {
        val lines = Fixtures.file("history_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }
        val rows = HistoryCommand.filter(HistoryReply.parse(lines), 1)
        assertEquals(2, rows.size)
        assertEquals(listOf(1, 1), rows.map { it.sensor })
        assertEquals(listOf(0, 1), rows.map { it.ageHours })
    }

    @Test
    fun fallsBackOnlyForUnknownOfOneSensorRead() {
        assertEquals(true, HistoryCommand.shouldFallBack(3, "ERR UNKNOWN"))
        assertEquals(false, HistoryCommand.shouldFallBack(3, "ERR BAD_ARG"))
        assertEquals(false, HistoryCommand.shouldFallBack(null, "ERR UNKNOWN"))
        assertEquals(false, HistoryCommand.shouldFallBack(3, "ERR NOT_OVER_BLE"))
        assertEquals(false, HistoryCommand.shouldFallBack(3, "ERR NO_CONSOLE"))
        assertEquals(false, HistoryCommand.shouldFallBack(3, "OK"))
    }
}
