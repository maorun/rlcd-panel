package com.rlcdpanel.watch.protocol

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
        assertEquals("Self-test: --", l.last())
        assertTrue(l.contains("--"))
    }

    @Test
    fun selfTestSectionHoldsEveryCheckAndTheSummary() {
        val reply = SelfTestReply.parse(frame("selftest_reply_sample.txt"))
        val l = lines(DiagnosticsReport.build(version, status, rejects, selfTest = reply))
        assertTrue(l.contains("## SELFTEST"))
        assertEquals(7, l.count { it.startsWith("CHECK ") })
        assertTrue(l.contains("CHECK shtc3 OK READ"))
        assertTrue(l.contains("CHECK reception SKIP NO_SLOT"))
        assertTrue(l.contains("Self-test: passed, 6 ok, 0 failed"))
    }

    @Test
    fun receptionSectionHoldsOneLineForEachSlot() {
        val rows = ReceptionReply.parse(frame("reception_reply_sample.txt"))
        val shown = lines(DiagnosticsReport.build(version, status, rejects, includeNames = true, reception = rows))
        assertTrue(shown.contains("## RECEPTION"))
        assertTrue(shown.contains("slot 1 -- 12/24"))
        assertTrue(shown.contains("slot 2 Cellar 4/6"))
        assertTrue(shown.indexOf("slot 1 -- 12/24") < shown.indexOf("slot 2 Cellar 4/6"))
        val hidden = DiagnosticsReport.build(version, status, rejects, reception = rows)
        assertTrue(hidden.contains("slot 2 <hidden> 4/6"))
        assertFalse(hidden.contains("Cellar"))
    }

    @Test
    fun emptyReceptionReplyGivesAnEmptySection() {
        val l = lines(DiagnosticsReport.build(version, status, rejects, reception = emptyList()))
        assertEquals("## RECEPTION", l.last())
    }

    @Test
    fun receptionSectionFollowsSelfTest() {
        val text = DiagnosticsReport.build(
            version, status, rejects,
            selfTest = SelfTestReply.parse(frame("selftest_reply_sample.txt")),
            reception = ReceptionReply.parse(frame("reception_reply_sample.txt"))
        )
        assertTrue(text.indexOf("## SELFTEST") < text.indexOf("## RECEPTION"))
    }

    @Test
    fun reportWithoutReceptionHasNoReceptionSection() {
        val text = DiagnosticsReport.build(version, status, rejects)
        assertFalse(text.contains("## RECEPTION"))
    }

    @Test
    fun missingTextReplacesDashesForEveryMissingReply() {
        val text = DiagnosticsReport.build(null, null, null, missing = "not read", reception = null, showMissingReception = true)
        val l = lines(text)
        assertEquals("Firmware: not read", l[1])
        assertTrue(l.contains("Sensors: not read"))
        assertTrue(l.contains("SD: not read"))
        assertEquals(listOf("## REJECTS", "not read"), l.subList(l.indexOf("## REJECTS"), l.indexOf("## REJECTS") + 2))
        assertTrue(l.contains("Self-test: not read"))
        assertEquals(listOf("## RECEPTION", "not read"), l.takeLast(2))
        assertFalse(text.contains("--"))
    }

    @Test
    fun sectionOrderMatchesThePhoneReport() {
        val text = DiagnosticsReport.build(
            version, status, rejects,
            selfTest = SelfTestReply.parse(frame("selftest_reply_sample.txt")),
            reception = ReceptionReply.parse(frame("reception_reply_sample.txt")),
            missing = "not read", showMissingReception = true
        )
        val order = listOf("Firmware:", "Sensor 1 ", "SD: ", "## REJECTS", "## SELFTEST", "## RECEPTION").map { text.indexOf(it) }
        assertEquals(order.sorted(), order)
        assertFalse(order.any { it < 0 })
    }

    @Test
    fun defaultKeepsDashesAndOmitsAMissingReceptionSection() {
        val text = DiagnosticsReport.build(null, null, null)
        assertTrue(text.contains("Firmware: --"))
        assertFalse(text.contains("## RECEPTION"))
    }

    @Test
    fun titleParameterSetsTheFirstLine() {
        assertEquals("RLCD Phone Diagnostics", lines(DiagnosticsReport.build(null, null, null))[0])
        assertEquals("RLCD Watch Diagnostics", lines(DiagnosticsReport.build(null, null, null, title = "RLCD Watch Diagnostics"))[0])
    }

    @Test
    fun missingTextAloneKeepsTheReceptionSectionOut() {
        val text = DiagnosticsReport.build(null, null, null, missing = "not read")
        assertFalse(text.contains("## RECEPTION"))
    }

    @Test
    fun presentReceptionWinsOverTheMissingText() {
        val rows = ReceptionReply.parse(frame("reception_reply_sample.txt"))
        val text = DiagnosticsReport.build(version, status, rejects, reception = rows, missing = "not read", showMissingReception = true)
        assertTrue(text.contains("slot 1 -- 12/24"))
        assertFalse(lines(text).takeLast(2).contains("not read"))
    }
}
