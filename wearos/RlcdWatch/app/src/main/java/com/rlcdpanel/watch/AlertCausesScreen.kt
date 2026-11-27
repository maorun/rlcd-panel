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
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText

/** Result of one `ALERTS` read: the cause lines, or a text that tells why there are none. */
data class AlertCausesUiState(
    val isReading: Boolean = false,
    val lines: List<String>? = null,
    val message: String? = null
)

/** Texts of the alert causes screen. */
object AlertCausesText {
    const val NONE = "No active alert"
    const val TAP = "Tippe auf Read. Panel an USB, ble_console 1 oder 2."
}

@Composable
fun AlertCausesScreen(state: AlertCausesUiState, onRead: () -> Unit) {
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
                        label = { Text(if (state.isReading) "Lese Panel ..." else "Read causes", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                state.message?.let { message ->
                    item { Note(message, Color(0xFFFF8A80)) }
                }
                val lines = state.lines
                if (lines == null && state.message == null && !state.isReading) {
                    item { Note(AlertCausesText.TAP, Color(0xFFB0BEC5)) }
                }
                if (lines != null && lines.isEmpty()) {
                    item { Note(AlertCausesText.NONE, Color(0xFFB0BEC5)) }
                }
                items(lines ?: emptyList()) { line ->
                    Text(line, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, fontSize = 10.sp, color = color, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
}
