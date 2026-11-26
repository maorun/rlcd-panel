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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.protocol.BatteryForecast
import com.rlcdpanel.phone.protocol.LogGaps
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.TailCommand
import com.rlcdpanel.phone.protocol.TailRow

/** Result of one `TAIL` read: the rows (oldest first), or a text that tells why there are none. */
data class TailState(
    val isReading: Boolean = false,
    val rows: List<TailRow> = emptyList(),
    val message: String? = null
)

@Composable
fun TailScreen(
    state: TailState,
    selectedRows: Int,
    panelConfig: PanelConfig,
    onSelectRows: (Int) -> Unit,
    onRead: () -> Unit,
    onBack: () -> Unit
) {
    /** Slots with a configured name (`slotN_name` or the name bound to the fixed MAC); the tile index of slot N is N. */
    val namedSlots = remember(panelConfig) {
        (1..10).filter { panelConfig.slotName(it) != null || panelConfig.nameForMac(panelConfig.slotMac(it)) != null }
    }
    val forecastLimit = BatteryForecast.limitPct(panelConfig)
    val forecasts = remember(state.rows, forecastLimit, namedSlots) {
        namedSlots.associateWith { BatteryForecast.text(BatteryForecast.estimate(state.rows, it, forecastLimit)) }
    }
    val gapReport = remember(state.rows) { LogGaps.report(state.rows) }
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
                        Text(if (state.isReading) "Lese…" else "Neueste Zeilen lesen")
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TailCommand.CHOICES.forEach { n ->
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
                    "Liest TAIL <N> über die BLE-Konsole, eine Zeile je Stunde. Das Panel braucht USB-Strom, ble_console 1 oder 2 und eine MicroSD-Karte.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (state.rows.isNotEmpty()) {
                item { Text(LogGaps.summaryText(gapReport), style = MaterialTheme.typography.titleSmall) }
                gapReport.gaps.asReversed().forEach { gap ->
                    item { Text(LogGaps.gapLine(gap), style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (state.rows.isNotEmpty() && namedSlots.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            namedSlots.forEach { slot ->
                                Text(panelConfig.tileName(slot), style = MaterialTheme.typography.titleSmall)
                                Text(forecasts[slot] ?: BatteryForecast.NOT_ENOUGH_DATA, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (BatteryForecast.needsLongerLog(state.rows)) {
                                Text(BatteryForecast.LONGER_LOG_HINT, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            // Newest row first. The index is the key: the panel logs local time, so a clock change can repeat a time stamp.
            itemsIndexed(TailCommand.newestFirst(state.rows), key = { index, _ -> index }) { _, row ->
                val lines = TailCommand.rowLines(
                    row,
                    panelConfig.tileName(0),
                    namedSlots.map { it to panelConfig.tileName(it) }
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(lines.first(), style = MaterialTheme.typography.titleMedium)
                        lines.drop(1).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
