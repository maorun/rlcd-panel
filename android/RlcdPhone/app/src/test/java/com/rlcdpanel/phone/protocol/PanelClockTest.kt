package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PanelClockTest {
    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun formatsWinterTimeAsCet() {
        assertEquals("2026-01-15T13:00:00", PanelClock.format(millis("2026-01-15T12:00:00Z")))
    }

    @Test
    fun formatsSummerTimeAsCest() {
        assertEquals("2026-07-15T14:00:00", PanelClock.format(millis("2026-07-15T12:00:00Z")))
    }

    @Test
    fun formatGivesNullOutsideValidYears() {
        assertNull(PanelClock.format(millis("2100-06-01T12:00:00Z")))
        assertNull(PanelClock.format(millis("2024-06-01T12:00:00Z")))
    }

    @Test
    fun parseRoundTripsFormat() {
        val t = millis("2026-07-15T12:00:00Z")
        assertEquals(t, PanelClock.parse("2026-07-15T14:00:00"))
        assertNull(PanelClock.parse("2026-13-15T14:00:00"))
        assertNull(PanelClock.parse("x"))
    }

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
        assertThrows(ParseException::class.java) { PanelClock.parseTimeReply(listOf("TIME 2026-11-19T10:00:00 valid=2 osf=0")) }
        assertThrows(ParseException::class.java) { PanelClock.parseTimeReply(emptyList()) }
    }

    @Test
    fun offsetSignIsPositiveWhenPanelIsAhead() {
        assertEquals(90L, PanelClock.offsetSeconds(100_000L + 90_000L, 100_000L))
        assertEquals(-90L, PanelClock.offsetSeconds(100_000L, 100_000L + 90_000L))
    }

    @Test
    fun timeSetNeedsBleConsoleLevelTwo() {
        assertFalse(PanelClock.isTimeSetAllowed(1))
        assertTrue(PanelClock.isTimeSetAllowed(2))
    }

    @Test
    fun formatUsesAsciiDigitsInAnyLocale() {
        val old = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"))
            assertEquals("2026-01-15T13:00:00", PanelClock.format(millis("2026-01-15T12:00:00Z")))
        } finally {
            java.util.Locale.setDefault(old)
        }
    }

    @Test
    fun timeLineWithExtraTokenThrows() {
        assertThrows(ParseException::class.java) {
            PanelClock.parseTimeReply(listOf("TIME 2026-11-19T10:00:00 valid=1 osf=0 x"))
        }
    }
}
