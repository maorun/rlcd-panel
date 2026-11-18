package com.rlcdpanel.phone

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.finder.FoundSensor
import com.rlcdpanel.phone.finder.SlotAssignment
import com.rlcdpanel.phone.protocol.PanelConfig

/** State of the sensor finder: the rows, the scan flag and the last message. */
data class SensorFinderState(
    val isScanning: Boolean = false,
    val isBusy: Boolean = false,
    val rows: List<FoundSensor> = emptyList(),
    val message: String? = null,
    val isError: Boolean = false
)

/** One-line texts of a finder row. */
object SensorFinderText {
    fun reading(row: FoundSensor): String {
        val ad = row.advertisement
        val battery = if (ad.batteryPct >= 0) ", Batterie ${ad.batteryPct} %" else ""
        return "%.1f °C, %d %%RH%s".format(ad.temperatureC, ad.humidityPct, battery)
    }

    /** Slot of the MAC in the cached config, or the free-slot hint. */
    fun slot(row: FoundSensor, config: PanelConfig): String =
        SlotAssignment.slotOf(row.advertisement.mac, config)?.let { "Slot $it" } ?: "Slot - (nicht fest zugewiesen)"
}

@Composable
fun SensorFinderScreen(
    state: SensorFinderState,
    panelConfig: PanelConfig,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onAssign: (slot: Int, mac: String, name: String) -> Unit,
    onBack: () -> Unit
) {
    var selectedMac by remember { mutableStateOf<String?>(null) }
    var slot by remember { mutableStateOf(1) }
    var name by remember { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(modifier = Modifier.height(24.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Zurück") }
                    if (state.isScanning) {
                        Button(onClick = onStopScan, enabled = !state.isBusy) { Text("Scan stoppen") }
                    } else {
                        Button(onClick = onScan, enabled = !state.isBusy) { Text("Sensoren suchen") }
                    }
                }
            }
            item {
                Text(
                    "Das Handy hört die Sensoren direkt ab. Zum Zuweisen braucht das Panel USB-Strom und ble_console 2.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.message?.let {
                item { Text(it, color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground) }
            }
            if (state.rows.isEmpty()) {
                item { Text(if (state.isScanning) "Suche läuft…" else "Keine Sensoren gefunden", style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.rows, key = { it.advertisement.mac }) { row ->
                val ad = row.advertisement
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${ad.mac} [${ad.format.badge}]", style = MaterialTheme.typography.titleMedium)
                        Text(SensorFinderText.reading(row))
                        Text("RSSI ${row.rssi} dBm, ${SensorFinderText.slot(row, panelConfig)}")
                        OutlinedButton(
                            onClick = { selectedMac = ad.mac; slot = SlotAssignment.slotOf(ad.mac, panelConfig) ?: slot },
                            enabled = !state.isBusy
                        ) { Text("Slot zuweisen") }
                    }
                }
            }
            selectedMac?.let { mac ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Zuweisen: $mac", style = MaterialTheme.typography.titleMedium)
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                for (n in SlotAssignment.SLOTS) {
                                    FilterChip(selected = slot == n, onClick = { slot = n }, label = { Text("$n") })
                                }
                            }
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it.take(SlotAssignment.MAX_NAME_CHARS).uppercase() },
                                label = { Text("Name (max. 12 Zeichen)") },
                                singleLine = true
                            )
                            val warning = SlotAssignment.warning(slot, mac, panelConfig)
                            val valid = SlotAssignment.batch(slot, mac, name) != null
                            warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (!valid) Text("Name: A-Z, 0-9 und - ohne Leerzeichen", style = MaterialTheme.typography.bodySmall)
                            Button(
                                onClick = { onAssign(slot, mac, name) },
                                enabled = valid && warning == null && !state.isBusy
                            ) { Text(if (state.isBusy) "Sende…" else "Anwenden") }
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
