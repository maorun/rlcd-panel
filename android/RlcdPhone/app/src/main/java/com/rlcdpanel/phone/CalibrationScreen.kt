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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.protocol.CalibrationProposal
import com.rlcdpanel.phone.protocol.CalibrationReply
import com.rlcdpanel.phone.protocol.FrsiCommand
import com.rlcdpanel.phone.protocol.FrsiReply
import com.rlcdpanel.phone.protocol.FrsiText
import com.rlcdpanel.phone.protocol.SaltReply
import com.rlcdpanel.phone.protocol.CalibrationCheck
import com.rlcdpanel.phone.protocol.CalibrationCheckRecord
import com.rlcdpanel.phone.protocol.CalibrationCheckResult
import com.rlcdpanel.phone.protocol.SaltText
import com.rlcdpanel.phone.protocol.SpreadCommand
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
    /** Salt of the next check: false is NaCl, true is MgCl2. */
    val magnesium: Boolean = false,
    /** Room sensor (0 to 10) and wall slot (1 to 10) of the wall factor section. */
    val frsiRoom: Int = 0,
    val frsiWall: Int = 1,
    /** Sample window of the wall factor check (3, 6, 12 or 24 hours). */
    val frsiHours: Int = FrsiCommand.DEFAULT_HOURS,
    /** Window of the request that produced [frsi]. */
    val frsiReplyHours: Int = FrsiCommand.DEFAULT_HOURS,
    val frsi: FrsiReply? = null,
    /** Last `SPREAD` reply of the co-location check. */
    val spread: SpreadReply? = null,
    /** Window picked for the next `SPREAD` (6, 12 or 24 hours). */
    val spreadHours: Int = SpreadCommand.DEFAULT_HOURS,
    /** Window of the request that produced [spread]; the re-read after an apply uses it. */
    val spreadReplyHours: Int = SpreadCommand.DEFAULT_HOURS,
    /** True while the salt watch sends `HISTORY N 6` every 10 minutes. */
    val isWatching: Boolean = false,
    /** Progress text or stop reason of the salt watch; null before the first start. */
    val watchText: String? = null,
    /** Stored applies that the user can check after the wait of the record, one for each sensor. */
    val checks: List<CalibrationCheckRecord> = emptyList(),
    /** Verdict of the last check for each sensor name. */
    val checkResults: Map<String, CalibrationCheckResult> = emptyMap()
) {
    /** Picks another reference sensor. A change drops the reply, the selection and both salt checks. */
    fun selectReference(sensor: Int): CalibrationState =
        if (sensor == reference) this
        else copy(reference = sensor, reply = null, selected = emptySet(), message = null, isError = false, naCl = null, mgCl2 = null)

    /** The proposals that a tap on `Apply` sends, in reply order. */
    val chosen: List<CalibrationProposal>
        get() = reply?.proposals.orEmpty().filter { it.key in selected }
}

/** One-line texts of the reply lines. */
object CalibrationText {
    fun sensor(s: CalibrationReply.Sensor): String =
        "SENSOR ${s.name}: ${s.hours} h, Temp ${s.tempDiff} °C, RH ${s.rhDiff} %RH (Offset ${s.tempOffset} / ${s.rhOffset})"

    fun skipped(s: CalibrationReply.Skipped): String = "SKIP ${s.name}: nur ${s.hours} h"

    fun excluded(e: CalibrationReply.Excluded): String =
        "SKIP ${e.name}: " + if (e.reason == CalibrationReply.Reason.OUTDOOR) "Außensensor" else "veraltet"

    /** Proposals that the screen lists as cards; the USB proposal has its own checkbox. */
    fun cardProposals(reply: CalibrationReply): List<CalibrationProposal> =
        reply.proposals.filter { it.key != CalibrationReply.USB_KEY }

    /** True when the screen shows no proposal at all. */
    fun hasNoProposal(reply: CalibrationReply): Boolean = cardProposals(reply).isEmpty() && reply.usbProposal == null

