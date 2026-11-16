package com.rlcdpanel.phone.ventilation

import com.rlcdpanel.phone.protocol.TelemetryTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutdoorResolverTest {
    private val now = 10_000_000L
    private val tiles = listOf(
        TelemetryTile(21.0, 50.0),
        TelemetryTile(5.0, 80.0),
        TelemetryTile(null, null)
    )

    private fun weather(ageMs: Long) = OutdoorClimate(8.0, 70.0, "Berlin", now - ageMs)

    @Test
    fun openMeteoWithinAgeLimitIsUsed() {
        val result = OutdoorResolver.resolve(OutdoorSource.OpenMeteo(), weather(59 * 60 * 1000L), tiles, now, now)
        assertNotNull(result)
        assertEquals(8.0, result!!.tempC, 0.001)
    }

    @Test
    fun openMeteoOlderThanOneHourIsRejected() {
        val result = OutdoorResolver.resolve(OutdoorSource.OpenMeteo(), weather(61 * 60 * 1000L), tiles, now, now)
        assertNull(result)
    }

    @Test
    fun missingOpenMeteoGivesNull() {
        assertNull(OutdoorResolver.resolve(OutdoorSource.OpenMeteo(), null, tiles, now, now))
    }

    @Test
    fun sensorSlotWithFreshValidTileIsUsed() {
        val result = OutdoorResolver.resolve(OutdoorSource.SensorSlot(1, "Slot 1"), null, tiles, now - 1000L, now)
        assertNotNull(result)
        assertEquals(5.0, result!!.tempC, 0.001)
        assertEquals("Slot 1", result.sourceName)
    }

    @Test
    fun sensorSlotWithStaleFrameIsRejected() {
        val receivedAt = now - OutdoorResolver.MAX_FRAME_AGE_MS - 1L
        assertNull(OutdoorResolver.resolve(OutdoorSource.SensorSlot(1, "Slot 1"), null, tiles, receivedAt, now))
    }

    @Test
    fun sensorSlotWithEmptyOrMissingTileIsRejected() {
        assertNull(OutdoorResolver.resolve(OutdoorSource.SensorSlot(2, "Slot 2"), null, tiles, now, now))
        assertNull(OutdoorResolver.resolve(OutdoorSource.SensorSlot(5, "Slot 5"), null, tiles, now, now))
        assertNull(OutdoorResolver.resolve(OutdoorSource.SensorSlot(1, "Slot 1"), null, null, null, now))
    }

    @Test
    fun frameFreshnessBoundary() {
        assertTrue(OutdoorResolver.isFrameFresh(now - OutdoorResolver.MAX_FRAME_AGE_MS, now))
        assertFalse(OutdoorResolver.isFrameFresh(now - OutdoorResolver.MAX_FRAME_AGE_MS - 1L, now))
        assertFalse(OutdoorResolver.isFrameFresh(null, now))
    }

    @Test
    fun adviceNeedsEveryPrecondition() {
        val outdoor = weather(0L)
        val room = tiles[0]
        assertTrue(OutdoorResolver.mayAdvise(false, true, room, outdoor))
        assertFalse(OutdoorResolver.mayAdvise(true, true, room, outdoor))
        assertFalse(OutdoorResolver.mayAdvise(false, false, room, outdoor))
        assertFalse(OutdoorResolver.mayAdvise(false, true, tiles[2], outdoor))
        assertFalse(OutdoorResolver.mayAdvise(false, true, null, outdoor))
        assertFalse(OutdoorResolver.mayAdvise(false, true, room, null))
    }
}
