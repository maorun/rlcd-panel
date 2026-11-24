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
import com.rlcdpanel.phone.protocol.ConfigRestorePlan

/** State of the backup screen: the busy flag, the loaded restore review and the last message. */
data class ConfigBackupState(
    val isBusy: Boolean = false,
    /** Review of the selected file; null until a file is loaded. */
    val plan: ConfigRestorePlan? = null,
    val fileName: String? = null,
    val message: String? = null,
    val isError: Boolean = false,
    /** True after a restore that stopped with applied keys: the cached values are behind the panel. */
    val needsRead: Boolean = false
)

@Composable
fun ConfigBackupScreen(
    state: ConfigBackupState,
    /** True when the cached panel config holds a `CONFIG` read. */
    hasConfig: Boolean,
    /** True when `ble_console` of the cached config is 2 (the panel takes `SET`). */
    consoleWritable: Boolean,
    onSave: () -> Unit,
    onPickFile: () -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit
) {
    val plan = state.plan
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(modifier = Modifier.height(24.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Zurück") }
                    Button(onClick = onSave, enabled = !state.isBusy) {
                        Text(if (state.isBusy) "Lese…" else "Save backup")
                    }
                    OutlinedButton(onClick = onPickFile, enabled = !state.isBusy) { Text("Restore backup") }
                }
            }
            item {
                Text(
                    "Save liest CONFIG über die BLE-Konsole und teilt die Werte als config.ini. " +
                        "Restore prüft eine Datei und sendet nur geänderte Schlüssel als SET. Das Panel braucht USB-Strom und " +
                        "ble_console 2. Die Datei enthält keine WLAN-Daten.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!hasConfig) {
                item { Text("Noch kein CONFIG gelesen. Restore vergleicht gegen die zuletzt gelesenen Werte.", style = MaterialTheme.typography.bodySmall) }
            }
            state.message?.let {
                item { Text(it, color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground) }
            }
            if (plan != null) {
                item { Text("Datei: ${state.fileName ?: "config.ini"}", style = MaterialTheme.typography.titleMedium) }
                items(plan.rejected) { Text(it.text, color = MaterialTheme.colorScheme.error) }
                if (plan.changes.isEmpty() && plan.rejected.isEmpty()) {
                    item { Text("Keine Änderungen. Die Datei entspricht den Werten des Panels.") }
                }
                items(plan.changes, key = { it.key }) { change ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(change.text)
                            plan.problems[change.key]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                if (!consoleWritable) {
                    item { Text("Set ble_console=2 to apply", color = MaterialTheme.colorScheme.error) }
                }
                item {
                    Button(onClick = onRestore, enabled = !state.isBusy && plan.canRestore(consoleWritable)) {
                        Text("Restore ${plan.proposals.size} changed")
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
