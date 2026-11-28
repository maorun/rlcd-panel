package com.rlcdpanel.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rlcdpanel.phone.protocol.ConfigChangedEntry
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.protocol.ConfigCategory
import com.rlcdpanel.phone.protocol.ConfigPlan
import com.rlcdpanel.phone.protocol.ConfigProfile
import com.rlcdpanel.phone.protocol.ConfigProfileStore
import com.rlcdpanel.phone.protocol.ConfigResetPlan
import com.rlcdpanel.phone.protocol.ConfigSchema
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.RuntimePreview
import com.rlcdpanel.phone.protocol.StatusReply

/** State of the config editor screen. */
data class ConfigEditorState(
    val isBusy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val readOnly: Boolean = false,
    /** Reply of `CONFIG CHANGED`; null until it was read. */
    val changed: List<ConfigChangedEntry>? = null
)

/** One field for each key of `DeviceConfigParser::KEYS`, grouped like the macOS config editor. */
@Composable
fun ConfigEditorScreen(
    state: ConfigEditorState,
    panelConfig: PanelConfig,
    onRead: () -> Unit,
    onApply: (List<String>) -> Unit,
    status: StatusReply? = null,
    measuredConfig: Map<String, String> = emptyMap(),
    profileStore: ConfigProfileStore? = null,
    onReadChanged: () -> Unit = {},
    onBack: () -> Unit
) {
    // Input text of the keys that the user touched; an untouched key shows the cached value.
    val edits = remember { mutableStateMapOf<String, String>() }
    // A fresh CONFIG replaces the cache after an apply. Drop the edits that the panel now holds.
    // The edits of a failed key and of the keys after it stay, so the user need not type them again.
    LaunchedEffect(panelConfig) {
        val pending = ConfigSchema.diff(panelConfig.values, panelConfig.values + edits.toMap()).map { it.key }.toSet()
        edits.keys.toList().filter { it !in pending }.forEach { edits.remove(it) }
    }
    var changedOnly by remember { mutableStateOf(false) }
    // Commands that wait for the confirmation of the user.
    var pendingReset by remember { mutableStateOf<ConfigResetPlan?>(null) }
    val changed = state.changed
    val changedKeys = changed.orEmpty().map { it.key }.toSet()
    val writable = panelConfig.canWrite && !state.readOnly
    val plan = ConfigSchema.plan(edits.toMap(), panelConfig.values)
    val problems = (plan as? ConfigPlan.Invalid)?.problems.orEmpty()
    val commands = (plan as? ConfigPlan.Commands)?.commands.orEmpty()
    // Warnings never block the apply button: they only name a conflict between valid values.
    val warnings = ConfigSchema.warnings(panelConfig.values + edits.filterKeys { it in ConfigSchema.keys })
    // Named profiles (for example Winter and Summer): the changed keys of the form, without names, MACs, offsets and gains.
    var profiles by remember { mutableStateOf(profileStore?.profiles().orEmpty()) }
    var profileName by remember { mutableStateOf("") }
    var profileMessage by remember { mutableStateOf<String?>(null) }
    var reviewProfile by remember { mutableStateOf<ConfigProfile?>(null) }
    reviewProfile?.let { profile ->
        // The diff against the last CONFIG reply. Nothing is sent before the user confirms.
        val profilePlan = profile.plan(panelConfig.values)
        AlertDialog(
            onDismissRequest = { reviewProfile = null },
            title = { Text("Profil ${profile.name} anwenden") },
            text = {
                Column {
                    Text(
                        profilePlan.changes.joinToString("\n") { it.text }.ifEmpty { "Das Panel hat diese Werte schon" }
                    )
                    profilePlan.problems.forEach { (key, text) ->
                        Text("$key: $text", color = MaterialTheme.colorScheme.error)
                    }
                    // A level below 2 makes the console read-only: later commands over BLE get no reply.
                    if (profilePlan.changes.any { it.key == "ble_console" && (it.new.toIntOrNull() ?: 0) < 2 }) {
                        Text(
                            "ble_console unter 2 stoppt SET über BLE. Einzeln anwenden oder USB nutzen.",
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (profilePlan.skipped.isNotEmpty()) {
                        Text(
                            "Übersprungen (unbekannt oder nicht im Profil erlaubt): " + profilePlan.skipped.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onApply(profilePlan.commands); reviewProfile = null },
                    // An empty cache would compare with the schema defaults: read CONFIG first.
                    enabled = writable && !state.isBusy && panelConfig.values.isNotEmpty() && profilePlan.canApply
                ) { Text("Anwenden (${profilePlan.commands.size})") }
            },
            dismissButton = { TextButton(onClick = { reviewProfile = null }) { Text("Abbrechen") } }
        )
    }
    pendingReset?.let { reset ->
        AlertDialog(
            onDismissRequest = { pendingReset = null },
            title = { Text("Auf Standard zurücksetzen?") },
            text = {
                Text(
                    (reset.commands + reset.skipped.map { "${it.key}: ${it.value}" }).joinToString("\n")
                        .ifEmpty { "Nichts zu senden" }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onApply(reset.commands); pendingReset = null },
                    enabled = reset.commands.isNotEmpty() && !state.isBusy
                ) { Text("Senden (${reset.commands.size})") }
            },
            dismissButton = { TextButton(onClick = { pendingReset = null }) { Text("Abbrechen") } }
        )
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(modifier = Modifier.height(24.dp)) }
            item { OutlinedButton(onClick = onBack) { Text("Zurück") } }
            item { Text("Konfiguration", style = MaterialTheme.typography.titleLarge) }
            item { OutlinedButton(onClick = onRead, enabled = !state.isBusy) { Text("CONFIG lesen") } }
            item { OutlinedButton(onClick = onReadChanged, enabled = !state.isBusy) { Text("CONFIG CHANGED lesen") } }
            item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = changedOnly, onCheckedChange = { changedOnly = it })
                    Text("Changed only")
                }
            }
            if (changedOnly) {
                item {
                    when {
                        changed == null -> Text("CONFIG CHANGED lesen, um die geänderten Einstellungen zu sehen")
                        changed.isEmpty() -> Text("Keine Einstellung weicht vom Standard ab")
                        else -> Button(
                            onClick = { pendingReset = ConfigSchema.resetPlan(changed) },
                            enabled = writable && !state.isBusy
                        ) { Text("Reset all (${changed.size})") }
                    }
                }
            }
            if (!writable) {
                item {
                    val text = if (panelConfig.bleConsole == 1 || state.readOnly) ConfigSchema.READ_ONLY_TEXT
                    else "Konsole nicht schreibbar. CONFIG lesen, dann ble_console=2 setzen"
                    Text(text, color = MaterialTheme.colorScheme.error)
                }
            }
            state.message?.let {
                item { Text(it, color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground) }
            }
            if (profileStore != null) {
                item(key = "profiles") {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Profile", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = profileName,
                            onValueChange = { profileName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Profilname") },
                            supportingText = { Text("Ein Profil enthält die geänderten Keys des Formulars. Namen, MACs, Offsets und Gains werden nie gespeichert.") },
                            singleLine = true
                        )
                        OutlinedButton(
                            onClick = {
                                val changes = (plan as? ConfigPlan.Commands)?.changes.orEmpty()
                                profileMessage = when (val made = ConfigProfile.make(profileName, changes)) {
                                    is ConfigProfile.Made.Failed -> made.text
                                    is ConfigProfile.Made.Ok ->
                                        if (profileStore.save(made.profile)) {
                                            profiles = profileStore.profiles()
                                            "Profil gespeichert: ${made.profile.name} (${made.profile.values.size} Keys)"
                                        } else "Höchstens ${ConfigProfileStore.MAX_PROFILES} Profile. Zuerst eines löschen"
                                }
                            },
                            enabled = profileName.isNotEmpty() && commands.isNotEmpty() && problems.isEmpty()
                        ) { Text("Änderungen als Profil speichern") }
                        profileMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        for (profile in profiles) {
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("${profile.name} (${profile.values.size})", modifier = Modifier.weight(1f))
                                OutlinedButton(onClick = { reviewProfile = profile }) { Text("Prüfen") }
                                TextButton(onClick = { profileStore.remove(profile.name); profiles = profileStore.profiles() }) { Text("Löschen") }
                            }
                        }
                    }
                }
            }
            for (category in ConfigCategory.values()) {
                item(key = "head_${category.name}") {
                    Text(category.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                for (entry in ConfigSchema.entries(category).filter { !changedOnly || it.key in changedKeys }) {
                    item(key = entry.key) {
                        val current = panelConfig.values[entry.key] ?: entry.defaultValue
                        val text = edits[entry.key] ?: current
                        val problem = problems[entry.key]
                        Column(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { edits[entry.key] = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(if (entry.unit.isEmpty()) entry.key else "${entry.key} (${entry.unit})") },
                                supportingText = { Text(problem ?: entry.description) },
                                isError = problem != null,
                                singleLine = true,
                                enabled = writable && !state.isBusy
                            )
                            warnings.filter { it.key == entry.key }.forEach {
                                Text("⚠ ${it.text}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            // Preview only: reads the last STATUS and sends no command.
                            if (entry.key == "night_wake_interval_s") {
                                // Without a STATUS read or a cached CONFIG the schedule is unknown: show `Laufzeit --`.
                                val hours = if (measuredConfig.isEmpty()) null
                                else RuntimePreview.hours(measuredConfig, edits.toMap(), status)
                                Text(
                                    RuntimePreview.text(hours),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (changedOnly) {
                                OutlinedButton(
                                    onClick = { pendingReset = ConfigSchema.resetPlan(changed.orEmpty().filter { it.key == entry.key }) },
                                    enabled = writable && !state.isBusy
                                ) { Text("Reset (Standard ${entry.defaultValue.ifEmpty { "leer" }})") }
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { onApply(commands) },
                    enabled = writable && !state.isBusy && commands.isNotEmpty() && problems.isEmpty()
                ) { Text("Anwenden (${commands.size})") }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
