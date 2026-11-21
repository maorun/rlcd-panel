package com.rlcdpanel.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import com.rlcdpanel.watch.console.BleConsoleClient
import com.rlcdpanel.watch.protocol.ConsoleReply
import com.rlcdpanel.watch.protocol.PanelHistoryRow
import com.rlcdpanel.watch.protocol.ReplyError
import java.util.Locale

/** Result of one `HISTORY <N>` read: the rows of the room, or a text that tells why there are none. */
data class HistoryState(
    val isReading: Boolean = false,
    val rows: List<PanelHistoryRow> = emptyList(),
    /** True after a successful read, so an empty reply shows its own text. */
    val loaded: Boolean = false,
    val message: String? = null
)

/** Texts of the room history. */
object HistoryText {
    const val NO_VALUE = "--"
    const val NO_DATA = "Keine Daten"
    const val OUT_OF_RANGE = "Panel not in range"

    /** Newest hours shown as rows. */
    const val ROW_COUNT = 6

    /** Rows of [sensor], newest hour (age 0) first. This is the filter for the full reply of an older firmware. */
    fun rowsOf(rows: List<PanelHistoryRow>, sensor: Int): List<PanelHistoryRow> =
        rows.filter { it.sensor == sensor }.sortedBy { it.ageHours }

    /** True when an older firmware rejects `HISTORY <N>`: `ERR BAD_ARG` or an unknown-command reply. Link and permission faults give no fallback. */
    fun needsFallback(reply: ConsoleReply): Boolean =
        reply is ConsoleReply.Status && (reply.line.startsWith("ERR BAD_ARG") || reply.line.startsWith("ERR UNKNOWN"))

    /** Text of a reply that holds no rows; a timeout gives [OUT_OF_RANGE]. */
    fun failure(reply: ConsoleReply): String =
        if (reply is ConsoleReply.Corrupt && reply.error == ReplyError.Timeout) OUT_OF_RANGE
        else BleConsoleClient.errorText(reply)

    /** `-<age> h <T> °C <RH> %`; a missing value gives `--`. */
    fun rowText(row: PanelHistoryRow): String {
        val t = row.temperatureC?.let { String.format(Locale.ROOT, "%.1f", it) } ?: NO_VALUE
        val h = row.humidityPct?.let { String.format(Locale.ROOT, "%.0f", it) } ?: NO_VALUE
        return "-${row.ageHours} h $t °C $h %"
    }

    /** The [ROW_COUNT] newest rows of [sensor] as text, newest first. */
    fun newestRows(rows: List<PanelHistoryRow>, sensor: Int): List<String> =
        rowsOf(rows, sensor).take(ROW_COUNT).map { rowText(it) }

    /** `Temp 20.5 .. 21.4 °C`; `--` without a temperature. */
    fun temperatureRange(rows: List<PanelHistoryRow>, sensor: Int): String {
        val v = rowsOf(rows, sensor).mapNotNull { it.temperatureC }
        return if (v.isEmpty()) "Temp $NO_VALUE" else String.format(Locale.ROOT, "Temp %.1f .. %.1f °C", v.min(), v.max())
    }

    /** `RH 45 .. 47 %`; `--` without a humidity. */
    fun humidityRange(rows: List<PanelHistoryRow>, sensor: Int): String {
        val v = rowsOf(rows, sensor).mapNotNull { it.humidityPct }
        return if (v.isEmpty()) "RH $NO_VALUE" else String.format(Locale.ROOT, "RH %.0f .. %.0f %%", v.min(), v.max())
    }
}

@Composable
fun HistoryScreen(
    state: HistoryState,
    roomName: String,
    sensor: Int,
    onRead: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    val hours = HistoryText.newestRows(state.rows, sensor)
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
                item { Text("Verlauf: $roomName", fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center) }
                item {
                    Chip(
                        onClick = onRead,
                        enabled = !state.isReading,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (state.isReading) "Lese Panel ..." else "Read history", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                state.message?.let { message ->
                    item {
                        Text(
                            text = message,
                            fontSize = 10.sp,
                            color = Color(0xFFFF8A80),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                if (!state.loaded && state.message == null) {
                    item {
                        Text(
                            "Tippe auf Read. Panel an USB, ble_console 1 oder 2.",
                            fontSize = 10.sp,
                            color = Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                if (state.loaded && hours.isEmpty()) {
                    item { Text(HistoryText.NO_DATA, fontSize = 10.sp, color = Color(0xFFB0BEC5)) }
                }
                if (hours.isNotEmpty()) {
                    item {
                        Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(0.95f)) {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text("24 h", fontSize = 10.sp, color = Color(0xFFB0BEC5))
                                Text(HistoryText.temperatureRange(state.rows, sensor), fontSize = 12.sp)
                                Text(HistoryText.humidityRange(state.rows, sensor), fontSize = 12.sp)
                            }
                        }
                    }
                    items(hours) { text -> Text(text, fontSize = 12.sp, maxLines = 1) }
                }
            }
        }
    }
}
