package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the macOS `BatteryForecastTests`: the same series give the same dates. */
class BatteryForecastTest {
    private val day = 86_400_000L
    private val start = PanelClock.parse("2026-09-01T12:00:00")!!

    private fun stamp(epoch: Long) = PanelClock.format(epoch)!!.replace('T', ' ')

    private fun row(dayOffset: Int, pct: Int?, sensor: Int = 1, base: Long = start): TailRow {
        val slots = MutableList(TailReply.SLOT_COUNT) { TailSensor(20.0, 50.0, null) }
        if (sensor in 1..TailReply.SLOT_COUNT) slots[sensor - 1] = TailSensor(20.0, 50.0, pct)
        return TailRow(stamp(base + dayOffset * day), null, null, null, TailSensor(20.0, 50.0, null), slots)
    }

    private fun config(text: String) = PanelConfig.parseText(text)

    @Test
    fun knownLineOnePercentPerWeek() {
        val rows = (0..4).map { row(it * 7, 80 - it) }
        val date = BatteryForecast.estimate(rows, 1, 10.0)
        assertNotNull(date)
        assertEquals((start + 70 * 7 * day).toDouble(), date!!.toDouble(), 60_000.0)
    }

    @Test
    fun tenDaySeriesReachesLimitOnDay70() {
        // 1 % per day from 80 %: the fit reaches 10 % on day 70, the same rule as the macOS knownLineOnePercentPerWeek.
        val rows = (0..9).map { row(it, 80 - it) }
        val date = BatteryForecast.estimate(rows, 1, 10.0)
        assertNotNull(date)
        assertEquals((start + 70 * day).toDouble(), date!!.toDouble(), 60_000.0)
    }

    @Test
    fun usesRowsAfterRiseOnly() {
        val old = (0..3).map { row(it * 7, 40 - it * 9) }
        val new = (0..3).map { row(28 + it * 7, 100 - it) }
        val date = BatteryForecast.estimate(old + new, 1, 10.0)
        assertNotNull(date)
        assertEquals((start + (28 + 90 * 7) * day).toDouble(), date!!.toDouble(), 60_000.0)
    }

    @Test
    fun nullForSixDays() {
        val rows = (0..6).map { row(it, 90 - it) }
        assertNull(BatteryForecast.estimate(rows, 1, 10.0))
    }

    @Test
    fun spanOfExactlySevenDaysCounts() {
        assertNotNull(BatteryForecast.estimate(listOf(row(0, 80), row(7, 76)), 1, 10.0))
    }

    @Test
    fun nullForRiseOfTwelvePercentWithoutLaterData() {
        val rows = listOf(row(0, 60), row(7, 50), row(14, 62))
        assertNull(BatteryForecast.estimate(rows, 1, 10.0))
    }

    @Test
    fun riseOfTenPercentStartsNewBattery() {
        val reset = listOf(row(0, 60), row(7, 50), row(14, 60), row(21, 55))
        val date = BatteryForecast.estimate(reset, 1, 10.0)
        assertNotNull(date)
        assertEquals((start + (14 + 7 * 10) * day).toDouble(), date!!.toDouble(), 60_000.0)
    }

    @Test
    fun riseOfNinePercentIsNoNewBattery() {
        val rows = listOf(row(0, 60), row(7, 50), row(14, 59), row(21, 49))
        // Least-squares line over the 4 rows: slope -84/245 % per day, mean 10.5 days / 54.5 %, limit 10 % at day 140.2917.
        assertEquals((start + 140.291667 * day).toDouble(), BatteryForecast.estimate(rows, 1, 10.0)!!.toDouble(), 60_000.0)
    }

    @Test
    fun nullForDropOfTwoPercent() {
        assertNull(BatteryForecast.estimate(listOf(row(0, 80), row(10, 79), row(20, 78)), 1, 10.0))
    }

    @Test
    fun nullForFlatOrRisingLine() {
        assertNull(BatteryForecast.estimate((0..4).map { row(it * 7, 80) }, 1, 10.0))
        assertNull(BatteryForecast.estimate((0..4).map { row(it * 7, 70 + it) }, 1, 10.0))
    }

    @Test
    fun nullForOnboardSensorAndBadSlots() {
        val rows = (0..4).map { row(it * 7, 80 - it * 5) }
        assertNull(BatteryForecast.estimate(rows, 0, 10.0))
        assertNull(BatteryForecast.estimate(rows, 11, 10.0))
        assertNull(BatteryForecast.estimate(rows, 1, Double.NaN))
    }

