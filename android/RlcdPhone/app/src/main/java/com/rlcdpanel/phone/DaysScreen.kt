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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.protocol.DaysReply
import com.rlcdpanel.phone.protocol.DaysRow
import com.rlcdpanel.phone.protocol.MoldRoomSummary
import com.rlcdpanel.phone.protocol.MoldSummary
import com.rlcdpanel.phone.protocol.PanelConfig

/** Result of one `DAYS` read: the rows (oldest first), or a text that tells why there are none. */
data class DaysState(
    val isReading: Boolean = false,
    val rows: List<DaysRow> = emptyList(),
    val message: String? = null
)

/** One chart of one sensor over the rows (oldest first): null marks a day without a value. */
class DaysSeries(val low: List<Double?>, val high: List<Double?>) {
    /** Scale over all days; the span is at least [minSpan], centered on the data and kept inside [floor] to [ceiling]. */
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

object DaysChartModel {
    const val TEMP_MIN_SPAN = 2.0
    const val RH_MIN_SPAN = 5.0
    const val RH_MAX = 100.0
    const val MOLD_MAX_H = 24

    fun temperature(rows: List<DaysRow>, sensor: Int) =
        DaysSeries(rows.map { it.sensors[sensor].temperatureMinC }, rows.map { it.sensors[sensor].temperatureMaxC })

    fun humidity(rows: List<DaysRow>, sensor: Int) =
        DaysSeries(rows.map { it.sensors[sensor].humidityMinPct }, rows.map { it.sensors[sensor].humidityMaxPct })

    fun mold(rows: List<DaysRow>, sensor: Int): List<Int?> = rows.map { it.sensors[sensor].moldHours }

    /** The selected sensor when [rows] hold values for it, else the first sensor with values, else null. */
    fun effectiveSensor(rows: List<DaysRow>, selected: Int): Int? {
        val available = DaysReply.sensors(rows)
        return if (selected in available) selected else available.firstOrNull()
    }
}

/** Text of the mold cards above the rows: one card for each room that holds a mold value. */
object DaysMoldCards {
    const val NONE = "Keine Schimmelstunden"

    /** Rooms with a mold value, most mold hours first; a room without a mold value has no card. */
    fun cards(rows: List<DaysRow>, panelConfig: PanelConfig): List<MoldRoomSummary> =
        MoldSummary.build(rows, panelConfig).filter { it.totalHours != null }

    /** One text line for each fact of the card: [NONE] alone for a room with 0 mold hours. */
    fun lines(room: MoldRoomSummary): List<String> =
        if (room.totalHours == 0) listOf(NONE) else listOf(
            "Schimmelstunden: ${MoldSummary.totalText(room)}",
            "Tage mit Schimmel: ${MoldSummary.daysText(room)}",
            "Schlimmster Tag: ${MoldSummary.worstText(room)}"
        )

    /** `Trocken <N> h` (sum of the dry hours of [sensor] over [rows]), or null when no row holds a dry value. */
    fun dryLine(rows: List<DaysRow>, sensor: Int): String? {
        val hours = rows.mapNotNull { it.sensors[sensor].dryHours }
        return if (hours.isEmpty()) null else "Trocken ${hours.sum()} h"
    }
}

@Composable
private fun RangeBars(series: DaysSeries, range: Pair<Double, Double>?, color: Color, outline: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(90.dp)) {
        val n = series.low.size
        val slot = size.width / n
        val barW = slot * 0.7f
        val inset = (slot - barW) / 2f
        for (i in 0 until n) {
            val x = i * slot + inset
            val lo = series.low[i]
            val hi = series.high[i]
            if (range == null || lo == null || hi == null) {
                drawRect(outline, Offset(x, 0f), Size(barW, size.height), style = Stroke(width = 1f))
                continue
            }
            val span = (range.second - range.first).toFloat()
            val top = size.height * (1f - ((hi - range.first).toFloat() / span))
            val bottom = size.height * (1f - ((lo - range.first).toFloat() / span))
            drawRect(color, Offset(x, top), Size(barW, (bottom - top).coerceAtLeast(2f)))
        }
    }
}

