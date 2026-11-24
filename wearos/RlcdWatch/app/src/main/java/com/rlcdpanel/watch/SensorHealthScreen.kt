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
import com.rlcdpanel.watch.protocol.ChimeCommand
import com.rlcdpanel.watch.protocol.ConsoleReply
import com.rlcdpanel.watch.protocol.LatchState
import com.rlcdpanel.watch.protocol.MemoryState
import com.rlcdpanel.watch.protocol.NoiseState
import com.rlcdpanel.watch.protocol.BleRejectState
import com.rlcdpanel.watch.protocol.NtpState
import com.rlcdpanel.watch.protocol.ReceptionRate
import com.rlcdpanel.watch.protocol.ReplyError
import com.rlcdpanel.watch.protocol.ResetState
import com.rlcdpanel.watch.protocol.SensorTrend
import com.rlcdpanel.watch.protocol.RuntimeState
import com.rlcdpanel.watch.protocol.Shtc3State
import com.rlcdpanel.watch.protocol.StatusReply
import com.rlcdpanel.watch.protocol.StatusSensor
import com.rlcdpanel.watch.protocol.TimeReply
import com.rlcdpanel.watch.protocol.VersionReply

/**
 * Result of the last `STATUS` read. A failed read sets [message] and keeps [status] of the last good reply.
 */
data class SensorHealthState(
    val isReading: Boolean = false,
    val isMuting: Boolean = false,
    val status: StatusReply? = null,
    val message: String? = null
)

/**
 * Result of the last `TIME` read. A failed read sets [message] and clears [clockReply].
 * [clockOffsetSeconds] is positive when the panel is ahead of the watch.
 */
data class ClockReadState(
    val isReadingClock: Boolean = false,
    val clockReply: TimeReply? = null,
    val clockOffsetSeconds: Long? = null,
    val clockMessage: String? = null
)

/** Result of the last `VERSION` read. A failed read sets [message] and clears [reply]. */
data class VersionReadState(
    val isReading: Boolean = false,
    val reply: VersionReply? = null,
    val message: String? = null
)

/**
 * State of the chime test. [bit] is the selected alert (0 to 7). [message] holds the last result text;
 * [isError] marks a failed command.
 */
data class ChimeTestState(
    val isTesting: Boolean = false,
    val bit: Int = 0,
    val message: String? = null,
    val isError: Boolean = false
)

/** One-line texts of the rows. */
object SensorHealthText {
    const val WARNING = "⚠"

    fun battery(sensor: StatusSensor): String = sensor.battery?.let { "Bat ${it.toInt()} %" } ?: "Bat --"
    fun rssi(sensor: StatusSensor): String = sensor.rssi?.let { "${it.toInt()} dBm" } ?: "-- dBm"
    fun rate(rate: ReceptionRate?): String = rate?.let { "Empfang ${it.received}/${it.scans} (${it.percent} %)" } ?: "Empfang --"
    fun isRateWeak(rate: ReceptionRate?): Boolean = rate?.isWeak == true

    /** Hours above `mold_alert_limit_rh` in the last 24 hours; the warning mark shows above 0 hours. */
    fun mold(sensor: StatusSensor): String =
        sensor.moldHours?.let { (if (it > 0) "$WARNING " else "") + "Schimmel $it h" } ?: "Schimmel --"
    fun trendSymbol(trend: SensorTrend?): String = trend?.symbol ?: "–"

    /** `T <value> <arrow>  RH <value> <arrow>`; `↑` rise, `↓` fall, `–` steady and unknown. */
    fun trend(sensor: StatusSensor): String =
        "T ${sensor.temperature?.let { "%.1f °C".format(java.util.Locale.ROOT, it) } ?: "--"} ${trendSymbol(sensor.temperatureTrend)}  " +
            "RH ${sensor.humidity?.let { "%.1f %%".format(java.util.Locale.ROOT, it) } ?: "--"} ${trendSymbol(sensor.humidityTrend)}"

    fun isMold(sensor: StatusSensor): Boolean = (sensor.moldHours ?: 0) > 0

