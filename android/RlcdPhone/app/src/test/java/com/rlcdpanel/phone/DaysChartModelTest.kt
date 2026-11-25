package com.rlcdpanel.phone

import com.rlcdpanel.phone.protocol.DaysRow
import com.rlcdpanel.phone.protocol.DaysReply
import com.rlcdpanel.phone.protocol.DaysSensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DaysChartModelTest {
    private val none = DaysSensor(null, null, null, null, null)

    private fun row(date: String, onboard: DaysSensor, slot1: DaysSensor = none) =
        DaysRow(date, List(DaysReply.SENSOR_COUNT) { if (it == 0) onboard else if (it == 1) slot1 else none })

    @Test
    fun scaleKeepsMinimumSpanInsideRange() {
        val series = DaysSeries(listOf(99.0, null), listOf(99.5, null))
        assertEquals(95.0 to 100.0, series.scale(DaysChartModel.RH_MIN_SPAN, 0.0, DaysChartModel.RH_MAX))
        assertEquals(10.0 to 14.0, DaysSeries(listOf(10.0, 12.0), listOf(14.0, 13.0)).scale(2.0))
        assertNull(DaysSeries(listOf(null), listOf(null)).scale(2.0))
    }

    @Test
    fun effectiveSensorFallsBackToFirstSensorWithData() {
        val rows = listOf(row("2026-01-01", none, DaysSensor(1.0, 2.0, null, null, null)))
        assertEquals(1, DaysChartModel.effectiveSensor(rows, 0))
        assertEquals(1, DaysChartModel.effectiveSensor(rows, 1))
        assertNull(DaysChartModel.effectiveSensor(emptyList(), 0))
    }

    @Test
    fun seriesFollowRowOrder() {
        val rows = listOf(
            row("2026-01-01", DaysSensor(1.0, 2.0, 30.0, 40.0, 0)),
            row("2026-01-02", DaysSensor(3.0, 4.0, null, null, 5))
        )
        assertEquals(listOf(1.0, 3.0), DaysChartModel.temperature(rows, 0).low)
        assertEquals(listOf(40.0, null), DaysChartModel.humidity(rows, 0).high)
        assertEquals(listOf(0, 5), DaysChartModel.mold(rows, 0))
    }
}
