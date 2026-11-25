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
import androidx.compose.ui.text.font.FontFamily
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
import com.rlcdpanel.watch.protocol.ReceptionRow

/** Result of one `RECEPTION` read: the rows, or a text that tells why there are none. */
data class ReceptionState(
    val isReading: Boolean = false,
    val rows: List<ReceptionRow> = emptyList(),
    /** True after a successful read, so an empty list shows its own text. */
    val loaded: Boolean = false,
    val message: String? = null
)

/** Texts of the reception rows. */
object ReceptionText {
    const val EMPTY = "No sensor in a slot"

    /** The name of the slot, else `Slot <N>`. */
    fun title(row: ReceptionRow): String = row.name.ifEmpty { "Slot ${row.slot}" }

    /** `12/24`, with the warning sign below 50 %. */
    fun counts(row: ReceptionRow): String = "${row.received}/${row.scans}" + if (row.isLow) " ⚠" else ""

    /** The newest 12 scans: `█` for a packet, `·` for a gap. Empty without a counted scan. */
    fun strip(row: ReceptionRow): String =
        row.newestCells().map { if (it == '1') '█' else '·' }.joinToString("")
}

@Composable
fun ReceptionScreen(
    state: ReceptionState,
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
                        label = { Text(if (state.isReading) "Lese Panel ..." else "Read reception", fontSize = 12.sp, maxLines = 1) }
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
                if (state.loaded && state.rows.isEmpty()) {
                    item { Text(ReceptionText.EMPTY, fontSize = 10.sp, color = Color(0xFFB0BEC5)) }
                }
                items(state.rows) { row ->
                    Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(0.95f)) {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(ReceptionText.title(row), fontSize = 12.sp, maxLines = 1)
                            Text(
                                ReceptionText.counts(row),
                                fontSize = 10.sp,
                                color = if (row.isLow) Color(0xFFFFB74D) else Color(0xFFB0BEC5)
                            )
                            if (row.scans > 0) {
                                Text(
                                    ReceptionText.strip(row),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
