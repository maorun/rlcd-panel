package com.rlcdpanel.watch.tile

import com.rlcdpanel.watch.protocol.CachedTelemetry
import com.rlcdpanel.watch.protocol.PanelConfig
import com.rlcdpanel.watch.protocol.TelemetryFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClimateTileModelTest {

    private fun hexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Vector "two_tiles": sequence 8, cell 55, alert mask 1, tile 0 22.0/40.0, tile 1 -3.0/65.0.
    private val twoTiles = TelemetryFrame.decodePayload(
        hexBytes("0208370103980850D4FE8200000000000000000000000091")
    )
    private val config = PanelConfig()

    @Test
    fun freshFrameShowsFocusedRoom() {
        val model = ClimateTileModel.build(CachedTelemetry(twoTiles, 0L), 1, config, 60_000L)
        assertFalse(model.isEmpty)
        assertEquals("Slot 1", model.roomName)
        assertEquals("-3.0 °C", model.temperature)
        assertEquals("65 %", model.humidity)
        assertEquals("Panel 55 %", model.cell)
        assertEquals("FREEZER", model.alerts)
        assertEquals("1 min ago", model.age)
        assertNull(model.hint)
    }

    @Test
    fun emptyCacheShowsPlaceholderAndHint() {
        val model = ClimateTileModel.build(null, 0, config, 0L)
        assertTrue(model.isEmpty)
        assertEquals("--", model.temperature)
        assertEquals("--", model.humidity)
        assertEquals("Open the app", model.hint)
    }

    @Test
    fun staleFrameShowsPlaceholderAndHint() {
        val cached = CachedTelemetry(twoTiles, 0L)
        val fresh = ClimateTileModel.build(cached, 0, config, 35L * 60_000L)
        val stale = ClimateTileModel.build(cached, 0, config, 35L * 60_000L + 1_000L)
        assertFalse(fresh.isEmpty)
        assertTrue(stale.isEmpty)
        assertEquals("Open the app", stale.hint)
    }

    @Test
    fun roomWithoutReadingShowsPlaceholder() {
        val model = ClimateTileModel.build(CachedTelemetry(twoTiles, 0L), 2, config, 1_000L)
        assertTrue(model.isEmpty)
        assertEquals("Slot 2", model.roomName)
    }

    @Test
    fun pageOneFocusReadsIndexModuloPageSize() {
        // Room 6 sits at index 0 of a page 1 frame, so the model uses tile 0 of the frame.
        val pageOne = twoTiles.copy(page = 1)
        val model = ClimateTileModel.build(CachedTelemetry(pageOne, 0L), 6, config, 1_000L)
        assertFalse(model.isEmpty)
        assertEquals("22.0 °C", model.temperature)
    }

    @Test
    fun cleanFrameShowsNoAlerts() {
        val clean = twoTiles.copy(alerts = com.rlcdpanel.watch.protocol.AlertMask.NONE)
        val model = ClimateTileModel.build(CachedTelemetry(clean, 0L), 0, config, 1_000L)
        assertEquals("No alerts", model.alerts)
    }

    @Test
    fun futureDatedFrameCountsAsStale() {
        val model = ClimateTileModel.build(CachedTelemetry(twoTiles, 10_000L), 0, config, 5_000L)
        assertTrue(model.isEmpty)
        assertEquals("Open the app", model.hint)
    }
}
