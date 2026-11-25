package com.rlcdpanel.watch.protocol

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

    private fun fixtureMeasured(): SpreadReply.Outcome.Measured {
        val frame = ReplyAssembler().append(Fixtures.file("spread_reply_sample.txt").readBytes()) as ConsoleReply.Frame
        return SpreadReply.parse(frame.lines).outcome as SpreadReply.Outcome.Measured
    }

    @Test
    fun fixtureProposalGivesSetCommandsTemperatureFirst() {
        val m = fixtureMeasured()
        assertEquals(
            listOf("SET slot2_temp_offset_c=-1.0", "SET slot2_rh_offset_pct=-5.0"),
            SpreadApply.proposalFor(m, 2)!!.setCommands
        )
    }

    @Test
    fun okSensorGivesNoCommands() {
        val m = fixtureMeasured()
        assertEquals(null, SpreadApply.proposalFor(m, 0))
        assertEquals(null, SpreadApply.proposalFor(m, 1))
        assertEquals(null, SpreadApply.proposalFor(m, 9))
    }

    @Test
    fun secondOutlierOwnsSecondProposal() {
        val m = parse(
            "SPREAD sensors=3 temp_median=20.0 rh_median=50.0",
            "SENSOR a temp_dev=1.0 rh_dev=0.0 OUTLIER",
            "temp_offset_c=-1.0 rh_offset_pct=0.0",
            "SENSOR b temp_dev=0.0 rh_dev=0.0 OK",
            "SENSOR c temp_dev=0.0 rh_dev=4.0 OUTLIER",
            "slot2_temp_offset_c=0.0 slot2_rh_offset_pct=-4.0"
        ).outcome as SpreadReply.Outcome.Measured
        assertEquals("temp_offset_c", SpreadApply.proposalFor(m, 0)?.tempKey)
        assertEquals("slot2_temp_offset_c", SpreadApply.proposalFor(m, 2)?.tempKey)
        assertEquals("Temp -1.0 °C, Feuchte 0.0 %RH", SpreadApply.confirmText(SpreadApply.proposalFor(m, 0)!!))
    }

    @Test
    fun batchSendsSetCommandsThenConfig() {
        val proposal = SpreadApply.proposalFor(fixtureMeasured(), 2)!!
        val sent = mutableListOf<String>()
        var result: CalibrationBatch.Result? = null
        SpreadApply.batch(proposal).run({ line, cb ->
            sent.add(line)
            cb(ConsoleReply.Status("OK"))
        }) { result = it }
        assertEquals(listOf("SET slot2_temp_offset_c=-1.0", "SET slot2_rh_offset_pct=-5.0", "CONFIG"), sent)
        assertEquals(null, result!!.failure)
    }

    @Test
    fun readOnlyErrorStopsTheBatchAfterTheFirstCommand() {
        val proposal = SpreadApply.proposalFor(fixtureMeasured(), 2)!!
        val sent = mutableListOf<String>()
        var result: CalibrationBatch.Result? = null
        SpreadApply.batch(proposal).run({ line, cb ->
            sent.add(line)
            cb(ConsoleReply.Status("ERR READ_ONLY"))
        }) { result = it }
        assertEquals(listOf("SET slot2_temp_offset_c=-1.0"), sent)
        assertEquals(emptyList<String>(), result!!.applied)
        assertTrue(SpreadApply.isReadOnly(result!!))
    }

    @Test
    fun otherErrorStopsTheBatchWithoutReadOnly() {
        val proposal = SpreadApply.proposalFor(fixtureMeasured(), 2)!!
        val sent = mutableListOf<String>()
        var result: CalibrationBatch.Result? = null
        SpreadApply.batch(proposal).run({ line, cb ->
            sent.add(line)
            cb(ConsoleReply.Status(if (sent.size == 1) "OK SET" else "ERR BAD_VALUE"))
        }) { result = it }
        assertEquals(2, sent.size)
        assertEquals("slot2_rh_offset_pct" to "ERR BAD_VALUE", result!!.failure)
        assertFalse(SpreadApply.isReadOnly(result!!))
    }

    @Test
    fun foreignKeyOrOutOfRangeValueGivesNoProposal() {
        fun measured(line: String) = parse(
            "SPREAD sensors=3 temp_median=20.0 rh_median=50.0",
            "SENSOR a temp_dev=1.0 rh_dev=0.0 OUTLIER",
            line
        ).outcome as SpreadReply.Outcome.Measured
        assertEquals(null, SpreadApply.proposalFor(measured("wake_interval_s=1 rh_offset_pct=0.0"), 0))
        assertEquals(null, SpreadApply.proposalFor(measured("slot1_temp_offset_c=9.0 slot1_rh_offset_pct=0.0"), 0))
        assertEquals(null, SpreadApply.proposalFor(measured("rh_offset_pct=1.0 temp_offset_c=1.0"), 0))
        assertEquals("slot1_temp_offset_c", SpreadApply.proposalFor(measured("slot1_temp_offset_c=-1.0 slot1_rh_offset_pct=0.0"), 0)?.tempKey)
    }

    @Test
    fun keysOfDifferentSensorsGiveNoProposal() {
        val m = parse(
            "SPREAD sensors=3 temp_median=20.0 rh_median=50.0",
            "SENSOR a temp_dev=1.0 rh_dev=0.0 OUTLIER",
            "slot1_temp_offset_c=-1.0 slot2_rh_offset_pct=0.0"
        ).outcome as SpreadReply.Outcome.Measured
        assertEquals(null, SpreadApply.proposalFor(m, 0))
    }

    @Test
    fun corruptReplyGivesUnknownState() {
        val proposal = SpreadApply.proposalFor(fixtureMeasured(), 2)!!
        var result: CalibrationBatch.Result? = null
        SpreadApply.batch(proposal).run({ _, cb -> cb(ConsoleReply.Corrupt(ReplyError.Timeout)) }) { result = it }
        assertTrue(SpreadApply.isUnknownState(result!!))
        assertFalse(SpreadApply.isReadOnly(result!!))
    }
}
