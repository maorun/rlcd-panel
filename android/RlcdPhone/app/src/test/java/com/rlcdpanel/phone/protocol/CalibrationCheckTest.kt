package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationCheckTest {
    private val t0 = 1_800_000_000_000L
    private val hour = 3_600_000L

    private class MemoryPrefs : CheckPrefs {
        val values = mutableMapOf<String, String?>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String?) { values[key] = value }
    }

    private class Clock(var now: Long) : () -> Long {
        override fun invoke(): Long = now
    }

    private val applied = listOf(CalibrationProposal("slot1_temp_offset_c", "-0.4"), CalibrationProposal("slot1_rh_offset_pct", "2.1"))
    private val old = mapOf("slot1_temp_offset_c" to "0.0", "slot1_rh_offset_pct" to "0.0")

    private fun store(prefs: MemoryPrefs = MemoryPrefs(), clock: Clock = Clock(t0)) = Triple(CalibrationCheckStore(prefs, clock), clock, prefs)

    private fun calib(tempDiff: String, rhDiff: String) = CalibrationReply.parse(
        listOf("REFERENCE onboard", "SENSOR slot1 hours=12 temp_offset=-0.4 rh_offset=2.1 temp_diff=$tempDiff rh_diff=$rhDiff")
    )

    private fun spread(tempDev: String, rhDev: String) = SpreadReply.parse(
        listOf(
            "SPREAD sensors=3 temp_median=21.0 rh_median=45.0",
            "SENSOR onboard temp_dev=0.0 rh_dev=0.0 OK",
            "SENSOR slot1 temp_dev=$tempDev rh_dev=$rhDev OK",
            "SENSOR slot2 temp_dev=0.1 rh_dev=0.5 OK"
        )
    )

    @Test
    fun storesSensorKeysOldAndNewValuesAndTime() {
        val (s, _, _) = store()
        s.recordApplied(CalibrationCheckRecord.Source.CALIB, 0, applied, old)
        val r = s.record("slot1")!!
        assertEquals(CalibrationCheckRecord.Source.CALIB, r.source)
        assertEquals(0, r.argument)
        assertEquals(
            listOf(CalibrationCheckRecord.Change("slot1_temp_offset_c", "0.0", "-0.4"), CalibrationCheckRecord.Change("slot1_rh_offset_pct", "0.0", "2.1")),
            r.changes
        )
        assertEquals(t0, r.appliedAtMs)
    }

    @Test
    fun goodResultAfterTheCalibWait() {
        val (s, clock, _) = store()
        s.recordApplied(CalibrationCheckRecord.Source.CALIB, 0, applied, old)
        clock.now = t0 + 25 * hour
        val result = CalibrationCheck.evaluate(s.record("slot1")!!, s.nowMs(), calib = calib("-0.2", "1.0"))
        assertEquals(CalibrationCheckResult.Good, result)
        assertEquals("Good", result.text)
    }

    @Test
    fun badResultNamesTheDeviation() {
        val (s, clock, _) = store()
        s.recordApplied(CalibrationCheckRecord.Source.SPREAD, 6, applied, old)
        clock.now = t0 + 7 * hour
        val r = s.record("slot1")!!
        val result = CalibrationCheck.evaluate(r, s.nowMs(), spread = spread("0.3", "-0.4"))
        assertEquals(CalibrationCheckResult.NotGood(0.3, -0.4), result)
        assertEquals("Not good: +0.3 °C / -0.4 %RH", result.text)
        assertEquals(CalibrationCheckResult.NotGood(0.1, 1.1), CalibrationCheck.evaluate(r, s.nowMs(), spread = spread("0.1", "1.1")))
    }

    @Test
    fun checkBeforeTheWaitIsTooEarly() {
        val (s, clock, _) = store()
        s.recordApplied(CalibrationCheckRecord.Source.CALIB, 0, applied, old)
        val r = s.record("slot1")!!
        val reply = calib("0.0", "0.0")
        assertEquals(CalibrationCheckResult.TooEarly(25), CalibrationCheck.evaluate(r, s.nowMs(), calib = reply))
        clock.now = t0 + (2.5 * hour).toLong()
        val result = CalibrationCheck.evaluate(r, s.nowMs(), calib = reply)
        assertEquals(CalibrationCheckResult.TooEarly(23), result)
        assertEquals("Too early: 23 h", result.text)
        clock.now = t0 + 25 * hour - 1
        assertEquals(CalibrationCheckResult.TooEarly(1), CalibrationCheck.evaluate(r, s.nowMs(), calib = reply))
        clock.now = t0 - 10 * hour
        assertEquals(CalibrationCheckResult.TooEarly(25), CalibrationCheck.evaluate(r, s.nowMs(), calib = reply))
    }

    @Test
    fun newBatchForTheSameSensorReplacesTheRecord() {
        val (s, clock, _) = store()
        s.recordApplied(CalibrationCheckRecord.Source.CALIB, 0, applied, old)
        s.recordApplied(CalibrationCheckRecord.Source.CALIB, 0, listOf(CalibrationProposal("slot2_temp_offset_c", "0.5")), mapOf("slot2_temp_offset_c" to "0.0"))
        assertEquals(listOf("slot1", "slot2"), s.records().map { it.sensor })
        clock.now = t0 + 3 * hour
        s.recordApplied(CalibrationCheckRecord.Source.SPREAD, 12, listOf(CalibrationProposal("slot1_rh_offset_pct", "1.5")), mapOf("slot1_rh_offset_pct" to "2.1"))
        val r = s.record("slot1")!!
        assertEquals(2, s.records().size)
        assertEquals(CalibrationCheckRecord.Source.SPREAD, r.source)
        assertEquals(12, r.argument)
        assertEquals(listOf(CalibrationCheckRecord.Change("slot1_rh_offset_pct", "2.1", "1.5")), r.changes)
        assertEquals(t0 + 3 * hour, r.appliedAtMs)
    }

    @Test
    fun secondApplyKeepsTheNewValueOfTheFirstAsOldValue() {
        val (s, _, _) = store()
        s.recordApplied(CalibrationCheckRecord.Source.CALIB, 0, applied, old)
        // the cached config still shows 0.0 for both keys
        s.recordApplied(CalibrationCheckRecord.Source.CALIB, 0, listOf(CalibrationProposal("slot1_temp_offset_c", "-0.2")), old)
        assertEquals(listOf(CalibrationCheckRecord.Change("slot1_temp_offset_c", "-0.4", "-0.2")), s.record("slot1")!!.changes)
    }

    @Test
    fun corruptStoredRecordGivesNoRecord() {
        val good = "slot1\tCALIB\t0\t1\tslot1_temp_offset_c,0.0,-0.4"
        val bad = listOf(
            "", "not a record", "slot1\tCALIB\t0\t1", "slot1\tOTHER\t0\t1\ta,b,c", "slot1\tCALIB\tx\t1\ta,b,c",
            "slot1\tCALIB\t0\tx\ta,b,c", "slot1\tCALIB\t0\t1\t", "slot1\tCALIB\t0\t1\ta,b", "slot1\tCALIB\t99\t1\ta,b,c",
            "slot12\tCALIB\t0\t1\ta,b,c", "slot4294967297\tCALIB\t0\t1\ta,b,c", "slot1\tCALIB\t0\t1\t,b,c", "slot1\tCALIB\t0\t1\ta,b,", "slot1\tSPREAD\t5\t1\ta,b,c"
        )
        for (text in bad) {
            val prefs = MemoryPrefs().apply { values[CalibrationCheckStore.KEY] = text }
            val (s, _, _) = store(prefs)
            assertTrue(text, s.records().isEmpty())
            assertNull(s.record("slot1"))
        }
        // two lines of one sensor: the last one wins
        val dup = MemoryPrefs().apply { values[CalibrationCheckStore.KEY] = "$good\n" + good.replace("CALIB\t0", "CALIB\t3") }
        assertEquals(listOf(3), store(dup).first.records().map { it.argument })
        // a good line next to a corrupt line survives
        val mixed = MemoryPrefs().apply { values[CalibrationCheckStore.KEY] = "junk\n$good" }
        assertEquals(listOf("slot1"), store(mixed).first.records().map { it.sensor })
        // a new record replaces the corrupt text
        val prefs = MemoryPrefs().apply { values[CalibrationCheckStore.KEY] = "not a record" }
        val (s, _, _) = store(prefs)
        s.recordApplied(CalibrationCheckRecord.Source.CALIB, 0, applied, old)
        assertEquals(listOf("slot1"), s.records().map { it.sensor })
    }

    @Test
    fun saltChecksHumidityAgainstReference() {
        val (s, clock, _) = store()
        s.recordApplied(CalibrationCheckRecord.Source.SALT, 1, listOf(CalibrationProposal("slot1_rh_offset_pct", "2.0")), mapOf("slot1_rh_offset_pct" to "0.0"))
        clock.now = t0 + 8 * hour
        val r = s.record("slot1")!!
        val good = SaltReply.parse(listOf("SALT slot1 hours=6 rh_mean=75.4 rh_span=0.3 temp_mean=20.0 reference=75.5"))
        assertEquals(CalibrationCheckResult.Good, CalibrationCheck.evaluate(r, s.nowMs(), salt = good))
        val bad = SaltReply.parse(listOf("SALT slot1 hours=6 rh_mean=77.0 rh_span=0.3 temp_mean=20.0 reference=75.5"))
        val result = CalibrationCheck.evaluate(r, s.nowMs(), salt = bad)
        assertEquals(CalibrationCheckResult.NotGood(null, 1.5), result)
        assertEquals("Not good: +1.5 %RH", result.text)
        val other = SaltReply.parse(listOf("SALT slot2 hours=6 rh_mean=75.4 rh_span=0.3 temp_mean=20.0 reference=75.5"))
        assertTrue(CalibrationCheck.evaluate(r, s.nowMs(), salt = other) is CalibrationCheckResult.Unavailable)
    }

    @Test
    fun calibBadResultAndMissingSensor() {
        val (s, clock, _) = store()
        s.recordApplied(CalibrationCheckRecord.Source.CALIB, 0, applied, old)
        clock.now = t0 + 25 * hour
        val r = s.record("slot1")!!
        assertEquals(CalibrationCheckResult.NotGood(0.3, 0.0), CalibrationCheck.evaluate(r, s.nowMs(), calib = calib("0.3", "0.0")))
        val other = CalibrationReply.parse(listOf("REFERENCE onboard", "SENSOR slot2 hours=12 temp_offset=0.0 rh_offset=0.0 temp_diff=0.0 rh_diff=0.0"))
        assertTrue(CalibrationCheck.evaluate(r, s.nowMs(), calib = other) is CalibrationCheckResult.Unavailable)
    }

    @Test
    fun skippedAndUnstableRepliesAreUnavailable() {
        val (s, clock, _) = store()
        s.recordApplied(CalibrationCheckRecord.Source.SPREAD, 6, applied, old)
        clock.now = t0 + 7 * hour
        val r = s.record("slot1")!!
        assertTrue(CalibrationCheck.evaluate(r, s.nowMs(), spread = SpreadReply.parse(listOf("SKIP sensors=2"))) is CalibrationCheckResult.Unavailable)
        s.recordApplied(CalibrationCheckRecord.Source.SALT, 1, listOf(CalibrationProposal("slot1_rh_offset_pct", "2.0")), old)
        clock.now = t0 + 20 * hour
        val salt = s.record("slot1")!!
        for (line in listOf("SKIP slot1 hours=3", "UNSTABLE slot1 span=2.5")) {
            assertTrue(line, CalibrationCheck.evaluate(salt, s.nowMs(), salt = SaltReply.parse(listOf(line))) is CalibrationCheckResult.Unavailable)
        }
    }

    @Test
    fun commandLinesAndKeyNames() {
        val s = CalibrationCheckRecord.Source.values()
        assertEquals("SPREAD", CalibrationCheckRecord("slot1", s[1], 6, emptyList(), 0).commandLine)
        assertEquals("SPREAD 12", CalibrationCheckRecord("slot1", s[1], 12, emptyList(), 0).commandLine)
        assertEquals("SALT 3", CalibrationCheckRecord("slot3", s[2], 3, emptyList(), 0).commandLine)
        assertEquals("CALIB 0", CalibrationCheckRecord("slot1", s[0], 0, emptyList(), 0).commandLine)
        assertEquals("onboard", CalibrationCheckRecord.sensorName("temp_offset_c"))
        assertEquals("slot10", CalibrationCheckRecord.sensorName("slot10_rh_offset_pct"))
        assertNull(CalibrationCheckRecord.sensorName("slot11_rh_offset_pct"))
        assertNull(CalibrationCheckRecord.sensorName("usb_temp_offset_c"))
        assertNull(CalibrationCheckRecord.sensorName("slot1_rh_gain"))
        assertNull(CalibrationCheckRecord.sensorName("slot0_rh_offset_pct"))
        assertNotNull(CalibrationCheckRecord.sensorNumber("onboard"))
    }

    @Test
    fun waitDependsOnTheCommand() {
        fun record(source: CalibrationCheckRecord.Source, argument: Int) =
            CalibrationCheckRecord("slot1", source, argument, listOf(CalibrationCheckRecord.Change("slot1_temp_offset_c", "0.0", "-0.4")), t0)
        assertEquals(7, record(CalibrationCheckRecord.Source.SALT, 1).waitHours)
        assertEquals(7, record(CalibrationCheckRecord.Source.SPREAD, 6).waitHours)
        assertEquals(25, record(CalibrationCheckRecord.Source.SPREAD, 24).waitHours)
        assertEquals(25, record(CalibrationCheckRecord.Source.CALIB, 0).waitHours)
        assertEquals(1, CalibrationCheck.hoursToWait(record(CalibrationCheckRecord.Source.SPREAD, 24), t0 + 24 * hour))
        assertEquals(null, CalibrationCheck.hoursToWait(record(CalibrationCheckRecord.Source.SPREAD, 24), t0 + 25 * hour))
        assertEquals(1, CalibrationCheck.hoursToWait(record(CalibrationCheckRecord.Source.SALT, 1), t0 + 6 * hour))
    }

    @Test
    fun onlyTheChangedValuesAreJudged() {
        val (s, clock, _) = store()
        s.recordApplied(CalibrationCheckRecord.Source.SPREAD, 6, listOf(CalibrationProposal("slot1_temp_offset_c", "-0.4")), old)
        clock.now = t0 + 7 * hour
        val r = s.record("slot1")!!
        assertEquals(CalibrationCheckResult.Good, CalibrationCheck.evaluate(r, s.nowMs(), spread = spread("0.1", "2.0")))
        assertEquals(CalibrationCheckResult.NotGood(0.3, null), CalibrationCheck.evaluate(r, s.nowMs(), spread = spread("0.3", "2.0")))
    }
}
