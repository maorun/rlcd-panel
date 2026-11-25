package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceptionWatchTest {
    private val watch = ReceptionWatch()

    @Test
    fun firstReadGivesNoSlot() {
        assertTrue(watch.update(0b101).isEmpty())
    }

    @Test
    fun risingBitGivesSlot() {
        watch.update(0)
        assertEquals(listOf(1, 4), watch.update(0b1001))
    }

    @Test
    fun equalMaskGivesNoSlot() {
        watch.update(0b10)
        assertTrue(watch.update(0b10).isEmpty())
    }

    @Test
    fun fallingBitGivesNoSlotAndRisesAgain() {
        watch.update(0b10)
        assertTrue(watch.update(0).isEmpty())
        assertEquals(listOf(2), watch.update(0b10))
    }

    @Test
    fun missingFieldGivesNoSlotAndKeepsMask() {
        watch.update(0b10)
        assertTrue(watch.update(null).isEmpty())
        assertTrue(watch.update(0b10).isEmpty())
        assertEquals(listOf(3), watch.update(0b110))
    }

    @Test
    fun slotTenIsReported() {
        watch.update(0)
        assertEquals(listOf(10), watch.update(1 shl 9))
    }

    @Test
    fun bitsAboveSlotTenGiveNoSlot() {
        watch.update(0)
        assertTrue(watch.update(1 shl 10).isEmpty())
    }

    @Test
    fun buildsMessageAndNotificationId() {
        assertEquals("Weak signal: Kitchen", ReceptionWatch.message("Kitchen"))
        assertEquals(2201, ReceptionWatch.notificationId(1))
        assertEquals(2210, ReceptionWatch.notificationId(10))
    }

    @Test
    fun missingFieldBeforeFirstReadStaysFirstRead() {
        assertTrue(watch.update(null).isEmpty())
        assertTrue(watch.update(0b1).isEmpty())
    }
}
