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
import com.rlcdpanel.watch.protocol.WeekMoldText
import com.rlcdpanel.watch.protocol.WeekRow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

/** Result of one `WEEK <N>` read: the 7 rows of one sensor, or a text that tells why there are none. */
data class WeekState(
    val isReading: Boolean = false,
    val rows: List<WeekRow> = emptyList(),
    /** True after a successful read, so an empty reply shows its own text. */
    val loaded: Boolean = false,
    val message: String? = null
)

/** Texts of the week rows. */
object WeekText {
    const val NO_VALUE = "--"

    private val WEEKDAYS = mapOf(
        DayOfWeek.MONDAY to "Mo",
        DayOfWeek.TUESDAY to "Di",
        DayOfWeek.WEDNESDAY to "Mi",
        DayOfWeek.THURSDAY to "Do",
        DayOfWeek.FRIDAY to "Fr",
        DayOfWeek.SATURDAY to "Sa",
        DayOfWeek.SUNDAY to "So"
    )

    /** The seven rows of [sensor], day 0 (the day before the stamp) first. */
    fun daysOf(rows: List<WeekRow>, sensor: Int): List<WeekRow> = rows.filter { it.sensor == sensor }.sortedBy { it.day }

    /** `Mo 17.11.` of a `YYYY-MM-DD` date; an empty or bad date gives `--`. */
    fun dayLabel(row: WeekRow): String {
        val date = try {
            LocalDate.parse(row.date)
        } catch (e: DateTimeParseException) {
            return NO_VALUE
        }
        return "%s %02d.%02d.".format(Locale.ROOT, WEEKDAYS.getValue(date.dayOfWeek), date.dayOfMonth, date.monthValue)
    }

    /** `15.0 .. 19.5 °C`; `--` for a gap day or a missing pair. */
    fun temperature(row: WeekRow): String {
        val lo = row.temperatureMinC
        val hi = row.temperatureMaxC
        return if (lo == null || hi == null) NO_VALUE else "%.1f .. %.1f °C".format(Locale.ROOT, lo, hi)
    }

    /** `40 .. 55 %`; `--` for a gap day or a missing pair. */
    fun humidity(row: WeekRow): String {
        val lo = row.humidityMinPct
        val hi = row.humidityMaxPct
        return if (lo == null || hi == null) NO_VALUE else "%.0f .. %.0f %%".format(Locale.ROOT, lo, hi)
    }

    /** `Schimmel 5 h` with the warning mark above 0 hours; an empty text for `null` (7-column reply or gap day). */
    fun mold(row: WeekRow): String =
        row.moldHours?.let { (if (it > 0) "${SensorHealthText.WARNING} " else "") + "Schimmel $it h" } ?: ""
}

@Composable
fun WeekScreen(
    state: WeekState,
    roomName: String,
    sensor: Int,
    onRead: () -> Unit,
    onNextSensor: () -> Unit = {}
) {
    val listState = rememberScalingLazyListState()
    val days = WeekText.daysOf(state.rows, sensor)
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
                item { Text("Woche: $roomName", fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center) }
                item {
                    Chip(
                        onClick = onRead,
                        enabled = !state.isReading,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (state.isReading) "Lese Panel ..." else "Read week", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                item {
                    Chip(
                        onClick = onNextSensor,
                        enabled = !state.isReading,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text("Next sensor", fontSize = 12.sp, maxLines = 1) }
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
                if (state.loaded && days.isEmpty()) {
                    item { Text("Keine Tage für diesen Raum.", fontSize = 10.sp, color = Color(0xFFB0BEC5)) }
                }
                items(days) { row ->
                    Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(0.95f)) {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(WeekText.dayLabel(row), fontSize = 10.sp, color = Color(0xFFB0BEC5))
                            Text(WeekText.temperature(row), fontSize = 12.sp)
                            Text(WeekText.humidity(row), fontSize = 12.sp)
                            val mold = WeekText.mold(row)
                            if (mold.isNotEmpty()) Text(mold, fontSize = 10.sp, color = Color(0xFFB0BEC5))
                        }
                    }
                }
                WeekMoldText.summary(days)?.let { text ->
                    item { Text(text, fontSize = 12.sp, textAlign = TextAlign.Center) }
                }
                WeekMoldText.dry(days)?.let { text ->
                    item { Text(text, fontSize = 12.sp, textAlign = TextAlign.Center) }
                }
                WeekMoldText.heat(days)?.let { text ->
                    item { Text(text, fontSize = 12.sp, textAlign = TextAlign.Center) }
                }
            }
        }
    }
}
