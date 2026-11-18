package com.rlcdpanel.watch.complication

import com.rlcdpanel.watch.protocol.PanelConfig
import com.rlcdpanel.watch.protocol.TelemetryCache
import com.rlcdpanel.watch.protocol.TelemetryCacheStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClimateComplicationModelTest {

    private class MemoryStore : TelemetryCacheStore {
        val strings = mutableMapOf<String, String>()
        val longs = mutableMapOf<String, Long>()
        val ints = mutableMapOf<String, Int>()

        override fun getString(key: String): String? = strings[key]
        override fun getLong(key: String, default: Long): Long = longs[key] ?: default
        override fun getInt(key: String, default: Int): Int = ints[key] ?: default
        override fun put(strings: Map<String, String>, longs: Map<String, Long>, ints: Map<String, Int>) {
            this.strings.putAll(strings)
            this.longs.putAll(longs)
            this.ints.putAll(ints)
        }
    }

    private fun fixtureHex(name: String): String {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            val candidate = File(dir, "tests/fixtures/ble_telemetry_vectors.txt")
            if (candidate.isFile) {
                val line = candidate.readLines()
                    .map { it.trim() }
                    .first { !it.startsWith("#") && it.startsWith("$name |") }
                // The hex has the 2-byte company ID in front; the cache stores the 24-byte payload.
                return line.split("|").last().trim().substring(4)
            }
            dir = dir.parentFile
        }
        error("Could not locate tests/fixtures/ble_telemetry_vectors.txt")
    }

    private fun payload(name: String): ByteArray = TelemetryCache.fromHex(fixtureHex(name))!!

    private fun cacheWith(name: String, receivedAt: Long, focus: Int = 0): TelemetryCache {
        val cache = TelemetryCache(MemoryStore())
        assertTrue(cache.save(payload(name), receivedAt))
        cache.saveFocusedRoom(focus)
        return cache
    }

    // Vector "two_tiles": tile 0 22.0 C / 40 %RH, tile 1 -3.0 C / 65 %RH.
    @Test
    fun fixtureVectorShowsTextOfFocusedRoom() {
        val first = ClimateComplicationModel.build(cacheWith("two_tiles", 0L, focus = 0), 60_000L)
        assertFalse(first.isEmpty)
        assertEquals("22°", first.temperatureText)
        assertEquals("40%", first.humidityText)

        val second = ClimateComplicationModel.build(cacheWith("two_tiles", 0L, focus = 1), 60_000L)
        assertEquals("-3°", second.temperatureText)
        assertEquals("65%", second.humidityText)
    }

    @Test
    fun roomNameComesFromPanelConfig() {
        val config = PanelConfig.parseText("slot1_name=Wohnzimmer\nslot1_mac=B0:E9:FE:00:00:01\n")
        val fresh = ClimateComplicationModel.build(cacheWith("two_tiles", 0L, focus = 1), 60_000L, config)
        assertEquals("Wohnzimmer", fresh.roomName)
        val stale = ClimateComplicationModel.build(cacheWith("two_tiles", 0L, focus = 1), 36L * 60_000L, config)
        assertTrue(stale.isEmpty)
        assertEquals("Wohnzimmer", stale.roomName)
        assertEquals("Slot 1", ClimateComplicationModel.build(cacheWith("two_tiles", 0L, focus = 1), 60_000L).roomName)
    }

    @Test
    fun emptyCacheShowsPlaceholder() {
        val model = ClimateComplicationModel.build(TelemetryCache(MemoryStore()), 0L)
        assertTrue(model.isEmpty)
        assertEquals("--", model.temperatureText)
        assertEquals("--", model.humidityText)
    }

    @Test
    fun frameOf36MinutesShowsPlaceholder() {
        val cache = cacheWith("two_tiles", 0L)
        assertFalse(ClimateComplicationModel.build(cache, 35L * 60_000L).isEmpty)
        val stale = ClimateComplicationModel.build(cache, 36L * 60_000L)
        assertTrue(stale.isEmpty)
        assertEquals("--", stale.temperatureText)
        assertEquals("--", stale.humidityText)
    }

    @Test
    fun badCrcShowsPlaceholder() {
        val store = MemoryStore()
        val bytes = payload("two_tiles")
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        store.strings["payload_0"] = TelemetryCache.toHex(bytes)
        store.longs["received_0"] = 0L
        val model = ClimateComplicationModel.build(TelemetryCache(store), 1_000L)
        assertTrue(model.isEmpty)
        assertEquals("--", model.temperatureText)
    }

    @Test
    fun roomWithoutReadingShowsPlaceholder() {
        val model = ClimateComplicationModel.build(cacheWith("two_tiles", 0L, focus = 2), 1_000L)
        assertTrue(model.isEmpty)
    }

    @Test
    fun rangedValueStaysBetween0And100() {
        val model = ClimateComplicationModel.build(cacheWith("two_tiles", 0L, focus = 1), 1_000L)
        assertEquals(65f, model.humidityValue, 0.01f)
        assertEquals(0f, ClimateComplicationModel.MIN_VALUE, 0f)
        assertEquals(100f, ClimateComplicationModel.MAX_VALUE, 0f)
        assertEquals(0f, ClimateComplicationModel.EMPTY.humidityValue, 0f)
    }
}
