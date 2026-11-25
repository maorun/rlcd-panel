package com.rlcdpanel.watch.protocol

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

    @Test
    fun hourLine() {
        assertEquals("HISTORY 4 6", HistoryCommand.lineHours(4, 6))
        assertEquals("HISTORY 0 1", HistoryCommand.lineHours(0, 1))
        assertEquals("HISTORY 10 24", HistoryCommand.lineHours(10, 24))
        assertThrows(IllegalArgumentException::class.java) { HistoryCommand.lineHours(11, 6) }
        assertThrows(IllegalArgumentException::class.java) { HistoryCommand.lineHours(-1, 6) }
        assertThrows(IllegalArgumentException::class.java) { HistoryCommand.lineHours(4, 0) }
        assertThrows(IllegalArgumentException::class.java) { HistoryCommand.lineHours(4, 25) }
    }

    @Test
    fun fallsBackFromHourWindowOnlyForBadArg() {
        assertEquals(true, HistoryCommand.shouldFallBackFromHours("ERR BAD_ARG"))
        assertEquals(false, HistoryCommand.shouldFallBackFromHours("ERR UNKNOWN"))
        assertEquals(false, HistoryCommand.shouldFallBackFromHours("ERR NOT_OVER_BLE"))
        assertEquals(false, HistoryCommand.shouldFallBackFromHours("OK"))
    }
}
