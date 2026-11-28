package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckWatchTest {
    private val watch = StuckWatch()

    @Test
    fun firstReadGivesNoTile() {
        assertTrue(watch.update(0b101).isEmpty())
    }

    @Test
    fun risingBitGivesTile() {
        watch.update(0)
        assertEquals(listOf(0, 3), watch.update(0b1001))
    }

    @Test
    fun equalMaskGivesNoTile() {
        watch.update(0b10)
        assertTrue(watch.update(0b10).isEmpty())
    }

    @Test
    fun fallingBitGivesNoTileAndRiseAfterwardsCounts() {
        watch.update(0b110)
        assertTrue(watch.update(0b010).isEmpty())
        assertEquals(listOf(2), watch.update(0b110))
    }

    @Test
    fun nullMaskGivesNoTileAndKeepsStoredMask() {
        watch.update(0b10)
        assertTrue(watch.update(null).isEmpty())
        assertTrue(watch.update(0b10).isEmpty())
        assertEquals(listOf(2), watch.update(0b110))
    }

    @Test
    fun bitAboveTileTenIsIgnored() {
        watch.update(0)
        assertTrue(watch.update(1 shl 11).isEmpty())
    }

    @Test
    fun messageAndIds() {
        assertEquals("Sensor frozen: Kitchen", StuckWatch.message("Kitchen"))
        assertEquals(2500, StuckWatch.notificationId(0))
        assertEquals(2510, StuckWatch.notificationId(10))
    }

    @Test
    fun idRangeIsFreeOfOtherWatches() {
        val stuck = (0 until StuckWatch.TILE_COUNT).map { StuckWatch.notificationId(it) }.toSet()
        val others = (0 until 11).flatMap {
            listOf(WindowWatch.notificationId(it), DriftWatch.notificationId(it), DryWatch.notificationId(it), ReceptionWatch.notificationId(it))
        }.toSet()
        assertTrue(stuck.intersect(others).isEmpty())
    }

    @Test
    fun isStuckRules() {
        assertTrue(StuckWatch.isStuck(0b10, 1))
        assertFalse(StuckWatch.isStuck(0b10, 0))
        assertFalse(StuckWatch.isStuck(null, 1))
        assertFalse(StuckWatch.isStuck(0x7ff, 11))
    }
}
