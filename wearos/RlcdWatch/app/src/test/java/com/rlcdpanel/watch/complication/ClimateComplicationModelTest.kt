package com.rlcdpanel.watch.complication

import com.rlcdpanel.watch.protocol.Checksums
import com.rlcdpanel.watch.protocol.PanelConfig
import com.rlcdpanel.watch.protocol.TelemetryCache
import com.rlcdpanel.watch.protocol.TelemetryCacheStore
import com.rlcdpanel.watch.ventilation.VentilationAction
import com.rlcdpanel.watch.ventilation.VentilationGuide
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

    // Vector "two_tiles": alerts=1, tile 0 22.0 C / 40 %RH, tile 1 -3.0 C / 65 %RH.
    @Test
    fun fixtureVectorShowsTextOfFocusedRoom() {
        val first = ClimateComplicationModel.build(cacheWith("two_tiles", 0L, focus = 0), 60_000L)
        assertFalse(first.isEmpty)
        assertTrue(first.hasAlert)
        assertEquals("!22.0°", first.temperatureText)
        assertEquals("40%", first.humidityText)

        val second = ClimateComplicationModel.build(cacheWith("two_tiles", 0L, focus = 1), 60_000L)
        assertEquals("!-3.0°", second.temperatureText)
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

    /** "two_tiles" with tile 0 set to [tempC] and the alert byte set to [alerts]; the CRC-8 is new. */
    private fun cacheWithAlert(alerts: Int, tempC: Double, receivedAt: Long = 0L): TelemetryCache {
        val bytes = payload("two_tiles")
        val raw = Math.round(tempC * 100).toInt()
        bytes[3] = alerts.toByte()
        bytes[5] = (raw and 0xFF).toByte()
        bytes[6] = ((raw shr 8) and 0xFF).toByte()
        bytes[bytes.size - 1] = Checksums.crc8(bytes, offset = 0, length = bytes.size - 1).toByte()
        val cache = TelemetryCache(MemoryStore())
        assertTrue(cache.save(bytes, receivedAt))
        cache.saveFocusedRoom(0)
        return cache
    }

    @Test
    fun alertMask0x02PutsMarkBeforeTemperature() {
        val model = ClimateComplicationModel.build(cacheWithAlert(0x02, 21.4), 60_000L)
        assertTrue(model.hasAlert)
        assertEquals("!21.4°", model.temperatureText)
        assertEquals(listOf("HUMIDITY"), model.alertNames)
    }

    @Test
    fun alertMask0GivesNoMark() {
        val model = ClimateComplicationModel.build(cacheWithAlert(0x00, 21.4), 60_000L)
        assertFalse(model.hasAlert)
        assertEquals("21°", model.temperatureText)
        assertTrue(model.alertNames.isEmpty())
    }

    @Test
    fun staleFrameWithAlertGivesNoMark() {
        val model = ClimateComplicationModel.build(cacheWithAlert(0x02, 21.4), 36L * 60_000L)
        assertTrue(model.isEmpty)
        assertFalse(model.hasAlert)
        assertEquals("--", model.temperatureText)
    }

    @Test
    fun emptyCacheGivesNoMark() {
        val model = ClimateComplicationModel.build(TelemetryCache(MemoryStore()), 0L)
        assertFalse(model.hasAlert)
        assertTrue(model.alertNames.isEmpty())
    }

    @Test
    fun alertTextStaysWithin7Characters() {
        for (temp in listOf(-10.5, -50.0, 100.0, 0.0)) {
            val model = ClimateComplicationModel.build(cacheWithAlert(0xFF, temp), 60_000L)
            assertTrue(model.hasAlert)
            assertTrue(model.temperatureText, model.temperatureText.length <= 7)
        }
        val cold = ClimateComplicationModel.build(cacheWithAlert(0x01, -10.5), 60_000L)
        assertEquals("!-10.5°", cold.temperatureText)
    }

    @Test
    fun contentDescriptionListsAlertNames() {
        val model = ClimateComplicationModel.build(cacheWithAlert(0x03, 21.4), 60_000L, PanelConfig())
        assertEquals(
            "Slot 1 Temperature !21.4°, Alert FREEZER, HUMIDITY",
            ClimateComplicationModel.describe("Temperature", model.temperatureText, "Slot 1", model.alertNames)
        )
        assertEquals("Humidity 45%", ClimateComplicationModel.describe("Humidity", "45%", "", emptyList()))
    }

    @Test
    fun smallNegativeReadingShowsNoNegativeZero() {
        val model = ClimateComplicationModel.build(cacheWithAlert(0x01, -0.04), 60_000L)
        assertEquals("!0.0°", model.temperatureText)
    }

    private fun ventilation(focus: Int, tempC: Double?, rh: Double?, receivedAt: Long = 0L): TelemetryCache {
        val cache = cacheWith("two_tiles", receivedAt, focus)
        if (tempC == null || rh == null) {
            cache.saveOutdoorSlot(0)
        } else {
            cache.saveOutdoorOpenMeteo()
            cache.saveOpenMeteoReading(tempC, rh, receivedAt)
        }
        return cache
    }

    private fun expectedDifference(inT: Double, inRh: Double, outT: Double, outRh: Double): String {
        val diff = VentilationGuide.berechneAbsoluteFeuchte(inT, inRh)!! -
            VentilationGuide.berechneAbsoluteFeuchte(outT, outRh)!!
        return String.format(java.util.Locale.US, "%+.1f", Math.round(diff * 10) / 10.0)
    }

    @Test
    fun ventilationAdviceStartShowsLueften() {
        // Room 1: -3.0 C / 65 %RH, outdoor -10 C / 50 %RH is drier.
        val model = VentilationComplicationModel.build(ventilation(1, -10.0, 50.0), 60_000L)
        assertFalse(model.isEmpty)
        assertEquals("Lüften", model.word)
        assertEquals(expectedDifference(-3.0, 65.0, -10.0, 50.0), model.title)
        assertEquals("+1.4", model.title)
    }

    @Test
    fun ventilationAdviceWetterOutdoorShowsZu() {
        val model = VentilationComplicationModel.build(ventilation(1, 20.0, 90.0), 60_000L)
        assertEquals("Zu", model.word)
        assertEquals(expectedDifference(-3.0, 65.0, 20.0, 90.0), model.title)
        assertTrue(model.title.startsWith("-"))
    }

    @Test
    fun ventilationAdviceEqualAirShowsZu() {
        val model = VentilationComplicationModel.build(ventilation(1, -3.0, 65.0), 60_000L)
        assertEquals("Zu", model.word)
        assertEquals("+0.0", model.title)
    }

    @Test
    fun ventilationAdviceDryRoomShowsZu() {
        // Room 0: 22.0 C / 40 %RH is below the start limit.
        val model = VentilationComplicationModel.build(ventilation(0, -10.0, 50.0), 60_000L)
        assertEquals("Zu", model.word)
        assertEquals(expectedDifference(22.0, 40.0, -10.0, 50.0), model.title)
    }

    @Test
    fun ventilationOutdoorSlotOnMissingPageShowsPlaceholder() {
        val cache = cacheWith("two_tiles", 0L, focus = 1)
        cache.saveOutdoorSlot(6)
        assertEquals("--", VentilationComplicationModel.build(cache, 60_000L).word)
    }

    @Test
    fun shortWordAndDifferenceFormat() {
        assertEquals("--", VentilationGuide.shortWord(null))
        val advice = VentilationGuide.analysiereLueftung(-3.0, 65.0, -10.0, 50.0)!!
        assertEquals("Lüften", VentilationGuide.shortWord(advice))
        assertEquals("+1.4", VentilationGuide.shortDifference(advice))
    }

    @Test
    fun ventilationStaleOpenMeteoShowsPlaceholder() {
        // Frame received at minute 30, Open-Meteo reading at minute 0.
        val cache = cacheWith("two_tiles", 30L * 60_000L, focus = 1)
        cache.saveOutdoorOpenMeteo()
        cache.saveOpenMeteoReading(-10.0, 50.0, 0L)
        assertEquals("Lüften", VentilationComplicationModel.build(cache, 60L * 60_000L).word)
        // Now is minute 61: the frame is fresh, the reading is older than 1 hour.
        val model = VentilationComplicationModel.build(cache, 61L * 60_000L)
        assertTrue(model.isEmpty)
        assertEquals("--", model.title)
    }

    @Test
    fun shortWordMapsEachAction() {
        val advice = VentilationGuide.analysiereLueftung(-3.0, 65.0, -10.0, 50.0)!!
        val expected = mapOf(
            VentilationAction.LUEFTUNG_STARTEN to "Lüften",
            VentilationAction.LUEFTUNG_BEENDEN to "--",
            VentilationAction.LUEFTUNG_ABBRECHEN to "--",
            VentilationAction.WEITER_LUEFTEN to "--",
            VentilationAction.NICHT_LUEFTEN to "Zu",
            VentilationAction.NEUTRAL to "Zu"
        )
        assertEquals(VentilationAction.values().size, expected.size)
        for ((action, word) in expected) {
            assertEquals(action.name, word, VentilationGuide.shortWord(advice.copy(aktion = action)))
        }
    }

    @Test
    fun ventilationOldFrameShowsPlaceholder() {
        val cache = ventilation(1, -10.0, 50.0)
        assertEquals("Lüften", VentilationComplicationModel.build(cache, 35L * 60_000L).word)
        val stale = VentilationComplicationModel.build(cache, 36L * 60_000L)
        assertTrue(stale.isEmpty)
        assertEquals("--", stale.word)
        assertEquals("--", stale.title)
    }

    @Test
    fun ventilationEmptyCacheShowsPlaceholder() {
        val model = VentilationComplicationModel.build(TelemetryCache(MemoryStore()), 0L)
        assertTrue(model.isEmpty)
        assertEquals("--", model.word)
        assertEquals("--", model.title)
    }

    @Test
    fun ventilationBadCrcShowsPlaceholder() {
        val store = MemoryStore()
        val bytes = payload("two_tiles")
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        store.strings["payload_0"] = TelemetryCache.toHex(bytes)
        store.longs["received_0"] = 0L
        val model = VentilationComplicationModel.build(TelemetryCache(store), 1_000L)
        assertEquals("--", model.word)
        assertEquals("--", model.title)
    }

    @Test
    fun ventilationWithoutOutdoorReadingShowsPlaceholder() {
        val cache = cacheWith("two_tiles", 0L, focus = 1)
        cache.saveOutdoorOpenMeteo()
        assertEquals("--", VentilationComplicationModel.build(cache, 60_000L).word)
        // The outdoor tile as focused room gives no advice.
        assertEquals("--", VentilationComplicationModel.build(ventilation(0, null, null), 60_000L).word)
    }

    @Test
    fun ventilationOutdoorFromSensorSlot() {
        // Room 1 is the focused room, tile 0 (22.0 C / 40 %RH) is the outdoor slot.
        val cache = ventilation(1, null, null)
        val model = VentilationComplicationModel.build(cache, 60_000L)
        assertEquals("Zu", model.word)
        assertEquals(expectedDifference(-3.0, 65.0, 22.0, 40.0), model.title)
    }
}
