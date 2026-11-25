package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpreadCommandTest {
    private fun assertRejected(hours: Int) {
        try {
            SpreadCommand.line(hours)
            fail("no error for $hours hours")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun sixHoursSendsThePlainCommand() {
        assertEquals("SPREAD", SpreadCommand.line(6))
        assertEquals(SpreadCommand.LINE, SpreadCommand.line(SpreadCommand.DEFAULT_HOURS))
    }

    @Test
    fun longerWindowsSendTheArgument() {
        assertEquals("SPREAD 7", SpreadCommand.line(7))
        assertEquals("SPREAD 12", SpreadCommand.line(12))
        assertEquals("SPREAD 24", SpreadCommand.line(24))
    }

    @Test
    fun hoursOutsideSixToTwentyFourThrow() {
        assertRejected(5)
        assertRejected(25)
        assertRejected(0)
        assertRejected(-6)
    }

    @Test
    fun pickerOffersSixTwelveAndTwentyFour() {
        assertEquals(listOf(6, 12, 24), SpreadCommand.PICKER_HOURS)
        assertEquals("Window 12 h", SpreadCommand.windowText(12))
    }

    @Test
    fun badArgOfALongWindowMeansOldFirmware() {
        assertTrue(SpreadCommand.isOldFirmware(12, "ERR BAD_ARG"))
        assertTrue(SpreadCommand.isOldFirmware(24, "ERR BAD_ARG"))
        assertTrue(SpreadCommand.isOldFirmware(12, "ERR BAD_ARG extra"))
        assertFalse(SpreadCommand.isOldFirmware(12, "ERR BAD_ARGX"))
        assertFalse(SpreadCommand.isOldFirmware(6, "ERR BAD_ARG"))
        assertFalse(SpreadCommand.isOldFirmware(12, "ERR READ_ONLY"))
        assertEquals("Update the panel firmware for longer windows", SpreadCommand.OLD_FIRMWARE_TEXT)
    }
}
