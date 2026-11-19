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
}
