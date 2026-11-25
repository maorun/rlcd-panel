package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TodayCommandTest {
    private fun assertRejected(sensor: Int) {
        try {
            TodayCommand.line(sensor)
            fail("no error for sensor $sensor")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    private fun row(sensor: Int) = TodayRow(sensor, "s$sensor", 1, 1.0, 2.0, 1.5, 40.0, 50.0, 45.0)

    @Test
    fun commandLine() {
        assertEquals("TODAY 3", TodayCommand.line(3))
        assertEquals("TODAY 0", TodayCommand.line(0))
        assertEquals("TODAY 10", TodayCommand.line(10))
        assertEquals("TODAY", TodayCommand.line(null))
    }

    @Test
    fun sensorOutsideRangeThrows() {
        assertRejected(11)
        assertRejected(-1)
    }

    @Test
    fun fallbackOnlyForBadArgOfOneRoom() {
        assertTrue(TodayCommand.needsFallback(3, "ERR BAD_ARG"))
        assertTrue(TodayCommand.needsFallback(0, "ERR BAD_ARG\r"))
        assertTrue(TodayCommand.needsFallback(2, "ERR BAD_ARG extra"))
        assertFalse(TodayCommand.needsFallback(2, "ERR BAD_ARGX"))
        assertFalse(TodayCommand.needsFallback(null, "ERR BAD_ARG"))
        assertFalse(TodayCommand.needsFallback(3, "ERR NO_TIME"))
        assertFalse(TodayCommand.needsFallback(3, "OK"))
    }

    @Test
    fun selectKeepsOnlyTheRoom() {
        val rows = listOf(row(0), row(1), row(3))
        assertEquals(rows, TodayCommand.select(rows, null))
        assertEquals(listOf(row(3)), TodayCommand.select(rows, 3))
        assertEquals(emptyList<TodayRow>(), TodayCommand.select(rows, 2))
    }

    @Test
    fun parsesOneRoomFixture() {
        val reply = ReplyAssembler().append(Fixtures.file("today_one_reply_sample.txt").readBytes())
        val lines = (reply as? ConsoleReply.Frame)?.lines ?: throw AssertionError("fixture is no valid frame")
        val rows = TodayReply.parse(lines)
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].sensor)
        assertEquals("slot1", rows[0].name)
        assertEquals(2, rows[0].hours)
        assertEquals(-3.5, rows[0].temperatureMinC!!, 0.0)
        assertEquals(80.5, rows[0].humidityMeanPct!!, 0.0)
        assertEquals(rows, TodayCommand.select(rows, 1))
    }
}
