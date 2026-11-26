package com.rlcdpanel.phone

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.protocol.AlertMonthStat
import com.rlcdpanel.phone.protocol.AlertTimeline
import com.rlcdpanel.phone.protocol.BadgeMonthStat
import com.rlcdpanel.phone.protocol.EventFilter
import com.rlcdpanel.phone.protocol.EventRow
import com.rlcdpanel.phone.protocol.EventStats
import com.rlcdpanel.phone.protocol.EventText
import java.time.ZoneId

/** Result of one `EVENTS` read: the rows oldest first, the count of lost events, or a text that tells why there are none. */
data class EventLogState(
    val isReading: Boolean = false,
    val rows: List<EventRow> = emptyList(),
    val dropped: Int = 0,
    /** True after a successful read, so an empty ring shows its own text. */
    val loaded: Boolean = false,
    val message: String? = null
) {
    /** The rows for the screen, newest first. */
    val newestFirst: List<EventRow> get() = rows.asReversed()

    /** The newest [count] rows, newest first. A [count] below 0 gives no rows. */
    fun newest(count: Int): List<EventRow> = newestFirst.take(count.coerceAtLeast(0))
}

@Composable
fun EventLogScreen(
    state: EventLogState,
    onRead: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit
) {
    var selectedCategories by remember { mutableStateOf(emptySet<EventFilter.Category>()) }
    val visibleRows = EventFilter.apply(state.newestFirst, selectedCategories)
    // The time of the last row load ends an open interval, so its duration ages only on a reload.
    val zone = remember { ZoneId.systemDefault() }
    val intervals = remember(state.rows) {
        AlertTimeline.build(state.rows, System.currentTimeMillis(), zone).asReversed()
    }

    val badges = remember(state.rows) {
        AlertTimeline.badges(state.rows, System.currentTimeMillis(), zone).asReversed()
    }

    // Newest month first; inside a month the alert, event and tile order stays ascending.
    val alertStats = remember(intervals) {
        EventStats.alerts(intervals, zone).sortedWith(compareByDescending<AlertMonthStat> { it.month }.thenBy { it.alert })
    }
    val badgeStats = remember(badges) {
        EventStats.badges(badges, zone)
            .sortedWith(compareByDescending<BadgeMonthStat> { it.month }.thenBy { it.event }.thenBy { it.tile })
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(modifier = Modifier.height(24.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Zurück") }
                    Button(onClick = onRead, enabled = !state.isReading) {
                        Text(if (state.isReading) "Lese…" else "Ereignisse lesen")
                    }
                    if (state.rows.isNotEmpty()) {
                        OutlinedButton(onClick = onShare) { Text("Share CSV") }
                    }
                }
            }
            item {
                Text(
                    "Liest EVENTS über die BLE-Konsole. Das Panel braucht USB-Strom und ble_console 1 oder 2.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (state.dropped > 0) {
                item { Text("${state.dropped} ältere Ereignisse gingen verloren.", style = MaterialTheme.typography.bodySmall) }
            }
            if (state.loaded && state.rows.isEmpty()) {
                item { Text("Keine Ereignisse.", style = MaterialTheme.typography.bodyMedium) }
            }
            if (state.rows.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (category in EventFilter.Category.entries) {
                            val selected = category in selectedCategories
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedCategories = if (selected) selectedCategories - category else selectedCategories + category
                                },
                                label = { Text(category.title) }
                            )
                        }
                    }
                }
            }
            if (intervals.isNotEmpty()) {
                item { Text("Alarm-Verlauf", style = MaterialTheme.typography.titleSmall) }
                items(intervals, key = { "${it.alert}-${it.startMs}" }) { interval ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            AlertTimeline.lineText(interval),
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (interval.isOpen) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            if (badges.isNotEmpty()) {
                item { Text("Badge-Verlauf", style = MaterialTheme.typography.titleSmall) }
                items(badges) { interval ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            AlertTimeline.badgeLineText(interval),
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (interval.isOpen) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            if (alertStats.isNotEmpty()) {
                item { Text("Alarm-Statistik je Monat (letzte Ereignisse)", style = MaterialTheme.typography.titleSmall) }
                items(alertStats, key = { "${it.month}-${it.alert}" }) { stat ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(EventStats.lineText(stat), modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (badgeStats.isNotEmpty()) {
                item { Text("Badge-Statistik je Monat (letzte Ereignisse)", style = MaterialTheme.typography.titleSmall) }
                items(badgeStats, key = { "${it.month}-${it.event}-${it.tile}" }) { stat ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(EventStats.badgeLineText(stat), modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (state.loaded && state.rows.isNotEmpty() && visibleRows.isEmpty()) {
                item { Text("Keine Ereignisse in der Auswahl.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(visibleRows) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(row.time.ifEmpty { "Zeit unbekannt" }, style = MaterialTheme.typography.labelSmall)
                        Text(
                            EventText.describe(row),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (EventText.isAbnormal(row)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
