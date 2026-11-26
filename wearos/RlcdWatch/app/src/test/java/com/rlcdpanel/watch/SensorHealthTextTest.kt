package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.BleRejectState
import com.rlcdpanel.watch.protocol.ConsoleReply
import com.rlcdpanel.watch.protocol.Fixtures
import com.rlcdpanel.watch.protocol.LatchState
import com.rlcdpanel.watch.protocol.MemoryState
import com.rlcdpanel.watch.protocol.NoiseState
import com.rlcdpanel.watch.protocol.ReplyAssembler
import com.rlcdpanel.watch.protocol.ResetState
import com.rlcdpanel.watch.protocol.RuntimeState
import com.rlcdpanel.watch.protocol.Shtc3State
import com.rlcdpanel.watch.protocol.ReplyError
import com.rlcdpanel.watch.protocol.StatusReply
import com.rlcdpanel.watch.protocol.StatusSensor
import com.rlcdpanel.watch.protocol.VersionReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun weakSignalMark() {
        val latch = StatusReply.parse(fixtureLines()).latch
        fun row(index: Int) = StatusSensor(index, "A", 20.0, 50.0, 80.0, -60.0, 10.0, "OK", "WOIO", null)
        assertEquals("${SensorHealthText.WARNING} Weak signal", SensorHealthText.weakSignal(latch, row(2)))
        assertEquals("${SensorHealthText.WARNING} Weak signal", SensorHealthText.weakSignal(latch, row(10)))
        assertNull(SensorHealthText.weakSignal(latch, row(1)))
        assertNull(SensorHealthText.weakSignal(latch, row(0)))
        assertNull(SensorHealthText.weakSignal(null, row(2)))
        assertNull(SensorHealthText.weakSignal(LatchState(0, 0, 0, 0), row(2)))
    }

    @Test
    fun driftMark() {
        val latch = StatusReply.parse(fixtureLines()).latch
        fun row(index: Int) = StatusSensor(index, "A", 20.0, 50.0, 80.0, -60.0, 10.0, "OK", "WOIO", null)
        assertEquals("${SensorHealthText.WARNING} Sensor drift", SensorHealthText.driftMark(latch, row(1)))
        assertNull(SensorHealthText.driftMark(latch, row(0)))
        assertNull(SensorHealthText.driftMark(latch, row(2)))
        assertNull(SensorHealthText.driftMark(null, row(1)))
        assertNull(SensorHealthText.driftMark(LatchState(0, 0, 0, 0), row(1)))
        assertNull(SensorHealthText.driftMark(LatchState(0, 0, 0, 0, drift = 0x7FF), row(11)))
    }

    @Test
    fun badgeText() {
        val latch = StatusReply.parse(fixtureLines()).latch
        fun row(index: Int) = StatusSensor(index, "A", 20.0, 50.0, 80.0, -60.0, 10.0, "OK", "WOIO", null)
        assertEquals("V · Wand · Fenster", SensorHealthText.badges(latch, row(1)))
        assertEquals("STALE", SensorHealthText.badges(latch, row(3)))
        assertEquals("BAT LOW", SensorHealthText.badges(latch, row(4)))
        assertNull(SensorHealthText.badges(latch, row(2)))
        assertEquals("Fenster", SensorHealthText.badges(latch, row(10)))
        assertNull(SensorHealthText.badges(null, row(1)))
        assertEquals("V · D · BAT LOW · STALE", SensorHealthText.badges(LatchState(2, 2, 1, 1), row(1)))
        assertEquals("V · D · Wand · Fenster · BAT LOW · STALE", SensorHealthText.badges(LatchState(2, 2, 1, 1, 2, 2), row(1)))
    }

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
    fun dryTextAndWarning() {
        fun dry(hours: Int?) = StatusSensor(1, "A", 20.0, 50.0, 80.0, -60.0, 10.0, "OK", "WOIO", dryHours = hours)
        assertEquals("Trocken 0 h", SensorHealthText.dry(dry(0)))
        assertEquals("⚠ Trocken 3 h", SensorHealthText.dry(dry(3)))
        assertEquals("Trocken --", SensorHealthText.dry(dry(null)))
        assertFalse(SensorHealthText.isDry(dry(0)))
        assertTrue(SensorHealthText.isDry(dry(3)))
        assertFalse(SensorHealthText.isDry(dry(null)))
    }

    @Test
    fun heatTextAndWarning() {
        fun heat(hours: Int?) = StatusSensor(1, "A", 20.0, 50.0, 80.0, -60.0, 10.0, "OK", "WOIO", heatHours = hours)
        assertEquals("Hitze 0 h", SensorHealthText.heat(heat(0)))
        assertEquals("⚠ Hitze 3 h", SensorHealthText.heat(heat(3)))
        assertEquals("Hitze --", SensorHealthText.heat(heat(null)))
        assertFalse(SensorHealthText.isHeat(heat(0)))
        assertTrue(SensorHealthText.isHeat(heat(3)))
        assertFalse(SensorHealthText.isHeat(heat(null)))
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
    fun runtimeTexts() {
        val r = RuntimeState(412, 18, 3.5, 1200)
        assertEquals("Runtime 412 h", SensorHealthText.runtimeHours(r))
        assertEquals("Cycles 3.5", SensorHealthText.cycles(r))
        val dashes = RuntimeState(null, 0, null, 0)
        assertEquals("Runtime --", SensorHealthText.runtimeHours(dashes))
        assertEquals("Cycles --", SensorHealthText.cycles(dashes))
        assertNull(SensorHealthText.runtimeHours(null))
        assertNull(SensorHealthText.cycles(null))
    }

    @Test
    fun runtimeTextsFromFixtureAndMissingLine() {
        val lines = fixtureLines()
        val full = StatusReply.parse(lines)
        assertEquals("Runtime 412 h", SensorHealthText.runtimeHours(full.runtime))
        val missing = StatusReply.parse(lines.filter { !it.startsWith("RUNTIME ") && !it.startsWith("AWAKE ") })
        assertNull(SensorHealthText.runtimeHours(missing.runtime))
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
    fun noiseText() {
        assertEquals("Lärm 48 dB MODERATE", SensorHealthText.noise(StatusReply.parse(fixtureLines()).noise))
        assertEquals("Lärm -- dB QUIET", SensorHealthText.noise(NoiseState(null, "QUIET", null, null, 0)))
        assertEquals("Lärm --", SensorHealthText.noise(null))
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

    @Test
    fun identifyResultTexts() {
        val busy = ChimeTestState(isTesting = true, bit = 2)
        val reply = ConsoleReply.Status("OK IDENTIFY 3F2A")
        assertEquals(ChimeTestState(isTesting = false, bit = 2, message = "Panel zeigt RLCD 3F2A", isError = false),
            SensorHealthText.identifyResult(busy, reply, null))
        assertEquals(true, SensorHealthText.identifyResult(busy, ConsoleReply.Status("OK IDENTIFY 3F"), null).isError)
        assertEquals("Panel meldet", SensorHealthText.identifyResult(busy, ConsoleReply.Status("ERR X"), "Panel meldet").message)
    }

    @Test
    fun bleRejectText() {
        assertEquals("BLE abgelehnt 7, Sprünge 2", SensorHealthText.bleRejects(BleRejectState(7, null, 0, 2)))
        assertEquals("BLE abgelehnt 7, Sprünge --", SensorHealthText.bleRejects(BleRejectState(7, null, null, null)))
        assertEquals(null, SensorHealthText.bleRejects(null))
        assertTrue(BleRejectState(7, null, 3, 2).isWarning)
        assertFalse(BleRejectState(7, null, 0, 2).isWarning)
        assertFalse(BleRejectState(7, null, null, null).isWarning)
    }
}
