package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfTestReplyTest {
    private fun fixtureLines(): List<String> =
        Fixtures.file("selftest_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }

    @Test
    fun sharedFixtureParses() {
        val r = SelfTestReply.parse(fixtureLines())
        assertEquals(listOf("shtc3", "rtc", "sd", "battery", "mic", "ble"), r.checks.map { it.name })
        assertTrue(r.checks.all { it.state == SelfTestState.OK })
        assertEquals("mv=3900", r.checks[3].detail)
        assertEquals("L=OK,R=OK", r.checks[4].detail)
        assertEquals(6, r.pass)
        assertEquals(0, r.fail)
        assertTrue(r.passed)
    }

    @Test
    fun failAndSkipAreCountedAndMarked() {
        val r = SelfTestReply.parse(
            listOf("BEGIN SELFTEST", "CHECK sd FAIL NO_CARD", "CHECK shtc3 OK READ", "CHECK ble SKIP CONSOLE", "END SELFTEST pass=1 fail=1")
        )
        assertEquals(listOf("✘", "✔", "–"), r.checks.map { it.mark })
        assertFalse(r.passed)
    }

    @Test
    fun unknownStateThrows() {
        val bad = fixtureLines().toMutableList().also { it[1] = "CHECK shtc3 MAYBE READ" }
        assertEquals("CHECK shtc3 MAYBE READ", assertThrows(ParseException::class.java) { SelfTestReply.parse(bad) }.line)
    }

    @Test
    fun countsThatDifferFromTheChecksThrow() {
        val bad = fixtureLines().dropLast(1) + "END SELFTEST pass=5 fail=1"
        assertThrows(ParseException::class.java) { SelfTestReply.parse(bad) }
        assertThrows(ParseException::class.java) { SelfTestReply.parse(listOf("BEGIN SELFTEST", "CHECK a OK x")) }
        assertThrows(ParseException::class.java) { SelfTestReply.parse(listOf("nope")) }
        assertThrows(ParseException::class.java) { SelfTestReply.parse(listOf("BEGIN SELFTEST", "CHECK a OK x", "END SELFTEST pass=01 fail=0")) }
    }

    @Test
    fun reportHoldsTheSelfTestSummary() {
        val r = SelfTestReply.parse(fixtureLines())
        val text = DiagnosticsReport.build(null, null, null, selfTest = r)
        assertTrue(text.contains("## SELFTEST"))
        assertTrue(text.contains("CHECK mic OK L=OK,R=OK"))
        assertTrue(text.contains("Self-test: passed, 6 ok, 0 failed"))
        assertTrue(DiagnosticsReport.build(null, null, null).contains("Self-test: --"))
    }

    @Test
    fun reportMarksAFailedSelfTest() {
        val r = SelfTestReply.parse(listOf(
            "BEGIN SELFTEST", "CHECK shtc3 OK READ", "CHECK sd FAIL NO_CARD", "CHECK ble SKIP CONSOLE", "END SELFTEST pass=1 fail=1"))
        val lines = DiagnosticsReport.build(null, null, null, selfTest = r).lines()
        val first = lines.indexOf("CHECK shtc3 OK READ")
        assertEquals("CHECK sd FAIL NO_CARD", lines[first + 1])
        assertEquals("CHECK ble SKIP CONSOLE", lines[first + 2])
        assertEquals("Self-test: FAILED, 1 ok, 1 failed", lines[first + 3])
    }
}
