package com.rlcdpanel.phone.widget

import android.content.Context
import com.rlcdpanel.phone.TelemetrySnapshot
import com.rlcdpanel.phone.protocol.AlertMask
import com.rlcdpanel.phone.protocol.TelemetryFrame
import com.rlcdpanel.phone.protocol.TelemetryTile

/**
 * Last snapshot for the widget, kept in `SharedPreferences`, so the widget has data after the service stops.
 * The text format is `sequence;cell;alerts;receivedAt;t,h|t,h|...` with `-` for an empty value.
 */
object WidgetSnapshotStore {
    private const val PREFS = "climate_widget"
    private const val KEY = "snapshot"

    fun encode(snapshot: TelemetrySnapshot): String {
        val frame = snapshot.frame
        val tiles = frame.tiles.joinToString("|") { t ->
            val temp = t.temperatureC
            val rh = t.humidityPct
            if (temp != null && rh != null) "$temp,$rh" else "-"
        }
        return "${frame.sequence};${frame.cellPercent ?: "-"};${frame.alerts.rawValue};${snapshot.receivedAtMillis};$tiles"
    }

    /** Null for each text that does not decode. */
    fun decode(text: String?): TelemetrySnapshot? {
        if (text == null) return null
        val parts = text.split(";")
        if (parts.size != 5) return null
        return try {
            val tiles = parts[4].split("|").map { item ->
                if (item == "-") {
                    TelemetryTile(null, null)
                } else {
                    val v = item.split(",")
                    if (v.size != 2) return null
                    val t = v[0].toDouble()
                    val h = v[1].toDouble()
                    if (!t.isFinite() || !h.isFinite()) return null
                    TelemetryTile(t, h)
                }
            }
            if (tiles.size != TelemetryFrame.TOTAL_TILES && tiles.size != TelemetryFrame.TILE_COUNT) return null
            val frame = TelemetryFrame(
                sequence = parts[0].toInt(),
                cellPercent = parts[1].toIntOrNull(),
                alerts = AlertMask(parts[2].toInt()),
                tiles = tiles
            )
            TelemetrySnapshot(frame = frame, rssi = 0, address = null, receivedAtMillis = parts[3].toLong())
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun save(context: Context, snapshot: TelemetrySnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encode(snapshot)).apply()
    }

    fun load(context: Context): TelemetrySnapshot? =
        decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
}
