package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StatusReplyTest {

    private fun fixtureReply(): ConsoleReply =
        ReplyAssembler().append(Fixtures.file("status_reply_sample.txt").readBytes())
            ?: throw AssertionError("fixture gave no reply")

    private fun fixtureLines(): List<String> = (fixtureReply() as ConsoleReply.Frame).lines

    @Test
    fun fixtureHasValidCrc() {
        assertTrue(fixtureReply() is ConsoleReply.Frame)
    }

    @Test
    fun parsesSensorLinesFromFixture() {
        val status = StatusReply.parse(fixtureLines())
        assertEquals(6, status.sensors.size)
        val s1 = status.sensors[1]
        assertEquals(1, s1.index)
        assertEquals("Wohnzimmer", s1.name)
        assertEquals(22.1, s1.temperature!!, 0.001)
        assertEquals(87.0, s1.battery!!, 0.001)
        assertEquals(-61.0, s1.rssi!!, 0.001)
        assertEquals("OK", s1.state)
    }

    @Test
    fun dashDashGivesNullFields() {
        val s2 = StatusReply.parse(fixtureLines()).sensors[2]
        assertEquals("EMPTY", s2.state)
        assertNull(s2.temperature)
        assertNull(s2.battery)
        assertNull(s2.rssi)
        assertNull(s2.ageSeconds)
    }

    @Test
    fun parsesReceptionRates() {
        val rates = StatusReply.parse(fixtureLines()).reception!!
        assertEquals(24, rates[0]!!.received)
        assertEquals(100, rates[0]!!.percent)
        assertNull(rates[1])
        assertEquals(0, rates[2]!!.percent)
        assertTrue(rates[2]!!.isWeak)
        assertEquals(41, rates[3]!!.percent)
        assertTrue(rates[3]!!.isWeak)
        assertEquals(45, rates[4]!!.percent)
        assertTrue(rates[4]!!.isWeak)
    }

    @Test
    fun missingReceptionLineGivesNull() {
        val lines = fixtureLines().filterNot { it.startsWith("RECEPTION") }
        assertNull(StatusReply.parse(lines).reception)
    }

    @Test
    fun badReceptionLineThrows() {
        val lines = fixtureLines().map { if (it.startsWith("RECEPTION")) "RECEPTION s1=x/24 s2=-- s3=-- s4=-- s5=--" else it }
        try {
            StatusReply.parse(lines)
            fail("expected ParseException")
        } catch (e: ParseException) {
        }
    }

    @Test
    fun missingSensorLineThrows() {
        try {
            StatusReply.parse(listOf("POWER src=USB v=4.0 pct=1 alerts=00 muted=0"))
            fail("expected ParseException")
        } catch (e: ParseException) {
        }
    }

    @Test
    fun usbPowerFlag() {
        assertTrue(StatusReply.parse(fixtureLines()).isUsbPower)
    }

    @Test
    fun sdFreeMbField() {
        val status = StatusReply.parse(fixtureLines())
        assertEquals("OK", status.sdStatus)
        assertEquals(14820, status.sdFreeMb)
        val unknown = fixtureLines().map { if (it.startsWith("SD ")) "SD status=OK age=30 free_mb=--" else it }
        assertNull(StatusReply.parse(unknown).sdFreeMb)
        val old = fixtureLines().map { if (it.startsWith("SD ")) "SD status=OK age=30" else it }
        assertNull(StatusReply.parse(old).sdFreeMb)
        val bad = fixtureLines().map { if (it.startsWith("SD ")) "SD status=OK age=30 free_mb=x" else it }
        try {
            StatusReply.parse(bad)
            fail("expected ParseException")
        } catch (e: ParseException) {
        }
    }

    @Test
    fun ntpLine() {
        val ntp = StatusReply.parse(fixtureLines()).ntp!!
        assertTrue(ntp.ok)
        assertEquals(0, ntp.failures)
        assertEquals(3600L, ntp.ageSeconds)
        assertEquals(82800L, ntp.nextSeconds)
        assertTrue(!ntp.isWarning)
        val failing = fixtureLines().map { if (it.startsWith("NTP")) "NTP ok=0 failures=3 age_s=100 next_s=--" else it }
        val warn = StatusReply.parse(failing).ntp!!
        assertTrue(warn.isWarning)
        assertNull(warn.nextSeconds)
        val none = fixtureLines().map { if (it.startsWith("NTP")) "NTP ok=-- failures=0 age_s=-- next_s=--" else it }
        assertNull(StatusReply.parse(none).ntp)
        assertNull(StatusReply.parse(fixtureLines().filterNot { it.startsWith("NTP") }).ntp)
        val bad = fixtureLines().map { if (it.startsWith("NTP")) "NTP ok=0 failures=x age_s=1 next_s=1" else it }
        try {
            StatusReply.parse(bad)
            fail("expected ParseException")
        } catch (e: ParseException) {
        }
    }

    @Test
    fun parsesMutedFlag() {
        assertEquals(false, StatusReply.parse(fixtureLines()).muted)
        val on = fixtureLines().map { if (it.startsWith("POWER")) it.replace("muted=0", "muted=1") else it }
        assertEquals(true, StatusReply.parse(on).muted)
        val missing = fixtureLines().map { if (it.startsWith("POWER")) it.replace(" muted=0", "") else it }
        assertNull(StatusReply.parse(missing).muted)
    }

    @Test
    fun parsesAlertAndAckedMasks() {
        val base = StatusReply.parse(fixtureLines())
        assertEquals(0, base.alertMask)
        assertEquals(0, base.ackedMask)
        val set = fixtureLines().map { if (it.startsWith("POWER")) it.replace("alerts=00", "alerts=32").replace("acked=00", "acked=02") else it }
        val status = StatusReply.parse(set)
        assertEquals(0x32, status.alertMask)
        assertEquals(0x02, status.ackedMask)
        val bad = fixtureLines().map { if (it.startsWith("POWER")) it.replace("alerts=00", "alerts=zz").replace(" acked=00", "") else it }
        assertNull(StatusReply.parse(bad).alertMask)
        assertNull(StatusReply.parse(bad).ackedMask)
    }

    @Test
    fun resetShtc3MemoryFromFixture() {
        val status = StatusReply.parse(fixtureLines())
        assertEquals("POWERON", status.reset!!.reason)
        assertEquals(0, status.reset!!.abnormalCount)
        assertEquals(false, status.reset!!.isAbnormal)
        assertEquals(0, status.shtc3!!.run)
        assertEquals(false, status.shtc3!!.isAlert)
        assertEquals(110592L, status.memory!!.largest)
        assertEquals(false, status.memory!!.isLow)
    }

    private fun replace(prefix: String, line: String) =
        StatusReply.parse(fixtureLines().map { if (it.startsWith(prefix)) line else it })

    @Test
    fun abnormalResetAndAlertStates() {
        val wdt = replace("RESET ", "RESET reason=TASK_WDT abnormal=2").reset!!
        assertTrue(wdt.isAbnormal)
        assertEquals(2, wdt.abnormalCount)
        val panic = replace("RESET ", "RESET reason=PANIC abnormal=--").reset!!
        assertEquals("PANIC", panic.reason)
        assertNull(panic.abnormalCount)
        assertTrue(replace("SHTC3 ", "SHTC3 nack=1 short=0 crc=0 run=3 retried=2").shtc3!!.isAlert)
        assertTrue(replace("MEMORY ", "MEMORY free=1 min=1 largest=16383 psram=0").memory!!.isLow)
    }

    @Test
    fun missingLinesGiveNull() {
        val lines = fixtureLines().filter { !it.startsWith("RESET ") && !it.startsWith("SHTC3 ") && !it.startsWith("MEMORY ") }
        val status = StatusReply.parse(lines)
        assertNull(status.reset)
        assertNull(status.shtc3)
        assertNull(status.memory)
    }

    @Test
    fun badDiagnosticsNumberThrows() {
        for ((prefix, line) in listOf(
            "SHTC3 " to "SHTC3 nack=x short=0 crc=0 run=0 retried=0",
            "RESET " to "RESET reason=PANIC abnormal=x",
            "MEMORY " to "MEMORY free=1 min=1 largest=x psram=0"
        )) {
            try {
                replace(prefix, line)
                fail("expected ParseException for $line")
            } catch (e: ParseException) {
            }
        }
    }

    @Test
    fun noiseFromFixtureAndMissingValues() {
        assertEquals(NoiseState(48, "MODERATE", 71, 19, 12), StatusReply.parse(fixtureLines()).noise)
        assertEquals(NoiseState(null, "QUIET", 71, 19, 0), replace("NOISE ", "NOISE db=-- band=QUIET peak_db=71 peak_hour=19 events_sum=0").noise)
        assertEquals(NoiseState(48, "LOUD", null, null, 3), replace("NOISE ", "NOISE db=48 band=LOUD peak_db=-- peak_hour=-- events_sum=3").noise)
        assertNull(StatusReply.parse(fixtureLines().filter { !it.startsWith("NOISE ") }).noise)
    }

    @Test
    fun badNoiseLineThrows() {
        for (line in listOf(
            "NOISE db=48 band=LOUDER peak_db=71 peak_hour=19 events_sum=12",
            "NOISE db=141 band=LOUD peak_db=71 peak_hour=19 events_sum=12",
            "NOISE db=48 band=LOUD peak_db=141 peak_hour=19 events_sum=12",
            "NOISE db=48 band=LOUD peak_db=71 peak_hour=24 events_sum=12",
            "NOISE db=x band=LOUD peak_db=71 peak_hour=19 events_sum=12",
            "NOISE db=48 band=LOUD peak_db=71 peak_hour=19 events_sum=x"
        )) {
            try {
                replace("NOISE ", line)
                fail("expected ParseException for $line")
            } catch (e: ParseException) {
            }
        }
    }

    @Test
    fun trendsOfFixtureAndBadFields() {
        val sensors = StatusReply.parse(fixtureLines()).sensors
        assertEquals(SensorTrend.RISE, sensors[0].temperatureTrend)
        assertEquals(SensorTrend.FALL, sensors[0].humidityTrend)
        assertEquals(SensorTrend.STEADY, sensors[1].temperatureTrend)
        assertEquals(SensorTrend.STEADY, sensors[1].humidityTrend)
        assertNull(sensors[2].temperatureTrend)
        assertNull(sensors[2].humidityTrend)
        assertEquals(SensorTrend.FALL, sensors[4].temperatureTrend)
        assertEquals(SensorTrend.RISE, sensors[4].humidityTrend)
        val without = fixtureLines().map { it.replace(Regex(" (temp|rh)_trend=\\S+"), "") }
        assertNull(StatusReply.parse(without).sensors[0].temperatureTrend)
        assertNull(StatusReply.parse(without).sensors[0].humidityTrend)
        val bad = fixtureLines().map { if (it.startsWith("SENSOR 1 ")) it.replace("rh_trend=STEADY", "rh_trend=UP") else it }
        try {
            StatusReply.parse(bad)
            fail("expected ParseException")
        } catch (e: ParseException) {
        }
    }

    @Test
    fun moldHoursOfFixtureAndBadFields() {
        val sensors = StatusReply.parse(fixtureLines()).sensors
        assertEquals(24, sensors[0].moldHours)
        assertEquals(3, sensors[1].moldHours)
        assertNull(sensors[2].moldHours)
        assertNull(sensors[3].moldHours)
        assertEquals(5, sensors[4].moldHours)
        val without = fixtureLines().map { it.replace(Regex(" mold_h=\\S+"), "") }
        assertNull(StatusReply.parse(without).sensors[1].moldHours)
        val bad = fixtureLines().map { if (it.startsWith("SENSOR 1 ")) it.replace("mold_h=3", "mold_h=x") else it }
        try {
            StatusReply.parse(bad)
            fail("expected ParseException")
        } catch (e: ParseException) {
        }
    }

    @Test
    fun dryHoursOfFixtureAndBadFields() {
        val sensors = StatusReply.parse(fixtureLines()).sensors
        assertEquals(2, sensors[0].dryHours)
        assertNull(sensors[1].dryHours)
        val five = fixtureLines().map { if (it.startsWith("SENSOR 1 ")) it.replace("dry_h=--", "dry_h=5") else it }
        assertEquals(5, StatusReply.parse(five).sensors[1].dryHours)
        val without = fixtureLines().map { it.replace(Regex(" dry_h=\\S+"), "") }
        assertNull(StatusReply.parse(without).sensors[0].dryHours)
        for (value in listOf("x", "-1")) {
            val bad = fixtureLines().map { if (it.startsWith("SENSOR 1 ")) it.replace("dry_h=--", "dry_h=$value") else it }
            try {
                StatusReply.parse(bad)
                fail("expected ParseException")
            } catch (e: ParseException) {
            }
        }
    }

    @Test
    fun parsesRtcLine() {
        assertEquals(RtcState(0, 5), StatusReply.parse(fixtureLines()).rtc)
        assertEquals(RtcState(null, null), replace("RTC ", "RTC trim=-- drift_ppm=--").rtc)
        assertEquals(RtcState(-64, -12), replace("RTC ", "RTC trim=-64 drift_ppm=-12").rtc)
        assertEquals(RtcState(63, null), replace("RTC ", "RTC trim=63 drift_ppm=--").rtc)
        assertNull(StatusReply.parse(fixtureLines().filter { !it.startsWith("RTC ") }).rtc)
    }

    @Test
    fun badRtcLineThrows() {
        for (line in listOf(
            "RTC trim=64 drift_ppm=5", "RTC trim=-65 drift_ppm=5", "RTC trim=0 drift_ppm=x",
            "RTC trim=x drift_ppm=--", "RTC trim=+1 drift_ppm=--", "RTC trim=0", "RTC trim=0 drift", "RTC trim=- drift_ppm=--"
        )) {
            try {
                replace("RTC ", line)
                fail("expected ParseException for $line")
            } catch (e: ParseException) {
            }
        }
    }

    @Test
    fun parsesChimeLine() {
        assertEquals(ChimeReminder(2, 2, 3600, 3600, 3540), StatusReply.parse(fixtureLines()).chime)
        assertEquals(3540L, StatusReply.parse(fixtureLines()).chime?.snoozeSeconds)
        assertNull(replace("CHIME ", "CHIME repeat_h=2 bits=02 last_s=3600 next_s=3600 snooze_s=--").chime?.snoozeSeconds)
        assertNull(replace("CHIME ", "CHIME repeat_h=2 bits=02 last_s=3600 next_s=3600").chime?.snoozeSeconds)
        assertEquals(ChimeReminder(2, 2, 3600, null), replace("CHIME ", "CHIME repeat_h=2 bits=02 last_s=3600 next_s=--").chime)
        assertEquals(ChimeReminder(2, 2, null, 3600), replace("CHIME ", "CHIME repeat_h=2 bits=02 last_s=-- next_s=3600").chime)
        assertEquals(ChimeReminder(24, 255, null, null), replace("CHIME ", "CHIME repeat_h=24 bits=FF last_s=-- next_s=--").chime)
        assertNull(StatusReply.parse(fixtureLines().filter { !it.startsWith("CHIME ") }).chime)
    }

    @Test
    fun badChimeLineThrows() {
        for (line in listOf(
            "CHIME repeat_h=25 bits=02 last_s=1 next_s=1", "CHIME repeat_h=2 bits=2 last_s=1 next_s=1",
            "CHIME repeat_h=2 bits=0g last_s=1 next_s=1", "CHIME repeat_h=2 bits=002 last_s=1 next_s=1",
            "CHIME repeat_h=x bits=02 last_s=1 next_s=1", "CHIME repeat_h=2 bits=02 last_s=x next_s=1",
            "CHIME repeat_h=2 bits=02 last_s=1 next_s=1 snooze_s=x", "CHIME repeat_h=2 bits=02 last_s=1", "CHIME repeat_h=2 bits=02 last_s=1 next_s=-1"
        )) {
            try {
                replace("CHIME ", line)
                fail("expected ParseException for $line")
            } catch (e: ParseException) {
            }
        }
    }

    @Test
    fun parsesQuietLine() {
        assertEquals(QuietHoursState(22, 7, true, 900), StatusReply.parse(fixtureLines()).quiet)
        assertNull(replace("QUIET ", "QUIET start_h=22 end_h=7 active=-- night_interval_s=0").quiet?.active)
        assertEquals(false, replace("QUIET ", "QUIET start_h=22 end_h=7 active=0 night_interval_s=0").quiet?.active)
        assertNull(StatusReply.parse(fixtureLines().filter { !it.startsWith("QUIET ") }).quiet)
    }

    @Test
    fun badQuietLineThrows() {
        for (line in listOf(
            "QUIET start_h=24 end_h=7 active=1 night_interval_s=900", "QUIET start_h=x end_h=7 active=1 night_interval_s=900",
            "QUIET start_h=22 end_h=7 active=2 night_interval_s=900", "QUIET start_h=22 end_h=7 active=1 night_interval_s=x",
            "QUIET start_h=22 end_h=7 active=1"
        )) {
            try {
                replace("QUIET ", line)
                fail("expected ParseException for $line")
            } catch (e: ParseException) {
            }
        }
    }

    @Test
    fun parsesWakeLine() {
        assertEquals(WakeState("TIMER", 1234, 600), StatusReply.parse(fixtureLines()).wake)
        assertEquals("KEY", replace("WAKE ", "WAKE cause=KEY boots=1 battery_interval_s=60").wake?.cause)
        assertEquals(4294967295L, replace("WAKE ", "WAKE cause=KEY boots=4294967295 battery_interval_s=60").wake?.boots)
        assertNull(StatusReply.parse(fixtureLines().filter { !it.startsWith("WAKE ") }).wake)
    }

    @Test
    fun badWakeLineThrows() {
        for (line in listOf(
            "WAKE cause=TIMER boots=x battery_interval_s=600", "WAKE cause=SLEEP boots=1 battery_interval_s=600",
            "WAKE cause=TIMER boots=1 battery_interval_s=x", "WAKE boots=1 battery_interval_s=600"
        )) {
            try {
                replace("WAKE ", line)
                fail("expected ParseException for $line")
            } catch (e: ParseException) {
            }
        }
    }

    @Test
    fun parsesRuntimeAndAwakeLines() {
        val status = StatusReply.parse(fixtureLines())
        assertEquals(RuntimeState(412, 18, 3.5, 1200), status.runtime)
        assertEquals(AwakeState(2100, 2350, 4100, 24), status.awake)
        val dashes = StatusReply.parse(fixtureLines().map {
            when {
                it.startsWith("RUNTIME ") -> "RUNTIME h=-- samples=0 cycles=-- scan_saved_ms=0"
                it.startsWith("AWAKE ") -> "AWAKE last_ms=-- avg_ms=-- max_ms=-- samples=0"
                else -> it
            }
        })
        assertEquals(RuntimeState(null, 0, null, 0), dashes.runtime)
        assertEquals(AwakeState(null, null, null, 0), dashes.awake)
        val missing = StatusReply.parse(fixtureLines().filter { !it.startsWith("RUNTIME ") && !it.startsWith("AWAKE ") })
        assertNull(missing.runtime)
        assertNull(missing.awake)
    }

    @Test
    fun badRuntimeAndAwakeLinesThrow() {
        for ((prefix, line) in listOf(
            "RUNTIME " to "RUNTIME h=x samples=1 cycles=1.0 scan_saved_ms=0",
            "RUNTIME " to "RUNTIME h=1 samples=x cycles=1.0 scan_saved_ms=0",
            "RUNTIME " to "RUNTIME h=1 samples=1 cycles=x scan_saved_ms=0",
            "RUNTIME " to "RUNTIME h=1 samples=1 cycles=1.0 scan_saved_ms=-1",
            "RUNTIME " to "RUNTIME h=1 samples=1 cycles=1.0",
            "AWAKE " to "AWAKE last_ms=x avg_ms=1 max_ms=1 samples=1",
            "AWAKE " to "AWAKE last_ms=1 avg_ms=1 max_ms=1",
            "AWAKE " to "AWAKE last_ms=1 avg_ms=-1 max_ms=1 samples=1"
        )) {
            try {
                replace(prefix, line)
                fail("expected ParseException for $line")
            } catch (e: ParseException) {
            }
        }
    }

    @Test
    fun parsesWallAndWindowMasks() {
        val latch = StatusReply.parse(fixtureLines()).latch!!
        assertEquals(0x002, latch.wall)
        assertEquals(0x402, latch.window)
        assertEquals(listOf("V", "Wand", "Fenster"), latch.badges(1))
        val old = replace("LATCH ", "LATCH vent=002 dry=000 batlow=008 stale=004").latch!!
        assertNull(old.wall)
        assertNull(old.window)
    }

    @Test
    fun parsesDriftMask() {
        val latch = StatusReply.parse(fixtureLines()).latch!!
        assertEquals(0x002, latch.drift)
        val old = replace("LATCH ", "LATCH vent=002 dry=000 batlow=008 stale=004").latch!!
        assertNull(old.drift)
        for (extra in listOf("drift=800", "drift=", "drift=00g", "drift=1")) {
            try {
                replace("LATCH ", "LATCH vent=002 dry=000 batlow=008 stale=004 $extra")
                fail("expected ParseException for $extra")
            } catch (e: ParseException) {
            }
        }
    }

    @Test
    fun badWallAndWindowThrow() {
        for (extra in listOf("wall=800", "window=800", "wall=", "window=00g", "wall=1")) {
            try {
                replace("LATCH ", "LATCH vent=000 dry=000 batlow=000 stale=000 $extra")
                fail("expected ParseException for $extra")
            } catch (e: ParseException) {
            }
        }
    }

    @Test
    fun parsesRxlowMask() {
        val latch = StatusReply.parse(fixtureLines()).latch!!
        assertEquals(0x202, latch.receptionLow)
        assertEquals(true, latch.hasWeakSignal(2))
        assertEquals(false, latch.hasWeakSignal(0))
        val old = replace("LATCH ", "LATCH vent=002 dry=000 batlow=008 stale=004").latch!!
        assertNull(old.receptionLow)
        for (extra in listOf("rxlow=400", "rxlow=", "rxlow=00g", "rxlow=1", "rxlow=0x1")) {
            try {
                replace("LATCH ", "LATCH vent=000 dry=000 batlow=000 stale=000 $extra")
                fail("expected ParseException for $extra")
            } catch (e: ParseException) {
            }
        }
    }
}
