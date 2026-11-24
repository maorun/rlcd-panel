package com.rlcdpanel.phone

import com.rlcdpanel.phone.protocol.TelemetryFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleTelemetryScannerFilterTest {
    @Test
    fun filterTargetsTelemetryCompanyId() {
        assertEquals(0xFFFF, BleTelemetryScanner.FILTER_COMPANY_ID)
        assertEquals(TelemetryFrame.COMPANY_ID, BleTelemetryScanner.FILTER_COMPANY_ID)
    }

    @Test
    fun filterMatchesEachPayloadOfTheCompanyId() {
        assertTrue(BleTelemetryScanner.FILTER_DATA.isEmpty())
        assertTrue(BleTelemetryScanner.FILTER_MASK.isEmpty())
    }
}
