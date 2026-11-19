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
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.rlcdpanel.watch.console.BleConsoleClient
import com.rlcdpanel.watch.protocol.ConsoleReply
import com.rlcdpanel.watch.protocol.NtpState
import com.rlcdpanel.watch.protocol.ReceptionRate
import com.rlcdpanel.watch.protocol.ReplyError
import com.rlcdpanel.watch.protocol.StatusReply
import com.rlcdpanel.watch.protocol.StatusSensor

/**
 * Result of the last `STATUS` read. A failed read sets [message] and keeps [status] of the last good reply.
 */
data class SensorHealthState(
    val isReading: Boolean = false,
    val isMuting: Boolean = false,
    val status: StatusReply? = null,
    val message: String? = null
)

/** One-line texts of the rows. */
object SensorHealthText {
    const val WARNING = "⚠"

    fun battery(sensor: StatusSensor): String = sensor.battery?.let { "Bat ${it.toInt()} %" } ?: "Bat --"
    fun rssi(sensor: StatusSensor): String = sensor.rssi?.let { "${it.toInt()} dBm" } ?: "-- dBm"
    fun rate(rate: ReceptionRate?): String = rate?.let { "Empfang ${it.received}/${it.scans} (${it.percent} %)" } ?: "Empfang --"
    fun isRateWeak(rate: ReceptionRate?): Boolean = rate?.isWeak == true
    fun ntp(ntp: NtpState?): String? = ntp?.let { "Uhr-Sync ${if (it.ok) "ok" else "Fehler"}, ${it.failures} Fehlversuche" }

    /** Text of a failed `MUTE` or `STATUS` reply that `ChimeCommand.failureText` does not word itself. */
    fun muteFallback(reply: ConsoleReply): String =
        if (reply is ConsoleReply.Corrupt && reply.error == ReplyError.Timeout) "Panel not in range"
        else BleConsoleClient.errorText(reply)

    /** Reception rate of the slot of a `SENSOR` line; slot 0 is the onboard sensor and has none; slots 1 to 10 are possible. */
    fun rateOf(status: StatusReply, sensor: StatusSensor): ReceptionRate? =
        if (sensor.index in 1..10) status.reception?.getOrNull(sensor.index - 1) else null
}

@Composable
fun SensorHealthScreen(
    state: SensorHealthState,
    onRead: () -> Unit,
    onSetMute: (Boolean) -> Unit = {},
    onOpenEvents: () -> Unit = {}
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
                        label = { Text(if (state.isReading) "Lese Panel ..." else "Read sensor health", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                item {
                    Chip(
                        onClick = onOpenEvents,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text("Event log", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                state.message?.let { message ->
                    item {
                        Text(
                            text = message + if (state.status != null) ". Letzter guter Stand bleibt." else "",
                            fontSize = 10.sp,
                            color = Color(0xFFFF8A80),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                val status = state.status
                if (status == null && state.message == null) {
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
                val muted = status?.muted
                if (muted != null) {
                    item {
                        ToggleChip(
                            checked = muted,
                            onCheckedChange = { onSetMute(!muted) },
                            enabled = !state.isReading && !state.isMuting,
                            modifier = Modifier.fillMaxWidth(0.85f),
                            label = { Text(if (state.isMuting) "Sende ..." else "Panel stumm", fontSize = 12.sp, maxLines = 1) },
                            toggleControl = {
                                Icon(
                                    imageVector = ToggleChipDefaults.switchIcon(checked = muted),
                                    contentDescription = if (muted) "an" else "aus"
                                )
                            }
                        )
                    }
                }
                if (status != null) {
                    SensorHealthText.ntp(status.ntp)?.let { text ->
                        item {
                            val warn = status.ntp?.isWarning == true
                            Text(
                                text = (if (warn) "${SensorHealthText.WARNING} " else "") + text,
                                fontSize = 10.sp,
                                color = if (warn) Color(0xFFFFB74D) else Color(0xFFB0BEC5),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    items(status.sensors) { sensor ->
                        val rate = SensorHealthText.rateOf(status, sensor)
                        Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(0.95f)) {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    "${sensor.name} ${sensor.state ?: "--"}",
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                                Text("${SensorHealthText.battery(sensor)}, ${SensorHealthText.rssi(sensor)}", fontSize = 10.sp)
                                Text(
                                    (if (SensorHealthText.isRateWeak(rate)) "${SensorHealthText.WARNING} " else "") + SensorHealthText.rate(rate),
                                    fontSize = 10.sp,
                                    color = if (SensorHealthText.isRateWeak(rate)) Color(0xFFFFB74D) else Color.Unspecified
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
