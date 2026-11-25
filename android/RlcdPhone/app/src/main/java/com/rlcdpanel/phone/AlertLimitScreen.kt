package com.rlcdpanel.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.protocol.AlertLimits
import com.rlcdpanel.phone.protocol.PanelConfig

/** State of the alert limit screen. */
data class AlertLimitState(
    val isBusy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false
)

@Composable
fun AlertLimitScreen(
    state: AlertLimitState,
    panelConfig: PanelConfig,
    onRead: () -> Unit,
    onApply: (List<String>) -> Unit,
    onBack: () -> Unit
) {
    // Input text of the keys that the user touched; an untouched key shows the cached value.
    val edits = remember { mutableStateMapOf<String, String>() }
    // A fresh CONFIG replaces the cache after a successful apply. Only then the input is obsolete.
    LaunchedEffect(panelConfig) { edits.clear() }
    val plan = AlertLimits.plan(edits.toMap(), panelConfig)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(modifier = Modifier.height(24.dp)) }
            item { OutlinedButton(onClick = onBack) { Text("Zurück") } }
            item { Text("Alarmgrenzen", style = MaterialTheme.typography.titleLarge) }
            item {
                OutlinedButton(onClick = onRead, enabled = !state.isBusy) { Text("CONFIG lesen") }
            }
            if (!AlertLimits.canEdit(panelConfig)) {
                item { Text(AlertLimits.READ_ONLY_TEXT, color = MaterialTheme.colorScheme.error) }
            }
            state.message?.let {
                item { Text(it, color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground) }
            }
            items(AlertLimits.LIMITS, key = { it.key }) { limit ->
                val shown = AlertLimits.shown(limit, panelConfig).let {
                    if (limit.decimal) String.format(java.util.Locale.ROOT, "%.1f", it) else it.toInt().toString()
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(limit.label, style = MaterialTheme.typography.titleMedium)
                        Text(limit.key, style = MaterialTheme.typography.bodySmall)
                        if (AlertLimits.canEdit(panelConfig)) {
                            val text = edits[limit.key] ?: shown
                            val error = AlertLimits.error(limit, text)
                            val hint = if (limit.unit == "h" && error == null) AlertLimits.valueText(limit, text) else null
                            OutlinedTextField(
                                value = text,
                                onValueChange = { edits[limit.key] = it },
                                label = { Text("Wert (${limit.unit})") },
                                supportingText = { Text(error ?: (if (hint == AlertLimits.OFF_TEXT) "${AlertLimits.OFF_TEXT} – ${limit.rangeText}" else limit.rangeText)) },
                                isError = error != null,
                                singleLine = true,
                                enabled = !state.isBusy
                            )
                        } else {
                            Text(AlertLimits.valueText(limit, shown))
                        }
                    }
                }
            }
            if (AlertLimits.canEdit(panelConfig)) {
                item {
                    val commands = (plan as? AlertLimits.Plan.Commands)?.commands.orEmpty()
                    (plan as? AlertLimits.Plan.Invalid)?.let { Text(it.reason, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = { onApply(commands) },
                        enabled = !state.isBusy && commands.isNotEmpty()
                    ) { Text("Anwenden (${commands.size})") }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
