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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.finder.FoundSensor
import com.rlcdpanel.phone.finder.SlotAssignment
import com.rlcdpanel.phone.protocol.SetupAssistant

/** Snapshot of the [SetupAssistant] for the screen, plus the flags that only the activity knows. */
data class SetupState(
    val step: SetupAssistant.Step = SetupAssistant.Step.SCAN,
    val stopText: String? = null,
    val notice: String? = null,
    val candidates: List<FoundSensor> = emptyList(),
    val chosenMac: String? = null,
    val slot: Int? = null,
    val verifyTries: Int = 0,
    /** Text of the `SALT` proposal, null without one. */
    val proposalText: String? = null,
    /** Text of the outdoor slot proposal, null without one. */
    val outdoorText: String? = null,
    /** Text of the door reference slot proposal, null without one. */
    val doorText: String? = null,
    val isScanning: Boolean = false,
    val isBusy: Boolean = false,
    val message: String? = null
)

/** Step titles of the screen, in the order of the assistant. */
object SetupAssistantText {
    fun title(step: SetupAssistant.Step): String = when (step) {
        SetupAssistant.Step.SCAN -> "1/5 Suchen"
        SetupAssistant.Step.CHOOSE -> "2/5 Auswählen"
        SetupAssistant.Step.ASSIGN -> "3/5 Zuweisen"
        SetupAssistant.Step.VERIFY -> "4/5 Prüfen"
        SetupAssistant.Step.CALIBRATE -> "5/5 Kalibrieren"
        SetupAssistant.Step.DONE -> "Fertig"
    }
}

@Composable
fun SetupAssistantScreen(
    state: SetupState,
    onScan: () -> Unit,
    onUseScan: () -> Unit,
    onChoose: (mac: String, name: String) -> Unit,
    onAssign: () -> Unit,
    onCheck: () -> Unit,
    onSalt: () -> Unit,
    onApply: () -> Unit,
    onReadWeek: () -> Unit,
    onApplyOutdoor: () -> Unit,
    onApplyDoor: () -> Unit,
    onFinish: () -> Unit,
    onRestart: () -> Unit,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val stopped = state.stopText != null
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(modifier = Modifier.height(24.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Zurück") }
                    if (stopped || state.step == SetupAssistant.Step.DONE) {
                        Button(onClick = onRestart) { Text("Neu starten") }
                    }
                }
            }
            item { Text("Neuen Sensor einrichten: " + SetupAssistantText.title(state.step), style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    "Jeder Schritt sendet Befehle nur nach einem Tipp. Das Panel braucht USB-Strom und ble_console 2.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.stopText?.let { item { Text("Gestoppt: $it", color = MaterialTheme.colorScheme.error) } }
            state.notice?.let { item { Text(it) } }
            state.message?.let { item { Text(it) } }
            if (!stopped) {
                when (state.step) {
                    SetupAssistant.Step.SCAN -> item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onScan, enabled = !state.isBusy) {
                                Text(if (state.isScanning) "Suche stoppen" else "Sensoren suchen")
                            }
                            Button(onClick = onUseScan, enabled = !state.isBusy) { Text("Liste übernehmen") }
                        }
                    }
                    SetupAssistant.Step.CHOOSE -> {
                        item {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it.take(SlotAssignment.MAX_NAME_CHARS).uppercase() },
                                label = { Text("Name (optional, max. 12 Zeichen)") },
                                singleLine = true
                            )
                        }
                        items(state.candidates, key = { it.advertisement.mac }) { row ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${row.advertisement.mac} [${row.advertisement.format.badge}]", style = MaterialTheme.typography.titleMedium)
                                    Text("${SensorFinderText.reading(row)}, RSSI ${row.rssi} dBm")
                                    Button(onClick = { onChoose(row.advertisement.mac, name) }, enabled = !state.isBusy) { Text("Wählen") }
                                }
                            }
                        }
                        item { OutlinedButton(onClick = onScan, enabled = !state.isBusy) { Text(if (state.isScanning) "Suche stoppen" else "Neu suchen") } }
                        item { OutlinedButton(onClick = onUseScan, enabled = !state.isBusy) { Text("Liste aktualisieren") } }
                    }
                    SetupAssistant.Step.ASSIGN -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Sensor ${state.chosenMac ?: "-"}")
                            Button(onClick = onAssign, enabled = !state.isBusy) { Text(if (state.isBusy) "Sende…" else "Freien Slot belegen") }
                        }
                    }
                    SetupAssistant.Step.VERIFY -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Slot ${state.slot ?: "-"}: Empfang prüfen (Versuch ${state.verifyTries} von ${SetupAssistant.MAX_VERIFY_TRIES})")
                            Button(onClick = onCheck, enabled = !state.isBusy) { Text(if (state.isBusy) "Sende…" else "Empfang prüfen") }
                        }
                    }
                    SetupAssistant.Step.CALIBRATE -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Optional: Sensor in die Salzbox legen, mindestens 6 h warten, dann SALT lesen.")
                            state.proposalText?.let { Text(it) }
                            Button(onClick = onSalt, enabled = !state.isBusy) { Text("Salzprüfung lesen") }
                            if (state.proposalText != null) {
                                Button(onClick = onApply, enabled = !state.isBusy) { Text("Vorschlag anwenden") }
                            }
                            state.outdoorText?.let { Text(it) }
                            OutlinedButton(onClick = onReadWeek, enabled = !state.isBusy) { Text("Wochendaten lesen (Außensensor)") }
                            if (state.outdoorText != null) {
                                Button(onClick = onApplyOutdoor, enabled = !state.isBusy) { Text("Als Außensensor übernehmen") }
                            }
                            state.doorText?.let { Text(it) }
                            if (state.doorText != null) {
                                Button(onClick = onApplyDoor, enabled = !state.isBusy) { Text("Als Türreferenz übernehmen") }
                            }
                            OutlinedButton(onClick = onFinish, enabled = !state.isBusy) { Text("Ohne Kalibrierung beenden") }
                        }
                    }
                    SetupAssistant.Step.DONE -> item { Text("Der Sensor ist eingerichtet.") }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
