package com.rlcdpanel.watch.protocol

import com.rlcdpanel.watch.protocol.SilenceWatch.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SilenceWatchTest {

    private var now = 1_000_000L
    private val watch = SilenceWatch { now }

    private fun advance(ms: Long) {
        now += ms
    }

    @Test
    fun silentOnceAfter35MinutesPlusOneSecond() {
        assertEquals(Event.NONE, watch.onFrame())
        advance(SilenceWatch.SILENCE_MS)
        assertEquals(Event.NONE, watch.check())
        advance(1_000L)
        assertEquals(Event.SILENT, watch.check())
    }

    @Test
    fun noSecondSilentAt60Minutes() {
        watch.onFrame()
        advance(SilenceWatch.SILENCE_MS + 1_000L)
        assertEquals(Event.SILENT, watch.check())
        advance(25L * 60L * 1000L)
        assertEquals(Event.NONE, watch.check())
    }

    @Test
    fun backOnceAtNextFrame() {
        watch.onFrame()
        advance(SilenceWatch.SILENCE_MS + 1_000L)
        watch.check()
        advance(1_000L)
        assertEquals(Event.BACK, watch.onFrame())
        assertEquals(Event.NONE, watch.onFrame())
    }

    @Test
    fun silentAgainAfterBack() {
        watch.onFrame()
        advance(SilenceWatch.SILENCE_MS + 1_000L)
        watch.check()
        watch.onFrame()
        advance(SilenceWatch.SILENCE_MS + 1_000L)
        assertEquals(Event.SILENT, watch.check())
    }

    @Test
    fun noReportWithoutFirstFrame() {
        assertNull(watch.lastFrameAtMillis)
        advance(2L * 60L * 60L * 1000L)
        assertEquals(Event.NONE, watch.check())
    }

    @Test
    fun clockRunningBackGivesNoSilent() {
        watch.onFrame()
        advance(-3L * 60L * 60L * 1000L)
        assertEquals(Event.NONE, watch.check())
        advance(3L * 60L * 60L * 1000L + SilenceWatch.SILENCE_MS + 1_000L)
        assertEquals(Event.SILENT, watch.check())
    }
}