    /** Panel badges of one sensor row, for example `V · STALE`; null without a `LATCH` line or without a badge. */
    fun badges(latch: LatchState?, sensor: StatusSensor): String? =
        latch?.badges(sensor.index)?.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** True for a slot sensor (index 1 to 10) with a MAC in the `STATUS` reply; the onboard sensor has no BLE packets. */
    fun canMeter(sensor: StatusSensor): Boolean = sensor.index in 1..10 && sensor.mac != null

    const val CLOCK_WARN_SECONDS = 120L

    /** True for an offset above 120 s in either direction, for `valid=0` and for `osf=1`. */
    fun isClockWarning(reply: TimeReply, offsetSeconds: Long?): Boolean =
        !reply.valid || reply.osf || (offsetSeconds != null && Math.abs(offsetSeconds) > CLOCK_WARN_SECONDS)

    fun clockOffset(offsetSeconds: Long?): String =
        offsetSeconds?.let { "Clock offset: ${if (it > 0) "+" else ""}$it s" } ?: "Clock offset: --"

    fun ntp(ntp: NtpState?): String? = ntp?.let { "Uhr-Sync ${if (it.ok) "ok" else "Fehler"}, ${it.failures} Fehlversuche" }

    /** `BLE abgelehnt <N>, Sprünge <N>` (`--` for a missing `jumps` field); null without a `BLE` line. */
    fun bleRejects(ble: BleRejectState?): String? =
        ble?.let { "BLE abgelehnt ${it.rejected}, Sprünge ${it.jumps ?: "--"}" }

    const val SD_LOW_MB = 100

    fun sdFree(mb: Int?): String = if (mb != null) "SD frei $mb MB" else "SD frei --"

    /** True for a known free space below [SD_LOW_MB]. */
    fun isSdLow(mb: Int?): Boolean = mb != null && mb < SD_LOW_MB

    /** `Runtime <N> h`; `Runtime --` for `h=--`; null without a `RUNTIME` line, so no row shows. */
    fun runtimeHours(r: RuntimeState?): String? = r?.let { rt -> rt.hours?.let { "Runtime $it h" } ?: "Runtime --" }

    /** `Cycles <N.N>`; `Cycles --` for `cycles=--`; null without a `RUNTIME` line. */
    fun cycles(r: RuntimeState?): String? =
        r?.let { "Cycles ${it.cycles?.let { c -> "%.1f".format(java.util.Locale.ROOT, c) } ?: "--"}" }

    fun reset(r: ResetState?): String = r?.let { "Reset ${it.reason}, abnormal ${it.abnormalCount ?: "--"}" } ?: "Reset --"
    fun shtc3(s: Shtc3State?): String =
        s?.let { "SHTC3 nack ${it.nack}, short ${it.shortReads}, crc ${it.crc}, run ${it.run}, retried ${it.retried}" } ?: "SHTC3 --"
    fun memory(m: MemoryState?): String =
        m?.let { "Heap frei ${it.free} B, min ${it.min} B, größter Block ${it.largest} B, PSRAM ${it.psram} B" } ?: "Heap --"

    /** One short line: `Lärm <db> dB <band>`; the peak stays out because the watch row has room for one line. */
    fun noise(n: NoiseState?): String = n?.let { "Lärm ${it.db ?: "--"} dB ${it.band}" } ?: "Lärm --"

    /** Text of a failed `MUTE` or `STATUS` reply that `ChimeCommand.failureText` does not word itself. */
    fun muteFallback(reply: ConsoleReply): String =
        if (reply is ConsoleReply.Corrupt && reply.error == ReplyError.Timeout) "Panel not in range"
        else BleConsoleClient.errorText(reply)

    /** One line of a parsed `VERSION` reply. */
    fun firmware(v: VersionReply): String = "FW ${v.firmware} (${v.git})"

    /** Text of a failed `VERSION` read: a timeout says the panel is not in range. */
    fun versionFailure(reply: ConsoleReply): String = muteFallback(reply)

    /** Next alert bit of the selector; wraps from the last name to bit 0. */
    fun nextChimeBit(bit: Int): Int = (bit + 1) % ChimeCommand.ALERT_NAMES.size

