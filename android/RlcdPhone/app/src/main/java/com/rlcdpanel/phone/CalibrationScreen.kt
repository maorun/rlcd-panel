package com.rlcdpanel.phone

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.protocol.CalibrationProposal
import com.rlcdpanel.phone.protocol.CalibrationReply
import com.rlcdpanel.phone.protocol.FrsiReply
import com.rlcdpanel.phone.protocol.FrsiText
import com.rlcdpanel.phone.protocol.SaltReply
import com.rlcdpanel.phone.protocol.SaltText
import com.rlcdpanel.phone.protocol.SpreadReply
import com.rlcdpanel.phone.protocol.SpreadText
import com.rlcdpanel.phone.protocol.TwoPointCalibration

/** State of the calibration screen: the picked reference, the reply, the selected keys and the last message. */
data class CalibrationState(
    val reference: Int = 0,
    val isBusy: Boolean = false,
    val reply: CalibrationReply? = null,
    val selected: Set<String> = emptySet(),
    val message: String? = null,
    /** True when [message] reports a failure; the screen shows it in the error color. */
    val isError: Boolean = false,
    /** Last NaCl and MgCl2 check of the picked reference sensor. */
    val naCl: SaltReply? = null,
    val mgCl2: SaltReply? = null,
    /** Room sensor (0 to 10) and wall slot (1 to 10) of the wall factor section. */
    val frsiRoom: Int = 0,
    val frsiWall: Int = 1,
    val frsi: FrsiReply? = null,
    /** Last `SPREAD` reply of the co-location check. */
    val spread: SpreadReply? = null,
    /** True while the salt watch sends `HISTORY N` every 10 minutes. */
    val isWatching: Boolean = false,
    /** Progress text or stop reason of the salt watch; null before the first start. */
    val watchText: String? = null
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
    /** Active `config.ini` values of the last `CONFIG` read; empty until a read. */
    currentConfig: Map<String, String>,
    /** Sensors of the picker; see `CalibrationSensors.selectable`. */
    sensors: List<Int>,
    frsiSensors: List<Int>,
    onPickReference: (Int) -> Unit,
    onRead: () -> Unit,
    onToggle: (String) -> Unit,
    onApply: () -> Unit,
    onSalt: (Boolean) -> Unit,
    onWatchSalt: (Boolean) -> Unit,
    onStopWatch: () -> Unit,
    onApplyTwoPoint: () -> Unit,
    onPickFrsiRoom: (Int) -> Unit,
    onPickFrsiWall: (Int) -> Unit,
    onFrsi: () -> Unit,
    onApplyFrsi: () -> Unit,
    onSpread: () -> Unit,
    onApplySpread: (SpreadReply.Proposal) -> Unit,
    onBack: () -> Unit
) {
    val evaluation = remember(state.naCl, state.mgCl2, currentConfig) {
        TwoPointCalibration.evaluate(state.naCl, state.mgCl2, currentConfig.takeIf { it.isNotEmpty() })
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(modifier = Modifier.height(24.dp)) }
            item { Text("Referenz-Sensor (0 = onboard)", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in sensors) {
                        FilterChip(
                            selected = state.reference == n,
                            onClick = { onPickReference(n) },
                            enabled = !state.isBusy,
                            label = { Text("$n") }
                        )
                    }
                }
            }
            if (sensors.none { it > 5 }) {
                item {
                    Text(
                        "Slots 6 bis 10 erscheinen nach einem STATUS-Lesen im Sensor-Health-Screen, sobald der Slot einen Messwert hat.",
                        style = MaterialTheme.typography.bodySmall
                    )
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
            item { Text("Salt check", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    "Lege den Sensor mit gesättigter Salzlösung in eine geschlossene Box. Je Salz 6 h warten.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSalt(false) }, enabled = !state.isBusy) { Text("NaCl (75 %)") }
                    Button(onClick = { onSalt(true) }, enabled = !state.isBusy) { Text("MgCl2 (33 %)") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.isWatching) {
                        OutlinedButton(onClick = onStopWatch) { Text("Beobachtung beenden") }
                    } else {
                        OutlinedButton(onClick = { onWatchSalt(false) }, enabled = !state.isBusy) { Text("NaCl beobachten") }
                        OutlinedButton(onClick = { onWatchSalt(true) }, enabled = !state.isBusy) { Text("MgCl2 beobachten") }
                    }
                }
            }
            state.watchText?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            state.naCl?.let { item { Text("NaCl: ${SaltText.outcome(it)}") } }
            state.mgCl2?.let { item { Text("MgCl2: ${SaltText.outcome(it)}") } }
            evaluation.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                Text(
                    "Gain und Offset stammen vom letzten CONFIG-Lesen. Lies CONFIG im Sensor-Health-Screen, wenn der Stand fehlt oder alt ist.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            evaluation.result?.let { r ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${r.gainKey}=${r.gain}")
                            Text("${r.offsetKey}=${r.offset}")
                            Button(onClick = onApplyTwoPoint, enabled = !state.isBusy) { Text("Apply gain and offset") }
                        }
                    }
                }
            }
            item { Text("Wall factor", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    "Lege einen Slot an die Innenseite der Außenwand. Der Außen-Slot (outdoor_slot) muss frische Werte haben.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SensorMenu("Raum", state.frsiRoom, frsiSensors, !state.isBusy, onPickFrsiRoom)
                    SensorMenu("Wand", state.frsiWall, frsiSensors.filter { it >= 1 }, !state.isBusy, onPickFrsiWall)
                    Button(onClick = onFrsi, enabled = !state.isBusy && state.frsiRoom != state.frsiWall) { Text("FRSI") }
                }
            }
            state.frsi?.let { r ->
                item { Text(FrsiText.outcome(r)) }
                r.proposal?.let { p ->
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${p.key}=${p.value}")
                                Button(onClick = onApplyFrsi, enabled = !state.isBusy) { Text("Apply wall factor") }
                            }
                        }
                    }
                }
            }
            item { Text("Co-location check", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    "Lege alle Sensoren 6 h an einen Ort. Danach vergleicht SPREAD jeden Sensor mit dem Median.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item { Button(onClick = onSpread, enabled = !state.isBusy) { Text("Read SPREAD") } }
            state.spread?.let { r ->
                when (val o = r.outcome) {
                    is SpreadReply.Outcome.Measured -> {
                        item { Text(SpreadText.summary(o)) }
                        items(o.rows) { row ->
                            Text(
                                SpreadText.row(row),
                                color = if (row.isOutlier) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
                            )
                        }
                        items(o.proposals, key = { it.tempKey }) { p ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${p.tempKey}=${p.tempOffset}")
                                    Text("${p.rhKey}=${p.rhOffset}")
                                    Button(onClick = { onApplySpread(p) }, enabled = !state.isBusy) { Text("Apply") }
                                }
                            }
                        }
                    }
                    is SpreadReply.Outcome.Skipped -> item { Text(SpreadText.SKIP_TEXT) }
                }
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

/** Drop-down menu of one sensor number. */
@Composable
private fun SensorMenu(label: String, value: Int, options: List<Int>, enabled: Boolean, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) { Text("$label $value") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (n in options) {
                DropdownMenuItem(text = { Text("$n") }, onClick = { open = false; onPick(n) })
            }
        }
    }
}
