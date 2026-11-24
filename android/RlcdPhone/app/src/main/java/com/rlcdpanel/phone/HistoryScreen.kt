package com.rlcdpanel.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.protocol.ComparePoints
import com.rlcdpanel.phone.protocol.HistoryCurve
import com.rlcdpanel.phone.protocol.HistoryMetric
import com.rlcdpanel.phone.protocol.HistoryPoint
import com.rlcdpanel.phone.protocol.PanelHistoryRow
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.TelemetryFrame
import com.rlcdpanel.phone.protocol.TelemetryHistory
import com.rlcdpanel.phone.protocol.value
import java.util.Locale

/** State of the panel history read. `rows` hold the `HISTORY` reply; `readAtMillis` is the time of age 0. */
data class PanelHistoryState(
    val isReading: Boolean = false,
    val rows: List<PanelHistoryRow> = emptyList(),
    val readAtMillis: Long = 0L,
    val loaded: Boolean = false,
    val message: String? = null
)

object PanelHistoryChart {
    /** The panel samples hourly, so a gap above 90 minutes breaks the line. */
    const val MAX_GAP_MILLIS = 90L * 60 * 1000

    private const val HOUR_MILLIS = 60L * 60 * 1000

    /** Sensors with at least one temperature or humidity value, ascending. */
    fun sensors(rows: List<PanelHistoryRow>): List<Int> =
        rows.filter { it.temperatureC != null || it.humidityPct != null }.map { it.sensor }.distinct().sorted()

    /** Points of one sensor, oldest first. Age 0 sits at [readAtMillis], the read time; the newest hourly sample is up to 1 h older. */
    fun points(rows: List<PanelHistoryRow>, sensor: Int, readAtMillis: Long, curve: HistoryCurve): List<HistoryPoint> =
        rows.filter { it.sensor == sensor }.sortedByDescending { it.ageHours }.map {
            HistoryPoint(readAtMillis - it.ageHours * HOUR_MILLIS, it.value(curve))
        }
}

object HistoryScreenText {
    /** Newest rows in the shared text. A longer text risks a `TransactionTooLargeException`. */
    const val SHARE_MAX_ROWS = 1500

    fun range(points: List<HistoryPoint>, unit: String): String {
        val values = points.mapNotNull { it.value }
        if (values.isEmpty()) return "--"
        return String.format(Locale.ROOT, "%.1f–%.1f %s", values.min(), values.max(), unit)
    }
}

/** Line segments of a chart. A `null` value ends the current segment, so a gap stays open. */
object HistoryChartGeometry {
    /** A pause between two rows above this time breaks the line. */
    const val MAX_GAP_MILLIS = 30L * 60 * 1000

    fun segments(
        points: List<HistoryPoint>,
        fromMillis: Long,
        toMillis: Long,
        minValue: Double,
        maxValue: Double,
        maxGapMillis: Long = MAX_GAP_MILLIS
    ): List<List<Pair<Float, Float>>> {
        val span = (toMillis - fromMillis).coerceAtLeast(1L).toDouble()
        // A flat curve gets a fixed span and sits in the middle of the box.
        val flat = maxValue - minValue < 1e-6
        val low = if (flat) minValue - 0.5 else minValue
        val valueSpan = if (flat) 1.0 else maxValue - minValue
        val out = mutableListOf<MutableList<Pair<Float, Float>>>()
        var current: MutableList<Pair<Float, Float>>? = null
        var lastTime: Long? = null
        for (p in points) {
            val v = p.value
            if (lastTime != null && p.timeMillis - lastTime > maxGapMillis) current = null
            lastTime = p.timeMillis
            if (v == null) {
                current = null
                continue
            }
            val x = ((p.timeMillis - fromMillis) / span).toFloat().coerceIn(0f, 1f)
            val y = 1f - ((v - low) / valueSpan).toFloat().coerceIn(0f, 1f)
            if (current == null) {
                current = mutableListOf()
                out.add(current)
            }
            current.add(x to y)
        }
        return out
    }
}

