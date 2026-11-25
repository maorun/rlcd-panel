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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.WeekRow

/** Result of one `WEEK` read: the rows, or a text that tells why there are none. */
data class WeekState(
    val isReading: Boolean = false,
    val rows: List<WeekRow> = emptyList(),
    val message: String? = null
)

/** One chart of one sensor: seven days, oldest first, null for a gap day. */
data class WeekSeries(val low: List<Double?>, val high: List<Double?>) {
    val hasData: Boolean get() = low.any { it != null }

    /**
     * Shared scale of the seven bars; the span is at least [minSpan] and centered on the data.
     * [floor] and [ceiling] keep the scale inside a physical range, for example 0 to 100 %RH.
     */
    fun scale(minSpan: Double, floor: Double = Double.NEGATIVE_INFINITY, ceiling: Double = Double.POSITIVE_INFINITY): Pair<Double, Double>? {
        val lo = low.filterNotNull().minOrNull() ?: return null
        val hi = high.filterNotNull().maxOrNull() ?: return null
        if (hi - lo >= minSpan) return lo to hi
        val mid = (lo + hi) / 2
        var start = mid - minSpan / 2
        var end = mid + minSpan / 2
        if (start < floor) { end += floor - start; start = floor }
        if (end > ceiling) { start -= end - ceiling; end = ceiling }
        return start.coerceAtLeast(floor) to end
    }
}

object WeekChartModel {
    const val TEMP_MIN_SPAN = 2.0
    const val RH_MIN_SPAN = 5.0
    const val RH_MAX = 100.0
    const val HOURS_MAX_H = 24

    fun humidityScale(series: WeekSeries) = series.scale(RH_MIN_SPAN, 0.0, RH_MAX)

    /** Sensor numbers with at least one row, ascending. */
    fun sensors(rows: List<WeekRow>): List<Int> = rows.map { it.sensor }.distinct().sorted()

    /** Seven entries from day 6 (left) to day 0 (right); a missing row is a gap like the firmware gap. */
    private fun series(rows: List<WeekRow>, sensor: Int, low: (WeekRow) -> Double?, high: (WeekRow) -> Double?): WeekSeries {
        val byDay = rows.filter { it.sensor == sensor }.associateBy { it.day }
        val days = (6 downTo 0).map { byDay[it] }
        return WeekSeries(days.map { r -> r?.let(low) }, days.map { r -> r?.let(high) })
    }

    fun temperature(rows: List<WeekRow>, sensor: Int) = series(rows, sensor, { it.temperatureMinC }, { it.temperatureMaxC })
    fun humidity(rows: List<WeekRow>, sensor: Int) = series(rows, sensor, { it.humidityMinPct }, { it.humidityMaxPct })

    private fun hoursByDay(rows: List<WeekRow>, sensor: Int, hours: (WeekRow) -> Int?): List<Int?> {
        val byDay = rows.filter { it.sensor == sensor }.associateBy { it.day }
        return (6 downTo 0).map { day -> byDay[day]?.let(hours) }
    }

    /** Mold hours of seven days from day 6 (left) to day 0 (right); null for a gap day, a missing row or a reply without the column. */
    fun mold(rows: List<WeekRow>, sensor: Int): List<Int?> = hoursByDay(rows, sensor) { it.moldHours }

    /** Dry hours of seven days from day 6 (left) to day 0 (right); same rules as [mold]. */
    fun dry(rows: List<WeekRow>, sensor: Int): List<Int?> = hoursByDay(rows, sensor) { it.dryHours }

    /** Label of an hour bar row, or null when each value is null (no bar row is drawn). */
    private fun hoursLabel(hours: List<Int?>): String? =
        hours.filterNotNull().maxOrNull()?.let { "0 bis 24, max. $it h" }

    // Named wrappers: both rows must keep the same rule, so each one calls hoursLabel.
    fun moldLabel(hours: List<Int?>): String? = hoursLabel(hours)
    fun dryLabel(hours: List<Int?>): String? = hoursLabel(hours)

    /** `MM-DD` of the day, or empty without a date. */
    fun dayLabel(rows: List<WeekRow>, sensor: Int, day: Int): String =
        rows.firstOrNull { it.sensor == sensor && it.day == day }?.date?.takeIf { it.length == 10 }?.substring(5) ?: ""
}

