package com.rlcdpanel.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.rlcdpanel.watch.protocol.SpreadApply
import com.rlcdpanel.watch.protocol.SpreadCommand
import com.rlcdpanel.watch.protocol.SpreadReply
import com.rlcdpanel.watch.protocol.SpreadText

/** Result of one `SPREAD` read: the reply, or a text that tells why there is none. */
data class SpreadState(
    val isReading: Boolean = false,
    val reply: SpreadReply? = null,
    /** Window of the request that produced [reply]. */
    val replyHours: Int = SpreadCommand.DEFAULT_HOURS,
    val message: String? = null,
    /** Row index whose proposal waits for a confirmation, or null. */
    val confirming: Int? = null,
    /** True while the `SET` commands run. */
    val isApplying: Boolean = false,
    /** True after `ERR READ_ONLY`: the chips stay hidden. */
    val readOnly: Boolean = false
)

/** Texts of the co-location check screen. */
object SpreadScreenText {
    const val IDLE = "Lege alle Sensoren für das gewählte Fenster an einen Ort. Tippe dann auf Lesen."

    /** `temp_dev` and `rh_dev` of one sensor, for example `+0.2 °C / -1.0 %RH`. */
    fun deviations(row: SpreadReply.SensorSpread): String = "${signed(row.tempDev)} °C / ${signed(row.rhDev)} %RH"

    /** Chip title of one sensor. An outlier gets the mark `⚠`. */
    fun title(row: SpreadReply.SensorSpread): String = "${if (row.isOutlier) "⚠ " else ""}${row.name}"

    private fun signed(value: Double): String = if (value > 0.0) "+$value" else "$value"
}

@Composable
fun SpreadScreen(
    state: SpreadState,
    hours: Int = SpreadCommand.DEFAULT_HOURS,
    onPickHours: (Int) -> Unit = {},
    onRead: () -> Unit,
    onAsk: (Int) -> Unit = {},
    onCancel: () -> Unit = {},
    onApply: (Int) -> Unit = {}
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
                    Row(
                        modifier = Modifier.fillMaxWidth(0.95f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (h in SpreadCommand.PICKER_HOURS) {
                            Chip(
                                onClick = { onPickHours(h) },
                                modifier = Modifier.weight(1f),
                                enabled = !state.isReading && !state.isApplying,
                                colors = if (h == hours) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                                label = { Text("$h h", fontSize = 11.sp, maxLines = 1) }
                            )
                        }
                    }
                }
                item {
                    Chip(
                        onClick = onRead,
                        enabled = !state.isReading && !state.isApplying,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (state.isReading) "Lese SPREAD ..." else "Read SPREAD", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                state.message?.let { message ->
                    item {
                        Text(
                            message,
                            fontSize = 10.sp,
                            color = Color(0xFFFF8A80),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                if (state.reply == null && state.message == null && !state.isReading) {
                    item {
                        Text(
                            SpreadScreenText.IDLE,
                            fontSize = 10.sp,
                            color = Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                state.reply?.let { reply ->
                    when (val outcome = reply.outcome) {
                        is SpreadReply.Outcome.Skipped -> item {
                            Text(
                                SpreadText.SKIP_TEXT,
                                fontSize = 11.sp,
                                color = Color(0xFFFFB74D),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                        is SpreadReply.Outcome.Measured -> {
                            item { Text(SpreadCommand.windowText(state.replyHours), fontSize = 10.sp, color = Color(0xFFB0BEC5)) }
                            item { Text("Median ${outcome.tempMedian} °C", fontSize = 12.sp, color = Color.White) }
                            item { Text("Median ${outcome.rhMedian} %RH", fontSize = 12.sp, color = Color.White) }
                            itemsIndexed(outcome.rows) { index, row ->
                                val proposal = SpreadApply.proposalFor(outcome, index)
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Chip(
                                        onClick = {},
                                        enabled = false,
                                        modifier = Modifier.fillMaxWidth(0.95f),
                                        colors = ChipDefaults.secondaryChipColors(),
                                        label = {
                                            Text(
                                                SpreadScreenText.title(row),
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                color = if (row.isOutlier) Color(0xFFFF8A80) else Color.Unspecified
                                            )
                                        },
                                        secondaryLabel = { Text(SpreadScreenText.deviations(row), fontSize = 10.sp, maxLines = 1) }
                                    )
                                    if (proposal != null && !state.readOnly) {
                                        if (state.confirming == index) {
                                            Text(
                                                SpreadApply.confirmText(proposal),
                                                fontSize = 10.sp,
                                                color = Color.White,
                                                textAlign = TextAlign.Center
                                            )
                                            Chip(
                                                onClick = { onApply(index) },
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
                                                onClick = { onAsk(index) },
                                                enabled = !state.isReading && !state.isApplying,
                                                modifier = Modifier.fillMaxWidth(0.85f),
                                                colors = ChipDefaults.secondaryChipColors(),
                                                label = { Text("Übernehmen", fontSize = 12.sp, maxLines = 1) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
