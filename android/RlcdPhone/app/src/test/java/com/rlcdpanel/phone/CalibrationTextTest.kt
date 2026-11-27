package com.rlcdpanel.phone

import com.rlcdpanel.phone.protocol.CalibrationReply
import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationTextTest {
    @Test
    fun excludedRowsNameTheReason() {
        val stale = CalibrationReply.Excluded("slot2", CalibrationReply.Reason.STALE)
        val outdoor = CalibrationReply.Excluded("slot3", CalibrationReply.Reason.OUTDOOR)
        assertEquals("SKIP slot2: veraltet", CalibrationText.excluded(stale))
        assertEquals("SKIP slot3: Außensensor", CalibrationText.excluded(outdoor))
    }
}
