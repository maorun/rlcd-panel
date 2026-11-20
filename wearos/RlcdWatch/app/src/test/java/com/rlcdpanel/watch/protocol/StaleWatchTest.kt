package com.rlcdpanel.watch.protocol

import com.rlcdpanel.watch.protocol.StaleWatch.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StaleWatchTest {

    private var now = 1_000_000L
    private val watch = StaleWatch { now }

    private fun advanceMin(min: Long) {
        now += min * 60_000L
    }

    @Test
    fun neverValidTileGivesNoResult() {
        assertNull(watch.update(2, false))
        advanceMin(60)
        assertNull(watch.update(2, false))
    }

    @Test
    fun noResultAfter34Minutes() {
        watch.update(2, true)
        advanceMin(34)
        assertNull(watch.update(2, false))
    }

    @Test
    fun lostAfter35Minutes() {
        watch.update(2, true)
        advanceMin(35)
        assertEquals(Result.Lost(2), watch.update(2, false))
    }

    @Test
    fun noSecondLostForLaterInvalidFrames() {
        watch.update(2, true)
        advanceMin(35)
        watch.update(2, false)
        advanceMin(10)
        assertNull(watch.update(2, false))
    }

    @Test
    fun backForNextValidFrameThenLostAgain() {
        watch.update(2, true)
        advanceMin(35)
        watch.update(2, false)
        advanceMin(1)
        assertEquals(Result.Back(2), watch.update(2, true))
        assertNull(watch.update(2, true))
        advanceMin(35)
        assertEquals(Result.Lost(2), watch.update(2, false))
    }

    @Test
    fun tilesAreIndependent() {
        watch.update(2, true)
        watch.update(7, true)
        advanceMin(35)
        watch.update(7, true)
        assertEquals(Result.Lost(2), watch.update(2, false))
        assertNull(watch.update(7, false))
    }

    @Test
    fun tileOutsideRangeGivesNoResult() {
        assertNull(watch.update(-1, true))
        assertNull(watch.update(11, true))
        advanceMin(60)
        assertNull(watch.update(11, false))
    }

    @Test
    fun clockThatRanBackGivesNoLost() {
        watch.update(2, true)
        now -= 3_600_000L
        assertNull(watch.update(2, false))
        advanceMin(34)
        assertNull(watch.update(2, false))
    }

    private fun frame(page: Int, vararg valid: Boolean) = TelemetryFrame(
        sequence = 1,
        cellPercent = null,
        alerts = AlertMask(0),
        tiles = valid.map { if (it) TelemetryTile(20.0, 50.0) else TelemetryTile(null, null) },
        page = page
    )

    @Test
    fun page1FrameFeedsTiles6To10() {
        val all = booleanArrayOf(true, true, true, true, true, true)
        watch.updateFrame(frame(1, *all))
        advanceMin(35)
        val results = watch.updateFrame(frame(1, true, false, true, true, true, true))
        assertEquals(listOf<Result>(Result.Lost(7)), results)
    }

    @Test
    fun page0FrameFeedsTiles0To5() {
        watch.updateFrame(frame(0, true, true, true, true, true, true))
        advanceMin(35)
        val results = watch.updateFrame(frame(0, true, true, true, false, true, true))
        assertEquals(listOf<Result>(Result.Lost(3)), results)
    }
}
