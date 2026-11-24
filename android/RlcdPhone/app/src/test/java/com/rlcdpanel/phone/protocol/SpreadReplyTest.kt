package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpreadReplyTest {
    private val header = "SPREAD sensors=1 temp_median=20.0 rh_median=50.0"

    private fun parse(vararg lines: String) = SpreadReply.parse(lines.toList())

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
        val frame = ReplyAssembler().append(Fixtures.file("spread_reply_sample.txt").readBytes()) as ConsoleReply.Frame
        val m = SpreadReply.parse(frame.lines).outcome as SpreadReply.Outcome.Measured
        assertEquals(3L, m.sensors)
        assertEquals(20.0, m.tempMedian, 0.0)
        assertEquals(50.0, m.rhMedian, 0.0)
        assertEquals(3, m.rows.size)
        assertEquals(SpreadReply.SensorSpread("onboard", 0.0, 0.0, false), m.rows[0])
        assertEquals(SpreadReply.SensorSpread("slot1", 0.0, 0.0, false), m.rows[1])
        assertEquals(SpreadReply.SensorSpread("slot2", 1.0, 5.0, true), m.rows[2])
    }

    @Test
    fun sharedFixtureGivesOneProposalWithTwoKeys() {
        val frame = ReplyAssembler().append(Fixtures.file("spread_reply_sample.txt").readBytes()) as ConsoleReply.Frame
        val m = SpreadReply.parse(frame.lines).outcome as SpreadReply.Outcome.Measured
        assertEquals(
            listOf(SpreadReply.Proposal("slot2_temp_offset_c", -1.0, "slot2_rh_offset_pct", -5.0)),
            m.proposals
        )
        assertEquals(
            listOf("SET slot2_temp_offset_c=-1.0", "SET slot2_rh_offset_pct=-5.0"),
            m.proposals[0].setCommands
        )
    }

    @Test
    fun replyWithoutProposalLinesGivesEmptyList() {
        val m = parse(header, "SENSOR a temp_dev=0.0 rh_dev=0.0 OK").outcome as SpreadReply.Outcome.Measured
        assertTrue(m.proposals.isEmpty())
    }

    @Test
    fun badProposalNumberThrows() {
        val sensor = "SENSOR a temp_dev=0.6 rh_dev=0.0 OUTLIER"
        assertBad(header, sensor, "temp_offset_c=1.2.3 rh_offset_pct=0.0")
        assertBad(header, sensor, "temp_offset_c=x rh_offset_pct=0.0")
        assertBad(header, sensor, "temp_offset_c=-- rh_offset_pct=0.0")
    }

    @Test
    fun outlierFlagIsKept() {
        val m = parse(header, "SENSOR slot3 temp_dev=0.6 rh_dev=-3.1 OUTLIER").outcome as SpreadReply.Outcome.Measured
        assertTrue(m.rows[0].isOutlier)
        assertEquals(-3.1, m.rows[0].rhDev, 0.0)
        assertTrue(SpreadText.row(m.rows[0]).startsWith("⚠ slot3"))
    }

    @Test
    fun nameWithSpaceIsKept() {
        val m = parse(header, "SENSOR LIVING ROOM temp_dev=0.1 rh_dev=0.5 OK").outcome as SpreadReply.Outcome.Measured
        assertEquals("LIVING ROOM", m.rows[0].name)
    }

    @Test
    fun skipGivesTheHint() {
        val r = parse("SKIP sensors=2")
        assertEquals(SpreadReply.Outcome.Skipped(2), r.outcome)
        assertEquals("Put 3 or more sensors at one place for 6 hours", SpreadText.outcome(r))
        assertFalse(SpreadText.row(SpreadReply.SensorSpread("a", 0.0, 0.0, false)).contains("⚠"))
    }

    @Test
    fun badLinesThrow() {
        assertBad(header, "SENSOR slot1 temp_dev=0.2 rh_dev=x OK")
        assertBad(header, "SENSOR slot1 temp_dev=0.2 rh_dev=1.0 MAYBE")
        assertBad(header, "SENSOR slot1 temp_dev=x rh_dev=1.0 OK")
        assertBad("SPREAD sensors=1 temp_median=20.0")
        assertBad("SPREAD sensors=2 temp_median=20.0 rh_median=50.0", "SENSOR a temp_dev=0.0 rh_dev=0.0 OK")
        assertBad("SKIP sensors=x")
        assertBad("SKIP sensors=2 extra=1")
        assertBad("SKIP sensors=2", "SENSOR a temp_dev=0.0 rh_dev=0.0 OK")
        assertBad(header, "SENSOR TOO LONG NAME 1 temp_dev=0.0 rh_dev=0.0 OK")
        assertBad(header, "SENSOR a_b temp_dev=0.0 rh_dev=0.0 OK")
        assertBad("SPREAD sensors=1 temp_median=20.0 rh_median=50.0 extra=1", "SENSOR a temp_dev=0.0 rh_dev=0.0 OK")
        assertBad(header, "SENSOR a temp_dev=0.0 rh_dev=0.0 OK", "JUNK")
        assertBad(header, "SENSOR a temp_dev=0.0 rh_dev=0.0 OK extra")
        assertBad("ERR BAD_ARG")
        assertBad()
    }
}
