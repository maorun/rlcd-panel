package com.rlcdpanel.phone.ventilation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WallWatchTest {
    private var nowMs = 1_000_000_000L
    private val watch = WallWatch(clock = { nowMs })

    @Test
    fun reportsRiseAtOrAboveEightyPercent() {
        assertNull(watch.update(1, 79.9))
        val result = watch.update(1, 80.0)
        assertNotNull(result)
        assertEquals(WallWatch.Result.Notify(1, 80.0), result)
        // No second result while still risen
        assertNull(watch.update(1, 81.0))
    }

    @Test
    fun releasesBelowSeventySevenPercent() {
        watch.update(1, 80.0)
        // Above release threshold: stays risen
        assertNull(watch.update(1, 77.0))
        // Releases below 77.0 %RH
        assertNull(watch.update(1, 76.9))
        // Next rise after 6 hours notifies again
        nowMs += WallWatch.REPEAT_INTERVAL_MS
        val result = watch.update(1, 80.5)
        assertEquals(WallWatch.Result.Notify(1, 80.5), result)
    }

    @Test
    fun respectsSixHourRepeatInterval() {
        val first = watch.update(1, 80.0)
        assertNotNull(first)

        // Release at 76.0
        watch.update(1, 76.0)

        // Rise 5 hours after first notification: suppressed by interval
        nowMs += 5L * 3600L * 1000L
        assertNull(watch.update(1, 82.0))

        // Release again
        watch.update(1, 76.0)

        // Rise 6 hours after first notification (1 hour later): reports result
        nowMs += 1L * 3600L * 1000L
        val second = watch.update(1, 82.0)
        assertEquals(WallWatch.Result.Notify(1, 82.0), second)
    }

    @Test
    fun releasesOnNull() {
        watch.update(1, 80.0)
        // Null releases
        assertNull(watch.update(1, null))
        // Rise after 6 hours notifies again
        nowMs += WallWatch.REPEAT_INTERVAL_MS
        val result = watch.update(1, 80.0)
        assertEquals(WallWatch.Result.Notify(1, 80.0), result)
    }

    @Test
    fun keepsSeparateStatesForTwoTiles() {
        val r0 = watch.update(0, 80.0)
        val r1 = watch.update(1, 85.0)
        assertEquals(WallWatch.Result.Notify(0, 80.0), r0)
        assertEquals(WallWatch.Result.Notify(1, 85.0), r1)
        assertNull(watch.update(0, 81.0))
        assertNull(watch.update(1, 86.0))
    }

    @Test
    fun ignoresTilesOutOfRange() {
        assertNull(watch.update(-1, 85.0))
        assertNull(watch.update(6, 85.0))
    }
}
