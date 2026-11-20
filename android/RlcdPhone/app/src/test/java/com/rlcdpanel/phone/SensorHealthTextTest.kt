package com.rlcdpanel.phone

import com.rlcdpanel.phone.protocol.ConsoleReply
import com.rlcdpanel.phone.protocol.Fixtures
import com.rlcdpanel.phone.protocol.MemoryState
import com.rlcdpanel.phone.protocol.ReplyAssembler
import com.rlcdpanel.phone.protocol.ResetState
import com.rlcdpanel.phone.protocol.SensorHealth
import com.rlcdpanel.phone.protocol.Shtc3State
import com.rlcdpanel.phone.protocol.StatusReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorHealthTextTest {

    private fun fixtureReply(): ConsoleReply =
        ReplyAssembler().append(Fixtures.file("status_reply_sample.txt").readBytes())
            ?: throw AssertionError("fixture gave no reply")

    private fun fixtureLines(): List<String> = (fixtureReply() as ConsoleReply.Frame).lines

    private fun row(moldHours: Int?) = SensorHealth(
        1, "A", -60.0, 80.0, 10.0, "OK", "WOIO", null, SensorHealth.Verdict.GOOD, moldHours
    )

    @Test
    fun moldTextAndWarning() {
        assertEquals("Schimmel --", SensorHealthText.mold(row(null)))
        assertEquals("Schimmel 0 h", SensorHealthText.mold(row(0)))
        assertEquals("⚠ Schimmel 3 h", SensorHealthText.mold(row(3)))
        assertFalse(SensorHealthText.isMold(row(null)))
        assertFalse(SensorHealthText.isMold(row(0)))
        assertTrue(SensorHealthText.isMold(row(3)))
    }

    @Test
    fun panelDiagnosticsTexts() {
        val status = StatusReply.parse(fixtureLines())
        val reset = status.reset!!
        val shtc3 = status.shtc3!!
        val memory = status.memory!!

        assertEquals("Reset POWERON, abnormal 0", SensorHealthText.reset(reset))
        assertEquals("SHTC3 nack 0, short 0, crc 0, run 0, retried 0", SensorHealthText.shtc3(shtc3))
        assertEquals("Heap frei 212345 B, min 150000 B, größter Block 110592 B, PSRAM 8300000 B", SensorHealthText.memory(memory))

        assertEquals("Reset PANIC, abnormal --", SensorHealthText.reset(ResetState("PANIC", null)))
        assertEquals("Reset --", SensorHealthText.reset(null))
        assertEquals("SHTC3 --", SensorHealthText.shtc3(null))
        assertEquals("Heap --", SensorHealthText.memory(null))
    }
}
