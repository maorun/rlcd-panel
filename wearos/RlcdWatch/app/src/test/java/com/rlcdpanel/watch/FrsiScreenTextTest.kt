package com.rlcdpanel.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class FrsiScreenTextTest {
    @Test
    fun hourPickerCyclesTheChoices() {
        val s = FrsiScreenText
        assertEquals(12, s.nextHours(6))
        assertEquals(24, s.nextHours(12))
        assertEquals(3, s.nextHours(24))
        assertEquals(6, s.nextHours(3))
        assertEquals(6, s.nextHours(7))
        assertEquals(6, FrsiState().hours)
    }
}
