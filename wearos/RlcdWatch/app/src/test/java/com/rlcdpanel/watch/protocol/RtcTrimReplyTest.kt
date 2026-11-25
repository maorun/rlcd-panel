package com.rlcdpanel.watch.protocol

import com.rlcdpanel.watch.SensorHealthText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtcTrimReplyTest {
    @Test
    fun okLineGivesOldAndNewValue() {
        assertEquals(RtcTrimReply.Result(2, -1), RtcTrimReply.parse("OK RTC TRIM 2 -1"))
        assertEquals(RtcTrimReply.Result(-64, 63), RtcTrimReply.parse("OK RTC TRIM -64 63"))
    }

    @Test
    fun badLineGivesNoValue() {
        assertNull(RtcTrimReply.parse("OK RTC TRIM 2"))
        assertNull(RtcTrimReply.parse("OK RTC TRIM 2 x"))
        assertNull(RtcTrimReply.parse("OK RTC TRIM 2 -1 5"))
        assertNull(RtcTrimReply.parse("OK RTC TRIM 64 0"))
        assertNull(RtcTrimReply.parse("OK RTC TRIM 0 -65"))
        assertNull(RtcTrimReply.parse("OK MUTE 1"))
        assertNull(RtcTrimReply.parse("ERR NO_DRIFT"))
        assertNull(RtcTrimReply.parse(""))
    }

    @Test
    fun errorTexts() {
        assertEquals("Kein Drift-Wert von 24 h oder mehr", RtcTrimReply.text("ERR NO_DRIFT"))
        assertEquals("Set ble_console=2 to apply", RtcTrimReply.text("ERR READ_ONLY"))
        assertEquals("Panel meldet: ERR WRITE_FAILED", RtcTrimReply.text("ERR WRITE_FAILED"))
        assertEquals("RTC getrimmt: 2 → -1", RtcTrimReply.text("OK RTC TRIM 2 -1"))
    }

    @Test
    fun textIgnoresSurroundingSpaces() {
        assertEquals("Kein Drift-Wert von 24 h oder mehr", RtcTrimReply.text(" ERR NO_DRIFT "))
    }

    @Test
    fun trimResultStates() {
        val fallback = { _: ConsoleReply -> "Zeitüberschreitung" }
        val ok = SensorHealthText.trimResult(ConsoleReply.Status("OK RTC TRIM 2 -1"), fallback)
        assertEquals("RTC getrimmt: 2 → -1", ok.message)
        assertEquals(false, ok.isError)
        val err = SensorHealthText.trimResult(ConsoleReply.Status("ERR NO_DRIFT"), fallback)
        assertEquals("Kein Drift-Wert von 24 h oder mehr", err.message)
        assertEquals(true, err.isError)
        val other = SensorHealthText.trimResult(ConsoleReply.Corrupt(ReplyError.Timeout), fallback)
        assertEquals("Zeitüberschreitung", other.message)
        assertEquals(true, other.isError)
    }

    @Test
    fun trimChipNeedsWritableConsoleAndDrift() {
        assertEquals(true, SensorHealthText.canTrim(RtcState(0, 3), true))
        assertEquals(false, SensorHealthText.canTrim(RtcState(0, 2), true))
        assertEquals(false, SensorHealthText.canTrim(RtcState(0, 5), false))
        assertEquals(false, SensorHealthText.canTrim(null, true))
    }
}
