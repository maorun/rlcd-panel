package com.rlcdpanel.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.rlcdpanel.watch.protocol.BatteryForecast
import com.rlcdpanel.watch.protocol.LogGaps
import com.rlcdpanel.watch.protocol.TailReply
import com.rlcdpanel.watch.protocol.TailRow
import com.rlcdpanel.watch.protocol.TailSensor
import java.util.Locale

/** Result of one `TAIL` read: the rows (oldest first), or a text that tells why there are none. */
data class TailState(
    val isReading: Boolean = false,
    val rows: List<TailRow> = emptyList(),
    val message: String? = null,
    /** True after a reply that parsed; an empty [rows] list then means the log holds no row. */
    val hasRead: Boolean = false
)

/** The newest row of one room: [name] is the room name, [time] and [value] the text of that row. */
data class TailRoomLine(val name: String, val time: String, val value: String)

/** Texts of the tail screen. All functions are pure. */
object TailScreenText {
    /** Number of rows that a tap reads. */
    const val ROWS = 6
    const val COMMAND = "TAIL $ROWS"
    const val IDLE = "Tippe auf Lesen für die neuesten Logzeilen und die Lücken im Log."
    const val EMPTY = "Keine Zeilen im Log"

    /** Clear text of a `TAIL` error line, or null for an unknown line. */
    /** `Letzte <N> Zeilen`: the report covers only these rows, so older gaps stay unseen. */
    fun coverageText(rowCount: Int): String = "Letzte $rowCount Zeilen"

    fun errorText(line: String): String? = when (line.trim()) {
        "ERR NO_CARD" -> "Keine MicroSD-Karte im Panel"
        "ERR NO_FILE" -> "Keine Logdatei oder keine gültige Uhrzeit im Panel"
        "ERR READ_FAILED" -> "Die MicroSD-Karte lässt sich nicht lesen"
        else -> null
    }

    /** `MM-DD hh:mm` of a panel time stamp (`YYYY-MM-DD hh:mm:ss`). A bad format stays unchanged. */
    fun timeText(timestamp: String): String =
        if (timestamp.length == 19) timestamp.substring(5, 16) else timestamp

    private fun tenths(v: Double): String = String.format(Locale.ROOT, "%.1f", v).let { if (it == "-0.0") "0.0" else it }

    /** `<T> °C <H> %`; a missing value shows `--`. */
    fun valueText(sensor: TailSensor): String {
        val t = sensor.temperatureC?.let { tenths(it) } ?: "--"
        val h = sensor.humidityPct?.let { tenths(it) } ?: "--"
        return "$t °C  $h %"
    }

    const val FORECAST_HINT = "Battery forecast: read more rows"

    /**
     * One line `<name>: change near <date>` for each slot 1 to 10 with a battery forecast, in slot order.
     * [names] hold the tile names by tile index; a slot without a name entry shows `Slot <N>`.
     */
    fun forecastLines(rows: List<TailRow>, names: List<String>, limitPct: Double): List<String> =
        (1..TailReply.SLOT_COUNT).mapNotNull { slot ->
            val estimate = BatteryForecast.estimate(rows, slot, limitPct) ?: return@mapNotNull null
            "${names.getOrNull(slot) ?: "Slot $slot"}: ${BatteryForecast.text(estimate).replace("Battery change near", "change near")}"
        }

    /** The hint to read more rows when [rows] span fewer than 7 days, else null. */
    fun forecastHint(rows: List<TailRow>): String? = if (BatteryForecast.needsLongerLog(rows)) FORECAST_HINT else null

    private fun sensorOf(row: TailRow, tile: Int): TailSensor? =
        if (tile == 0) row.onboard else row.slots.getOrNull(tile - 1)

    private fun hasValue(sensor: TailSensor?): Boolean =
        sensor != null && (sensor.temperatureC != null || sensor.humidityPct != null)

    /**
     * One line for each room (tile 0 = onboard, 1 to 10 = slot) that has a value in at least one row.
     * The line shows the newest row with a value of the room. [rows] hold the oldest row first,
     * [names] the tile names by tile index. A room without a name entry shows `Onboard` or `Slot <N>`.
     */
    fun roomLines(rows: List<TailRow>, names: List<String>): List<TailRoomLine> =
        (0..TailReply.SLOT_COUNT).mapNotNull { tile ->
            val row = rows.lastOrNull { hasValue(sensorOf(it, tile)) } ?: return@mapNotNull null
            val sensor = sensorOf(row, tile) ?: return@mapNotNull null
            TailRoomLine(names.getOrNull(tile) ?: if (tile == 0) "Onboard" else "Slot $tile", timeText(row.timestamp), valueText(sensor))
        }
}

@Composable
fun TailScreen(
    state: TailState,
    roomNames: List<String>,
    forecastLimitPct: Double = BatteryForecast.DEFAULT_LIMIT_PCT,
    onRead: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    val gapReport = remember(state.rows) { LogGaps.report(state.rows) }
    val forecastLines = remember(state.rows, roomNames, forecastLimitPct) {
        TailScreenText.forecastLines(state.rows, roomNames, forecastLimitPct)
    }
    val forecastHint = remember(state.rows) { TailScreenText.forecastHint(state.rows) }
    val rooms = remember(state.rows, roomNames) { TailScreenText.roomLines(state.rows, roomNames) }
    MaterialTheme {
        Scaffold(
            timeText = { TimeText() },
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
        ) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    Chip(
                        onClick = onRead,
                        enabled = !state.isReading,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (state.isReading) "Lese TAIL ..." else "Read log tail", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                state.message?.let { message ->
                    item {
                        Text(
                            message,
                            fontSize = 10.sp,
                            color = Color(0xFFFF8A80),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                if (!state.hasRead && state.message == null && !state.isReading) {
                    item {
                        Text(
                            TailScreenText.IDLE,
                            fontSize = 10.sp,
                            color = Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                if (state.hasRead && state.rows.isEmpty() && state.message == null) {
                    item { Text(TailScreenText.EMPTY, fontSize = 11.sp, color = Color(0xFFB0BEC5)) }
                }
                if (state.hasRead && state.rows.isNotEmpty()) {
                    item {
                        Text(
                            LogGaps.summaryText(gapReport),
                            fontSize = 12.sp,
                            color = if (gapReport.gaps.isEmpty()) Color(0xFFA5D6A7) else Color(0xFFFFB74D),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    item { Text(TailScreenText.coverageText(gapReport.rowCount), fontSize = 10.sp, color = Color(0xFFB0BEC5)) }
                    // Newest gap first, like the phone screen.
                    items(gapReport.gaps.asReversed()) { gap ->
                        Text(
                            LogGaps.gapLine(gap),
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    if (forecastLines.isNotEmpty() || forecastHint != null) {
                        item {
                            Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(0.95f)) {
                                forecastLines.forEach { Text(it, fontSize = 10.sp, color = Color.White) }
                                forecastHint?.let { Text(it, fontSize = 10.sp, color = Color(0xFFB0BEC5)) }
                            }
                        }
                    }
                    items(rooms) { room ->
                        Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(0.95f)) {
                            Text("${room.name}  ${room.time}", fontSize = 12.sp, maxLines = 1, color = Color.White)
                            Text(room.value, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
