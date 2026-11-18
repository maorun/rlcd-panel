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
import com.rlcdpanel.phone.protocol.EventRow
import com.rlcdpanel.phone.protocol.EventText

/** Result of one `EVENTS` read: the rows oldest first, the count of lost events, or a text that tells why there are none. */
data class EventLogState(
    val isReading: Boolean = false,
    val rows: List<EventRow> = emptyList(),
    val dropped: Int = 0,
    /** True after a successful read, so an empty ring shows its own text. */
    val loaded: Boolean = false,
    val message: String? = null
) {
    /** The rows for the screen, newest first. */
    val newestFirst: List<EventRow> get() = rows.asReversed()
}

@Composable
fun EventLogScreen(
    state: EventLogState,
    onRead: () -> Unit,
    onBack: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(modifier = Modifier.height(24.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Zurück") }
                    Button(onClick = onRead, enabled = !state.isReading) {
                        Text(if (state.isReading) "Lese…" else "Ereignisse lesen")
                    }
                }
            }
            item {
                Text(
                    "Liest EVENTS über die BLE-Konsole. Das Panel braucht USB-Strom und ble_console 1 oder 2.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (state.dropped > 0) {
                item { Text("${state.dropped} ältere Ereignisse gingen verloren.", style = MaterialTheme.typography.bodySmall) }
            }
            if (state.loaded && state.rows.isEmpty()) {
                item { Text("Keine Ereignisse.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.newestFirst) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(row.time.ifEmpty { "Zeit unbekannt" }, style = MaterialTheme.typography.labelSmall)
                        Text(
                            EventText.describe(row),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (EventText.isAbnormal(row)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
