package com.rlcdpanel.watch.complication

import com.rlcdpanel.watch.protocol.PanelConfig
import com.rlcdpanel.watch.protocol.TelemetryCache
import com.rlcdpanel.watch.protocol.TelemetryFrame
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Content of the watch face complication for one moment. [isEmpty] is true for an empty cache,
 * a failed decode, a stale frame or a focused room without a valid reading; both texts then show
 * `--` and [humidityValue] is 0. [hasAlert] is true only for a fresh frame with an alert bit; then
 * [temperatureText] starts with `!` and shows one decimal (`!21.4°`, at most 7 characters) and
 * [alertNames] lists the active alerts.
 */
data class ClimateComplicationModel(
    val temperatureText: String,
    val humidityText: String,
    val humidityValue: Float,
    val isEmpty: Boolean,
    val roomName: String = "",
    val hasAlert: Boolean = false,
    val alertNames: List<String> = emptyList()
) {
    companion object {
        const val PLACEHOLDER = "--"
        const val MIN_VALUE = 0f
        const val MAX_VALUE = 100f

        val EMPTY = ClimateComplicationModel(PLACEHOLDER, PLACEHOLDER, MIN_VALUE, true)

        /** Content description: room name of [PanelConfig.tileName], then the active alert names. */
        fun describe(label: String, value: String, roomName: String, alertNames: List<String>): String {
            val text = if (roomName.isEmpty()) "$label $value" else "$roomName $label $value"
            return if (alertNames.isEmpty()) text else "$text, Alert ${alertNames.joinToString(", ")}"
        }

        /** Reads the focused room from [cache]. It does not scan BLE. */
        fun build(
            cache: TelemetryCache,
            nowMillis: Long,
            config: PanelConfig = PanelConfig()
        ): ClimateComplicationModel {
            val focused = cache.focusedRoom()
            val roomName = config.tileName(focused)
            val cached = cache.read(page = focused / TelemetryFrame.TILES_PER_PAGE)
            if (cached == null || cached.isStale(nowMillis)) {
                return EMPTY.copy(roomName = roomName)
            }
            // A page 1 frame holds the rooms 6 to 10 at the indices 0 to 4.
            val tile = cached.frame.tiles.getOrNull(focused % TelemetryFrame.TILES_PER_PAGE)
            val temperature = tile?.temperatureC
            val humidity = tile?.humidityPct
            if (temperature == null || humidity == null) {
                return EMPTY.copy(roomName = roomName)
            }
            val alerts = cached.frame.alerts
            val hasAlert = !alerts.isEmpty
            return ClimateComplicationModel(
                temperatureText = if (hasAlert) {
                    // Round first, so a reading of -0.04 gives `!0.0°` and not `!-0.0°`.
                    String.format(Locale.US, "!%.1f°", Math.round(temperature * 10) / 10.0)
                } else {
                    "${temperature.roundToInt()}°"
                },
                humidityText = "${humidity.roundToInt()}%",
                humidityValue = humidity.coerceIn(MIN_VALUE.toDouble(), MAX_VALUE.toDouble()).toFloat(),
                isEmpty = false,
                roomName = roomName,
                hasAlert = hasAlert,
                alertNames = alerts.names
            )
        }
    }
}
