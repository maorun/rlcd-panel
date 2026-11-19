package com.rlcdpanel.watch.protocol

import com.rlcdpanel.watch.SensorHealthText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PanelClockTest {
    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun reply(valid: Boolean = true, osf: Boolean = false) = TimeReply("2026-11-19T10:00:00", 0L, valid, osf)

    @Test
    fun parsesTimeReply() {
        val r = PanelClock.parseTimeReply(listOf("TIME 2026-11-19T10:00:00 valid=1 osf=0"))
        assertEquals("2026-11-19T10:00:00", r.timeString)
        assertEquals(millis("2026-11-19T09:00:00Z"), r.epochMillis)
        assertTrue(r.valid)
        assertFalse(r.osf)
    }

    @Test
    fun badTimeLineThrows() {
        assertThrows(ParseException::class.java) { PanelClock.parseTimeReply(listOf("TIME x valid=1 osf=0")) }
        assertThrows(ParseException::class.java) { PanelClock.parseTimeReply(emptyList()) }
    }

    @Test
    fun offsetSignIsPositiveWhenPanelIsAhead() {
        assertEquals(5L, PanelClock.offsetSeconds(100_000L + 5_000L, 100_000L))
        assertEquals(-3L, PanelClock.offsetSeconds(100_000L, 100_000L + 3_000L))
    }

    @Test
    fun offsetTextShowsSign() {
        assertEquals("Clock offset: +5 s", SensorHealthText.clockOffset(5))
        assertEquals("Clock offset: -3 s", SensorHealthText.clockOffset(-3))
        assertEquals("Clock offset: 0 s", SensorHealthText.clockOffset(0))
    }

    @Test
    fun warnsAboveLimitInBothDirections() {
        assertTrue(SensorHealthText.isClockWarning(reply(), 121))
        assertTrue(SensorHealthText.isClockWarning(reply(), -121))
    }

    @Test
    fun warnsForInvalidClockAndOscillatorStop() {
        assertTrue(SensorHealthText.isClockWarning(reply(valid = false), 0))
        assertTrue(SensorHealthText.isClockWarning(reply(osf = true), 0))
    }

    @Test
    fun noWarningAtLimitWithGoodFlags() {
        assertFalse(SensorHealthText.isClockWarning(reply(), 120))
        assertFalse(SensorHealthText.isClockWarning(reply(), -120))
    }

    @Test
    fun formatAndParseHandleSummerAndWinterTime() {
        assertEquals("2026-01-15T13:00:00", PanelClock.format(millis("2026-01-15T12:00:00Z")))
        assertEquals("2026-07-15T14:00:00", PanelClock.format(millis("2026-07-15T12:00:00Z")))
        assertEquals(millis("2026-07-15T12:00:00Z"), PanelClock.parse("2026-07-15T14:00:00"))
    }

    @Test
    fun badFlagTokenThrows() {
        assertThrows(ParseException::class.java) {
            PanelClock.parseTimeReply(listOf("TIME 2026-11-19T10:00:00 valid=2 osf=0"))
        }
    }

    @Test
    fun nullOffsetWithGoodFlagsGivesNoWarning() {
        assertFalse(SensorHealthText.isClockWarning(reply(), null))
        assertEquals("Clock offset: --", SensorHealthText.clockOffset(null))
    }

    @Test
    fun repeatedAutumnHourTakesEarlierOffset() {
        assertEquals(millis("2026-10-24T23:30:00Z"), PanelClock.parse("2026-10-25T01:30:00"))
        assertEquals(millis("2026-10-25T00:30:00Z"), PanelClock.parse("2026-10-25T02:30:00"))
    }

    @Test
    fun missingSpringHourShiftsForward() {
        assertEquals(millis("2026-03-29T01:30:00Z"), PanelClock.parse("2026-03-29T02:30:00"))
    }
}