@Composable
private fun MoldBars(hours: List<Int?>, color: Color, outline: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(60.dp)) {
        val slot = size.width / hours.size
        val barW = slot * 0.7f
        val inset = (slot - barW) / 2f
        hours.forEachIndexed { i, h ->
            val x = i * slot + inset
            if (h == null) {
                drawRect(outline, Offset(x, 0f), Size(barW, size.height), style = Stroke(width = 1f))
            } else {
                val barH = size.height * (h.coerceIn(0, DaysChartModel.MOLD_MAX_H).toFloat() / DaysChartModel.MOLD_MAX_H)
                drawRect(color, Offset(x, size.height - barH), Size(barW, barH.coerceAtLeast(if (h > 0) 2f else 0f)))
            }
        }
    }
}

@Composable
fun DaysScreen(
    state: DaysState,
    selectedRows: Int,
    selectedSensor: Int,
    panelConfig: PanelConfig,
    onSelectRows: (Int) -> Unit,
    onSelectSensor: (Int) -> Unit,
    onRead: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit
) {
    val barColor = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val sensor = DaysChartModel.effectiveSensor(state.rows, selectedSensor)
    val moldCards = remember(state.rows, panelConfig) { DaysMoldCards.cards(state.rows, panelConfig) }
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
                        Text(if (state.isReading) "Lese…" else "Tage lesen")
                    }
                    OutlinedButton(onClick = onShare, enabled = state.rows.isNotEmpty()) { Text("CSV teilen") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DaysReply.CHOICES.forEach { n ->
                        FilterChip(
                            selected = n == selectedRows,
                            onClick = { onSelectRows(n) },
                            enabled = !state.isReading,
                            label = { Text("$n") }
                        )
                    }
                }
            }
            item {
                Text(
                    "Liest DAYS <N> über die BLE-Konsole, eine Zeile je Tag. Das Panel braucht USB-Strom, ble_console 1 oder 2 und eine MicroSD-Karte.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(moldCards, key = { it.sensor }) { room ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(room.name, style = MaterialTheme.typography.titleMedium)
                        DaysMoldCards.lines(room).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        DaysMoldCards.dryLine(state.rows, room.sensor)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            if (sensor != null) {
                item {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DaysReply.sensors(state.rows).forEach { s ->
                            FilterChip(selected = s == sensor, onClick = { onSelectSensor(s) }, label = { Text(panelConfig.tileName(s)) })
                        }
                    }
                }
                item {
                    val temperature = DaysChartModel.temperature(state.rows, sensor)
                    val humidity = DaysChartModel.humidity(state.rows, sensor)
                    val mold = DaysChartModel.mold(state.rows, sensor)
                    val tempRange = temperature.scale(DaysChartModel.TEMP_MIN_SPAN)
                    val rhRange = humidity.scale(DaysChartModel.RH_MIN_SPAN, 0.0, DaysChartModel.RH_MAX)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${panelConfig.tileName(sensor)}, ${state.rows.size} Tage", style = MaterialTheme.typography.titleMedium)
                            Text("Temperatur (°C)", style = MaterialTheme.typography.labelMedium)
                            RangeBars(temperature, tempRange, barColor, outline)
                            tempRange?.let { Text("%.1f bis %.1f".format(it.first, it.second), style = MaterialTheme.typography.bodySmall) }
                            Text("Feuchte (%rF)", style = MaterialTheme.typography.labelMedium)
                            RangeBars(humidity, rhRange, barColor, outline)
                            rhRange?.let { Text("%.0f bis %.0f".format(it.first, it.second), style = MaterialTheme.typography.bodySmall) }
                            if (mold.any { it != null }) {
                                Text("Schimmelstunden (h)", style = MaterialTheme.typography.labelMedium)
                                MoldBars(mold, barColor, outline)
                                Text("0 bis 24, max. ${mold.filterNotNull().max()} h", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(DaysReply.dayLabel(state.rows.first().date), style = MaterialTheme.typography.labelSmall)
                                Text(DaysReply.dayLabel(state.rows.last().date), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            } else if (state.rows.isNotEmpty()) {
                item { Text("Keine Werte in den gelesenen Tagen", style = MaterialTheme.typography.bodyMedium) }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
