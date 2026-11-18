package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TelemetryCacheTest {

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

    @Test
    fun roundTripOfFixtureVector() {
        val cache = TelemetryCache(MemoryStore())
        val bytes = payload("six_valid")
        assertTrue(cache.save(bytes, receivedAtMillis = 1_000L))

        val cached = cache.read()
        assertNotNull(cached)
        assertEquals(1_000L, cached!!.receivedAtMillis)
        assertEquals(7, cached.frame.sequence)
        assertEquals(80, cached.frame.cellPercent)
        assertEquals(21.5, cached.frame.tiles[0].temperatureC!!, 0.001)
        assertEquals(45.0, cached.frame.tiles[0].humidityPct!!, 0.001)
        assertEquals(-3.25, cached.frame.tiles[1].temperatureC!!, 0.001)
    }

    @Test
    fun storesPayloadAsHexText() {
        val store = MemoryStore()
        TelemetryCache(store).save(payload("six_valid"), 1L)
        assertEquals(fixtureHex("six_valid"), store.strings["payload_0"])
    }

    @Test
    fun emptyCacheGivesNull() {
        assertNull(TelemetryCache(MemoryStore()).read())
    }

    @Test
    fun invalidHexGivesNull() {
        val store = MemoryStore()
        store.strings["payload_0"] = "ZZ" + fixtureHex("six_valid").substring(2)
        store.longs["received_0"] = 1L
        assertNull(TelemetryCache(store).read())

        store.strings["payload_0"] = fixtureHex("six_valid").dropLast(1)
        assertNull(TelemetryCache(store).read())
    }

    @Test
    fun badCrcGivesNull() {
        val store = MemoryStore()
        val bytes = payload("six_valid")
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        store.strings["payload_0"] = TelemetryCache.toHex(bytes)
        store.longs["received_0"] = 1L
        assertNull(TelemetryCache(store).read())
    }

    @Test
    fun saveRejectsBadCrcAndKeepsOldEntry() {
        val cache = TelemetryCache(MemoryStore())
        assertTrue(cache.save(payload("six_valid"), 1L))
        val bad = payload("two_tiles")
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0x01).toByte()
        assertFalse(cache.save(bad, 2L))
        assertEquals(7, cache.read()!!.frame.sequence)
    }

    @Test
    fun staleAfterThirtyFiveMinutes() {
        val cache = TelemetryCache(MemoryStore())
        cache.save(payload("six_valid"), receivedAtMillis = 0L)
        val cached = cache.read()!!

        assertFalse(cached.isStale(34L * 60_000L))
        assertFalse(cached.isStale(35L * 60_000L))
        assertTrue(cached.isStale(35L * 60_000L + 1_000L))
    }

    @Test
    fun alertNamesOfTwoAlertBits() {
        val cache = TelemetryCache(MemoryStore())
        // 255 alert mask of extremes_hot_wet has all bits; build a frame with FREEZER + HEAT instead.
        val bytes = payload("two_tiles")
        bytes[3] = (1 or 128).toByte()
        bytes[bytes.size - 1] = Checksums.crc8(bytes, 0, bytes.size - 1).toByte()
        assertTrue(cache.save(bytes, 1L))
        assertEquals(listOf("FREEZER", "HEAT"), cache.read()!!.frame.alerts.names)
    }

    @Test
    fun focusedRoomIsClamped() {
        val cache = TelemetryCache(MemoryStore())
        assertEquals(0, cache.focusedRoom())
        cache.saveFocusedRoom(3)
        assertEquals(3, cache.focusedRoom())
        cache.saveFocusedRoom(99)
        assertEquals(TelemetryFrame.TOTAL_TILES - 1, cache.focusedRoom())
    }
}
