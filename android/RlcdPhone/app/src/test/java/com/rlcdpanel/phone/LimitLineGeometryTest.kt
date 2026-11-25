package com.rlcdpanel.phone

import com.rlcdpanel.phone.protocol.ChartLimit
import com.rlcdpanel.phone.protocol.HistoryPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class LimitLineGeometryTest {
    @Test
    fun limitLineSitsAtTheSameYAsACurveValue() {
        val points = listOf(HistoryPoint(0L, 20.0), HistoryPoint(1000L, 30.0))
        val y = HistoryChartGeometry.segments(points, 0L, 1000L, 20.0, 30.0).single().last().second
        assertEquals(y, LimitLineGeometry.y(30.0, 20.0, 30.0), 0f)
        assertEquals(1f, LimitLineGeometry.y(20.0, 20.0, 30.0), 0f)
    }

    @Test
    fun flatRangeUsesTheSameScaleAsTheCurve() {
        val points = listOf(HistoryPoint(0L, 25.0))
        val y = HistoryChartGeometry.segments(points, 0L, 1000L, 25.0, 25.0).single().single().second
        assertEquals(y, LimitLineGeometry.y(25.0, 25.0, 25.0), 0f)
        assertEquals(0.5f, y, 1e-6f)
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
