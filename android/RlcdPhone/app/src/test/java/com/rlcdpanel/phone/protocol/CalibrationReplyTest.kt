package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CalibrationReplyTest {

    private fun fixtureLines(): List<String> {
        val reply = ReplyAssembler().append(Fixtures.file("calib_reply_sample.txt").readBytes())
        return (reply as ConsoleReply.Frame).lines
    }

    private fun assertBad(line: String, lines: List<String> = listOf("REFERENCE slot1", line)) {
        try {
            CalibrationReply.parse(lines)
            fail("no error for $line")
        } catch (e: ParseException) {
            assertEquals(line, e.line)
        }
    }

    @Test
    fun excludedFixtureGivesTheSameValuesAsTheWatchAndSwift() {
        val frame = ReplyAssembler().append(Fixtures.file("calib_reply_excluded_sample.txt").readBytes()) as ConsoleReply.Frame
        val r = CalibrationReply.parse(frame.lines)
        assertEquals(
            listOf(
                CalibrationReply.Excluded("slot2", CalibrationReply.Reason.STALE),
                CalibrationReply.Excluded("slot3", CalibrationReply.Reason.OUTDOOR)
            ),
            r.excluded
        )
        assertEquals(listOf(CalibrationReply.Skipped("slot4", 3)), r.skipped)
        assertEquals(1, r.sensors.size)
        assertEquals(listOf(CalibrationProposal("temp_offset_c", "-0.4")), r.proposals)
    }

    @Test
    fun parsesStaleAndOutdoorSkipLines() {
        val r = CalibrationReply.parse(
            listOf("REFERENCE slot1", "SKIP slot2 stale", "SKIP slot3 outdoor", "SKIP slot4 hours=2")
        )
        assertEquals(
            listOf(
                CalibrationReply.Excluded("slot2", CalibrationReply.Reason.STALE),
                CalibrationReply.Excluded("slot3", CalibrationReply.Reason.OUTDOOR)
            ),
            r.excluded
        )
        assertEquals(listOf(CalibrationReply.Skipped("slot4", 2)), r.skipped)
        assertBad("SKIP slot2 old")
    }

    /** The Swift test `CalibrationTests.sharedFixture` asserts the same values. */
    @Test
    fun sharedFixtureGivesTheSwiftValues() {
        val r = CalibrationReply.parse(fixtureLines())
        assertEquals("slot1", r.reference)
        assertEquals(listOf(CalibrationReply.Sensor("onboard", 12, -1.0, 0.0, -1.6, 0.5)), r.sensors)
        assertEquals(listOf(CalibrationReply.Skipped("slot2", 3)), r.skipped)
        assertEquals(CalibrationReply.UsbSplit(6, 6, -2.0, -1.2), r.usb)
        assertEquals(
            listOf(CalibrationProposal("temp_offset_c", "-0.4"), CalibrationProposal("usb_temp_offset_c", "-0.8")),
            r.proposals
        )
    }

    @Test
    fun usbDashGivesNull() {
        val r = CalibrationReply.parse(
            listOf("REFERENCE slot1", "USB hours=2 bat_hours=6 usb_temp_offset=0.0 usb_temp_diff=--")
        )
        assertNull(r.usb!!.usbTempDiff)
        assertEquals(2, r.usb!!.usbHours)
    }

    @Test
    fun missingUsbLineGivesNull() {
        assertNull(CalibrationReply.parse(listOf("REFERENCE onboard")).usb)
    }

    @Test
    fun missingReferenceThrows() {
        try {
            CalibrationReply.parse(listOf("SKIP slot2 hours=3"))
            fail()
        } catch (e: ParseException) {
            assertEquals("REFERENCE", e.line)
        }
    }

    @Test
    fun hoursWithLetterThrows() {
        assertBad("SENSOR slot1 hours=x temp_offset=0.0 rh_offset=0.0 temp_diff=0.4 rh_diff=-2.1")
        assertBad("SKIP slot2 hours=x")
        assertBad("SKIP slot2 hours=-1")
        assertBad("USB hours=x bat_hours=6 usb_temp_offset=0.0 usb_temp_diff=--")
    }

    @Test
    fun badNumbersThrow() {
        assertBad("SENSOR slot1 hours=1 temp_offset=nan rh_offset=0.0 temp_diff=0.4 rh_diff=0.0")
        assertBad("SENSOR slot1 hours=1 temp_offset=+1.0 rh_offset=0.0 temp_diff=0.4 rh_diff=0.0")
        assertBad("SENSOR slot1 hours=1 temp_offset=0x1p3 rh_offset=0.0 temp_diff=0.4 rh_diff=0.0")
        assertBad("SENSOR slot1 hours=1 temp_offset=0.0 rh_offset=0.0 temp_diff=0.4 rh_diff=0.0 extra=1")
        assertBad("USB hours=2 bat_hours=6 usb_temp_offset=0.0 usb_temp_diff=nan")
        assertBad("USB hours=2 bat_hours=6 usb_temp_offset=0.0")
    }

    @Test
    fun outOfRangeProposalIsDropped() {
        val r = CalibrationReply.parse(
            listOf("REFERENCE onboard", "slot1_temp_offset_c=9.0", "slot1_rh_offset_pct=2.1")
        )
        assertEquals(listOf(CalibrationProposal("slot1_rh_offset_pct", "2.1")), r.proposals)
    }

    @Test
    fun usbProposalIsParsed() {
        val r = CalibrationReply.parse(listOf("REFERENCE onboard", "usb_temp_offset_c=-0.8"))
        assertEquals(-0.8, r.usbProposal!!, 0.0)
        assertEquals(-0.8, CalibrationReply.parse(fixtureLines()).usbProposal!!, 0.0)
    }

    @Test
    fun missingUsbProposalGivesNull() {
        assertNull(CalibrationReply.parse(listOf("REFERENCE onboard", "temp_offset_c=-0.4")).usbProposal)
    }

    @Test
    fun usbProposalOutsideRangeThrows() {
        assertBad("usb_temp_offset_c=0.5", listOf("REFERENCE onboard", "usb_temp_offset_c=0.5"))
        assertBad("usb_temp_offset_c=-5.1", listOf("REFERENCE onboard", "usb_temp_offset_c=-5.1"))
    }

    @Test
    fun duplicateProposalKeepsLastValue() {
        val r = CalibrationReply.parse(
            listOf("REFERENCE onboard", "slot1_temp_offset_c=1.0", "slot1_temp_offset_c=2.0")
        )
        assertEquals(listOf(CalibrationProposal("slot1_temp_offset_c", "2.0")), r.proposals)
    }

    @Test
    fun saltMeasuredReplyKeepsOnlyItsOwnKey() {
        val r = SaltReply.parse(
            listOf(
                "BEGIN SALT",
                "SALT slot2 hours=6 rh_mean=73.0 rh_span=0.4 temp_mean=25.0 reference=75.3",
                "slot3_rh_offset_pct=1.0",
                "slot2_rh_offset_pct=2.3"
            )
        )
        assertEquals(CalibrationProposal("slot2_rh_offset_pct", "2.3"), r.proposal)
        val m = (r.outcome as SaltReply.Outcome.Measured).measurement
        assertEquals(73.0, m.rhMean, 0.0)
        assertEquals(75.3, m.reference, 0.0)
    }

    @Test
    fun saltSkipAndUnstable() {
        assertEquals(SaltReply.Outcome.Skipped("slot1", 3), SaltReply.parse(listOf("SKIP slot1 hours=3")).outcome)
        assertEquals(SaltReply.Outcome.Unstable("slot1", 1.5), SaltReply.parse(listOf("UNSTABLE slot1 span=1.5")).outcome)
        assertNotNull(SaltReply.parse(listOf("SKIP slot1 hours=3")))
        try {
            SaltReply.parse(listOf("SKIP slot1 hours=x"))
            fail()
        } catch (e: ParseException) {
            assertEquals("SKIP slot1 hours=x", e.line)
        }
    }

    @Test
    fun rangesFollowTheFirmwareLimits() {
        assertTrue(CalibrationKeys.validate("temp_offset_c", "-10"))
        assertTrue(!CalibrationKeys.validate("temp_offset_c", "10.1"))
        assertTrue(CalibrationKeys.validate("slot5_temp_offset_c", "5.0"))
        assertTrue(!CalibrationKeys.validate("slot5_temp_offset_c", "5.1"))
        assertTrue(CalibrationKeys.validate("usb_temp_offset_c", "0.0"))
        assertTrue(!CalibrationKeys.validate("usb_temp_offset_c", "-5.1"))
        assertTrue(!CalibrationKeys.validate("slot11_temp_offset_c", "0.0"))
    }

    @Test
    fun readOnlyStopsBatchAndSuppressesConfig() {
        val sent = mutableListOf<String>()
        var result: CalibrationBatch.Result? = null
        val proposals = CalibrationReply.parse(fixtureLines()).proposals
        CalibrationBatch(proposals).run(
            send = { command, cb ->
                sent.add(command)
                cb(ConsoleReply.Status("ERR READ_ONLY"))
            },
            done = { result = it }
        )
        assertEquals(listOf("SET temp_offset_c=-0.4"), sent)
        val r = result!!
        assertEquals("temp_offset_c", r.failure!!.first)
        assertEquals("ERR READ_ONLY", r.failure!!.second)
        assertNull(r.config)
        assertTrue(r.summary.contains("Set ble_console=2 to apply"))
    }

    @Test
    fun goodBatchSendsConfigLast() {
        val sent = mutableListOf<String>()
        var result: CalibrationBatch.Result? = null
        CalibrationBatch(CalibrationReply.parse(fixtureLines()).proposals).run(
            send = { command, cb ->
                sent.add(command)
                cb(if (command == "CONFIG") ConsoleReply.Frame(listOf("SOURCE SD")) else ConsoleReply.Status("OK SET x"))
            },
            done = { result = it }
        )
        assertEquals(listOf("SET temp_offset_c=-0.4", "SET usb_temp_offset_c=-0.8", "CONFIG"), sent)
        assertEquals(2, result!!.applied.size)
        assertNull(result!!.failure)
    }

    private fun runBatch(replies: (String) -> ConsoleReply): Pair<List<String>, CalibrationBatch.Result> {
        val sent = mutableListOf<String>()
        var result: CalibrationBatch.Result? = null
        CalibrationBatch(CalibrationReply.parse(fixtureLines()).proposals).run(
            send = { command, cb -> sent.add(command); cb(replies(command)) },
            done = { result = it }
        )
        return sent to result!!
    }

    @Test
    fun errOnSecondSetKeepsAppliedCountAndSkipsConfig() {
        var n = 0
        val (sent, r) = runBatch { if (++n == 1) ConsoleReply.Status("OK SET a") else ConsoleReply.Status("ERR BAD_VALUE") }
        assertEquals(2, sent.size)
        assertEquals(listOf("temp_offset_c"), r.applied)
        assertEquals("usb_temp_offset_c", r.failure!!.first)
        assertNull(r.config)
    }

    @Test
    fun corruptReplyStopsBatchWithUnknownState() {
        val (sent, r) = runBatch { ConsoleReply.Corrupt(ReplyError.Timeout) }
        assertEquals(1, sent.size)
        assertEquals(CalibrationBatch.UNKNOWN_STATE, r.failure!!.second)
    }

    @Test
    fun failedConfigReadIsReported() {
        val (_, r) = runBatch { if (it == "CONFIG") ConsoleReply.Status("ERR READ_ONLY") else ConsoleReply.Status("OK SET x") }
        assertTrue(r.configFailed)
        assertNull(r.failure)
        assertTrue(r.summary.contains("CONFIG"))
    }

    @Test
    fun rangeEdges() {
        assertTrue(CalibrationKeys.validate("rh_offset_pct", "10"))
        assertTrue(!CalibrationKeys.validate("rh_offset_pct", "-10.1"))
        assertTrue(CalibrationKeys.validate("slot10_rh_offset_pct", "-10.0"))
        assertTrue(CalibrationKeys.validate("slot10_temp_offset_c", "-5"))
        assertTrue(!CalibrationKeys.validate("slot10_temp_offset_c", "5.01"))
        assertTrue(!CalibrationKeys.validate("temp_offset_c", "0.00000000000001"))
        assertTrue(!CalibrationKeys.validate("temp_offset_c", "1e1"))
    }

    @Test
    fun longHoursParseLikeSwiftInt() {
        val r = CalibrationReply.parse(listOf("REFERENCE slot1", "SKIP slot2 hours=99999999999"))
        assertEquals(99999999999L, r.skipped[0].hours)
        assertBad("SKIP slot2 hours=99999999999999999999")
    }

    @Test
    fun saltReplyOfSlot7AndMgCl2OfSlot10Parse() {
        val a = SaltReply.parse(listOf("SALT slot7 hours=6 rh_mean=73.0 rh_span=0.4 temp_mean=25.0 reference=75.3", "slot7_rh_offset_pct=2.3"))
        assertEquals("slot7", (a.outcome as SaltReply.Outcome.Measured).measurement.name)
        assertEquals(CalibrationProposal("slot7_rh_offset_pct", "2.3"), a.proposal)
        val b = SaltReply.parse(listOf("BEGIN SALT", "SALT slot10 hours=6 rh_mean=31.0 rh_span=0.3 temp_mean=25.0 reference=32.8"))
        assertEquals("slot10", (b.outcome as SaltReply.Outcome.Measured).measurement.name)
        assertEquals(32.8, (b.outcome as SaltReply.Outcome.Measured).measurement.reference, 0.0)
    }

    @Test
    fun batchOfSlot7SendsGainThenOffsetThenConfig() {
        val sent = mutableListOf<String>()
        CalibrationBatch(
            listOf(CalibrationProposal("slot7_rh_gain", "1.066"), CalibrationProposal("slot7_rh_offset_pct", "0.8"))
        ).run({ command, callback -> sent.add(command); callback(ConsoleReply.Status("OK")) }, {})
        assertEquals(listOf("SET slot7_rh_gain=1.066", "SET slot7_rh_offset_pct=0.8", "CONFIG"), sent)
    }

    @Test
    fun batchStopsAtTheFirstErrAndExplainsReadOnly() {
        val sent = mutableListOf<String>()
        var result: CalibrationBatch.Result? = null
        CalibrationBatch(
            listOf(CalibrationProposal("slot7_rh_gain", "1.066"), CalibrationProposal("slot7_rh_offset_pct", "0.8"))
        ).run({ command, callback -> sent.add(command); callback(ConsoleReply.Status("ERR READ_ONLY")) }, { result = it })
        assertEquals(listOf("SET slot7_rh_gain=1.066"), sent)
        assertTrue(result!!.summary.startsWith("Set ble_console=2 to apply"))
    }

    @Test
    fun regressionGuardProposalKeysOfSlots6To10() {
        val r = CalibrationReply.parse(
            listOf(
                "REFERENCE onboard",
                "SENSOR slot9 hours=8 temp_offset=0.0 rh_offset=0.0 temp_diff=0.4 rh_diff=-2.1",
                "slot9_temp_offset_c=-0.4",
                "slot9_rh_offset_pct=2.1",
                "slot10_temp_offset_c=-5.5"
            )
        )
        assertEquals(
            listOf(CalibrationProposal("slot9_temp_offset_c", "-0.4"), CalibrationProposal("slot9_rh_offset_pct", "2.1")),
            r.proposals
        )
    }

    @Test
    fun chosenKeepsReplyOrderWithUsbProposal() {
        val state = com.rlcdpanel.phone.CalibrationState(
            reply = CalibrationReply.parse(fixtureLines()),
            selected = setOf("usb_temp_offset_c", "temp_offset_c")
        )
        assertEquals(listOf("temp_offset_c", "usb_temp_offset_c"), state.chosen.map { it.key })
    }

    @Test
    fun nonNumericUsbProposalThrows() {
        assertBad("usb_temp_offset_c=abc", listOf("REFERENCE onboard", "usb_temp_offset_c=abc"))
    }

    @Test
    fun usbProposalHasNoCardAndCountsAsProposal() {
        val usbOnly = CalibrationReply.parse(listOf("REFERENCE onboard", "usb_temp_offset_c=-0.8"))
        assertTrue(com.rlcdpanel.phone.CalibrationText.cardProposals(usbOnly).isEmpty())
        assertTrue(!com.rlcdpanel.phone.CalibrationText.hasNoProposal(usbOnly))
        assertTrue(com.rlcdpanel.phone.CalibrationText.hasNoProposal(CalibrationReply.parse(listOf("REFERENCE onboard"))))
        assertEquals(
            listOf("temp_offset_c"),
            com.rlcdpanel.phone.CalibrationText.cardProposals(CalibrationReply.parse(fixtureLines())).map { it.key }
        )
    }
}
