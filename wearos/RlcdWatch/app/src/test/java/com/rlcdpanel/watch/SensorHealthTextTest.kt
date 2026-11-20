package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.ConsoleReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorHealthTextTest {
    @Test
    fun sdFreeText() {
        assertEquals("SD frei 14820 MB", SensorHealthText.sdFree(14820))
        assertEquals("SD frei --", SensorHealthText.sdFree(null))
    }

    @Test
    fun sdLowLimit() {
        assertTrue(SensorHealthText.isSdLow(99))
        assertFalse(SensorHealthText.isSdLow(100))
        assertFalse(SensorHealthText.isSdLow(null))
    }

    @Test
    fun chimeSelectorWraps() {
        assertEquals(1, SensorHealthText.nextChimeBit(0))
        assertEquals(0, SensorHealthText.nextChimeBit(7))
    }

    @Test
    fun chimeResultTexts() {
        val busy = ChimeTestState(isTesting = true, bit = 7)
        val ok = SensorHealthText.chimeResult(busy, ConsoleReply.Status("OK BEEP 7 420"), null)
        assertEquals(ChimeTestState(isTesting = false, bit = 7, message = "Heat: gespielt (420 ms)", isError = false), ok)
        val err = SensorHealthText.chimeResult(busy, ConsoleReply.Status("ERR MUTED"), "Panel ist stumm")
        assertTrue(err.isError)
        assertFalse(err.isTesting)
        assertEquals("Panel ist stumm", err.message)
        assertEquals("Antwort nicht lesbar", SensorHealthText.chimeResult(busy, ConsoleReply.Status("ERR X"), "").message)
        val frame = SensorHealthText.chimeResult(busy, ConsoleReply.Frame(emptyList()), null)
        assertTrue(frame.isError)
        assertEquals("Antwort nicht lesbar", frame.message)
    }
}
