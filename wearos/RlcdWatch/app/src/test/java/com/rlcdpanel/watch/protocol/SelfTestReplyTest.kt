package com.rlcdpanel.watch.protocol

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
        assertEquals(listOf("shtc3", "rtc", "sd", "battery", "mic", "ble", "reception"), r.checks.map { it.name })
        assertTrue(r.checks.dropLast(1).all { it.state == SelfTestState.OK })
        assertEquals(SelfTestState.SKIP, r.checks[6].state)
        assertEquals("NO_SLOT", r.checks[6].detail)
        assertEquals(listOf("READ", "RUNNING", "free_mb=0", "mv=3900", "L=OK,R=OK", "SCAN", "NO_SLOT"), r.checks.map { it.detail })
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
        assertEquals(SelfTestState.SKIP, r.checks[2].state)
        assertEquals("CONSOLE", r.checks[2].detail)
        assertFalse(r.passed)
    }

    @Test
    fun badVerdictThrows() {
        val bad = fixtureLines().toMutableList().also { it[1] = "CHECK shtc3 MAYBE READ" }
        assertEquals("CHECK shtc3 MAYBE READ", assertThrows(ParseException::class.java) { SelfTestReply.parse(bad) }.line)
    }

    @Test
    fun missingEndLineThrows() {
        assertThrows(ParseException::class.java) { SelfTestReply.parse(listOf("BEGIN SELFTEST", "CHECK a OK x")) }
        assertThrows(ParseException::class.java) { SelfTestReply.parse(listOf("nope")) }
    }

    @Test
    fun countsThatDifferFromTheChecksThrow() {
        val bad = fixtureLines().dropLast(1) + "END SELFTEST pass=5 fail=1"
        assertThrows(ParseException::class.java) { SelfTestReply.parse(bad) }
    }
}