    /** State after a `BEEP` reply. [failure] is the text of `ChimeCommand.failureText`; a blank failure of an unreadable reply gets a fixed text. */
    fun chimeResult(state: ChimeTestState, reply: ConsoleReply, failure: String?): ChimeTestState = when {
        failure != null -> state.copy(isTesting = false, message = failure.ifBlank { "Antwort nicht lesbar" }, isError = true)
        reply is ConsoleReply.Status -> state.copy(isTesting = false, message = ChimeCommand.okText(reply.line), isError = false)
        else -> state.copy(isTesting = false, message = "Antwort nicht lesbar", isError = true)
    }

    /** State after an `IDENTIFY` reply: `Panel zeigt RLCD <XXXX>`; [failure] is the text of `ChimeCommand.failureText`. */
    fun identifyResult(state: ChimeTestState, reply: ConsoleReply, failure: String?): ChimeTestState {
        val text = (reply as? ConsoleReply.Status)?.line?.let { ChimeCommand.identifyText(it) }
        return when {
            failure != null -> state.copy(isTesting = false, message = failure.ifBlank { "Antwort nicht lesbar" }, isError = true)
            text != null -> state.copy(isTesting = false, message = text, isError = false)
            else -> state.copy(isTesting = false, message = "Antwort nicht lesbar", isError = true)
        }
    }

    /** Reception rate of the slot of a `SENSOR` line; slot 0 is the onboard sensor and has none; slots 1 to 10 are possible. */
    fun rateOf(status: StatusReply, sensor: StatusSensor): ReceptionRate? =
        if (sensor.index in 1..10) status.reception?.getOrNull(sensor.index - 1) else null
}

