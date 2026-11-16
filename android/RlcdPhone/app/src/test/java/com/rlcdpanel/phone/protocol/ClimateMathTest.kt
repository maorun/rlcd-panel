package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClimateMathTest {

    @Test
    fun dewPointMatchesReference() {
        assertEquals(9.26, ClimateMath.dewPointC(20.0, 50.0)!!, 0.05)
    }

    @Test
    fun dewPointRejectsInvalidInput() {
        assertNull(ClimateMath.dewPointC(20.0, 0.0))
        assertNull(ClimateMath.dewPointC(Double.NaN, 50.0))
        assertNull(ClimateMath.dewPointC(70.0, 50.0))
        assertNull(ClimateMath.dewPointC(20.0, 101.0))
    }

    @Test
    fun absoluteHumidityMatchesReference() {
        assertEquals(8.65, ClimateMath.absoluteHumidityGm3(20.0, 50.0)!!, 0.1)
        assertEquals(0.0, ClimateMath.absoluteHumidityGm3(25.0, 0.0)!!, 0.0001)
        assertNull(ClimateMath.absoluteHumidityGm3(-46.0, 50.0))
        assertNull(ClimateMath.absoluteHumidityGm3(20.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun heatIndexEqualsAirTemperatureBelowThresholds() {
        assertEquals(22.0, ClimateMath.heatIndexC(22.0, 60.0)!!, 0.001)
        assertEquals(30.0, ClimateMath.heatIndexC(30.0, 39.0)!!, 0.001)
    }

    @Test
    fun heatIndexRisesWhenHotAndHumid() {
        val hi = ClimateMath.heatIndexC(32.0, 70.0)
        assertNotNull(hi)
        assertTrue(hi!! > 38.0)
        assertNull(ClimateMath.heatIndexC(Double.NaN, 70.0))
    }

    @Test
    fun comfortZonesFollowLimits() {
        assertEquals(ComfortZone.OK, ClimateMath.comfort(21.0, 45.0))
        assertEquals(ComfortZone.COLD, ClimateMath.comfort(16.0, 45.0))
        assertEquals(ComfortZone.WARM, ClimateMath.comfort(28.0, 45.0))
        assertEquals(ComfortZone.DRY, ClimateMath.comfort(21.0, 20.0))
        assertEquals(ComfortZone.HUMID, ClimateMath.comfort(21.0, 70.0))
        assertEquals(ComfortZone.INVALID, ClimateMath.comfort(Double.NaN, 50.0))
    }
}
