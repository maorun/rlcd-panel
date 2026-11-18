package com.rlcdpanel.phone.ventilation

/**
 * Source for outdoor climate measurements.
 */
sealed class OutdoorSource {
    abstract val displayName: String

    data class OpenMeteo(
        val latitude: Double = OutdoorLocation.DEFAULT_LATITUDE,
        val longitude: Double = OutdoorLocation.DEFAULT_LONGITUDE,
        val locationName: String = OutdoorLocation.DEFAULT_NAME
    ) : OutdoorSource() {
        override val displayName: String
            get() = locationName
    }

    data class SensorSlot(
        val tileIndex: Int,
        val slotName: String
    ) : OutdoorSource() {
        override val displayName: String
            get() = slotName
    }
}

/** Open-Meteo source for a stored location. */
fun OutdoorLocation.toSource(): OutdoorSource.OpenMeteo =
    OutdoorSource.OpenMeteo(latitude, longitude, name)

/**
 * Outdoor climate snapshot.
 */
data class OutdoorClimate(
    val tempC: Double,
    val rhPct: Double,
    val sourceName: String,
    val timestampMillis: Long
)
