package com.rlcdpanel.phone.ventilation

import android.content.SharedPreferences
import java.util.Locale

/** Place used for the Open-Meteo outdoor reading. */
data class OutdoorLocation(
    val latitude: Double = DEFAULT_LATITUDE,
    val longitude: Double = DEFAULT_LONGITUDE,
    val name: String = DEFAULT_NAME
) {
    companion object {
        const val DEFAULT_LATITUDE = 52.52
        const val DEFAULT_LONGITUDE = 13.41
        const val DEFAULT_NAME = "Berlin"
    }
}

/** Minimal key-value access, so the store runs in host unit tests without Android. */
interface LocationPrefs {
    fun getString(key: String): String?
    fun putStrings(values: Map<String, String>)
}

class SharedLocationPrefs(private val prefs: SharedPreferences) : LocationPrefs {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putStrings(values: Map<String, String>) {
        val editor = prefs.edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
    }
}

/** Result of a location input check. */
sealed class LocationInput {
    data class Valid(val location: OutdoorLocation) : LocationInput()
    data class Invalid(val message: String) : LocationInput()
}

/**
 * Stores the outdoor location in `SharedPreferences`. The values are text, so a coordinate keeps exactly
 * 4 decimals. The store never asks for a location permission.
 */
class OutdoorLocationStore(private val prefs: LocationPrefs) {

    fun load(): OutdoorLocation {
        val lat = prefs.getString(KEY_LAT)?.let { parseCoordinate(it, LAT_LIMIT) }
        val lon = prefs.getString(KEY_LON)?.let { parseCoordinate(it, LON_LIMIT) }
        if (lat == null || lon == null) return OutdoorLocation()
        val name = prefs.getString(KEY_NAME)?.trim().orEmpty().ifEmpty { OutdoorLocation.DEFAULT_NAME }
        return OutdoorLocation(lat, lon, name.take(MAX_NAME_LENGTH))
    }

    /** Validates the three texts and stores them. A rejected input changes nothing. */
    fun save(latitudeText: String, longitudeText: String, nameText: String): LocationInput {
        val result = validate(latitudeText, longitudeText, nameText)
        if (result is LocationInput.Valid) {
            val location = result.location
            prefs.putStrings(
                mapOf(
                    KEY_LAT to formatCoordinate(location.latitude),
                    KEY_LON to formatCoordinate(location.longitude),
                    KEY_NAME to location.name
                )
            )
        }
        return result
    }

    companion object {
        const val PREFS_NAME = "rlcd_outdoor_location"
        private const val KEY_LAT = "latitude"
        private const val KEY_LON = "longitude"
        private const val KEY_NAME = "place_name"
        private const val LAT_LIMIT = 90.0
        private const val LON_LIMIT = 180.0
        const val MAX_NAME_LENGTH = 40
        private val NUMBER = Regex("[+-]?\\d{1,3}(\\.\\d{1,12})?")

        fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.4f", value)

        /** Returns the value rounded to 4 decimals, or `null` for non-numeric text or a value out of range. */
        fun parseCoordinate(text: String, limit: Double): Double? {
            val plain = text.trim().replace(',', '.')
            if (!NUMBER.matches(plain)) return null
            val value = plain.toDoubleOrNull() ?: return null
            if (!value.isFinite() || value < -limit || value > limit) return null
            return formatCoordinate(value).toDouble()
        }

        fun validate(latitudeText: String, longitudeText: String, nameText: String): LocationInput {
            val lat = parseCoordinate(latitudeText, LAT_LIMIT)
                ?: return LocationInput.Invalid("Breitengrad: Zahl von -90 bis 90")
            val lon = parseCoordinate(longitudeText, LON_LIMIT)
                ?: return LocationInput.Invalid("Längengrad: Zahl von -180 bis 180")
            val name = nameText.trim().take(MAX_NAME_LENGTH).ifEmpty { OutdoorLocation.DEFAULT_NAME }
            return LocationInput.Valid(OutdoorLocation(lat, lon, name))
        }
    }
}
