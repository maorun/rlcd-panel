package com.rlcdpanel.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.rlcdpanel.watch.protocol.AlertLimitStepper
import com.rlcdpanel.watch.protocol.AlertLimits
import com.rlcdpanel.watch.protocol.PanelConfig

/** State of the alert limit screen: the open stepper (key and value text), the busy flag and the last message. */
data class AlertLimitState(
    /** Key of the limit in the stepper, null for the list. */
    val editing: String? = null,
    val value: String = "",
    val isBusy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false
)

/** Pure rules of the alert limit screen. */
object AlertLimitScreenText {
    /** The `SET` batch of one stepper value: at most one command, empty when the value equals the cached one. */
    fun commands(key: String, value: String, config: PanelConfig): List<String> {
        val plan = AlertLimits.plan(mapOf(key to value), config)
        return (plan as? AlertLimits.Plan.Commands)?.commands.orEmpty()
    }

    /** Big text of the stepper: `Aus` for the off value, else the value with its unit. */
    fun stepperLine(limit: AlertLimits.Limit, value: String): String {
        val number = value.toDoubleOrNull()
        val off = limit.offValue?.toDouble() ?: if (limit.unit == "h") 0.0 else null
        return if (number != null && number == off) AlertLimits.OFF_TEXT else AlertLimits.valueText(limit, value)
    }

    /** Value line of one chip: the cached value with its unit, `Aus` for a zero delay. */
    fun valueLine(limit: AlertLimits.Limit, config: PanelConfig): String {
        return stepperLine(limit, AlertLimitStepper.startText(limit, config))
    }
}

@Composable
fun AlertLimitScreen(
    state: AlertLimitState,
    config: PanelConfig,
    onOpen: (AlertLimits.Limit) -> Unit,
    onStep: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    val canEdit = AlertLimits.canEdit(config)
    val editing = AlertLimits.LIMITS.firstOrNull { it.key == state.editing }
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
                item { Text("Alarmgrenzen", fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center) }
                state.message?.let { message ->
                    item {
                        Text(
                            message,
                            fontSize = 10.sp,
                            color = if (state.isError) Color(0xFFFF8A80) else Color(0xFF80CBC4),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                if (editing == null) {
                    if (!canEdit) {
                        item {
                            Text(
                                AlertLimits.READ_ONLY_TEXT,
                                fontSize = 10.sp,
                                color = Color(0xFFFF8A80),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                    for (limit in AlertLimits.LIMITS) {
                        item {
                            Chip(
                                onClick = { onOpen(limit) },
                                enabled = canEdit && !state.isBusy,
                                modifier = Modifier.fillMaxWidth(0.85f),
                                colors = ChipDefaults.secondaryChipColors(),
                                label = { Text(limit.label, fontSize = 11.sp, maxLines = 1) },
                                secondaryLabel = { Text(AlertLimitScreenText.valueLine(limit, config), fontSize = 10.sp, maxLines = 1) }
                            )
                        }
                    }
                } else {
                    val error = AlertLimits.error(editing, state.value)
                    val pending = AlertLimitScreenText.commands(editing.key, state.value, config)
                    item { Text(editing.label, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center) }
                    item {
                        Text(
                            AlertLimitScreenText.stepperLine(editing, state.value),
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    item { Text(error ?: editing.rangeText, fontSize = 10.sp, color = Color(0xFFB0BEC5), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp)) }
                    item {
                        Chip(
                            onClick = { onStep(1) },
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(0.85f),
                            colors = ChipDefaults.secondaryChipColors(),
                            label = { Text("+", fontSize = 14.sp, maxLines = 1, textAlign = TextAlign.Center) }
                        )
                    }
                    item {
                        Chip(
                            onClick = { onStep(-1) },
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(0.85f),
                            colors = ChipDefaults.secondaryChipColors(),
                            label = { Text("−", fontSize = 14.sp, maxLines = 1, textAlign = TextAlign.Center) }
                        )
                    }
                    item {
                        Chip(
                            onClick = onConfirm,
                            enabled = !state.isBusy && error == null && pending.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(0.85f),
                            colors = ChipDefaults.primaryChipColors(),
                            label = { Text(if (state.isBusy) "Sende ..." else "Senden", fontSize = 12.sp, maxLines = 1) }
                        )
                    }
                    item {
                        Chip(
                            onClick = onCancel,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(0.85f),
                            colors = ChipDefaults.secondaryChipColors(),
                            label = { Text("Abbrechen", fontSize = 12.sp, maxLines = 1) }
                        )
                    }
                }
            }
        }
    }
}
