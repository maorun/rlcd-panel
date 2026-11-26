package com.rlcdpanel.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.finder.SlotAssignment
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.RejectAction
import com.rlcdpanel.phone.protocol.RejectRow
import com.rlcdpanel.phone.protocol.RejectsReply
import com.rlcdpanel.phone.protocol.SlotConfig

/** Result of one `REJECTS` read: the rows, the count of lost MACs, or a text that tells why there are none. */
data class RejectsState(
    val isReading: Boolean = false,
    val rows: List<RejectRow> = emptyList(),
    val dropped: Int = 0,
    /** True after a successful read, so an empty list shows its own text. */
    val loaded: Boolean = false,
    val message: String? = null,
    /** Result text of the last slot change; the list below is read again after it. */
    val info: String? = null,
    /** True while a slot command runs (the list is read again afterwards). */
    val isFixing: Boolean = false
) {
    /** The controls are off while a command runs. */
    val isBusy: Boolean get() = isReading || isFixing
}

@Composable
fun RejectsScreen(
    state: RejectsState,
    panelConfig: PanelConfig,
    onRead: () -> Unit,
    onFix: (List<String>) -> Unit,
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
                    Button(onClick = onRead, enabled = !state.isBusy) {
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
            state.info?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            if (state.rows.any { it.action != null } && !panelConfig.canWrite) {
                item { Text(SlotAssignment.READ_ONLY_TEXT, color = MaterialTheme.colorScheme.error) }
            }
            RejectsReply.droppedText(state.dropped)?.let { text ->
                item { Text(text, style = MaterialTheme.typography.bodySmall) }
            }
            if (state.loaded && state.rows.isEmpty()) {
                item { Text("Keine abgelehnten Pakete.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.rows, key = { it.mac }) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${row.mac}  [${row.format}]", style = MaterialTheme.typography.titleSmall)
                        Text("${row.reason.wire}, ${row.count}x", style = MaterialTheme.typography.bodyMedium)
                        Text(row.advice, style = MaterialTheme.typography.bodySmall)
                        val enabled = !state.isBusy && panelConfig.canWrite
                        when (row.action) {
                            RejectAction.ASSIGN -> AssignMenu(row, panelConfig, enabled, onFix)
                            RejectAction.FREE -> SlotConfig.freeableSlots(panelConfig).forEach { slot ->
                                OutlinedButton(
                                    enabled = enabled,
                                    onClick = {
                                        (SlotConfig.clearRejected(slot, panelConfig) as? SlotConfig.Plan.Commands)
                                            ?.let { onFix(it.commands) }
                                    }
                                ) { Text("Slot $slot freigeben") }
                            }
                            null -> Unit
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

/** Menu `Slot zuweisen` with slots 1 to 10. A slot with a fixed MAC is marked and cannot be picked. */
@Composable
private fun AssignMenu(row: RejectRow, panelConfig: PanelConfig, enabled: Boolean, onFix: (List<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // The firmware refuses a MAC that a slot already holds, so the menu stays off and the row tells why.
    val holder = SlotAssignment.slotOf(row.mac, panelConfig)
    if (holder != null) Text("Slot $holder hält diese MAC schon", style = MaterialTheme.typography.bodySmall)
    // The Box anchors the popup to the button.
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled && holder == null) { Text("Slot zuweisen") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (slot in SlotAssignment.SLOTS) {
                val fixed = panelConfig.slotMac(slot)
                val slotOn = panelConfig.slotEnabled(slot)
                DropdownMenuItem(
                    text = {
                        Text(
                            when {
                                fixed != null -> "Slot $slot (feste MAC $fixed)"
                                !slotOn -> "Slot $slot (deaktiviert)"
                                else -> "Slot $slot"
                            }
                        )
                    },
                    enabled = fixed == null && slotOn,
                    onClick = {
                        open = false
                        when (val plan = SlotConfig.assignRejected(slot, row.mac, panelConfig)) {
                            is SlotConfig.Plan.Commands -> if (plan.commands.isNotEmpty()) onFix(plan.commands)
                            is SlotConfig.Plan.Invalid -> Unit
                        }
                    }
                )
            }
        }
    }
}
