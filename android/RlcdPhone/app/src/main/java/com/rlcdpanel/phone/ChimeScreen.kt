package com.rlcdpanel.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.protocol.ChimeCommand

/** State of the chime screen: [muted] is null until a `STATUS` read or a `MUTE` command confirms it. */
data class ChimeState(
    val isBusy: Boolean = false,
    val muted: Boolean? = null,
    /** Text of the `CHIME` line of the last `STATUS` read; null without the line. */
    val reminder: String? = null,
    val message: String? = null,
    val isError: Boolean = false
)

@Composable
fun ChimeScreen(
    state: ChimeState,
    onRead: () -> Unit,
    onMute: (Boolean) -> Unit,
    onTest: (Int) -> Unit,
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
                    Button(onClick = onRead, enabled = !state.isBusy) {
                        Text(if (state.isBusy) "Sende…" else "Read mute state")
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when (state.muted) {
                            null -> "Mute: unbekannt"
                            true -> "Mute: an"
                            false -> "Mute: aus"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Switch(
                        checked = state.muted == true,
                        onCheckedChange = { onMute(it) },
                        enabled = !state.isBusy
                    )
                }
            }
            state.reminder?.let {
                item { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            state.message?.let {
                item {
                    Text(it, color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground)
                }
            }
            item {
                Text(
                    "Sendet MUTE und BEEP über die BLE-Konsole. Das Panel braucht USB-Strom und ble_console 2.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(ChimeCommand.ALERT_NAMES.indices.toList(), key = { it }) { bit ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("$bit: ${ChimeCommand.alertName(bit)}")
                    OutlinedButton(onClick = { onTest(bit) }, enabled = !state.isBusy) { Text("Test") }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
