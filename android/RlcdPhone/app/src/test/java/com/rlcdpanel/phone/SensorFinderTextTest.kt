package com.rlcdpanel.phone

import com.rlcdpanel.phone.finder.SignalSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorFinderTextTest {
    @Test
    fun seriesWithoutStatsWaits() {
        assertTrue(SensorFinderText.series(null).contains("wartet"))
    }

    @Test
    fun seriesTextShowsValuesAndMissedWindows() {
        val stats = SignalSeries.Stats("AA:BB:CC:DD:EE:01", -80, -70.0, -60, 3, 1, 4)
        val text = SensorFinderText.series(stats)
        assertTrue(text, text.contains("(3)"))
        assertTrue(text, text.contains("min -80"))
        assertTrue(text, text.contains("max -60"))
        assertTrue(text, text.contains("1 von 4"))
    }

    @Test
    fun stopTextOnlyForEndsThatNeedAnExplanation() {
        assertNull(SensorFinderText.seriesStop(null))
        assertNull(SensorFinderText.seriesStop(SignalSeries.StopReason.USER))
        assertNull(SensorFinderText.seriesStop(SignalSeries.StopReason.SCREEN_STOPPED))
        assertEquals("Serie nach 10 Minuten beendet", SensorFinderText.seriesStop(SignalSeries.StopReason.TIMEOUT))
        assertTrue(SensorFinderText.seriesStop(SignalSeries.StopReason.PERMISSION_LOST)!!.contains("Berechtigung"))
        assertTrue(SensorFinderText.seriesStop(SignalSeries.StopReason.SCAN_FAILED)!!.contains("fehlgeschlagen"))
    }
}
