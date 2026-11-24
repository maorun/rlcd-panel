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
import com.rlcdpanel.watch.console.BleConsoleClient
import com.rlcdpanel.watch.protocol.ConsoleReply
import com.rlcdpanel.watch.protocol.ReplyError
import com.rlcdpanel.watch.protocol.SelfTestCheck
import com.rlcdpanel.watch.protocol.SelfTestReply
import com.rlcdpanel.watch.protocol.SelfTestState

/** State of the self-test screen. The test starts only after [confirming] ended with a tap on the start chip. */
data class SelfTestUiState(
    /** True while the screen asks the user to confirm the start. */
    val confirming: Boolean = false,
    val isRunning: Boolean = false,
    val reply: SelfTestReply? = null,
    val message: String? = null
)

/** Texts of the self-test screen. */
object SelfTestText {
    const val WARNING = "Der Test mountet die SD-Karte und schaltet die Mikrofone für 1 s ein."
    const val IDLE = "Tippe auf Start. Panel an USB, ble_console 1 oder 2."
    const val LOST_LINK = "Verbindung zum Panel verloren oder Antwort zu langsam"
    const val NO_END = "Antwort unvollständig: END SELFTEST fehlt"

    /** `shtc3 OK READ`: name, verdict and detail of one check. */
    fun label(check: SelfTestCheck): String = "${check.mark} ${check.name} ${check.state.wire}"

    /** The detail of a check. `CHECK ble SKIP CONSOLE` is an expected result over BLE. */
    fun detail(check: SelfTestCheck): String =
        when {
            isExpectedSkip(check) -> "${check.detail} (erwartet über BLE)"
            check.detail.isEmpty() -> "–"
            else -> check.detail
        }

    fun isExpectedSkip(check: SelfTestCheck): Boolean =
        check.name == "ble" && check.state == SelfTestState.SKIP && check.detail == "CONSOLE"

    /** `Bestanden 6, fehlgeschlagen 0`. */
    fun counts(reply: SelfTestReply): String = "Bestanden ${reply.pass}, fehlgeschlagen ${reply.fail}"

    /**
     * Text for a reply that does not parse. A reply without an `END SELFTEST` line gives [NO_END];
     * another bad line gives a general text.
     */
    fun parseError(lines: List<String>): String =
        if (lines.none { it.startsWith("END SELFTEST") }) NO_END else "Antwort nicht lesbar"

    /** Text for a reply that is not a frame. A timeout means a lost link or a slow test; any other error keeps its own text. */
    fun linkError(reply: ConsoleReply): String {
        val text = BleConsoleClient.errorText(reply)
        return if (reply is ConsoleReply.Corrupt && reply.error == ReplyError.Timeout) "$LOST_LINK. $text" else text
    }
}

@Composable
fun SelfTestScreen(
    state: SelfTestUiState,
    onAskStart: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
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
                if (state.confirming) {
                    item {
                        Text(
                            SelfTestText.WARNING,
                            fontSize = 10.sp,
                            color = Color(0xFFFFB74D),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    item {
                        Chip(
                            onClick = onConfirm,
                            modifier = Modifier.fillMaxWidth(0.85f),
                            colors = ChipDefaults.primaryChipColors(),
                            label = { Text("Test jetzt starten", fontSize = 12.sp, maxLines = 1) }
                        )
                    }
                    item {
                        Chip(
                            onClick = onCancel,
                            modifier = Modifier.fillMaxWidth(0.85f),
                            colors = ChipDefaults.secondaryChipColors(),
                            label = { Text("Abbrechen", fontSize = 12.sp, maxLines = 1) }
                        )
                    }
                } else {
                    item {
                        Chip(
                            onClick = onAskStart,
                            enabled = !state.isRunning,
                            modifier = Modifier.fillMaxWidth(0.85f),
                            colors = ChipDefaults.secondaryChipColors(),
                            label = { Text(if (state.isRunning) "Test läuft ..." else "Start self-test", fontSize = 12.sp, maxLines = 1) }
                        )
                    }
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
                if (state.reply == null && state.message == null && !state.confirming && !state.isRunning) {
                    item {
                        Text(
                            SelfTestText.IDLE,
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
                            SelfTestText.counts(reply),
                            fontSize = 11.sp,
                            color = if (reply.passed) Color(0xFF81C784) else Color(0xFFFF8A80)
                        )
                    }
                    items(reply.checks) { check ->
                        Chip(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(0.95f),
                            colors = ChipDefaults.secondaryChipColors(),
                            label = { Text(SelfTestText.label(check), fontSize = 12.sp, maxLines = 1) },
                            secondaryLabel = { Text(SelfTestText.detail(check), fontSize = 10.sp, maxLines = 2) }
                        )
                    }
                }
            }
        }
    }
}
