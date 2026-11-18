package com.rlcdpanel.phone.ventilation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutdoorLocationStoreTest {
    private class MemoryPrefs : LocationPrefs {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putStrings(values: Map<String, String>) {
            this.values.putAll(values)
        }
    }

    private val prefs = MemoryPrefs()
    private val store = OutdoorLocationStore(prefs)

    @Test
    fun defaultIsBerlin() {
        val location = store.load()
        assertEquals(52.52, location.latitude, 0.0)
        assertEquals(13.41, location.longitude, 0.0)
        assertEquals("Berlin", location.name)
    }

    @Test
    fun roundTripKeepsFourDecimals() {
        val result = store.save("48.13743", "11,57549", " München ")
        assertTrue(result is LocationInput.Valid)
        val location = OutdoorLocationStore(prefs).load()
        assertEquals(48.1374, location.latitude, 0.0)
        assertEquals(11.5755, location.longitude, 0.0)
        assertEquals("München", location.name)
    }

    @Test
    fun rejectsLatitude91AndKeepsStoredValue() {
        store.save("48.1374", "11.5755", "München")
        assertTrue(store.save("91", "13.41", "X") is LocationInput.Invalid)
        assertEquals(48.1374, store.load().latitude, 0.0)
    }

    @Test
    fun rejectsLongitudeMinus181() {
        assertTrue(store.save("52.52", "-181", "X") is LocationInput.Invalid)
        assertEquals(OutdoorLocation(), store.load())
    }

    @Test
    fun acceptsRangeLimits() {
        assertTrue(store.save("-90", "180", "Pol") is LocationInput.Valid)
        assertTrue(store.save("90", "-180", "Pol") is LocationInput.Valid)
    }

    @Test
    fun rejectsNonNumericText() {
        assertTrue(store.save("abc", "13.41", "X") is LocationInput.Invalid)
        assertTrue(store.save("52.52", "", "X") is LocationInput.Invalid)
        assertTrue(store.save("NaN", "13.41", "X") is LocationInput.Invalid)
        assertTrue(store.save("52.52", "Infinity", "X") is LocationInput.Invalid)
        assertTrue(store.save("52.52f", "13.41", "X") is LocationInput.Invalid)
        assertTrue(store.save("1e1", "13.41", "X") is LocationInput.Invalid)
        assertTrue(prefs.values.isEmpty())
    }

    @Test
    fun emptyNameFallsBackToDefault() {
        store.save("48.1374", "11.5755", "  ")
        assertEquals("Berlin", store.load().name)
    }

    @Test
    fun corruptStoredValueFallsBackToDefault() {
        prefs.values["latitude"] = "99.0000"
        prefs.values["longitude"] = "11.0000"
        assertEquals(OutdoorLocation(), store.load())
    }

    @Test
    fun requestUrlFormatsFourDecimals() {
        val url = OutdoorWeatherService.requestUrl(48.1, -11.57549)
        assertTrue(url.contains("latitude=48.1000"))
        assertTrue(url.contains("longitude=-11.5755"))
    }

    @Test
    fun locationChangeClearsCachedReading() {
        val berlin = OutdoorLocation()
        val munich = OutdoorLocation(48.1374, 11.5755, "München")
        val reading = OutdoorClimate(8.0, 70.0, "Berlin", 1L)
        assertEquals(reading, OutdoorResolver.cacheFor(reading, berlin, berlin))
        assertNull(OutdoorResolver.cacheFor(reading, berlin, munich))
        assertNull(OutdoorResolver.cacheFor(reading, null, berlin))
        assertNull(OutdoorResolver.cacheFor(null, berlin, berlin))
        val resolved = OutdoorResolver.resolve(
            OutdoorSource.OpenMeteo(), OutdoorResolver.cacheFor(reading, berlin, munich), null, null, 2L
        )
        assertNull(resolved)
    }

    @Test
    fun renameKeepsReadingAndLateReplyOfOldPlaceIsDropped() {
        val berlin = OutdoorLocation()
        val renamed = berlin.copy(name = "Mitte")
        val munich = OutdoorLocation(48.1374, 11.5755, "München")
        val reading = OutdoorClimate(8.0, 70.0, "Berlin", 1L)
        assertEquals(reading, OutdoorResolver.cacheFor(reading, berlin, renamed))
        assertTrue(OutdoorResolver.acceptFetched(berlin, renamed))
        assertTrue(!OutdoorResolver.acceptFetched(berlin, munich))
    }
}
