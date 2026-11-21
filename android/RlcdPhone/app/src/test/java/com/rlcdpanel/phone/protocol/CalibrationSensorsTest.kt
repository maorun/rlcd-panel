package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationSensorsTest {
    private fun row(slot: Int, state: String) = SensorHealth(
        slot, "S$slot", -60.0, 80.0, 10.0, state, "WOIO", null, SensorHealth.Verdict.GOOD
    )

    @Test
    fun pickerShowsSlots6To10OnlyWithReading() {
        assertEquals((0..5).toList(), CalibrationSensors.selectable(emptyList()))
        val rows = listOf(row(6, "OK"), row(7, "EMPTY"), row(8, "STALE"), row(9, "DISABLED"), row(10, "BATLOW"))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 8, 10), CalibrationSensors.selectable(rows))
    }

    @Test
    fun pickedSlotStaysWithoutStatusRows() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 9), CalibrationSensors.selectable(emptyList(), keep = 9))
    }
}
