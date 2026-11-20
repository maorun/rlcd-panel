package com.rlcdpanel.phone

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rlcdpanel.phone.protocol.ConfigCache
import com.rlcdpanel.phone.protocol.ConfigCacheInfo
import com.rlcdpanel.phone.protocol.MemoryState
import com.rlcdpanel.phone.protocol.NtpState
import com.rlcdpanel.phone.protocol.RtcState
import com.rlcdpanel.phone.protocol.ResetState
import com.rlcdpanel.phone.protocol.Shtc3State
import com.rlcdpanel.phone.protocol.TimeReply
import com.rlcdpanel.phone.protocol.VersionReply
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.SensorHealth

/** Result of one `STATUS` read: the rows, or a text that tells why there are none. */
data class SensorHealthState(
    val isReading: Boolean = false,
    val rows: List<SensorHealth> = emptyList(),
    val message: String? = null,
    val ntp: NtpState? = null,
    val sdFreeMb: Int? = null,
    val reset: ResetState? = null,
    val shtc3: Shtc3State? = null,
    val memory: MemoryState? = null,
    val rtc: RtcState? = null
)

/** Cache of the last `CONFIG` read: [info] is null without a cache; [message] tells a failed read. */
data class ConfigReadState(
    val isReading: Boolean = false,
    val info: ConfigCacheInfo? = null,
    val message: String? = null
)

/** Result of one `TIME` read: [reply] and [offsetSeconds] (panel minus phone), or a [message] that tells why there is none. */
data class ClockState(
    val isBusy: Boolean = false,
    val reply: TimeReply? = null,
    val offsetSeconds: Long? = null,
    val message: String? = null
)

/** State of one `RTC TRIM` command: [isBusy] while it runs, [message] is the short result text. */
data class RtcTrimState(val isBusy: Boolean = false, val message: String? = null)

/** Result of one `VERSION` read: [reply], or a [message] that tells why there is none. */
data class VersionState(
    val isBusy: Boolean = false,
    val reply: VersionReply? = null,
    val message: String? = null
)

/** One-line texts of the card fields. */
object SensorHealthText {
    fun rssi(row: SensorHealth): String = row.rssi?.let { "RSSI ${it.toInt()} dBm" } ?: "RSSI --"
    fun rate(row: SensorHealth): String = row.reception?.let { "Empfang ${it.received}/${it.scans} (${it.percent} %)" } ?: "Empfang --"
    fun ntp(ntp: NtpState?): String = when {
        ntp == null -> "Uhr-Sync --"
        else -> "Uhr-Sync ${if (ntp.ok) "ok" else "fehlgeschlagen"}, Fehler ${ntp.failures}" +
            (ntp.ageSeconds?.let { ", vor ${it / 60} min" } ?: "") +
            (ntp.nextSeconds?.let { ", nächster Versuch in ${it / 60} min" } ?: "")
    }
    /** Free card space below this value shows a warning. */
    const val SD_LOW_MB = 100
    fun sdFree(mb: Int?): String = mb?.let { "SD frei $it MB" } ?: "SD frei --"
    fun isSdLow(mb: Int?): Boolean = mb != null && mb < SD_LOW_MB
    fun reset(r: ResetState?): String = r?.let { "Reset ${it.reason}, abnormal ${it.abnormalCount ?: "--"}" } ?: "Reset --"
    fun shtc3(s: Shtc3State?): String =
        s?.let { "SHTC3 nack ${it.nack}, short ${it.shortReads}, crc ${it.crc}, run ${it.run}, retried ${it.retried}" } ?: "SHTC3 --"
    fun memory(m: MemoryState?): String =
        m?.let { "Heap frei ${it.free} B, min ${it.min} B, größter Block ${it.largest} B, PSRAM ${it.psram} B" } ?: "Heap --"
    fun rtc(r: RtcState?): String =
        r?.let { "RTC Trim ${it.trim ?: "--"}, Drift ${it.driftPpm ?: "--"} ppm" } ?: "RTC Trim --, Drift -- ppm"
    /** The trim button needs a writable console and a drift of 3 ppm or more in either direction. */
    fun canTrim(r: RtcState?, canWrite: Boolean): Boolean = canWrite && r?.canTrim == true
    /** Short text of the `RTC TRIM` reply line. */
    fun trimResult(line: String): String {
        val words = line.trim().split(' ').filter { it.isNotEmpty() }
        return when {
            words.size == 5 && words[0] == "OK" && words[1] == "RTC" && words[2] == "TRIM" -> "RTC getrimmt: ${words[3]} → ${words[4]}"
            line.startsWith("ERR NO_DRIFT") -> "Kein Drift-Wert von 24 h oder mehr"
            line.startsWith("ERR READ_ONLY") -> "Set ble_console=2 to apply"
            else -> "Panel meldet: ${line.take(80)}"
        }
    }
    fun mold(row: SensorHealth): String =
        row.moldHours?.let { (if (it > 0) "⚠ " else "") + "Schimmel $it h" } ?: "Schimmel --"
    fun isMold(row: SensorHealth): Boolean = (row.moldHours ?: 0) > 0
    fun firmware(v: VersionReply): String = "Firmware ${v.firmware} (${v.git}), Zustand ${v.state}, gebaut ${v.built}"
    fun battery(row: SensorHealth): String = row.battery?.let { "Batterie ${it.toInt()} %" } ?: "Batterie --"
}

