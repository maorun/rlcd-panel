package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class SaltCommandTest {
    @Test
    fun commandLine() {
        for (n in 0..10) {
            assertEquals("SALT $n", SaltCommand.line(n, false))
            assertEquals("SALT $n MGCL2", SaltCommand.line(n, true))
        }
        assertEquals(null, SaltCommand.line(11, true))
        assertEquals(null, SaltCommand.line(-1, false))
    }
}
