package com.rlcdpanel.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.rlcdpanel.watch.protocol.TodayRow
import java.util.Locale

/** Result of one `TODAY` read: the rows, or a text that tells why there are none. */
data class TodayState(
    val isReading: Boolean = false,
    val rows: List<TodayRow> = emptyList(),
    val message: String? = null,
    /** True after a reply that parsed; an empty [rows] list then means the panel holds no sensor. */
    val hasRead: Boolean = false,
    /** Selected room 0 to 10, or null for all rooms (`Alle`). */
    val sensor: Int? = null
)

/** Texts of the today screen. A sensor without a sample since midnight shows `--`. */
object TodayScreenText {
    const val IDLE = "Tippe auf Lesen für die Tagesspanne seit Mitternacht."

    private fun value(v: Double?): String = if (v == null) "--" else String.format(Locale.ROOT, "%.1f", v)

    fun hours(row: TodayRow): String = if (row.hours == 0) "--" else "${row.hours} h"

    /** Mold and dry hours of the day, for example `Mold 3 h · Dry -- h`. */
    fun moldDry(row: TodayRow): String = "Mold ${count(row.moldHours)} · Dry ${count(row.dryHours)}"

    /** Heat hours of the day, for example `Heat 2 h`; `Heat -- h` for null. */
    fun heat(row: TodayRow): String = "Heat ${count(row.heatHours)}"

    private fun count(v: Int?): String = if (v == null) "-- h" else "$v h"

    /** Minimum, maximum and mean of the temperature, for example `T 13.0 / 17.0 / Ø 15.0 °C`. */
    fun temperature(row: TodayRow): String =
        if (row.hours == 0) "--" else "T ${value(row.temperatureMinC)} / ${value(row.temperatureMaxC)} / Ø ${value(row.temperatureMeanC)} °C"

    /** Minimum, maximum and mean of the humidity, for example `RH 43.0 / 47.0 / Ø 45.0 %`. */
    fun humidity(row: TodayRow): String =
        if (row.hours == 0) "--" else "RH ${value(row.humidityMinPct)} / ${value(row.humidityMaxPct)} / Ø ${value(row.humidityMeanPct)} %"
}

@Composable
fun TodayScreen(
    state: TodayState,
    roomNames: List<String>,
    onSelect: (Int?) -> Unit,
    onRead: () -> Unit
) {
    val listState = rememberScalingLazyListState()
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
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val options: List<Pair<Int?, String>> = listOf<Pair<Int?, String>>(null to "Alle") +
                            roomNames.mapIndexed { index, name -> index to name }
                        options.forEach { (sensor, name) ->
                            CompactChip(
                                onClick = { onSelect(sensor) },
                                enabled = !state.isReading,
                                colors = if (state.sensor == sensor) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                                label = { Text(name, fontSize = 10.sp, maxLines = 1) }
                            )
                        }
                    }
                }
                item {
                    Chip(
                        onClick = onRead,
                        enabled = !state.isReading,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (state.isReading) "Lese TODAY ..." else "Read today", fontSize = 12.sp, maxLines = 1) }
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
                            TodayScreenText.IDLE,
                            fontSize = 10.sp,
                            color = Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                if (state.hasRead && state.rows.isEmpty() && state.message == null) {
                    item { Text(if (state.sensor == null) "Keine Sensoren" else "Keine Daten für den Raum", fontSize = 11.sp, color = Color(0xFFB0BEC5)) }
                }
                items(state.rows) { row ->
                    Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(0.95f)) {
                        Text("${row.name}  ${TodayScreenText.hours(row)}", fontSize = 12.sp, maxLines = 1, color = Color.White)
                        Text(TodayScreenText.temperature(row), fontSize = 10.sp, maxLines = 1)
                        Text(TodayScreenText.humidity(row), fontSize = 10.sp, maxLines = 1)
                        Text(TodayScreenText.moldDry(row), fontSize = 10.sp, maxLines = 1)
                        Text(TodayScreenText.heat(row), fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}