@Composable
fun SensorHealthScreen(
    state: SensorHealthState,
    panelConfig: PanelConfig,
    onRead: () -> Unit,
    configState: ConfigReadState = ConfigReadState(),
    nowMillis: Long = 0L,
    onReadConfig: () -> Unit = {},
    onOpenCalibration: () -> Unit = {},
    onOpenFinder: () -> Unit = {},
    onOpenChime: () -> Unit = {},
    onOpenSlots: () -> Unit = {},
    onOpenRejects: () -> Unit = {},
    clockState: ClockState = ClockState(),
    versionState: VersionState = VersionState(),
    onReadVersion: () -> Unit = {},
    onReadClock: () -> Unit = {},
    onSyncClock: () -> Unit = {},
    rtcTrimState: RtcTrimState = RtcTrimState(),
    onTrimRtc: () -> Unit = {},
    onBack: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(modifier = Modifier.height(24.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Zurück") }
                    Button(onClick = onRead, enabled = !state.isReading && !configState.isReading) {
                        Text(if (state.isReading) "Lese…" else "Read sensor health")
                    }
                }
            }
            item {
                Button(onClick = onReadConfig, enabled = !state.isReading && !configState.isReading) {
                    Text(if (configState.isReading) "Lese…" else "Read panel config")
                }
            }
            item {
                OutlinedButton(onClick = onOpenCalibration) { Text("Calibration proposals") }
            }
            item {
                OutlinedButton(onClick = onOpenFinder) { Text("Find nearby sensors") }
            }
            item {
                OutlinedButton(onClick = onOpenChime) { Text("Chime and alerts") }
            }
            item {
                OutlinedButton(onClick = onOpenSlots) { Text("Slot configuration") }
            }
            item {
                OutlinedButton(onClick = onOpenRejects) { Text("Rejected packets") }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Panel clock", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onReadClock, enabled = !clockState.isBusy) { Text("Read clock") }
                            OutlinedButton(onClick = onSyncClock, enabled = !clockState.isBusy) { Text("Sync clock") }
                        }
                        clockState.reply?.let { r ->
                            val warn = !r.valid || r.osf
                            Text(
                                (if (warn) "⚠ " else "") + "${r.timeString}, Abweichung ${clockState.offsetSeconds?.let { "$it s" } ?: "--"}, valid=${if (r.valid) 1 else 0}, osf=${if (r.osf) 1 else 0}",
                                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
                            )
                        }
                        clockState.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item {
                Button(onClick = onReadVersion, enabled = !versionState.isBusy) {
                    Text(if (versionState.isBusy) "Lese…" else "Firmware-Version lesen")
                }
            }
            versionState.reply?.let { v -> item { Text(SensorHealthText.firmware(v)) } }
            versionState.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                Text(
                    configState.info?.let { "Panel-Config: ${ConfigCache.ageText(it, nowMillis)} gelesen" }
                        ?: "Panel-Config: noch nicht gelesen",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            configState.info?.takeIf { it.rejectedLines.isNotEmpty() }?.let { info ->
                item {
                    Text(
                        "Panel lehnt Zeilen ab: ${info.rejectedLines.joinToString(", ")}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            configState.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                Text(
                    "Liest STATUS über die BLE-Konsole. Das Panel braucht USB-Strom und ble_console 1 oder 2.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (state.rows.isNotEmpty()) {
                item {
                    Text(
                        (if (state.ntp?.isWarning == true) "⚠ " else "") + SensorHealthText.ntp(state.ntp),
                        color = if (state.ntp?.isWarning == true) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            if (state.rows.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(SensorHealthText.rtc(state.rtc))
                        if (SensorHealthText.canTrim(state.rtc, panelConfig.canWrite)) {
                            OutlinedButton(onClick = onTrimRtc, enabled = !rtcTrimState.isBusy) { Text("RTC trimmen") }
                        }
                    }
                }
            }
            rtcTrimState.message?.let { item { Text(it) } }
            if (state.rows.isNotEmpty()) {
                item {
                    val low = SensorHealthText.isSdLow(state.sdFreeMb)
                    Text(
                        (if (low) "⚠ " else "") + SensorHealthText.sdFree(state.sdFreeMb),
                        color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            if (state.rows.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Panel diagnostics", style = MaterialTheme.typography.titleMedium)
                            val lines = listOf(
                                (state.reset?.isAbnormal == true) to SensorHealthText.reset(state.reset),
                                (state.shtc3?.isAlert == true) to SensorHealthText.shtc3(state.shtc3),
                                (state.memory?.isLow == true) to SensorHealthText.memory(state.memory)
                            )
                            for ((warn, text) in lines) {
                                Text(
                                    (if (warn) "⚠ " else "") + text,
                                    color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }
                }
            }
            items(state.rows, key = { it.slot }) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Slot ${row.slot}: ${panelConfig.tileName(row.slot)}${row.badge?.let { " $it" } ?: ""}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(SensorHealthText.rssi(row))
                        Text(SensorHealthText.rate(row))
                        Text(SensorHealthText.battery(row))
                        Text(
                            SensorHealthText.mold(row),
                            color = if (SensorHealthText.isMold(row)) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onBackground
                        )
                        Text("${row.verdict.name}: ${row.verdict.advice}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
