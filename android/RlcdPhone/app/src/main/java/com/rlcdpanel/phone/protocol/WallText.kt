package com.rlcdpanel.phone.protocol

import java.util.Locale

/** Wall surface humidity text of a climate card, from the panel tile of `outdoor_slot` (same value as the panel). */
object WallText {
    /** Wall humidity at or above this value is a warning (mold risk). */
    const val WARNING_RH_PCT = 80.0

    data class Result(val text: String, val warning: Boolean)

    fun isWarning(rhPct: Double): Boolean = rhPct >= WARNING_RH_PCT

    /** The panel tile of `outdoor_slot`, `null` without the key, for an invalid slot or an invalid tile. */
    fun outdoorTile(tiles: List<TelemetryTile>, config: PanelConfig): TelemetryTile? {
        val slot = config.values["outdoor_slot"]?.trim()?.toIntOrNull() ?: return null
        return tiles.getOrNull(slot)?.takeIf { slot in 1..5 && it.isValid }
    }

    /**
     * Text `Wall <N>%` and warning flag for tile [index], or `null`. [outdoor] must be a fresh tile of
     * `outdoor_slot` (`null` when missing or stale). The caller hides stale cards.
     */
    fun format(tile: TelemetryTile, index: Int, outdoor: TelemetryTile?, config: PanelConfig): Result? {
        val pct = config.values["wall_frsi_pct"]?.trim()?.toIntOrNull() ?: return null
        if (pct == 0) return null
        val outSlot = config.values["outdoor_slot"]?.trim()?.toIntOrNull() ?: return null
        if (index == outSlot) return null
        val tIn = tile.temperatureC ?: return null
        val rhIn = tile.humidityPct ?: return null
        val tOut = outdoor?.temperatureC ?: return null
        val wall = ClimateMath.wallSurfaceRh(tIn, rhIn, tOut, pct / 100.0) ?: return null
        return Result(String.format(Locale.US, "Wall %.0f%%", wall), isWarning(wall))
    }
}
