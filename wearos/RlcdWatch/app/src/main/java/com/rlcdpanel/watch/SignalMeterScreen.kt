package com.rlcdpanel.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.rlcdpanel.watch.protocol.SignalMeter
import kotlinx.coroutines.delay

/** What the signal meter screen shows. [message] is a failure or stop text; [running] is false after it. */
data class SignalMeterState(
    val name: String = "",
    val mac: String = "",
    val reading: SignalMeter.Reading = SignalMeter.Reading(null, null, 0, null),
    val running: Boolean = false,
    val message: String? = null
)

/** Texts of the signal meter. */
object SignalMeterText {
    const val STOPPED_AFTER_LIMIT = "Scan nach 5 min beendet"

    fun title(state: SignalMeterState): String = state.name.ifBlank { state.mac }
}

@Composable
fun SignalMeterScreen(
    state: SignalMeterState,
    onTick: () -> Unit,
    onRestart: () -> Unit
) {
    // One tick each second moves the age text and checks the 5 minute limit.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            onTick()
        }
    }
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
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item { Text(SignalMeterText.title(state), fontSize = 12.sp, maxLines = 1, color = Color(0xFFB0BEC5)) }
                item { Text(SignalMeter.dbm(state.reading.newestDbm), fontSize = 28.sp, textAlign = TextAlign.Center) }
                item {
                    Text("Mittel ${SignalMeter.meanText(state.reading.meanDbm)} (${state.reading.packets})", fontSize = 16.sp, textAlign = TextAlign.Center)
                }
                item { Text("Alter ${SignalMeter.ageText(state.reading.ageMs)}", fontSize = 16.sp, textAlign = TextAlign.Center) }
                state.message?.let { message ->
                    item {
                        Text(message, fontSize = 10.sp, color = Color(0xFFFFB74D), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }
                if (!state.running) {
                    item {
                        Chip(
                            onClick = onRestart,
                            modifier = Modifier.fillMaxWidth(0.85f),
                            colors = ChipDefaults.secondaryChipColors(),
                            label = { Text("Scan neu starten", fontSize = 12.sp, maxLines = 1) }
                        )
                    }
                }
            }
        }
    }
}