@Composable
fun SensorHealthScreen(
    state: SensorHealthState,
    onRead: () -> Unit,
    onSetMute: (Boolean) -> Unit = {},
    onOpenEvents: () -> Unit = {},
    onOpenWeek: () -> Unit = {},
    onOpenSignal: (StatusSensor) -> Unit = {},
    onOpenRejects: () -> Unit = {},
    onOpenSelfTest: () -> Unit = {},
    onOpenSalt: () -> Unit = {},
    onOpenSpread: () -> Unit = {},
    onOpenToday: () -> Unit = {},
    clockState: ClockReadState = ClockReadState(),
    onReadClock: () -> Unit = {},
    chimeState: ChimeTestState = ChimeTestState(),
    onSelectChime: (Int) -> Unit = {},
    onTestChime: () -> Unit = {},
    onIdentify: () -> Unit = {},
    versionState: VersionReadState = VersionReadState(),
    onReadVersion: () -> Unit = {}
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
                item {
                    Chip(
                        onClick = onOpenWeek,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text("Week chart", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                item {
                    Chip(
                        onClick = onOpenSalt,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text("Salt check", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                item {
                    Chip(
                        onClick = onOpenSpread,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text("Co-location check", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                item {
                    Chip(
                        onClick = onOpenToday,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text("Today", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                item {
                    Chip(
                        onClick = onOpenRejects,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text("Rejected packets", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                item {
                    Chip(
                        onClick = onOpenSelfTest,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text("Self-test", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                item {
                    Chip(
                        onClick = onReadVersion,
                        enabled = !versionState.isReading && !state.isReading && !state.isMuting && !clockState.isReadingClock,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (versionState.isReading) "Lese Version ..." else "Firmware version", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                versionState.reply?.let { reply ->
                    item {
                        Text(
                            text = SensorHealthText.firmware(reply),
                            fontSize = 10.sp,
                            color = Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                }
                versionState.message?.let { message ->
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
                item {
                    Chip(
                        onClick = onReadClock,
                        enabled = !clockState.isReadingClock && !versionState.isReading && !state.isReading && !state.isMuting,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (clockState.isReadingClock) "Lese Uhr ..." else "Read clock", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                clockState.clockReply?.let { reply ->
                    item {
                        val warn = SensorHealthText.isClockWarning(reply, clockState.clockOffsetSeconds)
                        Text(
                            text = (if (warn) "${SensorHealthText.WARNING} " else "") + SensorHealthText.clockOffset(clockState.clockOffsetSeconds) +
                                "\nvalid=${if (reply.valid) 1 else 0} osf=${if (reply.osf) 1 else 0}",
                            fontSize = 10.sp,
                            color = if (warn) Color(0xFFFFB74D) else Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                clockState.clockMessage?.let { message ->
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
                item {
                    Chip(
                        onClick = { onSelectChime(SensorHealthText.nextChimeBit(chimeState.bit)) },
                        enabled = !chimeState.isTesting,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text("Alert: ${ChimeCommand.alertName(chimeState.bit)}", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                item {
                    Chip(
                        onClick = onTestChime,
                        enabled = !chimeState.isTesting && !state.isReading && !state.isMuting && !clockState.isReadingClock && !versionState.isReading,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (chimeState.isTesting) "Sende ..." else "Test chime", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                item {
                    Chip(
                        onClick = onIdentify,
                        enabled = !chimeState.isTesting && !state.isReading && !state.isMuting && !clockState.isReadingClock && !versionState.isReading,
                        modifier = Modifier.fillMaxWidth(0.85f),
                        colors = ChipDefaults.secondaryChipColors(),
                        label = { Text(if (chimeState.isTesting) "Sende ..." else "Panel finden", fontSize = 12.sp, maxLines = 1) }
                    )
                }
                chimeState.message?.let { message ->
                    item {
                        Text(
                            text = message,
                            fontSize = 10.sp,
                            color = if (chimeState.isError) Color(0xFFFF8A80) else Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
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
                    SensorHealthText.bleRejects(status.bleRejects)?.let { text ->
                        item {
                            val warn = status.bleRejects?.isWarning == true
                            Text(
                                text = (if (warn) "${SensorHealthText.WARNING} " else "") + text,
                                fontSize = 10.sp,
                                color = if (warn) Color(0xFFFFB74D) else Color(0xFFB0BEC5),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    item {
                        val low = SensorHealthText.isSdLow(status.sdFreeMb)
                        Text(
                            text = (if (low) "${SensorHealthText.WARNING} " else "") + SensorHealthText.sdFree(status.sdFreeMb),
                            fontSize = 10.sp,
                            color = if (low) Color(0xFFFFB74D) else Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center
                        )
                    }
                    listOfNotNull(SensorHealthText.runtimeHours(status.runtime), SensorHealthText.cycles(status.runtime)).forEach { text ->
                        item {
                            Text(
                                text = text,
                                fontSize = 10.sp,
                                color = Color(0xFFB0BEC5),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    item {
                        val warn = status.reset?.isAbnormal == true
                        Text(
                            text = (if (warn) "${SensorHealthText.WARNING} " else "") + SensorHealthText.reset(status.reset),
                            fontSize = 10.sp,
                            color = if (warn) Color(0xFFFFB74D) else Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    item {
                        val warn = status.shtc3?.isAlert == true
                        Text(
                            text = (if (warn) "${SensorHealthText.WARNING} " else "") + SensorHealthText.shtc3(status.shtc3),
                            fontSize = 10.sp,
                            color = if (warn) Color(0xFFFFB74D) else Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    item {
                        val warn = status.memory?.isLow == true
                        Text(
                            text = (if (warn) "${SensorHealthText.WARNING} " else "") + SensorHealthText.memory(status.memory),
                            fontSize = 10.sp,
                            color = if (warn) Color(0xFFFFB74D) else Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    item {
                        Text(
                            text = SensorHealthText.noise(status.noise),
                            fontSize = 10.sp,
                            color = Color(0xFFB0BEC5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    items(status.sensors) { sensor ->
                        val rate = SensorHealthText.rateOf(status, sensor)
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                Text(SensorHealthText.trend(sensor), fontSize = 10.sp)
                                Text(
                                    SensorHealthText.mold(sensor),
                                    fontSize = 10.sp,
                                    color = if (SensorHealthText.isMold(sensor)) Color(0xFFFFB74D) else Color.Unspecified
                                )
                                SensorHealthText.badges(status.latch, sensor)?.let { badges ->
                                    Text("Badges $badges", fontSize = 10.sp, color = Color(0xFFFFB74D))
                                }
                            }
                        }
                        if (SensorHealthText.canMeter(sensor)) {
                            Chip(
                                onClick = { onOpenSignal(sensor) },
                                modifier = Modifier.fillMaxWidth(0.85f),
                                colors = ChipDefaults.secondaryChipColors(),
                                label = { Text("Signal ${sensor.name}", fontSize = 12.sp, maxLines = 1) }
                            )
                        }
                        }
                    }
                }
            }
        }
    }
}
