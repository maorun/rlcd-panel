package com.rlcdpanel.phone.service

import com.rlcdpanel.phone.protocol.AlertMask
import com.rlcdpanel.phone.protocol.ClimateMath
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.TelemetryFrame
import java.util.Locale

/**
 * Text of the alert notification. The climate alerts name the room that caused them; every other bit,
 * and a frame without a usable tile, keeps the generic alert name.
 */
object AlertNotification {
    private const val BIT_FREEZER = 0
    private const val BIT_HUMIDITY = 1
    private const val BIT_HEAT = 7
    /** Firmware default of `climate_alert_tiles` (63): the onboard tile and slots 1 to 5. */
    private const val DEFAULT_TILES = 63

    /**
     * Notification text of alert [bit] (0 to 7); empty for a bit outside this range.
     * [frame] must be the merged 11-tile frame, so that a tile index is also a tile name index.
     * The panel sends the two pages one after the other, so a tile of the other page can come from an earlier frame.
     */
    fun text(bit: Int, frame: TelemetryFrame?, config: PanelConfig): String {
        if (bit !in 0..7) return ""
        val generic = AlertMask(1 shl bit).names.joinToString(", ")
        if (frame == null) return generic
        val tiles = candidateTiles(frame, config)
        return when (bit) {
            BIT_FREEZER -> tiles.minByOrNull { it.temperature }?.let {
                "$generic: ${config.tileName(it.index)} ${format1(it.temperature)} °C"
            }
            BIT_HUMIDITY -> tiles.maxByOrNull { it.humidity }?.let {
                "$generic: ${config.tileName(it.index)} ${Math.round(it.humidity)} %RH"
            }
            BIT_HEAT -> tiles.mapNotNull { tile -> ClimateMath.heatIndexC(tile.temperature, tile.humidity)?.let { tile to it } }
                .maxByOrNull { it.second }?.let { (tile, feels) ->
                    "$generic: ${config.tileName(tile.index)} feels like ${format1(feels)} °C"
                }
            else -> null
        } ?: generic
    }

    /** Text for every set bit of [mask], lowest bit first, joined with a comma. */
    fun text(mask: AlertMask, frame: TelemetryFrame?, config: PanelConfig): String =
        (0..7).filter { mask.rawValue shr it and 1 == 1 }.joinToString(", ") { text(it, frame, config) }

    private class Candidate(val index: Int, val temperature: Double, val humidity: Double)

    private fun candidateTiles(frame: TelemetryFrame, config: PanelConfig): List<Candidate> {
        val mask = config.values["climate_alert_tiles"]?.trim()?.toIntOrNull() ?: DEFAULT_TILES
        return frame.tiles.mapIndexedNotNull { index, tile ->
            val t = tile.temperatureC
            val h = tile.humidityPct
            if (t != null && h != null && mask shr index and 1 == 1) Candidate(index, t, h) else null
        }
    }

    private fun format1(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
}
