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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.protocol.CalibrationProposal
import com.rlcdpanel.phone.protocol.CalibrationReply

/** State of the calibration screen: the picked reference, the reply, the selected keys and the last message. */
data class CalibrationState(
    val reference: Int = 0,
    val isBusy: Boolean = false,
    val reply: CalibrationReply? = null,
    val selected: Set<String> = emptySet(),
    val message: String? = null,
    /** True when [message] reports a failure; the screen shows it in the error color. */
    val isError: Boolean = false
) {
    /** The proposals that a tap on `Apply` sends, in reply order. */
    val chosen: List<CalibrationProposal>
        get() = reply?.proposals.orEmpty().filter { it.key in selected }
}

/** One-line texts of the reply lines. */
object CalibrationText {
    fun sensor(s: CalibrationReply.Sensor): String =
        "SENSOR ${s.name}: ${s.hours} h, Temp ${s.tempDiff} °C, RH ${s.rhDiff} %RH (Offset ${s.tempOffset} / ${s.rhOffset})"

    fun skipped(s: CalibrationReply.Skipped): String = "SKIP ${s.name}: nur ${s.hours} h"

    fun usb(u: CalibrationReply.UsbSplit): String =
        "USB ${u.usbHours} h, Batterie ${u.batHours} h, usb_temp_offset ${u.usbTempOffset}, Differenz ${u.usbTempDiff ?: "--"}"
}

@Composable
fun CalibrationScreen(
    state: CalibrationState,
    onPickReference: (Int) -> Unit,
    onRead: () -> Unit,
    onToggle: (String) -> Unit,
    onApply: () -> Unit,
    onBack: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(modifier = Modifier.height(24.dp)) }
            item { Text("Referenz-Sensor (0 = onboard)", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in 0..5) {
                        FilterChip(
                            selected = state.reference == n,
                            onClick = { onPickReference(n) },
                            enabled = !state.isBusy,
                            label = { Text("$n") }
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Zurück") }
                    Button(onClick = onRead, enabled = !state.isBusy) {
                        Text(if (state.isBusy) "Lese…" else "Read proposals")
                    }
                }
            }
            item {
                Text(
                    "Sendet CALIB über die BLE-Konsole. Zum Anwenden braucht das Panel USB-Strom und ble_console 2.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.message?.let {
                item {
                    Text(it, color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground)
                }
            }
            state.reply?.let { reply ->
                item { Text("Referenz: ${reply.reference}", style = MaterialTheme.typography.titleMedium) }
                items(reply.sensors) { Text(CalibrationText.sensor(it)) }
                items(reply.skipped) { Text(CalibrationText.skipped(it)) }
                reply.usb?.let { u -> item { Text(CalibrationText.usb(u)) } }
                if (reply.proposals.isEmpty()) {
                    item { Text("Keine Vorschläge", style = MaterialTheme.typography.bodyMedium) }
                }
                items(reply.proposals, key = { it.key }) { p ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = p.key in state.selected, onCheckedChange = { onToggle(p.key) }, enabled = !state.isBusy)
                            Column { Text("${p.key}=${p.value}") }
                        }
                    }
                }
                if (reply.proposals.isNotEmpty()) {
                    item {
                        Button(onClick = onApply, enabled = !state.isBusy && state.chosen.isNotEmpty()) {
                            Text("Apply ${state.chosen.size} selected")
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
