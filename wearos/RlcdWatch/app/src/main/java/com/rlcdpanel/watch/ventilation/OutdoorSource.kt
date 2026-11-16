package com.rlcdpanel.watch.ventilation

/**
 * Source for outdoor climate measurements.
 */
sealed class OutdoorSource {
    abstract val displayName: String

    data class OpenMeteo(
        val latitude: Double = 52.52,
        val longitude: Double = 13.41,
        val locationName: String = "Wetterdienst"
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

/**
 * Outdoor climate snapshot.
 */
data class OutdoorClimate(
    val tempC: Double,
    val rhPct: Double,
    val sourceName: String,
    val timestampMillis: Long
)
