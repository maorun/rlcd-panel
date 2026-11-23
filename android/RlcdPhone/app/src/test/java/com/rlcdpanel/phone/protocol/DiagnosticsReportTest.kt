package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {
    private fun frame(name: String): List<String> =
        Fixtures.file(name).readLines().filter { !it.startsWith("CRC32") }

    private val status get() = StatusReply.parse(frame("status_reply_sample.txt"))
    private val rejects get() = RejectsReply.parse(frame("rejects_reply_sample.txt"))
    private val version = VersionReply("1.0.0", "abc1234", "3.3.12", "70", "2026-11-21")

    private fun lines(text: String) = text.trimEnd('\n').split("\n")

    @Test
    fun reportHoldsFirmwareSensorsSdAndRejects() {
        val l = lines(DiagnosticsReport.build(version, status, rejects, includeNames = true))
        assertTrue(l.contains("Firmware: 1.0.0 (abc1234), state 70, built 2026-11-21"))
        assertEquals(6, l.count { it.startsWith("Sensor ") })
        assertTrue(l.contains("Sensor 1 Wohnzimmer: temp=22.1 rh=48.5 bat=87 rssi=-61 age=14 state=OK mold_h=3"))
        assertTrue(l.contains("Sensor 2 Keller: temp=-- rh=-- bat=-- rssi=-- age=-- state=EMPTY mold_h=--"))
        assertTrue(l.contains("SD: status OK, free 14820 MB"))
        assertTrue(l.contains("AA:00:00:00:00:13,METER,FULL,2"))
        assertTrue(l.contains("AA:00:00:00:00:15,THERMOPRO,DISABLED,255"))
        assertTrue(l.contains("DROPPED,1"))
    }

    @Test
    fun badgeLineListsLatchedSensors() {
        val l = lines(DiagnosticsReport.build(version, status, rejects))
        assertTrue(l.contains("Panel badges: 1 V, Wand, Fenster; 3 STALE; 4 BAT LOW; 10 Fenster"))
    }

    @Test
    fun missingVersionGivesDashes() {
        val l = lines(DiagnosticsReport.build(null, status, rejects))
        assertEquals("Firmware: --", l[1])
    }

    @Test
    fun namesAndMacsAreHiddenByDefault() {
        val text = DiagnosticsReport.build(version, status, rejects)
        assertFalse(text.contains("Wohnzimmer"))
        assertFalse(text.contains("AA:00"))
        assertTrue(text.contains("Sensor 1 <hidden>: temp=22.1"))
        assertTrue(text.contains("<hidden>,METER,FULL,2"))
    }

    @Test
    fun reportHasNoWifiOrNtpData() {
        val text = DiagnosticsReport.build(version, status, rejects, includeNames = true)
        assertFalse(text.contains("NTP"))
        assertFalse(text.contains("next_s"))
    }

    @Test
    fun missingRepliesGiveDashes() {
        val l = lines(DiagnosticsReport.build(null, null, null))
        assertTrue(l.contains("Sensors: --"))
        assertTrue(l.contains("SD: --"))
        assertEquals("--", l.last())
    }
}