    @Test
    fun slotTenIsForecast() {
        val rows = (0..4).map { row(it * 7, 80 - it, sensor = 10) }
        assertNotNull(BatteryForecast.estimate(rows, 10, 10.0))
        assertNull(BatteryForecast.estimate(rows, 9, 10.0))
    }

    @Test
    fun limitMovesTheDate() {
        val rows = (0..4).map { row(it * 7, 80 - it) }
        assertTrue(BatteryForecast.estimate(rows, 1, 20.0)!! < BatteryForecast.estimate(rows, 1, 10.0)!!)
    }

    @Test
    fun rowsOlderThan60DaysAreIgnored() {
        val old = (0..3).map { row(it, 90 - it * 5) }
        val recent = (0..3).map { row(100 + it * 7, 70) }
        assertNull(BatteryForecast.estimate(old + recent, 1, 10.0))
    }

    @Test
    fun unsortedRowsAndGapsGiveTheSameDate() {
        val rows = (0..4).map { row(it * 7, 80 - it) }
        val a = BatteryForecast.estimate(rows, 1, 10.0)
        val b = BatteryForecast.estimate((rows + row(3, null)).reversed(), 1, 10.0)
        assertNotNull(a)
        assertEquals(a, b)
    }

    @Test
    fun reachedLimitGivesNewestRow() {
        val rows = (0..4).map { row(it * 7, 30 - it * 5) }
        assertEquals(start + 28 * day, BatteryForecast.estimate(rows, 1, 25.0))
    }

    @Test
    fun lineAcrossDstChangeKeepsTheDate() {
        val begin = PanelClock.parse("2026-10-12T12:00:00")!!
        // Local wall-clock stamps every 7 days at 12:00 cross the end of CEST on 2026-10-25, so the real step shrinks by 1 hour.
        val first = java.time.LocalDateTime.of(2026, 10, 12, 12, 0, 0)
        val rows = (0..4).map {
            val text = first.plusDays(it * 7L).toString().replace('T', ' ').let { t -> if (t.length == 16) "$t:00" else t }
            row(0, 80 - it).copy(timestamp = text)
        }
        val date = BatteryForecast.estimate(rows, 1, 10.0)
        // The 3 rows after the change are 1 hour closer, so the fit moves the date by about 0.9 day over 490 days. Stay below 2 days.
        assertEquals((begin + 70 * 7 * day).toDouble(), date!!.toDouble(), 2.0 * day)
    }

    @Test
    fun emptyAndUnparsableRowsGiveNull() {
        assertNull(BatteryForecast.estimate(emptyList(), 1, 10.0))
        val bad = (0..4).map { row(it * 7, 80 - it).copy(timestamp = "not a time") }
        assertNull(BatteryForecast.estimate(bad, 1, 10.0))
    }

    @Test
    fun limitFromConfigElseDefault() {
        assertEquals(25.0, BatteryForecast.limitPct(config("sensor_battery_limit_pct=25")), 0.0)
        assertEquals(10.0, BatteryForecast.limitPct(null), 0.0)
        assertEquals(10.0, BatteryForecast.limitPct(config("wake_interval_s=60")), 0.0)
    }

    @Test
    fun limitZeroUsesDefault() {
        assertEquals(10.0, BatteryForecast.limitPct(config("sensor_battery_limit_pct=0")), 0.0)
        assertEquals(10.0, BatteryForecast.limitPct(config("sensor_battery_limit_pct=Infinity")), 0.0)
    }

    @Test
    fun spanDaysOfRows() {
        assertEquals(0.0, BatteryForecast.spanDays(emptyList()), 0.0)
        assertEquals(3.0, BatteryForecast.spanDays(listOf(row(0, 80), row(3, 79))), 1e-9)
        assertTrue(BatteryForecast.needsLongerLog(listOf(row(0, 80), row(3, 79))))
        assertTrue(!BatteryForecast.needsLongerLog(listOf(row(0, 80), row(7, 76))))
    }

    @Test
    fun textShowsDateOrNotEnoughData() {
        assertEquals("Battery forecast: not enough data", BatteryForecast.text(null))
        assertEquals("Battery change near 2026-11-10", BatteryForecast.text(PanelClock.parse("2026-11-10T08:00:00")))
    }
}
