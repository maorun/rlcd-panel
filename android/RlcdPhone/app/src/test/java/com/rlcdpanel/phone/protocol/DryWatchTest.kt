package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DryWatchTest {
    private val watch = DryWatch()

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
    fun fallingBitGivesNoTileAndRisesAgain() {
        watch.update(0b10)
        assertTrue(watch.update(0).isEmpty())
        assertEquals(listOf(1), watch.update(0b10))
    }

    @Test
    fun missingFieldGivesNoTileAndKeepsMask() {
        watch.update(0b10)
        assertTrue(watch.update(null).isEmpty())
        assertTrue(watch.update(0b10).isEmpty())
        assertEquals(listOf(2), watch.update(0b110))
    }

    @Test
    fun missingFieldBeforeFirstReadStaysFirstRead() {
        assertTrue(watch.update(null).isEmpty())
        assertTrue(watch.update(0b1).isEmpty())
    }

    @Test
    fun bitsAboveTileTenGiveNoTile() {
        watch.update(0)
        assertTrue(watch.update(1 shl 11).isEmpty())
    }

    @Test
    fun buildsMessageAndDistinctNotificationIds() {
        assertEquals("Dry air: Kitchen", DryWatch.message("Kitchen"))
        val ids = (0 until DryWatch.TILE_COUNT).map { DryWatch.notificationId(it) }
        assertEquals(DryWatch.TILE_COUNT, ids.toSet().size)
        assertEquals(2400, ids.first())
        assertEquals(2410, ids.last())
    }

    @Test
    fun idsDifferFromOtherWatches() {
        val dry = (0 until DryWatch.TILE_COUNT).map { DryWatch.notificationId(it) }.toSet()
        val window = (0 until WindowWatch.TILE_COUNT).map { WindowWatch.notificationId(it) }.toSet()
        val reception = (1..10).map { ReceptionWatch.notificationId(it) }.toSet()
        val drift = (0 until DriftWatch.TILE_COUNT).map { DriftWatch.notificationId(it) }.toSet()
        assertTrue(dry.intersect(window).isEmpty())
        assertTrue(dry.intersect(reception).isEmpty())
        assertTrue(dry.intersect(drift).isEmpty())
    }
}
