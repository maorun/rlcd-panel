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
import com.rlcdpanel.phone.protocol.RejectRow

/** Result of one `REJECTS` read: the rows, the count of lost MACs, or a text that tells why there are none. */
data class RejectsState(
    val isReading: Boolean = false,
    val rows: List<RejectRow> = emptyList(),
    val dropped: Int = 0,
    /** True after a successful read, so an empty list shows its own text. */
    val loaded: Boolean = false,
    val message: String? = null
)

@Composable
fun RejectsScreen(
    state: RejectsState,
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
                        Text(if (state.isReading) "Lese…" else "Read rejected packets")
                    }
                }
            }
            item {
                Text(
                    "Liest REJECTS über die BLE-Konsole. Das Panel braucht USB-Strom und ble_console 1 oder 2. " +
                        "Die Liste zeigt den letzten BLE-Scan.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (state.dropped > 0) {
                item { Text("${state.dropped} weitere MACs sind nicht in der Liste.", style = MaterialTheme.typography.bodySmall) }
            }
            if (state.loaded && state.rows.isEmpty()) {
                item { Text("Keine abgelehnten Pakete.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.rows, key = { it.mac }) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${row.mac}  [${row.format}]", style = MaterialTheme.typography.titleSmall)
                        Text("${row.reason.wire}, ${row.count}x", style = MaterialTheme.typography.bodyMedium)
                        Text(row.fix, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
