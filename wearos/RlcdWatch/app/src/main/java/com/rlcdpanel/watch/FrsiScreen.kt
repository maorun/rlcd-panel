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
import com.rlcdpanel.watch.protocol.FrsiApply
import com.rlcdpanel.watch.protocol.FrsiReply
import com.rlcdpanel.watch.protocol.FrsiText
import com.rlcdpanel.watch.protocol.SaltCommand

/** State of the wall factor screen: the two pickers, the last read and the apply step. */
data class FrsiState(
    /** Room sensor 0 (onboard) to 10. */
    val room: Int = 0,
    /** Wall slot 1 to 10. */
    val wall: Int = 1,
    val isReading: Boolean = false,
    val reply: FrsiReply? = null,
    /** An error text that tells why there is no reply, or the text of the last apply. */
    val message: String? = null,
    val isApplying: Boolean = false,
    val confirming: Boolean = false,
    /** True after `ERR READ_ONLY`: the `Übernehmen` chip stays hidden. */
    val readOnly: Boolean = false,
    /** False for the text of a successful apply: it shows in green. */
    val messageIsError: Boolean = true
)

/** Pure picker rules of the wall factor screen. */
object FrsiScreenText {
    const val IDLE = "Panel an USB, Sensor an die Wand legen. Wähle Raum und Wand, tippe dann auf Messen."

    /** Next room after [room]: 0 to 10, then 0 again. */
    fun nextRoom(room: Int): Int = if (room >= SaltCommand.MAX_SENSOR) 0 else room + 1

    /** Next wall slot after [wall]: 1 to 10, then 1 again. */
    fun nextWall(wall: Int): Int = if (wall >= SaltCommand.MAX_SENSOR) 1 else wall + 1

    /** `Vorschlag: wall_frsi_pct=70`, or null without a valid proposal. */
    fun proposal(reply: FrsiReply): String? = reply.proposal?.let { "Vorschlag: ${it.key}=${it.value}" }
}

@Composable
fun FrsiScreen(
    state: FrsiState,
    roomName: String,
    wallName: String,
    onNextRoom: () -> Unit,
    onNextWall: () -> Unit,
    onRead: () -> Unit,
    onAsk: () -> Unit = {},
    onCancel: () -> Unit = {},
    onApply: () -> Unit = {}
) {
    val listState = rememberScalingLazyListState()
    val busy = state.isReading || state.isApplying
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
                item { Text("Wandfaktor", fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center) }
                item {
                    Chip(
                        onClick = onNextRoom,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text("Raum", fontSize = 12.sp, maxLines = 1) },
                        secondaryLabel = { Text(roomName, fontSize = 10.sp, maxLines = 1) }
                    )
                }
                item {
                    Chip(
                        onClick = onNextWall,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text("Wand", fontSize = 12.sp, maxLines = 1) },
                        secondaryLabel = { Text(wallName, fontSize = 10.sp, maxLines = 1) }
                    )
                }
                item {
                    Chip(
                        onClick = onRead,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (state.isReading) "Lese Panel ..." else "Messen", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                state.message?.let { message ->
                    item {
                        Text(
                            message,
                            fontSize = 10.sp,
                            color = if (state.messageIsError) Color(0xFFFF8A80) else Color(0xFF80CBC4),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                if (state.reply == null && state.message == null && !state.isReading) {
                    item {
                        Text(
                            FrsiScreenText.IDLE,
                            fontSize = 10.sp,
                            color = Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                state.reply?.let { reply ->
                    item {
                        Text(
                            FrsiText.outcome(reply),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    FrsiScreenText.proposal(reply)?.let { proposal ->
                        item {
                            Text(
                                proposal,
                                fontSize = 11.sp,
                                color = Color(0xFF80CBC4),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                        if (!state.readOnly && FrsiApply.batch(reply) != null) {
                            if (state.confirming) {
                                item {
                                    Chip(
                                        onClick = onApply,
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(0.85f),
                                        colors = ChipDefaults.primaryChipColors(),
                                        label = { Text(if (state.isApplying) "Sende ..." else "Senden", fontSize = 12.sp, maxLines = 1) }
                                    )
                                }
                                item {
                                    Chip(
                                        onClick = onCancel,
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(0.85f),
                                        colors = ChipDefaults.secondaryChipColors(),
                                        label = { Text("Abbrechen", fontSize = 12.sp, maxLines = 1) }
                                    )
                                }
                            } else {
                                item {
                                    Chip(
                                        onClick = onAsk,
                                        enabled = !busy,
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
