package com.rlcdpanel.phone.widget

import com.rlcdpanel.phone.TelemetrySnapshot
import com.rlcdpanel.phone.protocol.AlertMask
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.TelemetryFrame
import java.util.Locale

/** One row of the home screen widget. */
data class WidgetRow(
    val name: String,
    val temperature: String,
    val humidity: String,
    val badges: List<String>
)

/** Text of the widget for one moment. [isEmpty] is true when the widget shows [ClimateWidgetModel.EMPTY_VALUE]. */
data class WidgetState(
    val headline: String,
    val rows: List<WidgetRow>,
    val alertNames: List<String>,
    val ageText: String,
    val hint: String?
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

/** Builds the text of the home screen widget. Pure code: no Android classes, no BLE scan, no network. */
object ClimateWidgetModel {
    const val MAX_AGE_MS: Long = 35 * 60_000L
    const val MAX_ROWS = 4
    const val EMPTY_VALUE = "--"
    const val OPEN_HINT = "Open the app"
    const val BADGE_STALE = "STALE"
    const val BADGE_BAT_LOW = "BAT LOW"

    private val EMPTY = WidgetState(EMPTY_VALUE, emptyList(), emptyList(), EMPTY_VALUE, OPEN_HINT)

    /**
     * Rows for the onboard tile, each tile with a reading and each configured slot (fixed MAC or name), first
     * four only. A configured tile without its valid bit shows `--` and the `STALE` badge. An empty store
     * and a frame older than [MAX_AGE_MS] give the empty state.
     */
    fun build(snapshot: TelemetrySnapshot?, nowMs: Long, config: PanelConfig): WidgetState {
        if (snapshot == null) return EMPTY
        val ageMs = (nowMs - snapshot.receivedAtMillis).coerceAtLeast(0L)
        if (ageMs > MAX_AGE_MS) return EMPTY

        val frame = snapshot.frame
        val batteryAlert = frame.alerts.contains(AlertMask.SENSOR_BATTERY)
        val rows = ArrayList<WidgetRow>(MAX_ROWS)
        for (index in frame.tiles.indices) {
            if (rows.size >= MAX_ROWS) break
            val tile = frame.tiles[index]
            val tempC = tile.temperatureC
            val rhPct = tile.humidityPct
            val valid = tempC != null && rhPct != null
            val configured = index > 0 && (config.slotMac(index) != null || config.slotName(index) != null)
            if (index != 0 && !valid && !configured) continue
            val badges = ArrayList<String>(2)
            if (!valid) badges.add(BADGE_STALE)
            if (valid && batteryAlert && index != 0) badges.add(BADGE_BAT_LOW)
            rows.add(
                WidgetRow(
                    name = config.tileName(index),
                    temperature = if (tempC != null && rhPct != null) String.format(Locale.US, "%.1f°C", tempC) else EMPTY_VALUE,
                    humidity = if (tempC != null && rhPct != null) String.format(Locale.US, "%.0f%%", rhPct) else EMPTY_VALUE,
                    badges = badges
                )
            )
        }
        return WidgetState(
            headline = "RLCD Panel",
            rows = rows,
            alertNames = frame.alerts.names,
            ageText = ageText(ageMs),
            hint = null
        )
    }

    fun ageText(ageMs: Long): String {
        val minutes = ageMs / 60_000L
        return if (minutes < 1) "now" else "$minutes min ago"
    }

    /** Key of a frame for the update check: sequence number and page (page 1 can repeat the sequence of page 0). */
    fun updateKey(frame: TelemetryFrame): Int = frame.sequence or (frame.page shl 8)

    /** True for a frame with a sequence number that the last widget update did not show. */
    fun shouldRequestUpdate(lastKey: Int?, frame: TelemetryFrame): Boolean = lastKey != updateKey(frame)
}
