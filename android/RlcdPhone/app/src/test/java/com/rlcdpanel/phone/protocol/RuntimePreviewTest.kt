package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePreviewTest {
    private fun schedule(wake: Int, night: Int = 0) = RuntimePreview.Schedule(wake, night, 22, 7)

    private fun status(runtime: RuntimeState?, awake: AwakeState?, quiet: QuietHoursState? = null) =
        StatusReply(emptyList(), null, null, emptyList(), runtime = runtime, awake = awake, quiet = quiet)

    @Test
    fun doubledWakeIntervalGivesTheMacosValue() {
        // macOS RuntimePreviewTests: 400 * (120 + 6) / (60 + 6) = 763.6
        val h = RuntimePreview.hours(400.0, 6000, 24, schedule(60), schedule(120))
        assertEquals(764, h)
        val st = status(RuntimeState(400, 24, 3.5, 0), AwakeState(6400, 6000, 9000, 24))
        assertEquals(764, RuntimePreview.hours(mapOf("wake_interval_s" to "60"), mapOf("wake_interval_s" to "120"), st))
    }

    @Test
    fun nightIntervalCountsOnlyForTheQuietHours() {
        assertEquals(9, RuntimePreview.nightHours(22, 7))
        assertEquals(5, RuntimePreview.nightHours(1, 6))
        assertEquals(0, RuntimePreview.nightHours(5, 5))
        val awake = 6.0
        val mixed = RuntimePreview.drainRate(schedule(60, 600), awake)!!
        assertEquals((15.0 / (60 + awake) + 9.0 / (600 + awake)) / 24.0, mixed, 1e-12)
        assertEquals(1.0 / (60 + awake), RuntimePreview.drainRate(schedule(60), awake)!!, 1e-12)
        val base = RuntimePreview.hours(400.0, 6000, 24, schedule(60), schedule(60))!!
        val night = RuntimePreview.hours(400.0, 6000, 24, schedule(60), schedule(60, 600))!!
        assertTrue(night > base)
        // 400 * (1 / 66) / ((15 / 66 + 9 / 606) / 24) = 600.7
        assertEquals(601, night)
        // An empty quiet window gives the night interval no hour.
        val noWindow = RuntimePreview.Schedule(60, 600, 5, 5)
        assertEquals(base, RuntimePreview.hours(400.0, 6000, 24, schedule(60), noWindow))
    }

    @Test
    fun missingRuntimeOrZeroAwakeSamplesGiveNull() {
        assertNull(RuntimePreview.hours(null, 6000, 24, schedule(60), schedule(120)))
        assertNull(RuntimePreview.hours(400.0, null, 0, schedule(60), schedule(120)))
        val noRuntime = status(RuntimeState(null, 0, null, 0), AwakeState(6400, 6000, 9000, 24))
        assertNull(RuntimePreview.hours(emptyMap(), mapOf("wake_interval_s" to "120"), noRuntime))
        val noAwake = status(RuntimeState(400, 24, 3.5, 0), AwakeState(null, null, null, 0))
        assertNull(RuntimePreview.hours(emptyMap(), mapOf("wake_interval_s" to "120"), noAwake))
        // A mean without samples is no measurement: the firmware constant of 10 s applies, like in the macOS model.
        val noSamples = status(RuntimeState(400, 24, 3.5, 0), AwakeState(6400, 6000, 9000, 0))
        // 400 * (120 + 10) / (60 + 10) = 742.9
        assertEquals(743, RuntimePreview.hours(emptyMap(), mapOf("wake_interval_s" to "120"), noSamples))
        assertNull(RuntimePreview.hours(emptyMap(), mapOf("wake_interval_s" to "120"), null))
        assertNull(RuntimePreview.hours(emptyMap(), mapOf("wake_interval_s" to "120"), status(null, null)))
        assertEquals("Laufzeit --", RuntimePreview.text(null))
        assertEquals("Laufzeit ca. 764 h", RuntimePreview.text(764))
    }

    @Test
    fun invalidDraftValueGivesNull() {
        val st = status(RuntimeState(400, 24, 3.5, 0), AwakeState(6400, 6000, 9000, 24))
        val config = mapOf("wake_interval_s" to "60")
        assertNotNull(RuntimePreview.hours(config, mapOf("wake_interval_s" to "120"), st))
        assertNull(RuntimePreview.hours(config, mapOf("wake_interval_s" to "abc"), st))
        assertNull(RuntimePreview.hours(config, mapOf("wake_interval_s" to ""), st))
        assertNull(RuntimePreview.hours(config, mapOf("wake_interval_s" to "29"), st))
        assertNull(RuntimePreview.hours(config, mapOf("wake_interval_s" to "901"), st))
        assertNull(RuntimePreview.hours(config, mapOf("night_wake_interval_s" to "30"), st))
        assertNull(RuntimePreview.hours(config, mapOf("quiet_hours_start_hour" to "24"), st))
        assertNull(RuntimePreview.hours(config, mapOf("wake_interval_s" to "99999999999"), st))
    }

    @Test
    fun cappedRuntimeAndFewSamplesFollowTheFirmwareRules() {
        assertNull(RuntimePreview.hours(2388.0, 6000, 24, schedule(60), schedule(120)))
        assertEquals(2388, RuntimePreview.hours(2000.0, 6000, 24, schedule(30), schedule(900)))
        val constant = RuntimePreview.hours(400.0, 10000, 24, schedule(60), schedule(120))
        assertEquals(constant, RuntimePreview.hours(400.0, 3000, 7, schedule(60), schedule(120)))
        assertEquals(constant, RuntimePreview.hours(400.0, 2000, 24, schedule(60), schedule(120)))
    }

    @Test
    fun quietWindowComesFromTheDraftOverTheQuietLine() {
        val st = status(RuntimeState(400, 24, 3.5, 0), AwakeState(6400, 6000, 9000, 24), QuietHoursState(1, 6, null, 300))
        val config = mapOf("wake_interval_s" to "60", "night_wake_interval_s" to "300")
        val same = RuntimePreview.hours(config, emptyMap(), st)
        assertEquals(400, same)
        // A draft window that differs from the current one changes the result.
        // Current window 1 to 6 (5 night hours), draft window 20 to 6 (10 night hours), awake 6 s: 400 * 0.30422 / 0.24480 = 497.
        assertEquals(497, RuntimePreview.hours(config, mapOf("quiet_hours_start_hour" to "20"), st))
    }
}
