package com.rlcdpanel.phone

import com.rlcdpanel.phone.protocol.AwakeState
import com.rlcdpanel.phone.protocol.ConsoleReply
import com.rlcdpanel.phone.protocol.Fixtures
import com.rlcdpanel.phone.protocol.LatchState
import com.rlcdpanel.phone.protocol.MemoryState
import com.rlcdpanel.phone.protocol.NoiseState
import com.rlcdpanel.phone.protocol.QuietHoursState
import com.rlcdpanel.phone.protocol.ReplyAssembler
import com.rlcdpanel.phone.protocol.ResetState
import com.rlcdpanel.phone.protocol.RtcState
import com.rlcdpanel.phone.protocol.RuntimeState
import com.rlcdpanel.phone.protocol.SensorHealth
import com.rlcdpanel.phone.protocol.Shtc3State
import com.rlcdpanel.phone.protocol.StatusReply
import com.rlcdpanel.phone.protocol.WakeState
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

    @Test
    fun stuckMark() {
        assertEquals("⚠ Sensor frozen", SensorHealthText.stuckMark(LatchState(0, 0, 0, 0, stuck = 0x001), 0))
        assertNull(SensorHealthText.stuckMark(LatchState(0, 0, 0, 0, stuck = 0x001), 1))
        assertNull(SensorHealthText.stuckMark(null, 0))
        assertNull(SensorHealthText.stuckMark(LatchState(0, 0, 0, 0), 0))
        assertNull(SensorHealthText.stuckMark(LatchState(0, 0, 0, 0, stuck = 0x7FF), 11))
    }

    @Test
    fun heatMark() {
        val latch = StatusReply.parse(fixtureLines()).latch
        assertEquals("⚠ Heat", SensorHealthText.heatMark(latch, 0))
        assertNull(SensorHealthText.heatMark(latch, 1))
        assertNull(SensorHealthText.heatMark(null, 0))
        assertNull(SensorHealthText.heatMark(LatchState(0, 0, 0, 0), 0))
        assertNull(SensorHealthText.heatMark(LatchState(0, 0, 0, 0, heat = 0x7FF), 11))
    }

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

    private fun heatRow(heatHours: Int?) = SensorHealth(
        1, "A", -60.0, 80.0, 10.0, "OK", "WOIO", null, SensorHealth.Verdict.GOOD, null, null, null, null, heatHours
    )

    @Test
    fun heatTextAndWarning() {
        assertEquals("Hitze --", SensorHealthText.heat(heatRow(null)))
        assertEquals("Hitze 0 h", SensorHealthText.heat(heatRow(0)))
        assertEquals("⚠ Hitze 3 h", SensorHealthText.heat(heatRow(3)))
        assertFalse(SensorHealthText.isHeat(heatRow(null)))
        assertFalse(SensorHealthText.isHeat(heatRow(0)))
        assertTrue(SensorHealthText.isHeat(heatRow(3)))
    }

    private fun dryRow(dryHours: Int?) = SensorHealth(
        1, "A", -60.0, 80.0, 10.0, "OK", "WOIO", null, SensorHealth.Verdict.GOOD, null, null, null, dryHours
    )

    @Test
    fun dryTextAndWarning() {
        assertEquals("Trocken --", SensorHealthText.dry(dryRow(null)))
        assertEquals("Trocken 0 h", SensorHealthText.dry(dryRow(0)))
        assertEquals("⚠ Trocken 3 h", SensorHealthText.dry(dryRow(3)))
        assertFalse(SensorHealthText.isDry(dryRow(null)))
        assertFalse(SensorHealthText.isDry(dryRow(0)))
        assertTrue(SensorHealthText.isDry(dryRow(3)))
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

    @Test
    fun runtimeAndAwakeTexts() {
        val status = StatusReply.parse(fixtureLines())
        assertEquals("Restlaufzeit 412 h", SensorHealthText.runtimeHours(status.runtime))
        assertEquals("Ladezyklen 3.5", SensorHealthText.cycles(status.runtime))
        assertEquals("Wachzeit Ø 2350 ms", SensorHealthText.awake(status.awake))
        assertEquals("Restlaufzeit --", SensorHealthText.runtimeHours(RuntimeState(null, 0, null, 0)))
        assertEquals("Ladezyklen --", SensorHealthText.cycles(RuntimeState(null, 0, null, 0)))
        assertEquals("Wachzeit Ø --", SensorHealthText.awake(AwakeState(null, null, null, 0)))
        assertEquals("Restlaufzeit --", SensorHealthText.runtimeHours(null))
        assertEquals("Ladezyklen --", SensorHealthText.cycles(null))
        assertEquals("Wachzeit Ø --", SensorHealthText.awake(null))
    }

    @Test
    fun noiseText() {
        assertEquals("Lärm 48 dB (MODERATE), Spitze 71 dB um 19 Uhr", SensorHealthText.noise(StatusReply.parse(fixtureLines()).noise))
        assertEquals("Lärm 40 dB (QUIET)", SensorHealthText.noise(NoiseState(40, "QUIET", null, null, 0)))
        assertEquals("Lärm -- dB (QUIET)", SensorHealthText.noise(NoiseState(null, "QUIET", null, null, 0)))
        assertEquals("Lärm --", SensorHealthText.noise(null))
    }

    @Test
    fun rtcTextAndTrimButtonRule() {
        assertEquals("RTC Trim 0, Drift 5 ppm", SensorHealthText.rtc(StatusReply.parse(fixtureLines()).rtc))
        assertEquals("RTC Trim --, Drift -- ppm", SensorHealthText.rtc(RtcState(null, null)))
        assertEquals("RTC Trim --, Drift -- ppm", SensorHealthText.rtc(null))
        assertFalse(SensorHealthText.canTrim(RtcState(0, 2), true))
        assertTrue(SensorHealthText.canTrim(RtcState(0, 3), true))
        assertTrue(SensorHealthText.canTrim(RtcState(0, -3), true))
        assertFalse(SensorHealthText.canTrim(RtcState(0, -2), true))
        assertFalse(SensorHealthText.canTrim(RtcState(0, null), true))
        assertFalse(SensorHealthText.canTrim(null, true))
        assertFalse(SensorHealthText.canTrim(RtcState(0, 5), false))
    }

    @Test
    fun trimResultTexts() {
        assertEquals("RTC getrimmt: -8 → -9", SensorHealthText.trimResult("OK RTC TRIM -8 -9"))
        assertEquals("Kein Drift-Wert von 24 h oder mehr", SensorHealthText.trimResult("ERR NO_DRIFT"))
        assertEquals("Set ble_console=2 to apply", SensorHealthText.trimResult("ERR READ_ONLY"))
        assertEquals("Panel meldet: ERR WRITE_FAILED", SensorHealthText.trimResult("ERR WRITE_FAILED"))
    }

    @Test
    fun quietAndWakeTexts() {
        assertEquals("Ruhezeit 22–7 Uhr, aktiv", SensorHealthText.quiet(QuietHoursState(22, 7, true, 900)))
        assertEquals("Ruhezeit 22–7 Uhr", SensorHealthText.quiet(QuietHoursState(22, 7, null, 900)))
        assertEquals("Ruhezeit --", SensorHealthText.quiet(null))
        assertEquals("Nachtintervall aus", SensorHealthText.nightInterval(QuietHoursState(22, 7, false, 0)))
        assertEquals("Nachtintervall 900 s", SensorHealthText.nightInterval(QuietHoursState(22, 7, false, 900)))
        assertEquals("Nachtintervall --", SensorHealthText.nightInterval(null))
        assertEquals("Akku-Intervall 600 s", SensorHealthText.batteryInterval(WakeState("TIMER", 1, 600)))
        assertEquals("Akku-Intervall --", SensorHealthText.batteryInterval(null))
        assertEquals("Weckgrund TIMER", SensorHealthText.wakeCause(WakeState("TIMER", 1, 600)))
        assertEquals("Weckgrund --", SensorHealthText.wakeCause(null))
    }
}
