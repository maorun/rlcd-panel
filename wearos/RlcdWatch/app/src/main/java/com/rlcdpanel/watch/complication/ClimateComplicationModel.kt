package com.rlcdpanel.watch.complication

import com.rlcdpanel.watch.protocol.ClimateMath
import com.rlcdpanel.watch.protocol.PanelConfig
import com.rlcdpanel.watch.protocol.TelemetryCache
import com.rlcdpanel.watch.protocol.TelemetryFrame
import com.rlcdpanel.watch.ventilation.VentilationGuide
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

/**
 * Content of the ventilation advice complication. [word] is `Lüften`, `Zu` or `--`; [title] is
 * the absolute humidity difference in g/m3 (`+2.3`) or `--`. Both show `--` for an empty cache,
 * a failed decode, a stale frame, a room without reading and a missing outdoor source.
 */
data class VentilationComplicationModel(
    val word: String,
    val title: String,
    val roomName: String = ""
) {
    val isEmpty: Boolean get() = word == PLACEHOLDER

    companion object {
        const val PLACEHOLDER = "--"

        fun empty(roomName: String = "") = VentilationComplicationModel(PLACEHOLDER, PLACEHOLDER, roomName)

        /** Reads the focused room and the outdoor source from [cache]. It does not scan BLE. */
        fun build(
            cache: TelemetryCache,
            nowMillis: Long,
            config: PanelConfig = PanelConfig()
        ): VentilationComplicationModel {
            val focused = cache.focusedRoom()
            val roomName = config.tileName(focused)
            val page = focused / TelemetryFrame.TILES_PER_PAGE
            val cached = cache.read(page = page)
            if (cached == null || cached.isStale(nowMillis)) {
                return empty(roomName)
            }
            val tile = cached.frame.tiles.getOrNull(focused % TelemetryFrame.TILES_PER_PAGE)
            if (tile == null || !tile.isValid) {
                return empty(roomName)
            }
            val outdoor = cache.readOutdoor()
            val outdoorPage = outdoor.slotIndex?.let { it / TelemetryFrame.TILES_PER_PAGE } ?: page
            val outdoorCached = if (outdoorPage == page) cached else cache.read(page = outdoorPage)
            val advice = VentilationGuide.advise(
                tile.temperatureC, tile.humidityPct, focused, outdoor, outdoorCached, nowMillis
            ) ?: return empty(roomName)
            val word = VentilationGuide.shortWord(advice)
            if (word == PLACEHOLDER) {
                return empty(roomName)
            }
            return VentilationComplicationModel(
                word = word,
                title = VentilationGuide.shortDifference(advice),
                roomName = roomName
            )
        }
    }
}

/**
 * Content of the feels-like complication. [text] is the heat index rounded to whole degrees
 * (`27°`) or `--`; [value] is the heat index clamped to [MIN_VALUE] to [MAX_VALUE] for the ranged
 * type, 20 for `--`. Both show `--` for an empty cache, a failed decode, a stale frame, a room
 * without reading and an invalid heat index.
 */
data class FeelsComplicationModel(
    val text: String,
    val value: Float,
    val roomName: String = ""
) {
    val isEmpty: Boolean get() = text == PLACEHOLDER

    companion object {
        const val PLACEHOLDER = "--"
        const val MIN_VALUE = 20f
        const val MAX_VALUE = 45f

        fun empty(roomName: String = "") = FeelsComplicationModel(PLACEHOLDER, MIN_VALUE, roomName)

        /** Reads the focused room from [cache]. It does not scan BLE. */
        fun build(
            cache: TelemetryCache,
            nowMillis: Long,
            config: PanelConfig = PanelConfig()
        ): FeelsComplicationModel {
            val focused = cache.focusedRoom()
            val roomName = config.tileName(focused)
            val cached = cache.read(page = focused / TelemetryFrame.TILES_PER_PAGE)
            if (cached == null || cached.isStale(nowMillis)) {
                return empty(roomName)
            }
            val tile = cached.frame.tiles.getOrNull(focused % TelemetryFrame.TILES_PER_PAGE)
            val temperature = tile?.temperatureC
            val humidity = tile?.humidityPct
            if (temperature == null || humidity == null) {
                return empty(roomName)
            }
            val feels = ClimateMath.heatIndexC(temperature, humidity)
            if (feels == null || !feels.isFinite()) {
                return empty(roomName)
            }
            return FeelsComplicationModel(
                text = "${feels.roundToInt()}°",
                value = feels.coerceIn(MIN_VALUE.toDouble(), MAX_VALUE.toDouble()).toFloat(),
                roomName = roomName
            )
        }
    }
}
