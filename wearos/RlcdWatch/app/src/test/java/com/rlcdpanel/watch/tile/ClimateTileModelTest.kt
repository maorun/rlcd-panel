package com.rlcdpanel.watch.tile

import com.rlcdpanel.watch.protocol.CachedBatteryForecast
import com.rlcdpanel.watch.protocol.CachedMeteo
import com.rlcdpanel.watch.protocol.CachedOutdoor
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

    // Tile 0 of "two_tiles" is 22.0 C / 40.0 %RH, tile 1 is -3.0 C / 65.0 %RH (cold and moist outside).
    // Room 0 at 22 C and 40 %RH is below the start limit, so tile 0 of a copy becomes a humid room.
    private val humidRoom = twoTiles.copy(
        tiles = twoTiles.tiles.toMutableList().also {
            it[0] = it[0].copy(temperatureC = 22.0, humidityPct = 70.0)
        }
    )
    private val slotOutdoor = CachedOutdoor(slotIndex = 1, meteo = null)

    @Test
    fun adviceLineShowsTitleBelowClimateLine() {
        val model = ClimateTileModel.build(
            CachedTelemetry(humidRoom, 0L), 0, config, 60_000L, slotOutdoor
        )
        assertEquals("Kurz Stoßlüften (Kälteschutz!)", model.advice)
    }

    @Test
    fun noAdviceWithoutOutdoorSource() {
        val model = ClimateTileModel.build(CachedTelemetry(humidRoom, 0L), 0, config, 60_000L)
        assertNull(model.advice)
    }

    @Test
    fun noAdviceWhenOutdoorTileIsFocusedRoom() {
        val model = ClimateTileModel.build(
            CachedTelemetry(humidRoom, 0L), 1, config, 60_000L, slotOutdoor
        )
        assertNull(model.advice)
    }

    @Test
    fun openMeteoReadingGivesAdviceWhenFresh() {
        val outdoor = CachedOutdoor(null, CachedMeteo(tempC = 10.0, rhPct = 50.0, atMillis = 0L))
        val model = ClimateTileModel.build(
            CachedTelemetry(humidRoom, 0L), 0, config, 30L * 60_000L, outdoor
        )
        assertEquals("Jetzt optimal zum Lüften", model.advice)
    }

    @Test
    fun noAdviceForOpenMeteoReadingOlderThanOneHour() {
        val outdoor = CachedOutdoor(null, CachedMeteo(tempC = 10.0, rhPct = 50.0, atMillis = 0L))
        val cached = CachedTelemetry(humidRoom, 60L * 60_000L)
        val fresh = ClimateTileModel.build(cached, 0, config, 60L * 60_000L, outdoor)
        val stale = ClimateTileModel.build(cached, 0, config, 61L * 60_000L, outdoor)
        assertEquals("Jetzt optimal zum Lüften", fresh.advice)
        assertNull(stale.advice)
    }

    @Test
    fun noAdviceForFrameOlderThan35Minutes() {
        val model = ClimateTileModel.build(
            CachedTelemetry(humidRoom, 0L), 0, config, 35L * 60_000L + 1_000L, slotOutdoor
        )
        assertTrue(model.isEmpty)
        assertNull(model.advice)
    }

    @Test
    fun noAdviceForStaleOutdoorFrame() {
        val staleOutdoor = CachedTelemetry(twoTiles, 0L)
        val model = ClimateTileModel.build(
            CachedTelemetry(humidRoom, 50L * 60_000L), 0, config, 60L * 60_000L, slotOutdoor, staleOutdoor
        )
        assertNull(model.advice)
    }

    @Test
    fun outdoorSlotOnPageOneReadsIndexModuloPageSize() {
        // Room 7 is index 1 of a page 1 frame: -3.0 C / 65 %RH.
        val pageOne = CachedTelemetry(twoTiles.copy(page = 1), 50_000L)
        val outdoor = CachedOutdoor(slotIndex = 7, meteo = null)
        val model = ClimateTileModel.build(
            CachedTelemetry(humidRoom, 0L), 0, config, 60_000L, outdoor, pageOne
        )
        assertEquals("Kurz Stoßlüften (Kälteschutz!)", model.advice)
    }

    @Test
    fun noAdviceForOutdoorFrameOfWrongPage() {
        val outdoor = CachedOutdoor(slotIndex = 7, meteo = null)
        val model = ClimateTileModel.build(
            CachedTelemetry(humidRoom, 0L), 0, config, 60_000L, outdoor, CachedTelemetry(twoTiles, 0L)
        )
        assertNull(model.advice)
    }

    @Test
    fun noAdviceForOutdoorTileWithoutReading() {
        val outdoor = CachedOutdoor(slotIndex = 2, meteo = null)
        val model = ClimateTileModel.build(
            CachedTelemetry(humidRoom, 0L), 0, config, 60_000L, outdoor
        )
        assertNull(model.advice)
    }

    @Test
    fun noAdviceWithoutOutdoorFrame() {
        val model = ClimateTileModel.build(
            CachedTelemetry(humidRoom, 0L), 0, config, 60_000L, slotOutdoor, null
        )
        assertNull(model.advice)
    }

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun withForecast(forecast: CachedBatteryForecast?) =
        ClimateTileModel.build(CachedTelemetry(twoTiles, now), 0, config, now + 1_000L, forecast = forecast)

    @Test
    fun batteryLineShowsNameAndDateForForecast10DaysAhead() {
        val date = com.rlcdpanel.watch.protocol.PanelClock.format(now + 10 * day)!!.substring(0, 10)
        assertEquals("Battery: Slot 1 $date", withForecast(CachedBatteryForecast(1, now + 10 * day)).batteryLine)
    }

    @Test
    fun noBatteryLineForForecast40DaysAhead() {
        assertNull(withForecast(CachedBatteryForecast(1, now + 40 * day)).batteryLine)
    }

    @Test
    fun noBatteryLineForOldForecast() {
        assertNull(withForecast(CachedBatteryForecast(1, now - 5 * day)).batteryLine)
    }

    @Test
    fun noBatteryLineForEmptyCache() {
        val empty = com.rlcdpanel.watch.protocol.TelemetryCache(EmptyStore()).readBatteryForecast()
        assertNull(empty)
        assertNull(withForecast(empty).batteryLine)
    }

    private class EmptyStore : com.rlcdpanel.watch.protocol.TelemetryCacheStore {
        override fun getString(key: String): String? = null
        override fun getLong(key: String, default: Long): Long = default
        override fun getInt(key: String, default: Int): Int = default
        override fun put(strings: Map<String, String>, longs: Map<String, Long>, ints: Map<String, Int>) {}
    }
}
