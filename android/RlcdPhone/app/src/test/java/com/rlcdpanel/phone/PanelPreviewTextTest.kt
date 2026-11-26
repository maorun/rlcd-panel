package com.rlcdpanel.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelPreviewTextTest {
    @Test
    fun ageShowsWholeMinutes() {
        assertEquals("Frame: vor < 1 min", PanelPreviewText.age(1_000L, 30_000L))
        assertEquals("Frame: vor 1 min", PanelPreviewText.age(0L, 60_000L))
        assertEquals("Frame: vor 5 min", PanelPreviewText.age(0L, 359_999L))
    }

    @Test
    fun ageOfAFutureFrameIsUnderOneMinute() {
        assertEquals("Frame: vor < 1 min", PanelPreviewText.age(100_000L, 0L))
    }

    @Test
    fun labelAndLimitsNameWhatTheImageIs() {
        assertEquals("Preview from telemetry, not the panel image", PanelPreviewText.LABEL)
        assertTrue(PanelPreviewText.LIMITS.contains("NO SIGNAL"))
    }
}
