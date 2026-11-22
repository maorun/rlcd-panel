package com.rlcdpanel.watch.protocol

import com.rlcdpanel.watch.SensorHealthText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ChimeCommandTest {
    private fun fallback(reply: ConsoleReply): String = "Panel meldet: ${(reply as ConsoleReply.Status).line}"

    @Test
    fun muteLines() {
        assertEquals("MUTE 1", ChimeCommand.mute(true))
        assertEquals("MUTE 0", ChimeCommand.mute(false))
    }

    @Test
    fun beepLinesForBits0To7() {
        for (bit in 0..7) assertEquals("BEEP $bit", ChimeCommand.beep(bit))
    }

    @Test
    fun beepRejectsOutOfRangeBits() {
        for (bit in listOf(8, -1)) {
            try {
                ChimeCommand.beep(bit)
                fail("expected IllegalArgumentException for $bit")
            } catch (e: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun alertNames() {
        assertEquals("Freezer", ChimeCommand.alertName(0))
        assertEquals("Charge full", ChimeCommand.alertName(3))
        assertEquals("Sensor battery", ChimeCommand.alertName(6))
        assertEquals("Heat", ChimeCommand.alertName(7))
    }

    @Test
    fun timeoutFallbackText() {
        val timeout = ConsoleReply.Corrupt(ReplyError.Timeout)
        assertEquals("Panel not in range", ChimeCommand.failureText(timeout) { SensorHealthText.muteFallback(it) })
    }

    @Test
    fun otherFailuresKeepPanelText() {
        assertEquals("Panel meldet: ERR BAD_ARG", SensorHealthText.muteFallback(ConsoleReply.Status("ERR BAD_ARG")))
    }

    @Test
    fun replyTexts() {
        assertNull(ChimeCommand.failureText(ConsoleReply.Status("OK BEEP 7 420"), ::fallback))
        assertNull(ChimeCommand.failureText(ConsoleReply.Status("OK MUTE 1"), ::fallback))
        assertEquals("Set ble_console=2 to apply", ChimeCommand.failureText(ConsoleReply.Status("ERR READ_ONLY"), ::fallback))
        assertEquals("Panel ist stumm, in den Ruhezeiten oder Lautstärke 0", ChimeCommand.failureText(ConsoleReply.Status("ERR MUTED"), ::fallback))
        assertEquals("Wiedergabe fehlgeschlagen", ChimeCommand.failureText(ConsoleReply.Status("ERR PLAY_FAILED"), ::fallback))
        assertEquals("Heat: gespielt (420 ms)", ChimeCommand.okText("OK BEEP 7 420"))
        assertEquals("OK BEEP 7 x1", ChimeCommand.okText("OK BEEP 7 x1"))
        assertEquals("OK BEEP 9 420", ChimeCommand.okText("OK BEEP 9 420"))
        assertEquals("Panel meldet: ERR BAD_ARG", ChimeCommand.failureText(ConsoleReply.Status("ERR BAD_ARG"), ::fallback))
        assertEquals("Panel meldet: OK STATUS", ChimeCommand.failureText(ConsoleReply.Status("OK STATUS"), ::fallback))
    }

    @Test
    fun identifyCommandAndTexts() {
        assertEquals("IDENTIFY", ChimeCommand.IDENTIFY)
        assertEquals("Panel zeigt RLCD 3F2A", ChimeCommand.identifyText("OK IDENTIFY 3F2A"))
        assertEquals("Panel zeigt RLCD 3F2A", ChimeCommand.identifyText("OK IDENTIFY 3f2a"))
        assertNull(ChimeCommand.identifyText("OK IDENTIFY 3F"))
        assertNull(ChimeCommand.identifyText("OK IDENTIFY 3F2AB"))
        assertNull(ChimeCommand.identifyText("OK IDENTIFY 3G2A"))
        assertNull(ChimeCommand.identifyText("ERR BAD_ARG"))
        assertNull(ChimeCommand.failureText(ConsoleReply.Status("OK IDENTIFY 3F2A")) { "x" })
    }
}
