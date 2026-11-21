package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertVibrationTest {
    @Test
    fun freezerGivesThreeShortPulses() {
        assertArrayEquals(
            longArrayOf(0L, 120L, 120L, 120L, 120L, 120L),
            AlertVibration.pattern(AlertMask.FREEZER)
        )
    }

    @Test
    fun humidityGivesTwoLongPulses() {
        assertArrayEquals(
            longArrayOf(0L, 400L, 120L, 400L),
            AlertVibration.pattern(AlertMask.HUMIDITY)
        )
    }

    @Test
    fun heatGivesFourShortPulses() {
        assertArrayEquals(
            longArrayOf(0L, 120L, 120L, 120L, 120L, 120L, 120L, 120L),
            AlertVibration.pattern(AlertMask.HEAT)
        )
    }

    @Test
    fun otherBitsGiveOneLongPulse() {
        val one = longArrayOf(0L, 400L)
        assertArrayEquals(one, AlertVibration.pattern(AlertMask.NOISE_HIGH))
        assertArrayEquals(one, AlertVibration.pattern(AlertMask.SENSOR_BATTERY))
    }

    @Test
    fun lowestBitWins() {
        val mask = AlertMask(AlertMask.HUMIDITY.rawValue or AlertMask.HEAT.rawValue)
        assertArrayEquals(AlertVibration.pattern(AlertMask.HUMIDITY), AlertVibration.pattern(mask))
    }

    @Test
    fun emptyMaskGivesEmptyArray() {
        assertEquals(0, AlertVibration.pattern(AlertMask.NONE).size)
    }
}
