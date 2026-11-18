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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.protocol.HistoryMetric
import com.rlcdpanel.phone.protocol.HistoryPoint
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.TelemetryFrame
import com.rlcdpanel.phone.protocol.TelemetryHistory
import java.util.Locale

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
            val tiles = series.keys.toList()
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { Spacer(modifier = Modifier.height(24.dp)) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onBack) { Text("Zurück") }
                        Button(onClick = onShare, enabled = history.rows.isNotEmpty()) { Text("Share CSV") }
                    }
                }
                item {
                    Text(
                        "Verlauf der letzten 24 Stunden. Der Verlauf wächst nur, solange die App scannt. Share CSV sendet die letzten ${HistoryScreenText.SHARE_MAX_ROWS} Zeilen.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (tiles.isEmpty()) {
                    item { Text("Noch keine Daten") }
                }
                items(tiles, key = { it }) { index ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(panelConfig.tileName(index), style = MaterialTheme.typography.titleMedium)
                            val (temp, rh) = series.getValue(index)
                            MetricChart(temp, windowNow, "Temperatur", "°C", Color(0xFFFF8A65))
                            MetricChart(rh, windowNow, "Feuchte", "%RH", Color(0xFF4FC3F7))
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun MetricChart(points: List<HistoryPoint>, nowMillis: Long, label: String, unit: String, color: Color) {
    val values = points.mapNotNull { it.value }
    val lo = values.minOrNull() ?: 0.0
    val hi = values.maxOrNull() ?: 1.0
    val from = nowMillis - TelemetryHistory.WINDOW_MILLIS
    val segments = HistoryChartGeometry.segments(points, from, nowMillis, lo, hi)
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
