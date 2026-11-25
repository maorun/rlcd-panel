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
import com.rlcdpanel.watch.protocol.CalibrationReply
import com.rlcdpanel.watch.protocol.CalibrationText

/** State of the `CALIB` screen: the picked reference, the reply and the last message. */
data class CalibrationState(
    val reference: Int = 0,
    val isReading: Boolean = false,
    val isApplying: Boolean = false,
    val reply: CalibrationReply? = null,
    val message: String? = null,
    val isError: Boolean = false
)

@Composable
fun CalibrationScreen(
    state: CalibrationState,
    /** Picker entries: sensor number and name, see [CalibrationText.picker]. */
    picker: List<Pair<Int, String>>,
    onPick: (Int) -> Unit,
    onRead: () -> Unit,
    onApply: () -> Unit
) {
    val busy = state.isReading || state.isApplying
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
                item { Text("Referenz wählen", fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center) }
                for ((n, name) in picker) {
                    item {
                        Chip(
                            onClick = { onPick(n) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(0.85f),
                            colors = if (state.reference == n) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                            label = { Text(name, fontSize = 12.sp, maxLines = 1) }
                        )
                    }
                }
                item {
                    Chip(
                        onClick = onRead,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (state.isReading) "Lese Panel ..." else "Vergleichen", fontSize = 12.sp, maxLines = 1) }
                    )
                }
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
                state.reply?.let { reply ->
                    item { Text(CalibrationText.reference(reply), fontSize = 11.sp, textAlign = TextAlign.Center) }
                    for (row in CalibrationText.rows(reply)) {
                        item {
                            Text(
                                CalibrationText.line(row),
                                fontSize = 10.sp,
                                color = if (row.tempDiff == "--") Color(0xFFFFB74D) else Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                    reply.usb?.let { usb ->
                        item {
                            Text(
                                CalibrationText.usb(usb),
                                fontSize = 10.sp,
                                color = Color(0xFFB0BEC5),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                    if (reply.proposals.isNotEmpty()) {
                        item {
                            Text(
                                reply.proposals.joinToString("\n") { "${it.key}=${it.value}" },
                                fontSize = 10.sp,
                                color = Color(0xFF80CBC4),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                        item {
                            Chip(
                                onClick = onApply,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(0.85f),
                                colors = ChipDefaults.primaryChipColors(),
                                label = { Text(if (state.isApplying) "Sende ..." else "Übernehmen", fontSize = 12.sp, maxLines = 1) }
                            )
                        }
                    }
                }
                if (state.reply == null && state.message == null && !state.isReading) {
                    item {
                        Text(
                            "Wähle den Referenz-Sensor und tippe auf Vergleichen. Übernehmen braucht ble_console 2.",
                            fontSize = 10.sp,
                            color = Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            }
        }
    }
}
