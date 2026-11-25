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
import com.rlcdpanel.phone.protocol.DriftWatch
import com.rlcdpanel.phone.protocol.ConfigCache
import com.rlcdpanel.phone.protocol.ConfigCacheInfo
import com.rlcdpanel.phone.protocol.AwakeState
import com.rlcdpanel.phone.protocol.QuietHoursState
import com.rlcdpanel.phone.protocol.WakeState
import com.rlcdpanel.phone.protocol.MemoryState
import com.rlcdpanel.phone.protocol.NoiseState
import com.rlcdpanel.phone.protocol.RuntimeState
import com.rlcdpanel.phone.protocol.NtpState
import com.rlcdpanel.phone.protocol.RtcState
import com.rlcdpanel.phone.protocol.StatusReply
import com.rlcdpanel.phone.protocol.ResetState
import com.rlcdpanel.phone.protocol.Shtc3State
import com.rlcdpanel.phone.protocol.TimeReply
import com.rlcdpanel.phone.protocol.VersionReply
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.SensorHealth
import com.rlcdpanel.phone.protocol.SensorTrend
import com.rlcdpanel.phone.protocol.SelfTestReply
import com.rlcdpanel.phone.protocol.SelfTestState as CheckState

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
    val rtc: RtcState? = null,
    val noise: NoiseState? = null,
    val runtime: RuntimeState? = null,
    val awake: AwakeState? = null,
    /** The last good `STATUS` reply, for the shared diagnostics report. */
    val status: StatusReply? = null
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

/** Result of one `SELFTEST` run: [reply], or a [message] that tells why there is none. */
data class SelfTestRunState(
    val isBusy: Boolean = false,
    val reply: SelfTestReply? = null,
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
    fun runtimeHours(r: RuntimeState?): String = r?.hours?.let { "Restlaufzeit $it h" } ?: "Restlaufzeit --"
    fun cycles(r: RuntimeState?): String = r?.cycles?.let { "Ladezyklen ${"%.1f".format(java.util.Locale.ROOT, it)}" } ?: "Ladezyklen --"
    fun awake(a: AwakeState?): String = a?.avgMs?.let { "Wachzeit Ø $it ms" } ?: "Wachzeit Ø --"
    fun quiet(q: QuietHoursState?): String =
        q?.let { "Ruhezeit ${it.startHour}–${it.endHour} Uhr" + if (it.active == true) ", aktiv" else "" } ?: "Ruhezeit --"
    fun batteryInterval(w: WakeState?): String = w?.let { "Akku-Intervall ${it.batteryIntervalSeconds} s" } ?: "Akku-Intervall --"
    fun nightInterval(q: QuietHoursState?): String =
        q?.let { "Nachtintervall " + if (it.nightIntervalSeconds == 0) "aus" else "${it.nightIntervalSeconds} s" } ?: "Nachtintervall --"
    fun wakeCause(w: WakeState?): String = w?.let { "Weckgrund ${it.cause}" } ?: "Weckgrund --"
    fun noise(n: NoiseState?): String {
        if (n == null) return "Lärm --"
        val peak = if (n.peakDb != null && n.peakHour != null) ", Spitze ${n.peakDb} dB um ${n.peakHour} Uhr" else ""
        return "Lärm ${n.db ?: "--"} dB (${n.band})$peak"
    }
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
    fun trendSymbol(trend: SensorTrend?): String = trend?.symbol ?: "–"
    fun trend(row: SensorHealth, temperature: Double?, humidity: Double?): String =
        "Temp ${temperature?.let { "%.1f °C".format(it) } ?: "--"} ${trendSymbol(row.temperatureTrend)}  " +
            "Feuchte ${humidity?.let { "%.1f %%".format(it) } ?: "--"} ${trendSymbol(row.humidityTrend)}"
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
    onOpenBackup: () -> Unit = {},
    onOpenFinder: () -> Unit = {},
    onOpenChime: () -> Unit = {},
    onOpenSlots: () -> Unit = {},
    onOpenAlertLimits: () -> Unit = {},
    onOpenConfigEditor: () -> Unit = {},
    onOpenRejects: () -> Unit = {},
    clockState: ClockState = ClockState(),
    versionState: VersionState = VersionState(),
    onReadVersion: () -> Unit = {},
    onReadClock: () -> Unit = {},
    onSyncClock: () -> Unit = {},
    rtcTrimState: RtcTrimState = RtcTrimState(),
    onTrimRtc: () -> Unit = {},
    onShareReport: () -> Unit = {},
    selfTestState: SelfTestRunState = SelfTestRunState(),
    onRunSelfTest: () -> Unit = {},
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
                OutlinedButton(onClick = onOpenBackup) { Text("Config backup and restore") }
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
                OutlinedButton(onClick = onOpenAlertLimits) { Text("Alert limits") }
            }
            item {
                OutlinedButton(onClick = onOpenConfigEditor) { Text("Config editor") }
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
            item {
                OutlinedButton(onClick = onShareReport, enabled = !state.isReading && !versionState.isBusy) { Text("Bericht teilen") }
            }
            item {
                Button(onClick = onRunSelfTest, enabled = !selfTestState.isBusy && !state.isReading && !versionState.isBusy) {
                    Text(if (selfTestState.isBusy) "Teste…" else "Run self-test")
                }
            }
            selfTestState.reply?.let { r ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Self-test: ${r.pass} ok, ${r.fail} failed", style = MaterialTheme.typography.titleMedium)
                            for (c in r.checks) {
                                Text(
                                    "${c.mark} ${c.name} ${c.detail}".trimEnd(),
                                    color = if (c.state == CheckState.FAIL) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }
                }
            }
            selfTestState.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
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
                                (state.memory?.isLow == true) to SensorHealthText.memory(state.memory),
                                false to SensorHealthText.runtimeHours(state.runtime),
                                false to SensorHealthText.cycles(state.runtime),
                                false to SensorHealthText.awake(state.awake),
                                false to SensorHealthText.noise(state.noise),
                                false to SensorHealthText.quiet(state.status?.quiet),
                                false to SensorHealthText.batteryInterval(state.status?.wake),
                                false to SensorHealthText.nightInterval(state.status?.quiet),
                                false to SensorHealthText.wakeCause(state.status?.wake)
                            )
                            if (DriftWatch.isDrifting(state.status?.latch?.drift, 0)) {
                                Text("⚠ ${DriftWatch.TEXT}: ${panelConfig.tileName(0)}", color = MaterialTheme.colorScheme.error)
                            }
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
                        if (DriftWatch.isDrifting(state.status?.latch?.drift, row.slot)) {
                            Text("⚠ ${DriftWatch.TEXT}", color = MaterialTheme.colorScheme.error)
                        }
                        Text(SensorHealthText.rssi(row))
                        Text(SensorHealthText.rate(row))
                        Text(SensorHealthText.battery(row))
                        val values = state.status?.sensors?.firstOrNull { it.index == row.slot }
                        Text(SensorHealthText.trend(row, values?.temperature, values?.humidity))
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
