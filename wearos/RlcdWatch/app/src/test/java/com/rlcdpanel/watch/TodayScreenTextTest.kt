package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.TodayRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayScreenTextTest {
    private val row = TodayRow(0, "onboard", 5, 1.0, 2.0, 1.5, 1.0, 2.0, 1.5, 3, 0, 2)

    @Test
    fun heatTextForTwoHours() {
        assertEquals("Heat 2 h", TodayScreenText.heat(row))
        assertTrue(TodayScreenText.heatWarning(row))
    }

    @Test
    fun noHeatTextForNull() {
        val none = row.copy(heatHours = null)
        assertNull(TodayScreenText.heat(none))
        assertFalse(TodayScreenText.heatWarning(none))
    }

    @Test
    fun noWarningForZero() {
        val zero = row.copy(heatHours = 0)
        assertEquals("Heat 0 h", TodayScreenText.heat(zero))
        assertFalse(TodayScreenText.heatWarning(zero))
    }
}
