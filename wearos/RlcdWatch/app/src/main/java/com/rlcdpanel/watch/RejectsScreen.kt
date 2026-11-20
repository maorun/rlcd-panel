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
import com.rlcdpanel.watch.protocol.RejectRow

/** Result of one `REJECTS` read: the rows, the count of lost MACs, or a text that tells why there are none. */
data class RejectsState(
    val isReading: Boolean = false,
    val rows: List<RejectRow> = emptyList(),
    val dropped: Int = 0,
    /** True after a successful read, so an empty list shows its own text. */
    val loaded: Boolean = false,
    val message: String? = null
)

/** Texts of the reject rows. */
object RejectsText {
    const val EMPTY = "No rejected packets"

    /** The last 5 characters of the MAC, for example `00:13`. */
    fun shortMac(mac: String): String = mac.takeLast(5)

    /** `METER FULL x2`: format, reason and count. */
    fun detail(row: RejectRow): String = "${row.format} ${row.reason.wire} x${row.count}"

    /** `DROPPED 3`; null when no MAC was lost. */
    fun dropped(count: Int): String? = if (count > 0) "DROPPED $count" else null
}

@Composable
fun RejectsScreen(
    state: RejectsState,
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
                        label = { Text(if (state.isReading) "Lese Panel ..." else "Read rejects", fontSize = 12.sp, maxLines = 1) }
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
                if (state.loaded && state.rows.isEmpty() && state.dropped == 0) {
                    item { Text(RejectsText.EMPTY, fontSize = 10.sp, color = Color(0xFFB0BEC5)) }
                }
                RejectsText.dropped(state.dropped)?.let { text ->
                    item { Text(text, fontSize = 10.sp, color = Color(0xFFFFB74D)) }
                }
                items(state.rows) { row ->
                    Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(0.95f)) {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(RejectsText.shortMac(row.mac), fontSize = 12.sp)
                            Text(RejectsText.detail(row), fontSize = 10.sp, color = Color(0xFFB0BEC5))
                        }
                    }
                }
            }
        }
    }
}
