package com.rlcdpanel.watch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.rlcdpanel.watch.protocol.ChartLimit
import com.rlcdpanel.watch.protocol.ChartLimits
import com.rlcdpanel.watch.protocol.ConsoleReply
import com.rlcdpanel.watch.protocol.PanelConfig
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

/** Curve geometry of the 24-hour chart: x 0 is the oldest hour, 1 the newest; y 0 is the top, 1 the bottom. */
object HistoryChartGeometry {
    const val HOURS = 24

    /** Value range of [values] widened by [limits], so the curve and each limit line fit. Null without a value. */
    fun range(values: List<Double>, limits: List<ChartLimit>): Pair<Double, Double>? {
        val all = values + limits.map { it.value }
        if (values.isEmpty() || all.isEmpty()) return null
        return all.min() to all.max()
    }

    /** Runs of consecutive hours of [sensor] with a value; a missing value or hour ends a run. Oldest hour first. */
    fun segments(
        rows: List<PanelHistoryRow>, sensor: Int, value: (PanelHistoryRow) -> Double?, minValue: Double, maxValue: Double
    ): List<List<Pair<Float, Float>>> {
        val out = mutableListOf<MutableList<Pair<Float, Float>>>()
        var current: MutableList<Pair<Float, Float>>? = null
        var previousAge = -1
        for (row in HistoryText.rowsOf(rows, sensor).filter { it.ageHours in 0 until HOURS }.sortedByDescending { it.ageHours }) {
            val v = value(row)
            if (v == null) { current = null; continue }
            if (current == null || previousAge - row.ageHours != 1) {
                current = mutableListOf()
                out.add(current)
            }
            previousAge = row.ageHours
            val x = (HOURS - 1 - row.ageHours).toFloat() / (HOURS - 1)
            current.add(x to LimitLineGeometry.y(v, minValue, maxValue))
        }
        return out
    }
}

/**
 * Y position (0 top, 1 bottom) of a limit line, with the same scale as [HistoryChartGeometry.segments].
 * Keep the scale rule equal to `LimitLineGeometry` in `android/RlcdPhone/.../HistoryScreen.kt` (this file is not in the protocol sync list).
 */
object LimitLineGeometry {
    fun y(value: Double, minValue: Double, maxValue: Double): Float {
        val flat = maxValue - minValue < 1e-6
        val low = if (flat) minValue - 0.5 else minValue
        val span = if (flat) 1.0 else maxValue - minValue
        return 1f - ((value - low) / span).toFloat().coerceIn(0f, 1f)
    }

    fun legend(limits: List<ChartLimit>, unit: String): String =
        limits.joinToString(", ") { String.format(Locale.ROOT, "%s %.1f %s", it.label, it.value, unit) }
}

private val LIMIT_COLOR = Color(0xFFE53935)

/** 24-hour curve with one dashed line for each active alert limit; the limits widen the range. */
@Composable
private fun MetricChart(rows: List<PanelHistoryRow>, sensor: Int, unit: String, color: Color, limits: List<ChartLimit>, value: (PanelHistoryRow) -> Double?) {
    val values = HistoryText.rowsOf(rows, sensor).filter { it.ageHours in 0 until HistoryChartGeometry.HOURS }.mapNotNull(value)
    val (lo, hi) = HistoryChartGeometry.range(values, limits) ?: return
    val segments = HistoryChartGeometry.segments(rows, sensor, value, lo, hi)
    Canvas(modifier = Modifier.fillMaxWidth(0.85f).height(44.dp)) {
        drawLine(Color.Gray, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
        for (limit in limits) {
            val y = LimitLineGeometry.y(limit.value, lo, hi) * size.height
            drawLine(LIMIT_COLOR, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }
        for (segment in segments) {
            if (segment.size == 1) {
                drawCircle(color, radius = 2.5f, center = Offset(segment[0].first * size.width, segment[0].second * size.height))
                continue
            }
            val path = androidx.compose.ui.graphics.Path()
            segment.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * size.width, y * size.height) else path.lineTo(x * size.width, y * size.height) }
            drawPath(path, color, style = Stroke(width = 2.5f))
        }
    }
    if (limits.isNotEmpty()) {
        Text("- - - ${LimitLineGeometry.legend(limits, unit)}", fontSize = 9.sp, color = LIMIT_COLOR, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
fun HistoryScreen(
    state: HistoryState,
    roomName: String,
    sensor: Int,
    onRead: () -> Unit,
    panelConfig: PanelConfig
) {
    val listState = rememberScalingLazyListState()
    val hours = HistoryText.newestRows(state.rows, sensor)
    val limits = remember(panelConfig) { ChartLimits.from(panelConfig) }
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
                    item { MetricChart(state.rows, sensor, "°C", Color(0xFFFF8A65), ChartLimits.temperature(limits)) { it.temperatureC } }
                    item { MetricChart(state.rows, sensor, "%RH", Color(0xFF4FC3F7), ChartLimits.humidity(limits)) { it.humidityPct } }
                    items(hours) { text -> Text(text, fontSize = 12.sp, maxLines = 1) }
                }
            }
        }
    }
}
