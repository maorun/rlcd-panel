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
import com.rlcdpanel.watch.protocol.SaltReply
import com.rlcdpanel.watch.protocol.SaltText

/** Result of one `SALT N` read: the parsed reply, or a text that tells why there is none. */
data class SaltState(
    val isReading: Boolean = false,
    val reply: SaltReply? = null,
    val message: String? = null
)

/** Texts of the salt screen. The screen shows the proposal only; it sends no `SET`. */
object SaltScreenText {
    /** `Vorschlag: rh_offset_pct=1.2`; an empty text for a result without a proposal. */
    fun proposal(reply: SaltReply): String? = reply.proposal?.let { "Vorschlag: ${it.key}=${it.value}" }
}

@Composable
fun SaltScreen(
    state: SaltState,
    roomName: String,
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
                item { Text("Salztest: $roomName", fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center) }
                item {
                    Chip(
                        onClick = onRead,
                        enabled = !state.isReading,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (state.isReading) "Lese Panel ..." else "Run NaCl check", fontSize = 12.sp, maxLines = 1) }
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
                state.reply?.let { reply ->
                    item {
                        Text(
                            text = SaltText.outcome(reply),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    SaltScreenText.proposal(reply)?.let { proposal ->
                        item {
                            Text(
                                text = proposal,
                                fontSize = 11.sp,
                                color = Color(0xFF80CBC4),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                        item {
                            Text(
                                text = "Nur Anzeige. Setze den Wert am Telefon oder am Mac.",
                                fontSize = 9.sp,
                                color = Color(0xFFB0BEC5),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                }
                if (state.reply == null && state.message == null && !state.isReading) {
                    item {
                        Text(
                            "Tippe auf Run. Sensor in der Salzbox, Panel an USB, ble_console 1 oder 2.",
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
