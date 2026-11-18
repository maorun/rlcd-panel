package com.rlcdpanel.phone.protocol

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Same input values as `TwoPointCalibrationTests` of the macOS app. */
class TwoPointCalibrationTest {
    private fun reply(name: String, mean: Double, reference: Double): SaltReply = SaltReply.parse(
        listOf(
            "SALT $name hours=6 rh_mean=${String.format(Locale.ROOT, "%.1f", mean)} rh_span=0.2 " +
                "temp_mean=25.0 reference=${String.format(Locale.ROOT, "%.1f", reference)}"
        )
    )

    private fun failure(na: SaltReply?, mg: SaltReply?, current: Map<String, String>?): TwoPointCalibration.Failure {
        try {
            TwoPointCalibration.compute(na, mg, current)
        } catch (f: TwoPointCalibration.Failure) {
            return f
        }
        fail("no failure")
        throw AssertionError()
    }

    @Test
    fun twoPointResultForKnownRawMeans() {
        val r = TwoPointCalibration.compute(reply("slot2", 70.0, 75.4), reply("slot2", 30.0, 32.8), emptyMap())
        assertEquals("slot2_rh_gain", r.gainKey)
        assertEquals("slot2_rh_offset_pct", r.offsetKey)
        assertEquals("1.066", r.gain)
        assertEquals("0.8", r.offset)
        assertEquals(70.0, r.rawNaCl, 0.0)
        assertEquals(30.0, r.rawMgCl2, 0.0)
        assertEquals(
            listOf(CalibrationProposal("slot2_rh_gain", "1.066"), CalibrationProposal("slot2_rh_offset_pct", "0.8")),
            r.proposals
        )
    }

    @Test
    fun rawMeansUseTheActiveGainAndOffset() {
        val r = TwoPointCalibration.compute(
            reply("onboard", 73.0, 75.3), reply("onboard", 31.0, 32.8),
            mapOf("rh_gain" to "1.05", "rh_offset_pct" to "-2.0")
        )
        assertEquals(71.428571, r.rawNaCl, 0.001)
        assertEquals(31.428571, r.rawMgCl2, 0.001)
        assertEquals("rh_gain", r.gainKey)
        assertEquals("rh_offset_pct", r.offsetKey)
        assertEquals("1.062", r.gain)
    }

    @Test
    fun gainOutsideTheKeyRangeGivesAnErrorAndNoSet() {
        assertTrue(failure(reply("onboard", 60.0, 75.3), reply("onboard", 30.0, 32.8), emptyMap()) is TwoPointCalibration.Failure.GainOutOfRange)
        val f = failure(reply("onboard", 60.0, 72.0), reply("onboard", 30.0, 36.0), emptyMap())
        assertEquals(TwoPointCalibration.Failure.GainOutOfRange(1.2), f)
        assertEquals("Gain 1.200 is outside 0.90 to 1.10, no SET sent", f.text)
    }

    @Test
    fun gainOf112GivesAnErrorAndNoCommand() {
        // raw means 60 and 30, references 72.2 and 38.6: slope 1.12
        val f = failure(reply("slot1", 60.0, 72.2), reply("slot1", 30.0, 38.6), emptyMap())
        assertEquals("Gain 1.120 is outside 0.90 to 1.10, no SET sent", f.text)
    }

    @Test
    fun resultsOfDifferentSensorsGiveAnError() {
        val f = failure(reply("slot1", 70.0, 75.3), reply("slot2", 30.0, 32.8), emptyMap())
        assertEquals(TwoPointCalibration.Failure.DifferentSensors, f)
        assertEquals("The two checks belong to different sensors", f.text)
    }

    @Test
    fun otherFailures() {
        val na = reply("slot1", 70.0, 75.3)
        assertEquals(TwoPointCalibration.Failure.NotMeasured, failure(na, null, emptyMap()))
        assertEquals(TwoPointCalibration.Failure.NotMeasured, failure(null, na, emptyMap()))
        assertEquals(
            TwoPointCalibration.Failure.NotMeasured,
            failure(na, SaltReply.parse(listOf("SKIP slot1 hours=3")), emptyMap())
        )
        assertEquals("Check both salts first", TwoPointCalibration.Failure.NotMeasured.text)
        assertEquals(TwoPointCalibration.Failure.ConfigUnknown, failure(na, reply("slot1", 30.0, 32.8), null))
        assertEquals(TwoPointCalibration.Failure.PointsTooClose, failure(na, reply("slot1", 70.5, 32.8), emptyMap()))
    }

