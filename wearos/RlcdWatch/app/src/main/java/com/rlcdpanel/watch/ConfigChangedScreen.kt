package com.rlcdpanel.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.rlcdpanel.watch.protocol.ConfigChangedEntry
import com.rlcdpanel.watch.protocol.ConfigReset

/** Result of one `CONFIG CHANGED` read: the changed keys, or a text that tells why there are none. */
data class ConfigChangedState(
    val isReading: Boolean = false,
    val entries: List<ConfigChangedEntry> = emptyList(),
    /** True after a successful read, so a count of 0 shows its own text. */
    val loaded: Boolean = false,
    val message: String? = null,
    /** Key whose reset waits for a confirmation, or null. */
    val confirming: String? = null,
    /** True while the reset runs. */
    val isApplying: Boolean = false,
    /** True after `ERR READ_ONLY`: the reset chips stay hidden. */
    val readOnly: Boolean = false
)

/** Texts of the changed-settings screen. */
object ConfigChangedText {
    const val EMPTY = "Alle Werte Standard"
    const val COMMAND = "CONFIG CHANGED"
}

/** List of the settings that differ from the default. A reset sends one `SET` only after a confirmation. */
@Composable
fun ConfigChangedScreen(
    state: ConfigChangedState,
    onRead: () -> Unit,
    onAsk: (String) -> Unit = {},
    onCancel: () -> Unit = {},
    onReset: (String) -> Unit = {}
) {
    val listState = rememberScalingLazyListState()
    MaterialTheme {
        Scaffold(
            timeText = { TimeText() },
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
        ) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    Chip(
                        onClick = onRead,
                        enabled = !state.isReading && !state.isApplying,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (state.isReading) "Lese Panel ..." else "Read settings", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                state.message?.let { message ->
                    item {
                        Text(
                            text = message,
                            fontSize = 10.sp,
                            color = Color(0xFFFF8A80),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                if (!state.loaded && state.message == null) {
                    item {
                        Text(
                            "Tippe auf Read. Panel an USB, ble_console 1 oder 2.",
                            fontSize = 10.sp,
                            color = Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                if (state.loaded && state.entries.isEmpty()) {
                    item { Text(ConfigChangedText.EMPTY, fontSize = 10.sp, color = Color(0xFFB0BEC5)) }
                }
                items(state.entries) { entry ->
                    val plan = ConfigReset.planFor(entry)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(0.95f)) {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(entry.key, fontSize = 12.sp)
                                Text(entry.value, fontSize = 10.sp, color = Color(0xFFB0BEC5))
                            }
                        }
                        if (plan is ConfigReset.RowPlan.Skipped) {
                            Text(
                                plan.reason,
                                fontSize = 10.sp,
                                color = Color(0xFFFFB74D),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        } else if (plan is ConfigReset.RowPlan.Sendable && !state.readOnly) {
                            if (state.confirming == entry.key) {
                                Text(
                                    ConfigReset.confirmText(plan),
                                    fontSize = 10.sp,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )
                                Chip(
                                    onClick = { onReset(entry.key) },
                                    enabled = !state.isApplying,
                                    modifier = Modifier.fillMaxWidth(0.85f),
                                    colors = ChipDefaults.primaryChipColors(),
                                    label = { Text("Senden", fontSize = 12.sp, maxLines = 1) }
                                )
                                Chip(
                                    onClick = onCancel,
                                    enabled = !state.isApplying,
                                    modifier = Modifier.fillMaxWidth(0.85f),
                                    colors = ChipDefaults.secondaryChipColors(),
                                    label = { Text("Abbrechen", fontSize = 12.sp, maxLines = 1) }
                                )
                            } else {
                                Chip(
                                    onClick = { onAsk(entry.key) },
                                    enabled = !state.isReading && !state.isApplying,
                                    modifier = Modifier.fillMaxWidth(0.85f),
                                    colors = ChipDefaults.secondaryChipColors(),
                                    label = { Text(ConfigReset.RESET_LABEL, fontSize = 12.sp, maxLines = 1) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
