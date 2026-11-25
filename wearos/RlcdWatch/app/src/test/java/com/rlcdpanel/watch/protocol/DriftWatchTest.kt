package com.rlcdpanel.watch.protocol

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
    fun slotTenIsReportedAndHigherBitsAreNot() {
        watch.update(0)
        assertEquals(listOf(10), watch.update(1 shl 10))
        assertTrue(watch.update(0).isEmpty())
        assertTrue(watch.update(1 shl 11).isEmpty())
    }

    @Test
    fun buildsMessageAndNotificationId() {
        assertEquals("Sensor drift: Kitchen", DriftWatch.message("Kitchen"))
        assertEquals(2300, DriftWatch.notificationId(0))
        assertEquals(2310, DriftWatch.notificationId(10))
    }

    @Test
    fun isDriftingTestsOneTile() {
        assertTrue(DriftWatch.isDrifting(0b10, 1))
        assertFalse(DriftWatch.isDrifting(0b10, 0))
        assertFalse(DriftWatch.isDrifting(null, 1))
        assertFalse(DriftWatch.isDrifting(0x7FF, 11))
    }
}
