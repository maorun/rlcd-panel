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
import com.rlcdpanel.phone.protocol.ConfigCache
import com.rlcdpanel.phone.protocol.ConfigCacheInfo
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.SensorHealth

/** Result of one `STATUS` read: the rows, or a text that tells why there are none. */
data class SensorHealthState(
    val isReading: Boolean = false,
    val rows: List<SensorHealth> = emptyList(),
    val message: String? = null
)

/** Cache of the last `CONFIG` read: [info] is null without a cache; [message] tells a failed read. */
data class ConfigReadState(
    val isReading: Boolean = false,
    val info: ConfigCacheInfo? = null,
    val message: String? = null
)

/** One-line texts of the card fields. */
object SensorHealthText {
    fun rssi(row: SensorHealth): String = row.rssi?.let { "RSSI ${it.toInt()} dBm" } ?: "RSSI --"
    fun rate(row: SensorHealth): String = row.reception?.let { "Empfang ${it.received}/${it.scans} (${it.percent} %)" } ?: "Empfang --"
    fun battery(row: SensorHealth): String = row.battery?.let { "Batterie ${it.toInt()} %" } ?: "Batterie --"
}

@Composable
fun SensorHealthScreen(
    state: SensorHealthState,
    panelConfig: PanelConfig,
    onRead: () -> Unit,
    configState: ConfigReadState = ConfigReadState(),
    nowMillis: Long = 0L,
    onReadConfig: () -> Unit = {},
    onOpenCalibration: () -> Unit = {},
    onBack: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(modifier = Modifier.height(24.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Zurück") }
                    Button(onClick = onRead, enabled = !state.isReading && !configState.isReading) {
                        Text(if (state.isReading) "Lese…" else "Read sensor health")
                    }
                }
            }
            item {
                Button(onClick = onReadConfig, enabled = !state.isReading && !configState.isReading) {
                    Text(if (configState.isReading) "Lese…" else "Read panel config")
                }
            }
            item {
                OutlinedButton(onClick = onOpenCalibration) { Text("Calibration proposals") }
            }
            item {
                Text(
                    configState.info?.let { "Panel-Config: ${ConfigCache.ageText(it, nowMillis)} gelesen" }
                        ?: "Panel-Config: noch nicht gelesen",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            configState.info?.takeIf { it.rejectedLines.isNotEmpty() }?.let { info ->
                item {
                    Text(
                        "Panel lehnt Zeilen ab: ${info.rejectedLines.joinToString(", ")}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            configState.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                Text(
                    "Liest STATUS über die BLE-Konsole. Das Panel braucht USB-Strom und ble_console 1 oder 2.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(state.rows, key = { it.slot }) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Slot ${row.slot}: ${panelConfig.tileName(row.slot)}${row.badge?.let { " $it" } ?: ""}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(SensorHealthText.rssi(row))
                        Text(SensorHealthText.rate(row))
                        Text(SensorHealthText.battery(row))
                        Text("${row.verdict.name}: ${row.verdict.advice}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
