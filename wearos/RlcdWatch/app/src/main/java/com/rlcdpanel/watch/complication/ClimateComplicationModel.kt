package com.rlcdpanel.watch.complication

import com.rlcdpanel.watch.protocol.TelemetryCache
import com.rlcdpanel.watch.protocol.TelemetryFrame
import kotlin.math.roundToInt

/**
 * Content of the watch face complication for one moment. [isEmpty] is true for an empty cache,
 * a failed decode, a stale frame or a focused room without a valid reading; both texts then show
 * `--` and [humidityValue] is 0.
 */
data class ClimateComplicationModel(
    val temperatureText: String,
    val humidityText: String,
    val humidityValue: Float,
    val isEmpty: Boolean
) {
    companion object {
        const val PLACEHOLDER = "--"
        const val MIN_VALUE = 0f
        const val MAX_VALUE = 100f

        val EMPTY = ClimateComplicationModel(PLACEHOLDER, PLACEHOLDER, MIN_VALUE, true)

        /** Reads the focused room from [cache]. It does not scan BLE. */
        fun build(cache: TelemetryCache, nowMillis: Long): ClimateComplicationModel {
            val focused = cache.focusedRoom()
            val cached = cache.read(page = focused / TelemetryFrame.TILES_PER_PAGE)
            if (cached == null || cached.isStale(nowMillis)) {
                return EMPTY
            }
            // A page 1 frame holds the rooms 6 to 10 at the indices 0 to 4.
            val tile = cached.frame.tiles.getOrNull(focused % TelemetryFrame.TILES_PER_PAGE)
            val temperature = tile?.temperatureC
            val humidity = tile?.humidityPct
            if (temperature == null || humidity == null) {
                return EMPTY
            }
            return ClimateComplicationModel(
                temperatureText = "${temperature.roundToInt()}°",
                humidityText = "${humidity.roundToInt()}%",
                humidityValue = humidity.coerceIn(MIN_VALUE.toDouble(), MAX_VALUE.toDouble()).toFloat(),
                isEmpty = false
            )
        }
    }
}
