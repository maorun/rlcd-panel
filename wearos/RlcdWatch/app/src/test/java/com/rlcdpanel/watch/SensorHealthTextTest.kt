package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.ConsoleReply
import com.rlcdpanel.watch.protocol.Fixtures
import com.rlcdpanel.watch.protocol.MemoryState
import com.rlcdpanel.watch.protocol.ReplyAssembler
import com.rlcdpanel.watch.protocol.ResetState
import com.rlcdpanel.watch.protocol.Shtc3State
import com.rlcdpanel.watch.protocol.ReplyError
import com.rlcdpanel.watch.protocol.StatusReply
import com.rlcdpanel.watch.protocol.StatusSensor
import com.rlcdpanel.watch.protocol.VersionReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorHealthTextTest {
    private fun fixtureReply(): ConsoleReply =
        ReplyAssembler().append(Fixtures.file("status_reply_sample.txt").readBytes())
            ?: throw AssertionError("fixture gave no reply")

    private fun fixtureLines(): List<String> = (fixtureReply() as ConsoleReply.Frame).lines
    private fun sensor(moldHours: Int?) =
        StatusSensor(1, "A", 20.0, 50.0, 80.0, -60.0, 10.0, "OK", "WOIO", moldHours)

    @Test
    fun moldText() {
        assertEquals("Schimmel 0 h", SensorHealthText.mold(sensor(0)))
        assertEquals("⚠ Schimmel 5 h", SensorHealthText.mold(sensor(5)))
        assertEquals("Schimmel --", SensorHealthText.mold(sensor(null)))
        assertFalse(SensorHealthText.isMold(sensor(0)))
        assertTrue(SensorHealthText.isMold(sensor(5)))
        assertFalse(SensorHealthText.isMold(sensor(null)))
    }

    @Test
    fun firmwareLineAndTimeoutText() {
        val v = VersionReply.parse(listOf("VERSION fw=1.0.0 git=abc1234 core=3.3.12 state=68 built=unknown"))
        assertEquals("FW 1.0.0 (abc1234)", SensorHealthText.firmware(v))
        assertEquals("Panel not in range", SensorHealthText.versionFailure(ConsoleReply.Corrupt(ReplyError.Timeout)))
    }

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

    @Test
    fun panelDiagnosticsTextsAndWarnings() {
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

        assertFalse(reset.isAbnormal)
        assertFalse(shtc3.isAlert)
        assertFalse(memory.isLow)

        val abnormalReset = ResetState("TASK_WDT", 2)
        val alertShtc3 = Shtc3State(0, 0, 0, 3, 0)
        val lowMemory = MemoryState(212345L, 150000L, 16383L, 8300000L)
        assertTrue(abnormalReset.isAbnormal)
        assertTrue(alertShtc3.isAlert)
        assertTrue(lowMemory.isLow)
    }
}
