package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FrsiReplyTest {
    private fun parse(vararg lines: String) = FrsiReply.parse(lines.toList())

    private fun assertBad(vararg lines: String) {
        try {
            parse(*lines)
            fail("no error for ${lines.toList()}")
        } catch (e: ParseException) {
            // expected
        }
    }

    @Test
    fun sharedFixtureGivesTheMeasurement() {
        val frame = ReplyAssembler().append(Fixtures.file("frsi_reply_sample.txt").readBytes()) as ConsoleReply.Frame
        val r = FrsiReply.parse(frame.lines)
        val m = (r.outcome as FrsiReply.Outcome.Measured).measurement
        assertEquals("onboard", m.room)
        assertEquals("slot2", m.wall)
        assertEquals(6L, m.hours)
        assertEquals(0.70, m.frsi, 0.0)
        assertEquals(0.0, m.span, 0.0)
        assertEquals(20.0, m.deltaC, 0.0)
        assertEquals(70, m.proposalPct)
        assertEquals(CalibrationProposal("wall_frsi_pct", "70"), r.proposal)
    }

    @Test
    fun skipAndUnstable() {
        assertEquals(FrsiReply.Outcome.Skipped(4), parse("SKIP hours=4").outcome)
        assertEquals(FrsiReply.Outcome.Unstable(0.25), parse("UNSTABLE span=0.25").outcome)
        assertEquals(FrsiReply.Outcome.Unstable(null), parse("UNSTABLE span=--").outcome)
        assertNull(parse("SKIP hours=4").proposal)
    }

    @Test
    fun proposalOutsideRangeIsDropped() {
        val head = "FRSI room=onboard wall=slot2 hours=6 frsi=0.70 span=0.00 delta_c=20.0"
        assertNull(parse(head, "wall_frsi_pct=49").proposal)
        assertNull(parse(head, "wall_frsi_pct=96").proposal)
        assertNull(parse(head).proposal)
    }

    @Test
    fun badNumberThrows() {
        assertBad("FRSI room=onboard wall=slot2 hours=6 frsi=x span=0.00 delta_c=20.0")
        assertBad("FRSI room=onboard wall=slot2 hours=-1 frsi=0.70 span=0.00 delta_c=20.0")
        assertBad("SKIP hours=x")
        assertBad("UNSTABLE span=nan")
        assertBad("OTHER")
        assertBad()
    }

    @Test
    fun commandHours() {
        assertEquals("FRSI 0 2", FrsiCommand.line(0, 2, 6))
        assertEquals("FRSI 0 2 12", FrsiCommand.line(0, 2, 12))
        assertEquals("FRSI 0 2 3", FrsiCommand.line(0, 2, 3))
        assertEquals("FRSI 0 2 24", FrsiCommand.line(0, 2, 24))
        for (h in listOf(2, 25, 0, -6)) {
            try {
                FrsiCommand.line(0, 2, h)
                fail("no error for $h")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(listOf(3, 6, 12, 24), FrsiCommand.HOUR_CHOICES)
    }

    @Test
    fun skipForLongerWindow() {
        val reply = parse("SKIP hours=6")
        assertEquals(FrsiReply.Outcome.Skipped(6), reply.outcome)
        assertEquals("SKIP: nur 6 h, mindestens 12 h nötig", FrsiText.outcome(reply, 12))
        assertEquals("SKIP: nur 6 h, mindestens 6 h nötig", FrsiText.outcome(reply))
    }

    @Test
    fun badArgHintOnlyForOtherWindows() {
        assertTrue(FrsiText.status("ERR BAD_ARG", 12).contains("Update"))
        assertEquals(false, FrsiText.status("ERR BAD_ARG", 6).contains("Update"))
        assertEquals(false, FrsiText.status("ERR BAD_ARG").contains("Update"))
    }

    @Test
    fun commandLine() {
        assertEquals("FRSI 0 2", FrsiCommand.line(0, 2))
        for ((r, w) in listOf(1 to 1, 0 to 0, 0 to 11, 11 to 1, 1 to 0)) {
            try {
                FrsiCommand.line(r, w)
                fail("no error for $r $w")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun statusTexts() {
        assertEquals("Set ble_console=2 to apply", FrsiText.status("ERR READ_ONLY"))
        assertTrue(FrsiText.status("ERR LOW_DELTA").contains("10 °C"))
        assertTrue(FrsiText.status("ERR NO_OUTDOOR").contains("outdoor_slot"))
        assertTrue(FrsiText.status("ERR BAD_ARG").contains("verschieden"))
        assertEquals("OTHER", FrsiText.status("OTHER"))
    }

    @Test
    fun malformedLinesThrowOrDropProposal() {
        val head = "FRSI room=onboard wall=slot2 hours=6 frsi=0.70 span=0.00 delta_c=20.0"
        assertBad("$head extra=1")
        assertBad("FRSI room=onboard wall=slot2 hours=6 frsi=0.70 span=0.00")
        assertBad("SKIP hours=4 extra=1")
        assertBad("UNSTABLE span=0.25 extra=1")
        assertNull(parse(head, "wall_frsi_pct=070").proposal)
        assertNull(parse(head, "wall_frsi_pct=100").proposal)
        assertEquals(FrsiReply.Outcome.Skipped(4), parse("BEGIN FRSI", "SKIP hours=4").outcome)
    }
}
