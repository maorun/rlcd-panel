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
    onSwap: (String) -> Unit,
    onBack: () -> Unit
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    var swapping by remember { mutableStateOf<Int?>(null) }
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
            if (panelConfig.canWrite) {
                item {
                    OutdoorSelector(
                        panelConfig = panelConfig,
                        enabled = !state.isBusy,
                        onSelect = { slot ->
                            val commands = SlotConfig.setOutdoorSlot(slot, panelConfig)
                            if (commands.isNotEmpty()) onApply(commands)
                        }
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
                        val slotOn = panelConfig.slotEnabled(slot)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Slot $slot: ${storedName ?: "-"}",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = slotOn,
                                onCheckedChange = { onApply(SlotConfig.setEnabled(slot, it)) },
                                enabled = !state.isBusy && panelConfig.canWrite
                            )
                        }
                        if (SlotConfig.outdoorSlot(panelConfig) == slot) {
                            Text("Außensensor", color = MaterialTheme.colorScheme.primary)
                        }
                        if (!slotOn) Text("Deaktiviert", color = MaterialTheme.colorScheme.error)
                        Text("MAC: ${panelConfig.slotMac(slot) ?: "nicht fest"}")
                        Text("Format: ${formats[slot] ?: "-"}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editing = slot }, enabled = !state.isBusy && panelConfig.canWrite && slotOn) { Text("Bearbeiten") }
                            OutlinedButton(
                                onClick = { (SlotConfig.clear(slot) as? SlotConfig.Plan.Commands)?.let { onApply(it.commands) } },
                                enabled = !state.isBusy && panelConfig.canWrite
                            ) { Text("Leeren") }
                            if (panelConfig.canWrite) {
                                OutlinedButton(
                                    onClick = { swapping = slot },
                                    enabled = !state.isBusy && panelConfig.slotMac(slot) == null && slotOn
                                ) { Text("Tauschen mit …") }
                            }
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
    swapping?.let { slot ->
        SlotSwapDialog(
            slot = slot,
            panelConfig = panelConfig,
            onSwap = { command -> swapping = null; onSwap(command) },
            onDismiss = { swapping = null }
        )
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
private fun OutdoorSelector(panelConfig: PanelConfig, enabled: Boolean, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val active = SlotConfig.outdoorSlot(panelConfig)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Außensensor", style = MaterialTheme.typography.titleMedium)
            Text("outdoor_slot", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { open = true }, enabled = enabled) { Text(outdoorLabel(active, panelConfig)) }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Außensensor wählen") },
            text = {
                LazyColumn {
                    items((0..SlotAssignment.SLOTS.last).toList(), key = { it }) { slot ->
                        TextButton(onClick = { open = false; onSelect(slot) }) {
                            Text((if (slot == active) "✓ " else "") + outdoorLabel(slot, panelConfig))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text("Abbrechen") } }
        )
    }
}

private fun outdoorLabel(slot: Int, config: PanelConfig): String =
    if (slot == 0) "Keiner" else "Slot $slot" + (config.slotName(slot)?.let { ": $it" } ?: "")

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
                enabled = plan is SlotConfig.Plan.Commands && plan.commands.isNotEmpty() && panelConfig.canWrite
            ) { Text("Anwenden") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun SlotSwapDialog(
    slot: Int,
    panelConfig: PanelConfig,
    onSwap: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Slot $slot tauschen mit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (other in SlotAssignment.SLOTS.filter { it != slot }) {
                    val plan = SlotConfig.swap(slot, other, panelConfig)
                    TextButton(
                        onClick = { (plan as? SlotConfig.Plan.Commands)?.let { onSwap(it.commands.first()) } },
                        enabled = plan is SlotConfig.Plan.Commands && panelConfig.canWrite
                    ) { Text("Slot $other") }
                }
                Text("Nur Slots ohne feste MAC und nicht deaktiviert.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