@Composable
private fun WeekBars(range: Pair<Double, Double>?, series: WeekSeries, color: Color, outline: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(90.dp)) {
        val slot = size.width / 7f
        val barW = slot * 0.6f
        val inset = (slot - barW) / 2f
        for (i in 0 until 7) {
            val x = i * slot + inset
            val lo = series.low[i]
            val hi = series.high[i]
            if (range == null || lo == null || hi == null) {
                // A gap day is an empty bar: only the outline of the full height.
                drawRect(outline, Offset(x, 0f), Size(barW, size.height), style = Stroke(width = 2f))
                continue
            }
            val span = (range.second - range.first).toFloat()
            val top = size.height * (1f - ((hi - range.first).toFloat() / span))
            val bottom = size.height * (1f - ((lo - range.first).toFloat() / span))
            drawRect(color, Offset(x, top), Size(barW, (bottom - top).coerceAtLeast(3f)))
        }
    }
}

@Composable
private fun HourBars(hours: List<Int?>, color: Color, outline: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(60.dp)) {
        val slot = size.width / 7f
        val barW = slot * 0.6f
        val inset = (slot - barW) / 2f
        for (i in 0 until 7) {
            val x = i * slot + inset
            val h = hours[i]
            if (h == null) {
                drawRect(outline, Offset(x, 0f), Size(barW, size.height), style = Stroke(width = 2f))
                continue
            }
            val barH = size.height * (h.coerceIn(0, WeekChartModel.HOURS_MAX_H).toFloat() / WeekChartModel.HOURS_MAX_H)
            drawRect(color, Offset(x, size.height - barH), Size(barW, barH.coerceAtLeast(if (h > 0) 3f else 0f)))
        }
    }
}

@Composable
fun WeekScreen(
    state: WeekState,
    panelConfig: PanelConfig,
    onRead: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit
) {
    val barColor = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(modifier = Modifier.height(24.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Zurück") }
                    Button(onClick = onRead, enabled = !state.isReading) {
                        Text(if (state.isReading) "Lese…" else "Read 7-day chart")
                    }
                    OutlinedButton(onClick = onShare, enabled = state.rows.isNotEmpty()) { Text("CSV teilen") }
                }
            }
            item {
                Text(
                    "Liest WEEK über die BLE-Konsole. Das Panel braucht USB-Strom und ble_console 1 oder 2.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(WeekChartModel.sensors(state.rows), key = { it }) { sensor ->
                val temperature = WeekChartModel.temperature(state.rows, sensor)
                val humidity = WeekChartModel.humidity(state.rows, sensor)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(panelConfig.tileName(sensor), style = MaterialTheme.typography.titleMedium)
                        if (!temperature.hasData && !humidity.hasData) {
                            Text("Keine Daten", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("Temperatur (°C)", style = MaterialTheme.typography.labelMedium)
                        WeekBars(temperature.scale(WeekChartModel.TEMP_MIN_SPAN), temperature, barColor, outline)
                        temperature.scale(WeekChartModel.TEMP_MIN_SPAN)?.let {
                            Text("%.1f bis %.1f".format(it.first, it.second), style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Feuchte (%rF)", style = MaterialTheme.typography.labelMedium)
                        WeekBars(WeekChartModel.humidityScale(humidity), humidity, barColor, outline)
                        WeekChartModel.humidityScale(humidity)?.let {
                            Text("%.0f bis %.0f".format(it.first, it.second), style = MaterialTheme.typography.bodySmall)
                        }
                        val mold = WeekChartModel.mold(state.rows, sensor)
                        WeekChartModel.moldLabel(mold)?.let { label ->
                            Text("Schimmelstunden (h)", style = MaterialTheme.typography.labelMedium)
                            HourBars(mold, barColor, outline)
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                        val dry = WeekChartModel.dry(state.rows, sensor)
                        WeekChartModel.dryLabel(dry)?.let { label ->
                            Text("Trockenstunden (h)", style = MaterialTheme.typography.labelMedium)
                            HourBars(dry, MaterialTheme.colorScheme.tertiary, outline)
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            for (day in 6 downTo 0) {
                                Text(WeekChartModel.dayLabel(state.rows, sensor, day), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
