package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.ChartLimit
import com.rlcdpanel.watch.protocol.PanelHistoryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitLineGeometryTest {
    private fun row(age: Int, t: Double?, h: Double? = null, sensor: Int = 0) = PanelHistoryRow(sensor, age, t, h, null)

    @Test
    fun limitLineSitsAtTheSameYAsACurveValue() {
        val rows = listOf(row(1, 20.0), row(0, 30.0))
        val y = HistoryChartGeometry.segments(rows, 0, { it.temperatureC }, 20.0, 30.0).single().last().second
        assertEquals(y, LimitLineGeometry.y(30.0, 20.0, 30.0), 0f)
        assertEquals(1f, LimitLineGeometry.y(20.0, 20.0, 30.0), 0f)
    }

    @Test
    fun yUsesALiteralScaleAndClampsOutsideTheRange() {
        assertEquals(0f, LimitLineGeometry.y(30.0, 20.0, 30.0), 0f)
        assertEquals(0.5f, LimitLineGeometry.y(25.0, 20.0, 30.0), 1e-6f)
        assertEquals(0f, LimitLineGeometry.y(99.0, 20.0, 30.0), 0f)
        assertEquals(1f, LimitLineGeometry.y(-99.0, 20.0, 30.0), 0f)
    }

    @Test
    fun rowsOutsideTheDayAreNotDrawn() {
        val rows = listOf(row(24, 1.0), row(-1, 1.0), row(0, 1.0))
        assertEquals(listOf(1), HistoryChartGeometry.segments(rows, 0, { it.temperatureC }, 1.0, 2.0).map { it.size })
    }

    @Test
    fun flatRangeUsesTheSameScaleAsTheCurve() {
        val y = HistoryChartGeometry.segments(listOf(row(0, 25.0)), 0, { it.temperatureC }, 25.0, 25.0).single().single().second
        assertEquals(y, LimitLineGeometry.y(25.0, 25.0, 25.0), 0f)
        assertEquals(0.5f, y, 1e-6f)
    }

    @Test
    fun newestHourIsRightAndOldestIsLeft() {
        val segments = HistoryChartGeometry.segments(listOf(row(0, 1.0), row(23, 2.0)), 0, { it.temperatureC }, 1.0, 2.0)
        assertEquals(0f, segments.first().single().first, 0f)
        assertEquals(1f, segments.last().single().first, 0f)
    }

    @Test
    fun missingValueOrHourBreaksTheCurve() {
        val rows = listOf(row(0, 1.0), row(1, null), row(2, 1.0), row(4, 1.0), row(5, 1.0), row(0, 9.0, sensor = 3))
        val segments = HistoryChartGeometry.segments(rows, 0, { it.temperatureC }, 1.0, 2.0)
        assertEquals(listOf(2, 1, 1), segments.map { it.size })
    }

    @Test
    fun rangeIncludesLimitsAndIsNullWithoutData() {
        val limits = listOf(ChartLimit("mold_alert_limit_rh", "Mold", 70.0, ChartLimit.Axis.HUMIDITY))
        assertEquals(40.0 to 70.0, HistoryChartGeometry.range(listOf(40.0, 50.0), limits))
        assertTrue(HistoryChartGeometry.range(emptyList(), limits) == null)
        val low = listOf(ChartLimit("dry_alert_limit_rh", "Dry", 30.0, ChartLimit.Axis.HUMIDITY))
        assertEquals(30.0 to 50.0, HistoryChartGeometry.range(listOf(50.0), low))
    }

    @Test
    fun legendListsLabelsAndValues() {
        val limits = listOf(
            ChartLimit("mold_alert_limit_rh", "Mold", 70.0, ChartLimit.Axis.HUMIDITY),
            ChartLimit("dry_alert_limit_rh", "Dry", 30.0, ChartLimit.Axis.HUMIDITY)
        )
        assertEquals("Mold 70.0 %RH, Dry 30.0 %RH", LimitLineGeometry.legend(limits, "%RH"))
    }
}
