package com.rlcdpanel.watch.protocol

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
    fun fixtureStatesAndReception() {
        val status = StatusReply.parse(fixtureLines())
        assertEquals(6, status.sensorLines.size)
        assertEquals(listOf("OK", "OK", "EMPTY", "STALE", "BATLOW", "OK"), status.sensors.map { it.state })
        val s3 = status.reception!![2]!!
        assertEquals(0, s3.received)
        assertEquals(12, s3.scans)
        assertEquals(false, status.muted)
    }

    @Test
    fun badCrcThrowsParseException() {
        val text = Fixtures.file("status_reply_sample.txt").readText().replace(Regex("CRC32 [0-9a-fA-F]+"), "CRC32 00000000")
        val reply = ReplyAssembler().append(text.toByteArray())!!
        assertTrue(reply is ConsoleReply.Corrupt)
        try {
            StatusReply.parseReply(reply)
            fail("expected ParseException")
        } catch (e: ParseException) {
        }
        try {
            StatusReply.parseReply(ConsoleReply.Status("ERR READ_ONLY"))
            fail("expected ParseException")
        } catch (e: ParseException) {
        }
        try {
            StatusReply.parseReply(ConsoleReply.Corrupt(ReplyError.Timeout))
            fail("expected ParseException")
        } catch (e: ParseException) {
        }
    }

    @Test
    fun receptionTextAndWarningFollowRate() {
        val status = StatusReply.parse(fixtureLines())
        assertNull(com.rlcdpanel.watch.SensorHealthText.rateOf(status, status.sensors[0]))
        val weak = com.rlcdpanel.watch.SensorHealthText.rateOf(status, status.sensors[3])
        assertEquals("Empfang 0/12 (0 %)", com.rlcdpanel.watch.SensorHealthText.rate(weak))
        assertTrue(com.rlcdpanel.watch.SensorHealthText.isRateWeak(weak))
        val good = com.rlcdpanel.watch.SensorHealthText.rateOf(status, status.sensors[1])
        assertTrue(!com.rlcdpanel.watch.SensorHealthText.isRateWeak(good))
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

    @Test
    fun parsesHeatMask() {
        val latch = StatusReply.parse(fixtureLines()).latch!!
        assertEquals(0x001, latch.heat)
        assertEquals(true, latch.hasHeat(0))
        assertEquals(false, latch.hasHeat(1))
        assertEquals(false, latch.hasHeat(11))
        val old = replace("LATCH ", "LATCH vent=002 dry=000 batlow=008 stale=004 drift=002").latch!!
        assertNull(old.heat)
        assertEquals(false, old.hasHeat(0))
        for (extra in listOf("heat=800", "heat=", "heat=00g", "heat=1")) {
            try {
                replace("LATCH ", "LATCH vent=002 dry=000 batlow=008 stale=004 $extra")
                fail("expected ParseException for $extra")
            } catch (e: ParseException) {
            }
        }
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
    fun parsesRtcLine() {
        assertEquals(RtcState(0, 5), StatusReply.parse(fixtureLines()).rtc)
        assertEquals(RtcState(null, null), replace("RTC ", "RTC trim=-- drift_ppm=--").rtc)
        assertNull(StatusReply.parse(fixtureLines().filter { !it.startsWith("RTC ") }).rtc)
        assertEquals(RtcState(-64, -12), replace("RTC ", "RTC trim=-64 drift_ppm=-12").rtc)
        assertEquals(RtcState(63, null), replace("RTC ", "RTC trim=63 drift_ppm=--").rtc)
        for (line in listOf("RTC trim=64 drift_ppm=5", "RTC trim=-65 drift_ppm=5", "RTC trim=+1 drift_ppm=--", "RTC trim=0 drift_ppm=x", "RTC trim=0")) {
            try {
                replace("RTC ", line)
                fail("expected ParseException for $line")
            } catch (e: ParseException) {
            }
        }
    }

    @Test
    fun parsesLatchLine() {
        val latch = StatusReply.parse(fixtureLines()).latch!!
        assertEquals(LatchState(0x002, 0x000, 0x008, 0x004, 0x002, 0x402, 0x202, 0x002, 0x001), latch)
        assertEquals(0x202, latch.receptionLow)
        assertEquals(0x002, latch.wall)
        assertEquals(0x402, latch.window)
        assertEquals(listOf("V", "Wand", "Fenster"), latch.badges(1))
        assertEquals(listOf("Fenster"), latch.badges(10))
        assertEquals(listOf("BAT LOW"), latch.badges(4))
        assertEquals(listOf("STALE"), latch.badges(3))
        assertEquals(emptyList<String>(), latch.badges(11))
        assertEquals(emptyList<String>(), latch.badges(-1))
        assertEquals(emptyList<String>(), latch.badges(0))
        assertNull(StatusReply.parse(fixtureLines().filter { !it.startsWith("LATCH ") }).latch)
    }

    @Test
    fun latchBadgeOrderAndOnboardRules() {
        val all = replace("LATCH ", "LATCH vent=7FF dry=7ff batlow=3ff stale=3FF wall=7ff window=7FF").latch!!
        assertEquals(listOf("V", "D", "Wand", "Fenster"), all.badges(0))
        assertEquals(listOf("V", "D", "Wand", "Fenster", "BAT LOW", "STALE"), all.badges(10))
    }

    @Test
    fun latchWithEmptyWallThrows() {
        try {
            replace("LATCH ", "LATCH vent=000 dry=000 batlow=000 stale=000 wall=")
            fail("expected ParseException")
        } catch (e: ParseException) {
        }
    }

    @Test
    fun latchWithoutWallAndWindowGivesNull() {
        val latch = replace("LATCH ", "LATCH vent=002 dry=000 batlow=008 stale=004").latch!!
        assertNull(latch.wall)
        assertNull(latch.window)
        assertEquals(listOf("V"), latch.badges(1))
    }

    @Test
    fun parsesRxlowMask() {
        val latch = StatusReply.parse(fixtureLines()).latch!!
        assertEquals(0x202, latch.receptionLow)
        assertEquals(false, latch.hasWeakSignal(0))
        assertEquals(true, latch.hasWeakSignal(2))
        assertEquals(true, latch.hasWeakSignal(10))
        assertEquals(false, latch.hasWeakSignal(3))
        assertEquals(false, latch.hasWeakSignal(11))
        assertEquals(0x3ff, replace("LATCH ", "LATCH vent=000 dry=000 batlow=000 stale=000 rxlow=3FF").latch!!.receptionLow)
    }

    @Test
    fun missingRxlowGivesNull() {
        val latch = replace("LATCH ", "LATCH vent=002 dry=000 batlow=008 stale=004 wall=002 window=402").latch!!
        assertNull(latch.receptionLow)
        assertEquals(false, latch.hasWeakSignal(2))
    }

    @Test
    fun badRxlowThrows() {
        for (extra in listOf("rxlow=400", "rxlow=", "rxlow=00g", "rxlow=1", "rxlow=0x1")) {
            try {
                replace("LATCH ", "LATCH vent=000 dry=000 batlow=000 stale=000 $extra")
                fail("expected ParseException for $extra")
            } catch (e: ParseException) {
            }
        }
    }

    @Test
    fun badLatchLineThrows() {
        for (line in listOf(
            "LATCH vent=800 dry=000 batlow=000 stale=000", "LATCH vent=000 dry=12 batlow=000 stale=000",
            "LATCH vent=000 dry=000 batlow=400 stale=000", "LATCH vent=000 dry=000 batlow=000 stale=400",
            "LATCH vent=00g dry=000 batlow=000 stale=000", "LATCH vent=000 dry=000 batlow=000",
            "LATCH vent=000 dry=000 batlow=000 stale=000 wall=800", "LATCH vent=000 dry=000 batlow=000 stale=000 window=800",
            "LATCH vent=000 dry=000 batlow=000 stale=000 wall=1", "LATCH vent=000 dry=000 batlow=000 stale=000 window=00g"
        )) {
            try {
                replace("LATCH ", line)
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

    private fun withBle(line: String) = fixtureLines().map { if (it.startsWith("BLE ")) line else it }

    @Test
    fun parsesBleRejectLine() {
        val old = StatusReply.parse(fixtureLines()).bleRejects!!
        assertEquals(0, old.rejected)
        assertNull(old.last)
        assertNull(old.unassigned)
        assertNull(old.jumps)
        assertTrue(!old.isWarning)
        val full = StatusReply.parse(withBle("BLE rejected=7 last=AA:BB:CC:DD:EE:FF woio=1 meter=2 bthome=0 atc=0 thermopro=4 unassigned=3 jumps=2")).bleRejects!!
        assertEquals(7, full.rejected)
        assertEquals("AA:BB:CC:DD:EE:FF", full.last)
        assertEquals(3, full.unassigned)
        assertEquals(2, full.jumps)
        assertTrue(full.isWarning)
        val noJumps = StatusReply.parse(withBle("BLE rejected=1 last=-- woio=1 meter=0 bthome=0 atc=0 thermopro=0 unassigned=0")).bleRejects!!
        assertNull(noJumps.jumps)
        assertEquals(0, noJumps.unassigned)
        assertNull(StatusReply.parse(fixtureLines().filterNot { it.startsWith("BLE ") }).bleRejects)
        assertEquals(65535, StatusReply.parse(withBle("BLE rejected=65535 last=--")).bleRejects!!.rejected)
    }

    @Test
    fun badBleRejectLineThrows() {
        for (bad in listOf("BLE rejected=x last=--", "BLE last=--", "BLE rejected=1 last=-- jumps=x", "BLE rejected=65536 last=--")) {
            try {
                StatusReply.parse(withBle(bad))
                fail("expected ParseException for $bad")
            } catch (e: ParseException) {
            }
        }
    }

    @Test
    fun heatHoursOfFixtureAndBadFields() {
        val sensors = StatusReply.parse(fixtureLines()).sensors
        assertEquals(3, sensors[0].heatHours)
        assertNull(sensors[1].heatHours)
        val seven = fixtureLines().map { if (it.startsWith("SENSOR 1 ")) it.replace("heat_h=--", "heat_h=7") else it }
        assertEquals(7, StatusReply.parse(seven).sensors[1].heatHours)
        val without = fixtureLines().map { it.replace(Regex(" heat_h=\\S+"), "") }
        assertNull(StatusReply.parse(without).sensors[0].heatHours)
        for (value in listOf("x", "-1")) {
            val bad = fixtureLines().map { if (it.startsWith("SENSOR 1 ")) it.replace("heat_h=--", "heat_h=$value") else it }
            try {
                StatusReply.parse(bad)
                fail("expected ParseException")
            } catch (e: ParseException) {
            }
        }
    }

    @Test
    fun dryHoursOfFixtureAndMissingField() {
        val sensors = StatusReply.parse(fixtureLines()).sensors
        assertEquals(2, sensors[0].dryHours)
        assertNull(sensors[1].dryHours)
        val without = fixtureLines().map { it.replace(Regex(" dry_h=\\S+"), "") }
        assertNull(StatusReply.parse(without).sensors[0].dryHours)
    }
}
