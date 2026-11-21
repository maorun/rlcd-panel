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
        val text = Fixtures.file("status_reply_sample.txt").readText().replace("CRC32 7108cbc2", "CRC32 00000000")
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
        assertEquals(LatchState(0x002, 0x000, 0x008, 0x004), latch)
        assertEquals(listOf("V"), latch.badges(1))
        assertEquals(listOf("BAT LOW"), latch.badges(4))
        assertEquals(listOf("STALE"), latch.badges(3))
        assertEquals(emptyList<String>(), latch.badges(11))
        assertEquals(emptyList<String>(), latch.badges(-1))
        assertEquals(emptyList<String>(), latch.badges(0))
        assertNull(StatusReply.parse(fixtureLines().filter { !it.startsWith("LATCH ") }).latch)
    }

    @Test
    fun latchBadgeOrderAndOnboardRules() {
        val all = replace("LATCH ", "LATCH vent=7FF dry=7ff batlow=3ff stale=3FF").latch!!
        assertEquals(listOf("V", "D"), all.badges(0))
        assertEquals(listOf("V", "D", "BAT LOW", "STALE"), all.badges(10))
    }

    @Test
    fun badLatchLineThrows() {
        for (line in listOf(
            "LATCH vent=800 dry=000 batlow=000 stale=000", "LATCH vent=000 dry=12 batlow=000 stale=000",
            "LATCH vent=000 dry=000 batlow=400 stale=000", "LATCH vent=000 dry=000 batlow=000 stale=400",
            "LATCH vent=00g dry=000 batlow=000 stale=000", "LATCH vent=000 dry=000 batlow=000"
        )) {
            try {
                replace("LATCH ", line)
                fail("expected ParseException for $line")
            } catch (e: ParseException) {
            }
        }
    }
}
