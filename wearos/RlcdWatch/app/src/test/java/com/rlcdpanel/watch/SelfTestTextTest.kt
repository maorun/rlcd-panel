package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.ConsoleReply
import com.rlcdpanel.watch.protocol.ReplyError
import com.rlcdpanel.watch.protocol.SelfTestCheck
import com.rlcdpanel.watch.protocol.SelfTestReply
import com.rlcdpanel.watch.protocol.SelfTestState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfTestTextTest {
    @Test
    fun bleSkipConsoleIsAnExpectedResult() {
        val skip = SelfTestCheck("ble", SelfTestState.SKIP, "CONSOLE")
        assertTrue(SelfTestText.isExpectedSkip(skip))
        assertEquals("CONSOLE (erwartet über BLE)", SelfTestText.detail(skip))
        assertFalse(SelfTestText.isExpectedSkip(SelfTestCheck("ble", SelfTestState.FAIL, "START_FAILED")))
        assertFalse(SelfTestText.isExpectedSkip(SelfTestCheck("mic", SelfTestState.SKIP, "MUTED")))
    }

    @Test
    fun emptyDetailShowsADash() {
        assertEquals("–", SelfTestText.detail(SelfTestCheck("rtc", SelfTestState.OK, "")))
    }

    @Test
    fun labelAndCountsShowVerdicts() {
        assertEquals("✘ sd FAIL", SelfTestText.label(SelfTestCheck("sd", SelfTestState.FAIL, "NO_CARD")))
        val reply = SelfTestReply.parse(listOf("BEGIN SELFTEST", "CHECK sd FAIL NO_CARD", "END SELFTEST pass=0 fail=1"))
        assertEquals("Bestanden 0, fehlgeschlagen 1", SelfTestText.counts(reply))
    }

    @Test
    fun missingEndLineGivesItsOwnText() {
        assertEquals(SelfTestText.NO_END, SelfTestText.parseError(listOf("BEGIN SELFTEST", "CHECK a OK x")))
        assertEquals("Antwort nicht lesbar", SelfTestText.parseError(listOf("BEGIN SELFTEST", "CHECK a MAYBE x", "END SELFTEST pass=0 fail=0")))
    }

    @Test
    fun timeoutGivesLostLinkText() {
        val text = SelfTestText.linkError(ConsoleReply.Corrupt(ReplyError.Timeout))
        assertTrue(text.startsWith(SelfTestText.LOST_LINK))
        assertEquals("Antwort zu groß", SelfTestText.linkError(ConsoleReply.Corrupt(ReplyError.TooLarge)))
        assertEquals("Panel meldet: ERR NO_CARD", SelfTestText.linkError(ConsoleReply.Status("ERR NO_CARD")))
    }
}
