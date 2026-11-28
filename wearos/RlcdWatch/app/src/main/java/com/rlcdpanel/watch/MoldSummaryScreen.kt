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
import com.rlcdpanel.watch.protocol.MoldRoomSummary
import com.rlcdpanel.watch.protocol.MoldSummary

/** Result of one `DAYS 31` read: the rooms sorted by mold hours, or a text that tells why there are none. */
data class MoldSummaryState(
    val isReading: Boolean = false,
    val rooms: List<MoldRoomSummary> = emptyList(),
    val message: String? = null,
    /** True after a reply that parsed; an empty [rooms] list then means the rows hold no sensor. */
    val hasRead: Boolean = false
)

/** Texts of the mold summary screen. */
object MoldSummaryScreenText {
    const val IDLE = "Tippe auf Lesen für die Schimmelstunden der letzten 31 Tage."
    const val EMPTY = "Keine Tagesdaten im Panel"
}

@Composable
fun MoldSummaryScreen(
    state: MoldSummaryState,
    onRead: () -> Unit
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
                        enabled = !state.isReading,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (state.isReading) "Lese DAYS ..." else "Read mold days", fontSize = 12.sp, maxLines = 1) }
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
                if (!state.hasRead && state.message == null && !state.isReading) {
                    item {
                        Text(
                            MoldSummaryScreenText.IDLE,
                            fontSize = 10.sp,
                            color = Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                if (state.hasRead && state.rooms.isEmpty() && state.message == null) {
                    item { Text(MoldSummaryScreenText.EMPTY, fontSize = 11.sp, color = Color(0xFFB0BEC5)) }
                }
                items(state.rooms) { room ->
                    Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(0.95f)) {
                        Text("${room.name}  ${MoldSummary.totalText(room)}", fontSize = 12.sp, maxLines = 1, color = Color.White)
                        Text("Tage mit Schimmel: ${MoldSummary.daysText(room)}", fontSize = 10.sp, maxLines = 1)
                        Text("Schlimmster Tag: ${MoldSummary.worstText(room)}", fontSize = 10.sp, maxLines = 1)
                        Text("Hitzestunden: ${MoldSummary.heatText(room)}", fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}