    @Test
    fun offsetOutOfRangeAndBadActiveValues() {
        val f = failure(reply("onboard", 70.0, 85.0), reply("onboard", 30.0, 45.5), emptyMap())
        assertTrue(f is TwoPointCalibration.Failure.OffsetOutOfRange)
        assertTrue(f.text.endsWith("is outside -10.0 to 10.0, no SET sent"))
        val ok1 = reply("onboard", 70.0, 75.3)
        val ok2 = reply("onboard", 30.0, 32.8)
        assertEquals(TwoPointCalibration.Failure.BadActiveValues, failure(ok1, ok2, mapOf("rh_gain" to "abc")))
        assertEquals(TwoPointCalibration.Failure.BadActiveValues, failure(ok1, ok2, mapOf("rh_gain" to "0")))
    }

    @Test
    fun clampedMeanGivesAnError() {
        assertEquals(
            TwoPointCalibration.Failure.ClampedSample,
            failure(reply("onboard", 100.0, 75.3), reply("onboard", 30.0, 32.8), emptyMap())
        )
        assertEquals(
            TwoPointCalibration.Failure.ClampedSample,
            failure(reply("onboard", 70.0, 75.3), reply("onboard", 0.0, 32.8), emptyMap())
        )
    }

    @Test
    fun resultGoesThroughTheBatchInOrder() {
        val r = TwoPointCalibration.compute(reply("slot2", 70.0, 75.4), reply("slot2", 30.0, 32.8), emptyMap())
        val sent = mutableListOf<String>()
        CalibrationBatch(r.proposals).run(
            { command, callback -> sent.add(command); callback(ConsoleReply.Status("OK")) },
            {}
        )
        assertEquals(listOf("SET slot2_rh_gain=1.066", "SET slot2_rh_offset_pct=0.8", "CONFIG"), sent)
    }

    @Test
    fun smallNegativeOffsetPrintsWithoutNegativeZero() {
        // slope 1.000, residuals -0.04 at both points: the offset rounds to -0.0
        val na = SaltReply.parse(listOf("SALT onboard hours=6 rh_mean=70.0 rh_span=0.2 temp_mean=25.0 reference=69.96"))
        val mg = SaltReply.parse(listOf("SALT onboard hours=6 rh_mean=30.0 rh_span=0.2 temp_mean=25.0 reference=29.96"))
        val r = TwoPointCalibration.compute(na, mg, emptyMap())
        assertEquals("1.000", r.gain)
        assertEquals("0.0", r.offset)
    }

    @Test
    fun slot7AndSlot10KeysAreUsed() {
        assertEquals("slot7_rh_gain", TwoPointCalibration.compute(reply("slot7", 70.0, 75.4), reply("slot7", 30.0, 32.8), emptyMap()).gainKey)
        assertEquals("slot10_rh_offset_pct", TwoPointCalibration.compute(reply("slot10", 70.0, 75.4), reply("slot10", 30.0, 32.8), emptyMap()).offsetKey)
    }

    @Test
    fun unknownSensorNameGivesItsOwnError() {
        assertEquals(TwoPointCalibration.Failure.UnknownSensor, failure(reply("slot11", 70.0, 75.4), reply("slot11", 30.0, 32.8), emptyMap()))
    }

    @Test
    fun negativeOffsetKeepsItsSign() {
        // slope 1.000, both residuals -0.3
        val r = TwoPointCalibration.compute(reply("onboard", 70.1, 69.8), reply("onboard", 30.1, 29.8), emptyMap())
        assertEquals("1.000", r.gain)
        assertEquals("-0.3", r.offset)
    }

    @Test
    fun evaluationWaitsWithoutShowingAnErrorWhileACheckIsMissing() {
        val na = reply("slot1", 70.0, 75.3)
        assertEquals(TwoPointCalibration.Evaluation(), TwoPointCalibration.evaluate(na, null, emptyMap()))
        assertEquals(TwoPointCalibration.Evaluation(), TwoPointCalibration.evaluate(null, na, null))
        val skipped = SaltReply.parse(listOf("SKIP slot1 hours=3"))
        assertEquals("Check both salts first", TwoPointCalibration.evaluate(na, skipped, emptyMap()).error)
        assertEquals(
            "Read the config first: the active gain and offset are unknown",
            TwoPointCalibration.evaluate(na, reply("slot1", 30.0, 32.8), null).error
        )
        val ok = TwoPointCalibration.evaluate(na, reply("slot1", 30.0, 32.8), emptyMap())
        assertTrue(ok.result != null && ok.error == null)
    }
}