    fun usbOffset(x: Double): String = "USB offset $x C"

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
    onPickSalt: (Boolean) -> Unit,
    onSalt: (Boolean) -> Unit,
    onWatchSalt: (Boolean) -> Unit,
    onStopWatch: () -> Unit,
    onApplyTwoPoint: () -> Unit,
    onPickFrsiRoom: (Int) -> Unit,
    onPickFrsiWall: (Int) -> Unit,
    onPickFrsiHours: (Int) -> Unit,
    onFrsi: () -> Unit,
    onApplyFrsi: () -> Unit,
    onPickSpreadHours: (Int) -> Unit,
    onSpread: () -> Unit,
    onApplySpread: (SpreadReply.Proposal) -> Unit,
    onCheck: (String) -> Unit,
    /** Time of the injected clock for the wait text of the result check. */
    clock: () -> Long,
    onBack: () -> Unit
) {
    // The injected clock ticks every 30 s, so the wait text and the Check result button follow the 6 hour mark.
    var nowMs by remember { mutableStateOf(clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            nowMs = clock()
        }
    }
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
            if (state.checks.isNotEmpty()) {
                item { Text("Result check", style = MaterialTheme.typography.titleMedium) }
                items(state.checks, key = { it.sensor }) { record ->
                    val wait = CalibrationCheck.hoursToWait(record, nowMs)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(record.sensor, style = MaterialTheme.typography.titleSmall)
                            for (c in record.changes) Text("${c.key}: ${c.old.ifEmpty { "(empty)" }} -> ${c.new}")
                            if (wait != null) {
                                Text("Check in $wait h", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Button(onClick = { onCheck(record.sensor) }, enabled = !state.isBusy && !state.isWatching) { Text("Check result") }
                            }
                            state.checkResults[record.sensor]?.takeUnless { wait == null && it is CalibrationCheckResult.TooEarly }?.let { result ->
                                Text(
                                    result.text,
                                    color = if (result == CalibrationCheckResult.Good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
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
                    FilterChip(selected = !state.magnesium, enabled = !state.isBusy, onClick = { onPickSalt(false) }, label = { Text("NaCl (75 %)") })
                    FilterChip(selected = state.magnesium, enabled = !state.isBusy, onClick = { onPickSalt(true) }, label = { Text("MgCl2 (33 %)") })
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSalt(state.magnesium) }, enabled = !state.isBusy) {
                        Text(if (state.magnesium) "MgCl2 prüfen" else "NaCl prüfen")
                    }
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
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Fenster")
                    for (h in FrsiCommand.HOUR_CHOICES) {
                        FilterChip(
                            selected = state.frsiHours == h,
                            onClick = { onPickFrsiHours(h) },
                            enabled = !state.isBusy,
                            label = { Text("$h h") }
                        )
                    }
                }
            }
            state.frsi?.let { r ->
                item { Text(FrsiText.outcome(r, state.frsiReplyHours)) }
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
                    "Lege alle Sensoren für das gewählte Fenster an einen Ort. Danach vergleicht SPREAD jeden Sensor mit dem Median.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Fenster")
                    for (h in SpreadCommand.PICKER_HOURS) {
                        FilterChip(
                            selected = state.spreadHours == h,
                            onClick = { onPickSpreadHours(h) },
                            enabled = !state.isBusy,
                            label = { Text("$h h") }
                        )
                    }
                }
            }
            item { Button(onClick = onSpread, enabled = !state.isBusy) { Text("Read SPREAD") } }
            state.spread?.let { r ->
                when (val o = r.outcome) {
                    is SpreadReply.Outcome.Measured -> {
                        item { Text(SpreadCommand.windowText(state.spreadReplyHours)) }
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
                items(reply.excluded) { Text(CalibrationText.excluded(it)) }
                reply.usb?.let { u -> item { Text(CalibrationText.usb(u)) } }
                reply.usbProposal?.let { x ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = CalibrationReply.USB_KEY in state.selected,
                                onCheckedChange = { onToggle(CalibrationReply.USB_KEY) },
                                enabled = !state.isBusy
                            )
                            Text(CalibrationText.usbOffset(x))
                        }
                    }
                }
                if (CalibrationText.hasNoProposal(reply)) {
                    item { Text("Keine Vorschläge", style = MaterialTheme.typography.bodyMedium) }
                }
                items(CalibrationText.cardProposals(reply), key = { it.key }) { p ->
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
