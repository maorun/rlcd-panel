package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.SpreadReply
import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadScreenTextTest {
    @Test
    fun outlierTitleHasTheMark() {
        assertEquals("⚠ slot3", SpreadScreenText.title(SpreadReply.SensorSpread("slot3", 0.6, -3.1, true)))
        assertEquals("slot1", SpreadScreenText.title(SpreadReply.SensorSpread("slot1", 0.2, 1.0, false)))
    }

    @Test
    fun deviationsShowTheSign() {
        assertEquals("+0.2 °C / -1.0 %RH", SpreadScreenText.deviations(SpreadReply.SensorSpread("a", 0.2, -1.0, false)))
        assertEquals("0.0 °C / 0.0 %RH", SpreadScreenText.deviations(SpreadReply.SensorSpread("a", 0.0, 0.0, false)))
    }
}
