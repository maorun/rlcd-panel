package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftWatchTest {
    private val watch = DriftWatch()

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
    fun messageNamesTile() {
        assertEquals("Sensor drift: Kitchen", DriftWatch.message("Kitchen"))
        assertEquals("Sensor drift", DriftWatch.TEXT)
    }

    @Test
    fun notificationIdPerTile() {
        assertEquals(2300, DriftWatch.notificationId(0))
        assertEquals(2310, DriftWatch.notificationId(10))
    }

    @Test
    fun idRangeIsFreeOfOtherWatches() {
        val drift = (0 until DriftWatch.TILE_COUNT).map { DriftWatch.notificationId(it) }.toSet()
        val window = (0 until WindowWatch.TILE_COUNT).map { WindowWatch.notificationId(it) }.toSet()
        val reception = (1..10).map { ReceptionWatch.notificationId(it) }.toSet()
        assertTrue(drift.intersect(window).isEmpty())
        assertTrue(drift.intersect(reception).isEmpty())
    }

    @Test
    fun isDriftingRules() {
        assertTrue(DriftWatch.isDrifting(0b10, 1))
        assertFalse(DriftWatch.isDrifting(0b10, 0))
        assertFalse(DriftWatch.isDrifting(null, 1))
        assertFalse(DriftWatch.isDrifting(0x7ff, 11))
    }
}
