package com.rlcdpanel.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.rlcdpanel.watch.protocol.AlertTimeline
import com.rlcdpanel.watch.protocol.EventFilter
import com.rlcdpanel.watch.protocol.EventRow
import com.rlcdpanel.watch.protocol.EventText

/** Result of one `EVENTS` read: the rows oldest first, the count of lost events, or a text that tells why there are none. */
data class EventLogState(
    val isReading: Boolean = false,
    val rows: List<EventRow> = emptyList(),
    val dropped: Int = 0,
    /** True after a successful read, so an empty ring shows its own text. */
    val loaded: Boolean = false,
    val message: String? = null,
    /** True after the `EVENTS` read of `Show all`; the list then keeps every row. */
    val all: Boolean = false
) {
    /** The newest [MAX_ROWS] rows for the small screen, newest first. */
    val visibleRows: List<EventRow> get() = visibleRows(null)

    /** The newest [MAX_ROWS] rows of [category] (`null` keeps each row), newest first. After `Show all` the list keeps every row. */
    fun visibleRows(category: EventFilter.Category?): List<EventRow> {
        val filtered = EventFilter.apply(rows, setOfNotNull(category))
        return (if (all) filtered else filtered.takeLast(MAX_ROWS)).asReversed()
    }

    companion object {
        const val MAX_ROWS = 8

        /** The timeline shows the newest [MAX_INTERVALS] intervals, so the event rows stay in reach on the small screen. */
        const val MAX_INTERVALS = 8

        /** The first read asks for [SHORT_COUNT] events, because a short reply needs less BLE time. */
        const val SHORT_COUNT = MAX_ROWS
        const val UNKNOWN_TIME = "--:--"

        /** `HH:mm` of `YYYY-MM-DDTHH:mm:ss` or `YYYY-MM-DD HH:mm:ss`; an empty or short time gives [UNKNOWN_TIME]. */
        fun formatTime(time: String): String {
            val sep = time.indexOfFirst { it == 'T' || it == ' ' }
            if (sep < 0 || time.length < sep + 6) return UNKNOWN_TIME
            return time.substring(sep + 1, sep + 6)
        }
    }
}

@Composable
fun EventLogScreen(
    state: EventLogState,
    onRead: () -> Unit,
    onReadAll: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    var category by remember { mutableStateOf<EventFilter.Category?>(null) }
    val shownRows = state.visibleRows(category)
    // Intervals come from all rows, newest first; the clock is read once per read result.
    val intervals = remember(state.rows) { AlertTimeline.build(state.rows, System.currentTimeMillis()).asReversed().take(EventLogState.MAX_INTERVALS) }
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
                if (state.loaded && intervals.isNotEmpty()) {
                    item { Text("Alarm-Zeitleiste", fontSize = 10.sp, color = Color(0xFFB0BEC5)) }
                    if (!state.all && state.rows.size >= EventLogState.SHORT_COUNT) {
                        // The short read holds only the newest events, so an older rise is missing and its start shows with `~`.
                        item {
                            Text(
                                "Nur die letzten Ereignisse. Alle Ereignisse laden: Show all.",
                                fontSize = 9.sp,
                                color = Color(0xFFFFB74D),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                    items(intervals) { interval ->
                        Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(0.95f)) {
                            Text(AlertTimeline.lineText(interval), fontSize = 11.sp)
                        }
                    }
                }
                item {
                    Chip(
                        onClick = { category = EventFilterChip.next(category) },
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(EventFilterChip.label(category, shownRows.size), fontSize = 12.sp, maxLines = 1) }
                    )
                }
                item {
                    Chip(
                        onClick = onRead,
                        enabled = !state.isReading,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (state.isReading) "Lese Panel ..." else "Read events", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                if (!state.all) {
                    item {
                        Chip(
                            onClick = onReadAll,
                            enabled = !state.isReading,
                            modifier = Modifier.fillMaxWidth(0.85f),
                            colors = ChipDefaults.secondaryChipColors(),
                            label = { Text("Show all", fontSize = 12.sp, maxLines = 1) }
                        )
                    }
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
                if (state.dropped > 0) {
                    item {
                        Text(
                            "${state.dropped} ältere Ereignisse gingen verloren.",
                            fontSize = 10.sp,
                            color = Color(0xFFFFB74D),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                if (state.loaded && state.rows.isEmpty()) {
                    item { Text("Keine Ereignisse.", fontSize = 10.sp, color = Color(0xFFB0BEC5)) }
                }
                if (state.loaded && state.rows.isNotEmpty() && shownRows.isEmpty()) {
                    item { Text("Keine Ereignisse in dieser Kategorie.", fontSize = 10.sp, color = Color(0xFFB0BEC5)) }
                }
                items(shownRows) { row ->
                    Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(0.95f)) {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(EventLogState.formatTime(row.time), fontSize = 10.sp, color = Color(0xFFB0BEC5))
                            Text(
                                EventText.describe(row),
                                fontSize = 12.sp,
                                color = if (EventText.isAbnormal(row)) MaterialTheme.colors.error else Color.Unspecified
                            )
                        }
                    }
                }
            }
        }
    }
}
