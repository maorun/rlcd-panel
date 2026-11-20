package com.rlcdpanel.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorHealthTextTest {
    @Test
    fun sdFreeText() {
        assertEquals("SD frei 14820 MB", SensorHealthText.sdFree(14820))
        assertEquals("SD frei --", SensorHealthText.sdFree(null))
    }

    @Test
    fun sdLowLimit() {
        assertTrue(SensorHealthText.isSdLow(99))
        assertFalse(SensorHealthText.isSdLow(100))
        assertFalse(SensorHealthText.isSdLow(null))
    }
}
