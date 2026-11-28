package com.rlcdpanel.phone

import com.rlcdpanel.phone.protocol.DaysNoise
import com.rlcdpanel.phone.protocol.DaysReply
import com.rlcdpanel.phone.protocol.DaysRow
import com.rlcdpanel.phone.protocol.DaysSensor
import com.rlcdpanel.phone.protocol.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DaysNoiseModelTest {
    private val none = DaysSensor(null, null, null, null, null)

    private fun row(date: String, noise: DaysNoise) = DaysRow(date, List(DaysReply.SENSOR_COUNT) { none }, noise)

    /** Rows of the v7 fixture with full noise fields on the first and the third day. */
    private fun fixtureRows(): List<DaysRow> {
        val lines = Fixtures.file("days_reply_v7_sample.txt").readLines().filter { !it.startsWith("CRC32") }
        val first = 1 + 6 * DaysReply.SENSOR_COUNT
        fun full(line: String, vararg v: String): String =
            line.split(",").toMutableList().also { c -> v.forEachIndexed { i, x -> c[first + i] = x } }.joinToString(",")
        return DaysReply.parse(
            listOf(lines[0], lines[1], full(lines[2], "30", "60", "72", "20", "9"), lines[3], full(lines[4], "34", "50", "50", "8", "1"))
        )
    }

    @Test
    fun fixtureWithoutNoiseHasNoData() {
        val lines = Fixtures.file("days_reply_v7_sample.txt").readLines().filter { !it.startsWith("CRC32") }
        val rows = DaysReply.parse(lines)
        assertFalse(DaysNoiseModel.hasData(rows))
        assertNull(DaysNoiseModel.scale(rows))
        assertNull(DaysNoiseModel.rangeText(rows))
        assertEquals(listOf<Double?>(null, null, null), DaysNoiseModel.peaks(rows))
        assertEquals(listOf<Double?>(null, null, null), DaysNoiseModel.bars(rows).low)
        rows.forEach { assertEquals("--", DaysNoiseModel.hourLabel(it.noise)) }
    }

    @Test
    fun fullAndEmptyFieldsGiveBarsMarksAndGaps() {
        val rows = fixtureRows()
        assertTrue(DaysNoiseModel.hasData(rows))
        val bars = DaysNoiseModel.bars(rows)
        assertEquals(listOf<Double?>(30.0, null, 34.0), bars.low)
        assertEquals(listOf<Double?>(60.0, null, 50.0), bars.high)
        assertEquals(listOf<Double?>(72.0, null, 50.0), DaysNoiseModel.peaks(rows))
        assertEquals(listOf("20", "--", "08"), DaysNoiseModel.days(rows).map { DaysNoiseModel.hourLabel(it) })
    }

    @Test
    fun scaleCoversBarsAndPeaks() {
        assertEquals(30.0 to 72.0, DaysNoiseModel.scale(fixtureRows()))
        assertEquals("30 bis 72 dB", DaysNoiseModel.rangeText(fixtureRows()))
    }

    @Test
    fun scaleKeepsMinimumSpan() {
        val rows = listOf(row("2026-01-01", DaysNoise(40, 42, 44, 3, 0)))
        assertEquals(37.0 to 47.0, DaysNoiseModel.scale(rows))
    }

    @Test
    fun dayWithoutPairKeepsItsPeakMark() {
        val rows = listOf(row("2026-01-01", DaysNoise(minDb = 40, peakDb = 55, peakHour = 6)), row("2026-01-02", DaysNoise(maxDb = 70)))
        val bars = DaysNoiseModel.bars(rows)
        assertEquals(listOf<Double?>(null, null), bars.low)
        assertEquals(listOf<Double?>(null, null), bars.high)
        assertEquals(listOf<Double?>(55.0, null), DaysNoiseModel.peaks(rows))
        assertEquals(40.0 to 70.0, DaysNoiseModel.scale(rows))
        assertEquals("06", DaysNoiseModel.hourLabel(rows[0].noise))
    }

    @Test
    fun peakHourZeroIsNotAGap() {
        assertEquals("00", DaysNoiseModel.hourLabel(DaysNoise(peakHour = 0)))
    }

    @Test
    fun dayWithMinimumAboveMaximumIsAGap() {
        val rows = listOf(row("2026-01-01", DaysNoise(70, 40, 55, 6, 1)))
        assertEquals(listOf<Double?>(null), DaysNoiseModel.bars(rows).low)
        assertEquals(listOf<Double?>(55.0), DaysNoiseModel.peaks(rows))
    }

    @Test
    fun hourAndEventsWithoutLevelGiveNoChart() {
        assertFalse(DaysNoiseModel.hasData(listOf(row("2026-01-01", DaysNoise(peakHour = 5, eventsSum = 3)))))
        assertTrue(DaysNoiseModel.hasData(listOf(row("2026-01-01", DaysNoise(peakDb = 50)))))
    }
}
