package com.rlcdpanel.phone

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
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.protocol.TodayRow
import java.util.Locale

/** Result of one `TODAY` read: the rows, or a text that tells why there are none. */
data class TodayState(
    val isReading: Boolean = false,
    val rows: List<TodayRow> = emptyList(),
    val message: String? = null,
    /** True after a reply that parsed; an empty [rows] list then means the panel holds no sensor. */
    val hasRead: Boolean = false
)

object TodayText {
    private fun value(v: Double?, unit: String) = if (v == null) "--" else String.format(Locale.ROOT, "%.1f %s", v, unit)

    fun temperature(row: TodayRow): String =
        "Temperatur: min ${value(row.temperatureMinC, "°C")} • max ${value(row.temperatureMaxC, "°C")} • Ø ${value(row.temperatureMeanC, "°C")}"

    fun humidity(row: TodayRow): String =
        "Feuchte: min ${value(row.humidityMinPct, "%rF")} • max ${value(row.humidityMaxPct, "%rF")} • Ø ${value(row.humidityMeanPct, "%rF")}"
}

@Composable
fun TodayScreen(state: TodayState, onRead: () -> Unit, onBack: () -> Unit) {
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
                        Text(if (state.isReading) "Lese…" else "Read today")
                    }
                }
            }
            item {
                Text(
                    "Liest TODAY über die BLE-Konsole. Das Panel braucht USB-Strom und ble_console 1 oder 2.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (state.hasRead && state.rows.isEmpty() && state.message == null) {
                item { Text("Keine Sensoren", style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.rows, key = { it.sensor }) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.name, style = MaterialTheme.typography.titleMedium)
                        if (row.hours == 0) {
                            Text("Keine Daten seit Mitternacht", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text("${row.hours} h", style = MaterialTheme.typography.labelMedium)
                            Text(TodayText.temperature(row), style = MaterialTheme.typography.bodyMedium)
                            Text(TodayText.humidity(row), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
