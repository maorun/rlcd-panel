package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SaltCommandTest {
    private fun assertRejected(sensor: Int, magnesium: Boolean) {
        try {
            SaltCommand.line(sensor, magnesium)
            fail("no error for sensor $sensor")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun commandLine() {
        for (n in 0..10) {
            assertEquals("SALT $n", SaltCommand.line(n, false))
            assertEquals("SALT $n MGCL2", SaltCommand.line(n, true))
        }
        assertEquals("SALT 10", SaltCommand.line(10, false))
    }

    @Test
    fun sensorOutsideRangeThrows() {
        assertRejected(11, true)
        assertRejected(11, false)
        assertRejected(-1, false)
    }
}
