package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaltTextTest {
    @Test
    fun skipAndUnstableHaveReadableTexts() {
        assertEquals("SKIP slot1: nur 3 h, mindestens 6 h nötig", SaltText.outcome(SaltReply.parse(listOf("SKIP slot1 hours=3"))))
        assertEquals(
            "UNSTABLE slot1: Schwankung 1.5 %RH, Messung zu unruhig",
            SaltText.outcome(SaltReply.parse(listOf("UNSTABLE slot1 span=1.5")))
        )
    }

    @Test
    fun errorLinesHaveReadableTexts() {
        assertEquals("ERR BAD_TEMP: Temperatur außerhalb 10 bis 35 °C", SaltText.status("ERR BAD_TEMP"))
        assertEquals("Set ble_console=2 to apply", SaltText.status("ERR READ_ONLY"))
        assertTrue(SaltText.status("ERR BAD_ARG").startsWith("ERR BAD_ARG"))
    }

    @Test
    fun measuredResultHasTheMeanAndTheReference() {
        val r = SaltReply.parse(listOf("SALT slot2 hours=6 rh_mean=73.0 rh_span=0.4 temp_mean=25.0 reference=75.3"))
        assertEquals("slot2: 6 h, Mittel 73.0 %RH, Soll 75.3 %RH", SaltText.outcome(r))
    }
}
