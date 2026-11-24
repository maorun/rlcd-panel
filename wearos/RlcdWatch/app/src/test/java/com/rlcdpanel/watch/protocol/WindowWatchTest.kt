package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowWatchTest {
    private val watch = WindowWatch()

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
    fun bitsAboveTileTenGiveNoTile() {
        watch.update(0)
        assertTrue(watch.update(1 shl 11).isEmpty())
    }

    @Test
    fun buildsMessageAndNotificationId() {
        assertEquals("Window open: Kitchen", WindowWatch.message("Kitchen"))
        assertEquals(2100, WindowWatch.notificationId(0))
        assertEquals(2110, WindowWatch.notificationId(10))
    }

    @Test
    fun missingFieldBeforeFirstReadStaysFirstRead() {
        assertTrue(watch.update(null).isEmpty())
        assertTrue(watch.update(0b1).isEmpty())
    }
}
