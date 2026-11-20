package com.rlcdpanel.phone

import com.rlcdpanel.phone.protocol.SensorHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorHealthTextTest {

    private fun row(moldHours: Int?) = SensorHealth(
        1, "A", -60.0, 80.0, 10.0, "OK", "WOIO", null, SensorHealth.Verdict.GOOD, moldHours
    )

    @Test
    fun moldTextAndWarning() {
        assertEquals("Schimmel --", SensorHealthText.mold(row(null)))
        assertEquals("Schimmel 0 h", SensorHealthText.mold(row(0)))
        assertEquals("⚠ Schimmel 3 h", SensorHealthText.mold(row(3)))
        assertFalse(SensorHealthText.isMold(row(null)))
        assertFalse(SensorHealthText.isMold(row(0)))
        assertTrue(SensorHealthText.isMold(row(3)))
    }
}
