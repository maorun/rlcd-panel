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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.finder.SlotAssignment
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.SlotConfig

/** State of the slot screen. [readOnly] shows the `ble_console=2` hint. */
data class SlotConfigState(
    val isBusy: Boolean = false,
    val readOnly: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false
)

@Composable
fun SlotConfigScreen(
    state: SlotConfigState,
    panelConfig: PanelConfig,
    /** Format badge of each slot from the last sensor health read (slot 1..10); empty before a read. */
    formats: Map<Int, String>,
    onApply: (List<String>) -> Unit,
    onBack: () -> Unit
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(modifier = Modifier.height(24.dp)) }
            item { OutlinedButton(onClick = onBack) { Text("Zurück") } }
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Neue Sensoren automatisch zuweisen", style = MaterialTheme.typography.titleMedium)
                        Text("slot_auto_assign", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = panelConfig.slotAutoAssign,
                        onCheckedChange = { onApply(SlotConfig.autoAssign(it)) },
                        enabled = !state.isBusy && panelConfig.canWrite
                    )
                }
            }
            if (state.readOnly || !panelConfig.canWrite) {
                item { Text(SlotAssignment.READ_ONLY_TEXT, color = MaterialTheme.colorScheme.error) }
            }
            state.message?.takeIf { !state.readOnly }?.let {
                item { Text(it, color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground) }
            }
            item {
                Text(
                    "Die Änderung braucht USB-Strom am Panel und ble_console 2.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(SlotAssignment.SLOTS.toList(), key = { it }) { slot ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val storedName = panelConfig.slotName(slot)
                        val keptName = if (storedName != null && panelConfig.slotMac(slot) == null) " (Name bleibt gespeichert)" else ""
                        Text("Slot $slot: ${storedName ?: "-"}$keptName", style = MaterialTheme.typography.titleMedium)
                        Text("MAC: ${panelConfig.slotMac(slot) ?: "nicht fest"}")
                        Text("Format: ${formats[slot] ?: "-"}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editing = slot }, enabled = !state.isBusy && panelConfig.canWrite) { Text("Bearbeiten") }
                            OutlinedButton(
                                onClick = { (SlotConfig.clear(slot) as? SlotConfig.Plan.Commands)?.let { onApply(it.commands) } },
                                enabled = !state.isBusy && panelConfig.canWrite
                            ) { Text("Leeren") }
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
    editing?.let { slot ->
        SlotEditorDialog(
            slot = slot,
            panelConfig = panelConfig,
            onApply = { commands -> editing = null; onApply(commands) },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun SlotEditorDialog(
    slot: Int,
    panelConfig: PanelConfig,
    onApply: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(panelConfig.slotName(slot) ?: "") }
    var mac by remember { mutableStateOf(panelConfig.slotMac(slot) ?: "") }
    val plan = SlotConfig.define(slot, mac.trim(), name.trim(), panelConfig)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Slot $slot bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.uppercase() },
                    label = { Text("Name (max. 12 Zeichen)") },
                    supportingText = { Text("Ein gespeicherter Name lässt sich nur ersetzen, nicht leeren.") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = mac,
                    onValueChange = { mac = it.uppercase() },
                    label = { Text("Feste MAC (AA:BB:CC:DD:EE:FF)") },
                    singleLine = true
                )
                (plan as? SlotConfig.Plan.Invalid)?.let { Text(it.reason, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { (plan as? SlotConfig.Plan.Commands)?.let { onApply(it.commands) } },
                enabled = plan is SlotConfig.Plan.Commands && panelConfig.canWrite
            ) { Text("Anwenden") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