@Composable
fun HistoryScreen(
    history: TelemetryHistory,
    panelConfig: PanelConfig,
    nowMillis: Long,
    panel: PanelHistoryState,
    showPanel: Boolean,
    onReadPanel: () -> Unit,
    onReadSensor: (Int) -> Unit,
    onLocal: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit
) {
    MaterialTheme(colorScheme = PhoneColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // The clock ticks each second. Round it to a minute, so the 20,000 rows are not scanned each second.
            val minute = nowMillis / 60_000L
            // The window ends one minute late, so rows of the last seconds stay in the chart.
            val windowNow = (minute + 1) * 60_000L
            val series = remember(history, minute) {
                (0 until TelemetryFrame.TILE_COUNT).associateWith { i ->
                    history.windowPoints(windowNow, i, HistoryMetric.TEMPERATURE) to
                        history.windowPoints(windowNow, i, HistoryMetric.HUMIDITY)
                }.filterValues { (temp, _) -> temp.any { it.value != null } }
            }
            val panelView = showPanel && panel.loaded
            var curve by remember { mutableStateOf(HistoryCurve.TEMPERATURE) }
            val panelSeries = remember(panel, curve) {
                PanelHistoryChart.sensors(panel.rows).associateWith { i ->
                    PanelHistoryChart.points(panel.rows, i, panel.readAtMillis, curve) to emptyList<HistoryPoint>()
                }.filterValues { (points, _) -> points.any { it.value != null } }
            }
            val shown = if (panelView) panelSeries else series
            val shownNow = if (panelView) panel.readAtMillis else windowNow
            val maxGap = if (panelView) PanelHistoryChart.MAX_GAP_MILLIS else HistoryChartGeometry.MAX_GAP_MILLIS
            val tiles = shown.keys.toList()
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { Spacer(modifier = Modifier.height(24.dp)) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onBack) { Text("Zurück") }
                        Button(onClick = onShare, enabled = history.rows.isNotEmpty() && !panelView) { Text("Share CSV") }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onReadPanel, enabled = !panel.isReading) {
                            Text(if (panel.isReading) "Lese Panel…" else "Panel-Verlauf lesen")
                        }
                        if (panelView) OutlinedButton(onClick = onLocal) { Text("Lokal") }
                    }
                }
                if (panelView) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in HistoryCurve.values()) {
                            if (c == curve) Button(onClick = {}) { Text(c.label, style = MaterialTheme.typography.labelSmall) }
                            else OutlinedButton(onClick = { curve = c }) { Text(c.label, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                item {
                    Text(
                        when {
                            panel.message != null -> panel.message
                            panelView -> "Panel-Verlauf: 24 Stundenwerte aus dem Speicher des Panels."
                            else -> "Lokaler Verlauf der empfangenen Frames."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                item {
                    if (!panelView) Text(
                        "Verlauf der letzten 24 Stunden. Der Verlauf wächst nur, solange die App scannt. Share CSV sendet die letzten ${HistoryScreenText.SHARE_MAX_ROWS} Zeilen.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (tiles.isEmpty()) {
                    item { Text("Noch keine Daten") }
                }
                if (!panelView && series.isNotEmpty()) {
                    item(key = "compare") { CompareCard(history, panelConfig, windowNow, minute) }
                }
                items(tiles, key = { it }) { index ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(panelConfig.tileName(index), style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = { onReadSensor(index) }, enabled = !panel.isReading) { Text("Nur diesen Raum lesen") }
                            val (temp, rh) = shown.getValue(index)
                            if (panelView) {
                                val color = if (curve == HistoryCurve.HUMIDITY || curve == HistoryCurve.ABSOLUTE_HUMIDITY) Color(0xFF4FC3F7) else Color(0xFFFF8A65)
                                MetricChart(temp, shownNow, curve.label, curve.unit, color, maxGap)
                            } else {
                                MetricChart(temp, shownNow, "Temperatur", "°C", Color(0xFFFF8A65), maxGap)
                                MetricChart(rh, shownNow, "Feuchte", "%RH", Color(0xFF4FC3F7), maxGap)
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun MetricChart(points: List<HistoryPoint>, nowMillis: Long, label: String, unit: String, color: Color, maxGapMillis: Long = HistoryChartGeometry.MAX_GAP_MILLIS) {
    val values = points.mapNotNull { it.value }
    val lo = values.minOrNull() ?: 0.0
    val hi = values.maxOrNull() ?: 1.0
    val from = nowMillis - TelemetryHistory.WINDOW_MILLIS
    val segments = HistoryChartGeometry.segments(points, from, nowMillis, lo, hi, maxGapMillis)
    Text("$label ${HistoryScreenText.range(points, unit)}", style = MaterialTheme.typography.bodySmall)
    Canvas(modifier = Modifier.fillMaxWidth().height(80.dp)) {
        drawLine(Color.Gray, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
        for (segment in segments) {
            if (segment.size == 1) {
                val (x, y) = segment[0]
                drawCircle(color, radius = 3f, center = Offset(x * size.width, y * size.height))
                continue
            }
            val path = androidx.compose.ui.graphics.Path()
            segment.forEachIndexed { i, (x, y) ->
                if (i == 0) path.moveTo(x * size.width, y * size.height) else path.lineTo(x * size.width, y * size.height)
            }
            drawPath(path, color, style = Stroke(width = 3f))
        }
    }
}

/** One color for each tile index, shared by the curves and the legend of the room comparison. */
private val COMPARE_COLORS = listOf(
    Color(0xFFFF8A65), Color(0xFF4FC3F7), Color(0xFF81C784), Color(0xFFFFD54F), Color(0xFFBA68C8), Color(0xFFF06292)
)

@Composable
private fun CompareCard(history: TelemetryHistory, panelConfig: PanelConfig, windowNow: Long, minute: Long) {
    var humidity by remember { mutableStateOf(false) }
    val metric = if (humidity) HistoryMetric.HUMIDITY else HistoryMetric.TEMPERATURE
    val compare: ComparePoints = remember(history, minute, metric) { history.comparePoints(windowNow, metric) }
    val unit = if (humidity) "%RH" else "°C"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Räume vergleichen", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (humidity) {
                    OutlinedButton(onClick = { humidity = false }) { Text("Temperatur") }
                    Button(onClick = {}) { Text("Feuchte") }
                } else {
                    Button(onClick = {}) { Text("Temperatur") }
                    OutlinedButton(onClick = { humidity = true }) { Text("Feuchte") }
                }
            }
            val lo = compare.min
            val hi = compare.max
            if (lo == null || hi == null) {
                Text("Noch keine Daten", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    if (lo == hi) String.format(Locale.ROOT, "Achse %.1f %s", lo, unit)
                    else String.format(Locale.ROOT, "Achse %.1f–%.1f %s", lo, hi, unit),
                    style = MaterialTheme.typography.bodySmall
                )
                val from = windowNow - TelemetryHistory.WINDOW_MILLIS
                val lines = compare.series.map { (index, points) ->
                    index to HistoryChartGeometry.segments(points, from, windowNow, lo, hi)
                }
                Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                    drawLine(Color.Gray, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
                    for ((index, segments) in lines) {
                        val color = COMPARE_COLORS[index % COMPARE_COLORS.size]
                        for (segment in segments) {
                            if (segment.size == 1) {
                                val (x, y) = segment[0]
                                drawCircle(color, radius = 3f, center = Offset(x * size.width, y * size.height))
                                continue
                            }
                            val path = androidx.compose.ui.graphics.Path()
                            segment.forEachIndexed { i, (x, y) ->
                                if (i == 0) path.moveTo(x * size.width, y * size.height) else path.lineTo(x * size.width, y * size.height)
                            }
                            drawPath(path, color, style = Stroke(width = 3f))
                        }
                    }
                }
                for (index in compare.series.keys) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Canvas(modifier = Modifier.height(12.dp).width(12.dp)) {
                            drawRect(COMPARE_COLORS[index % COMPARE_COLORS.size])
                        }
                        Text(panelConfig.tileName(index), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
