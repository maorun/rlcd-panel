package com.rlcdpanel.phone.protocol

class ParseException(val line: String) : Exception("Bad console line: $line")

/** 3-hour direction of one value: `temp_trend=` or `rh_trend=` of a `SENSOR` line (`RISE`, `FALL`, `STEADY`). */
enum class SensorTrend(val symbol: String) {
    RISE("↑"), FALL("↓"), STEADY("–");

    companion object {
        /** `--` and a missing field give null; another text throws. */
        fun parse(text: String?, line: String): SensorTrend? = when (text) {
            null, "--" -> null
            "RISE" -> RISE
            "FALL" -> FALL
            "STEADY" -> STEADY
            else -> throw ParseException(line)
        }
    }
}

/** `SENSOR <index> <name> temp=<C> rh=<%RH> bat=<%> rssi=<dBm> age=<s> state=<...> format=<...> mac=<...> mold_h=<N|-->`; `--` gives null. */
data class StatusSensor(
    val index: Int,
    val name: String,
    val temperature: Double?,
    val humidity: Double?,
    val battery: Double?,
    val rssi: Double?,
    val ageSeconds: Double?,
    val state: String?,
    /** `format=` of the `SENSOR` line (`SHTC3`, `WOIO`, ...); null for a missing field or `--`. */
    val format: String? = null,
    /** `mold_h=` of the `SENSOR` line: hourly samples above `mold_alert_limit_rh`; null for `--` and a missing field. */
    val moldHours: Int? = null,
    /** `mac=` of the `SENSOR` line as `AA:BB:CC:DD:EE:FF`; null for `--`, a missing field and a bad text. */
    val mac: String? = null,
    /** `temp_trend=` of the `SENSOR` line; null for `--` and a missing field. */
    val temperatureTrend: SensorTrend? = null,
    /** `rh_trend=` of the `SENSOR` line; null for `--` and a missing field. */
    val humidityTrend: SensorTrend? = null
) {
    /** The protocol source badge of [format]; null without a known format. */
    val badge: String? get() = SensorHealth.badgeFor(format)
}

/** Scans with a packet of the last [scans] scans (up to 24) of one slot. */
data class ReceptionRate(val received: Int, val scans: Int) {
    val percent: Int get() = if (scans > 0) received * 100 / scans else 0
    val isWeak: Boolean get() = scans > 0 && received * 100 < WARN_BELOW_PERCENT * scans

    companion object {
        const val WARN_BELOW_PERCENT = 50
    }
}

/** `NTP ok=<0|1|--> failures=<N> age_s=<N|--> next_s=<N|-->`: state of the clock sync. */
data class NtpState(val ok: Boolean, val failures: Int, val ageSeconds: Long?, val nextSeconds: Long?) {
    val isWarning: Boolean get() = failures >= WARN_FAILURES

    companion object {
        const val WARN_FAILURES = 3
    }
}

/**
 * `BLE rejected=<N> last=<MAC|--> ... unassigned=<N> jumps=<N>`: rejected BLE packets of the last scan.
 * [last] is null for `--`; [unassigned] and [jumps] are null for older firmware.
 */
data class BleRejectState(val rejected: Int, val last: String?, val unassigned: Int?, val jumps: Int?) {
    val isWarning: Boolean get() = (unassigned ?: 0) > 0

    companion object {
        const val MAX_COUNT = 65535L
    }
}

/** `RESET reason=<TEXT> abnormal=<N|-->`: last reset reason and count of abnormal resets (null for `--`). */
data class ResetState(val reason: String, val abnormalCount: Int?) {
    val isAbnormal: Boolean get() = reason in ABNORMAL_REASONS

    companion object {
        val ABNORMAL_REASONS = setOf("PANIC", "INT_WDT", "TASK_WDT", "WDT", "BROWNOUT")
    }
}

/** `SHTC3 nack=<N> short=<N> crc=<N> run=<N> retried=<N>`: failed reads of the onboard sensor by cause. */
data class Shtc3State(val nack: Int, val shortReads: Int, val crc: Int, val run: Int, val retried: Int) {
    val isAlert: Boolean get() = run >= ALERT_RUN

    companion object {
        const val ALERT_RUN = 3
    }
}

/** `RTC trim=<N|--> drift_ppm=<N|-->`: PCF85063A offset register (-64 to 63) and drift rate in ppm (positive is a fast clock). */
data class RtcState(val trim: Int?, val driftPpm: Int?) {
    /** The panel can trim only with a drift of 3 ppm or more: a smaller rate rounds to 0 steps. */
    val canTrim: Boolean get() = driftPpm != null && kotlin.math.abs(driftPpm) >= MIN_TRIM_PPM

    companion object {
        const val MIN_TRIM_PPM = 3
        const val MIN_TRIM = -64
        const val MAX_TRIM = 63
    }
}

/** `MEMORY free=<N> min=<N> largest=<N> psram=<N>` (bytes). */
data class MemoryState(val free: Long, val min: Long, val largest: Long, val psram: Long) {
    val isLow: Boolean get() = largest < LOW_LARGEST_BYTES

    companion object {
        const val LOW_LARGEST_BYTES = 16384
    }
}

/** `RUNTIME h=<N|--> samples=<N> cycles=<N.N|--> scan_saved_ms=<N>`: battery runtime estimate and charge cycles; `--` gives a null field. */
data class RuntimeState(val hours: Int?, val samples: Int, val cycles: Double?, val scanSavedMs: Long)

/** `AWAKE last_ms=<N|--> avg_ms=<N|--> max_ms=<N|--> samples=<N>`: measured awake time per wake cycle; `--` gives a null field. */
data class AwakeState(val lastMs: Int?, val avgMs: Int?, val maxMs: Int?, val samples: Int)

/** `NOISE db=<N|--> band=<QUIET|MODERATE|LOUD> peak_db=<N|--> peak_hour=<N|--> events_sum=<N>`; `--` gives a null field. */
data class NoiseState(val db: Int?, val band: String, val peakDb: Int?, val peakHour: Int?, val eventsSum: Int) {
    companion object {
        const val MAX_DB = 140
        const val MAX_HOUR = 23
        val BANDS = setOf("QUIET", "MODERATE", "LOUD")
    }
}

/** `CHIME repeat_h=<N> bits=<2 hex digits> last_s=<N|--> next_s=<N|--> snooze_s=<N|-->`: chime reminder state; `--` and a missing `snooze_s` give a null field. */
data class ChimeReminder(val repeatHours: Int, val bits: Int, val lastS: Long?, val nextS: Long?, val snoozeSeconds: Long? = null) {
    companion object {
        const val MAX_REPEAT_HOURS = 24
    }
}

/** `QUIET start_h=<H> end_h=<H> active=<0|1|--> night_interval_s=<N>`: quiet hours; `active` is null for `--` (no valid RTC time), `night_interval_s` 0 is off. */
data class QuietHoursState(val startHour: Int, val endHour: Int, val active: Boolean?, val nightIntervalSeconds: Int) {
    companion object {
        const val MAX_HOUR = 23
    }
}

/** `WAKE cause=<POWER_ON|TIMER|KEY|OTHER> boots=<N> battery_interval_s=<N>`: last wake cause, boot count and the wake interval on battery power. */
data class WakeState(val cause: String, val boots: Long, val batteryIntervalSeconds: Int) {
    companion object {
        val CAUSES = setOf("POWER_ON", "TIMER", "KEY", "OTHER")
    }
}

/**
 * `LATCH vent=<3 hex> dry=<3 hex> batlow=<3 hex> stale=<3 hex> [wall=<3 hex>] [window=<3 hex>]`: the badge
 * latches of the panel. `vent`, `dry`, `wall` and `window` use bit 0 for the onboard tile and bits 1 to 10
 * for the slots; `batlow` and `stale` use bits 0 to 9 for the slots 1 to 10. `wall` and `window` are null
 * for a panel firmware that does not send them.
 */
data class LatchState(
    val ventilate: Int,
    val dry: Int,
    val batteryLow: Int,
    val stale: Int,
    val wall: Int? = null,
    val window: Int? = null
) {
    /**
     * Badge texts of sensor 0 (onboard) or 1 to 10 (slot), in the order `V`, `D`, `Wand`, `Fenster`,
     * `BAT LOW`, `STALE`.
     * The onboard sensor never gets `BAT LOW` or `STALE`; an unknown sensor has no badge.
     */
    fun badges(sensor: Int): List<String> {
        if (sensor !in 0 until SENSOR_COUNT) return emptyList()
        val out = mutableListOf<String>()
        if (ventilate shr sensor and 1 == 1) out.add("V")
        if (dry shr sensor and 1 == 1) out.add("D")
        if (wall != null && wall shr sensor and 1 == 1) out.add("Wand")
        if (window != null && window shr sensor and 1 == 1) out.add("Fenster")
        if (sensor >= 1) {
            if (batteryLow shr (sensor - 1) and 1 == 1) out.add("BAT LOW")
            if (stale shr (sensor - 1) and 1 == 1) out.add("STALE")
        }
        return out
    }

    companion object {
        const val SENSOR_COUNT = 11
        const val MAX_TILE_MASK = 0x7ff
        const val MAX_SLOT_MASK = 0x3ff
    }
}

/** Parsed `STATUS` reply. Unknown lines are ignored, so a newer firmware does not break the parser. */
data class StatusReply(
    val sensors: List<StatusSensor>,
    /** One entry for each slot 1 to 5 (null for an empty slot); null without a `RECEPTION` line. */
    val reception: List<ReceptionRate?>?,
    val powerSource: String?,
    /** The raw `SENSOR` lines, for the strict parser of [SensorHealth]. */
    val sensorLines: List<String>,
    /** Null for `ok=--` (no attempt, no Wi-Fi credentials) and without an `NTP` line. */
    val ntp: NtpState? = null,
    /** `muted=<0|1>` of the `POWER` line; null for a missing or bad field. */
    val muted: Boolean? = null,
    /** `status=` of the `SD` line; null for a missing field. */
    val sdStatus: String? = null,
    /** `free_mb=` of the `SD` line: free card space in MB; null for `--`, a missing field and older firmware. */
    val sdFreeMb: Int? = null,
    /** Null without a `RESET` line. */
    val reset: ResetState? = null,
    /** Null without an `SHTC3` line. */
    val shtc3: Shtc3State? = null,
    /** Null without a `MEMORY` line. */
    val memory: MemoryState? = null,
    /** Null without an `RTC` line. */
    val rtc: RtcState? = null,
    /** Null without a `RUNTIME` line. */
    val runtime: RuntimeState? = null,
    /** Null without an `AWAKE` line. */
    val awake: AwakeState? = null,
    /** Null without a `NOISE` line. */
    val noise: NoiseState? = null,
    /** Null without a `CHIME` line. */
    val chime: ChimeReminder? = null,
    /** Null without a `LATCH` line. */
    val latch: LatchState? = null,
    /** `alerts=<2 hex digits>` of the `POWER` line; null for a missing or bad field. */
    val alertMask: Int? = null,
    /** `acked=<2 hex digits>` of the `POWER` line; null for a missing or bad field. */
    val ackedMask: Int? = null,
    /** Null without a `QUIET` line. */
    val quiet: QuietHoursState? = null,
    /** Null without a `WAKE` line. */
    val wake: WakeState? = null,
    /** Null without a `BLE` line. */
    val bleRejects: BleRejectState? = null
) {
    val isUsbPower: Boolean get() = powerSource == "USB"

    companion object {
        fun fields(line: String): Map<String, String> =
            line.split(' ').drop(1).mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq > 0) part.substring(0, eq) to part.substring(eq + 1) else null
            }.toMap()

        /** `--` and an empty text give null. */
        fun format(s: String?): String? = if (s == null || s == "--" || s.isEmpty()) null else s

        private val MAC_PATTERN = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")

        /** Upper-case `AA:BB:CC:DD:EE:FF`; `--`, an empty text and a bad text give null. */
        private fun macText(s: String?): String? = s?.takeIf { MAC_PATTERN.matches(it) }?.uppercase()

        /** `--` and an empty text give null; a non-numeric text gives null here. */
        private fun number(s: String?): Double? =
            if (s == null || s == "--" || s.isEmpty()) null else s.toDoubleOrNull()

        /** Parses a console reply; a reply without a good CRC-32 frame (bad CRC-32, timeout, `ERR`) throws. */
        fun parseReply(reply: ConsoleReply): StatusReply = when (reply) {
            is ConsoleReply.Frame -> parse(reply.lines)
            is ConsoleReply.Status -> throw ParseException(reply.line)
            is ConsoleReply.Corrupt -> throw ParseException(reply.error.toString())
        }

        fun parse(lines: List<String>): StatusReply {
            val sensors = mutableListOf<StatusSensor>()
            val sensorLines = mutableListOf<String>()
            var reception: List<ReceptionRate?>? = null
            var src: String? = null
            var ntp: NtpState? = null
            var muted: Boolean? = null
            var alertMask: Int? = null
            var ackedMask: Int? = null
            var sdStatus: String? = null
            var sdFreeMb: Int? = null
            var reset: ResetState? = null
            var shtc3: Shtc3State? = null
            var memory: MemoryState? = null
            var rtc: RtcState? = null
            var runtime: RuntimeState? = null
            var awake: AwakeState? = null
            var noise: NoiseState? = null
            var chime: ChimeReminder? = null
            var latch: LatchState? = null
            var quiet: QuietHoursState? = null
            var wake: WakeState? = null
            var bleRejects: BleRejectState? = null
            for (line in lines) {
                val words = line.split(' ').filter { it.isNotEmpty() }
                val head = words.firstOrNull() ?: continue
                val f = fields(line)
                when (head) {
                    "SENSOR" -> {
                        sensorLines.add(line)
                        val idx = words.getOrNull(1)?.toIntOrNull()
                        if (words.size < 3 || idx == null) throw ParseException(line)
                        val moldField = f["mold_h"]
                        val moldHours = if (moldField == null || moldField == "--") null
                        else moldField.toIntOrNull()?.takeIf { it >= 0 } ?: throw ParseException(line)
                        sensors.add(
                            StatusSensor(
                                idx, words[2], number(f["temp"]), number(f["rh"]), number(f["bat"]),
                                number(f["rssi"]), number(f["age"]), f["state"], format(f["format"]), moldHours, macText(f["mac"]),
                                SensorTrend.parse(f["temp_trend"], line), SensorTrend.parse(f["rh_trend"], line)
                            )
                        )
                    }
                    "POWER" -> {
                        src = f["src"]
                        muted = when (f["muted"]) {
                            "0" -> false
                            "1" -> true
                            else -> null
                        }
                        alertMask = hexByte(f["alerts"])
                        ackedMask = hexByte(f["acked"])
                    }
                    "SD" -> {
                        sdStatus = f["status"]
                        val free = f["free_mb"]
                        sdFreeMb = if (free == null || free == "--") null else free.toIntOrNull() ?: throw ParseException(line)
                    }
                    "RECEPTION" -> reception = parseReception(line)
                    "NTP" -> ntp = parseNtp(line)
                    "RESET" -> reset = parseReset(line)
                    "SHTC3" -> shtc3 = parseShtc3(line)
                    "MEMORY" -> memory = parseMemory(line)
                    "RTC" -> rtc = parseRtc(line)
                    "RUNTIME" -> runtime = parseRuntime(line)
                    "AWAKE" -> awake = parseAwake(line)
                    "NOISE" -> noise = parseNoise(line)
                    "CHIME" -> chime = parseChime(line)
                    "LATCH" -> latch = parseLatch(line)
                    "QUIET" -> quiet = parseQuiet(line)
                    "WAKE" -> wake = parseWake(line)
                    "BLE" -> bleRejects = parseBleRejects(line)
                }
            }
            if (sensors.isEmpty()) throw ParseException("SENSOR")
            return StatusReply(sensors, reception, src, sensorLines, ntp, muted, sdStatus, sdFreeMb, reset, shtc3, memory, rtc, runtime, awake, noise, chime, latch, alertMask, ackedMask, quiet, wake, bleRejects)
        }

        private fun hexByte(s: String?): Int? =
            if (s != null && s.length == 2 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) s.toInt(16) else null

        private fun digits(s: String?): Long? =
            if (s != null && s.isNotEmpty() && s.length <= 12 && s.all { it in '0'..'9' }) s.toLongOrNull() else null

        private fun counter(f: Map<String, String>, key: String, line: String, max: Long = Int.MAX_VALUE.toLong()): Long =
            digits(f[key])?.takeIf { it <= max } ?: throw ParseException(line)

        /** `RESET reason=<TEXT> abnormal=<N|-->`; `abnormal=--` gives a null count, a bad field throws. */
        private fun parseReset(line: String): ResetState {
            val f = fields(line)
            val reason = f["reason"]
            if (reason.isNullOrEmpty()) throw ParseException(line)
            val count = if (f["abnormal"] == "--") null else counter(f, "abnormal", line).toInt()
            return ResetState(reason, count)
        }

        private fun parseShtc3(line: String): Shtc3State {
            val f = fields(line)
            return Shtc3State(
                counter(f, "nack", line).toInt(), counter(f, "short", line).toInt(), counter(f, "crc", line).toInt(),
                counter(f, "run", line).toInt(), counter(f, "retried", line).toInt()
            )
        }

        /** `RTC trim=<N|--> drift_ppm=<N|-->`; `--` gives a null field, a bad number or a trim outside -64 to 63 throws. */
        private fun parseRtc(line: String): RtcState {
            val f = fields(line)
            fun signed(key: String): Int? {
                val v = f[key] ?: throw ParseException(line)
                if (v == "--") return null
                val body = if (v.startsWith("-")) v.substring(1) else v
                if (body.isEmpty() || body.length > 9 || !body.all { it in '0'..'9' }) throw ParseException(line)
                return v.toInt()
            }
            val trim = signed("trim")
            if (trim != null && (trim < RtcState.MIN_TRIM || trim > RtcState.MAX_TRIM)) throw ParseException(line)
            return RtcState(trim, signed("drift_ppm"))
        }

        /** `RUNTIME h=<N|--> samples=<N> cycles=<N.N|--> scan_saved_ms=<N>`; `--` gives a null field, a bad number throws. */
        private fun parseRuntime(line: String): RuntimeState {
            val f = fields(line)
            val hours = if (f["h"] == "--") null else counter(f, "h", line).toInt()
            val cyclesText = f["cycles"]
            val cycles = if (cyclesText == "--") null else {
                if (cyclesText == null || !Regex("[0-9]{1,9}(\\.[0-9])?").matches(cyclesText)) throw ParseException(line)
                cyclesText.toDouble()
            }
            return RuntimeState(hours, counter(f, "samples", line).toInt(), cycles, counter(f, "scan_saved_ms", line))
        }

        /** `AWAKE last_ms=<N|--> avg_ms=<N|--> max_ms=<N|--> samples=<N>`; `--` gives a null field, a bad number throws. */
        private fun parseAwake(line: String): AwakeState {
            val f = fields(line)
            fun optional(key: String): Int? = if (f[key] == "--") null else counter(f, key, line).toInt()
            return AwakeState(optional("last_ms"), optional("avg_ms"), optional("max_ms"), counter(f, "samples", line).toInt())
        }

        /** `NOISE db=<N|--> band=... peak_db=<N|--> peak_hour=<N|--> events_sum=<N>`; a bad band, number or range throws. */
        private fun parseNoise(line: String): NoiseState {
            val f = fields(line)
            fun optional(key: String, max: Int): Int? {
                if (f[key] == "--") return null
                return digits(f[key])?.takeIf { it <= max }?.toInt() ?: throw ParseException(line)
            }
            val band = f["band"]
            if (band == null || band !in NoiseState.BANDS) throw ParseException(line)
            return NoiseState(
                optional("db", NoiseState.MAX_DB), band, optional("peak_db", NoiseState.MAX_DB),
                optional("peak_hour", NoiseState.MAX_HOUR), counter(f, "events_sum", line).toInt()
            )
        }

        /** `CHIME repeat_h=<N> bits=<2 hex digits> last_s=<N|--> next_s=<N|-->`; `repeat_h` above 24, bad bits or a bad number throws. */
        private fun parseChime(line: String): ChimeReminder {
            val f = fields(line)
            val repeat = counter(f, "repeat_h", line, ChimeReminder.MAX_REPEAT_HOURS.toLong()).toInt()
            val bitsText = f["bits"]
            if (bitsText == null || bitsText.length != 2 || !bitsText.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                throw ParseException(line)
            }
            fun optional(key: String): Long? = if (f[key] == "--") null else counter(f, key, line)
            val snooze = if (f.containsKey("snooze_s")) optional("snooze_s") else null
            return ChimeReminder(repeat, bitsText.toInt(16), optional("last_s"), optional("next_s"), snooze)
        }

        /**
         * `LATCH vent=<3 hex> dry=<3 hex> batlow=<3 hex> stale=<3 hex>` with the optional `wall` and `window`; a value
         * that is not 3 hex digits or above its limit throws, a missing `wall` or `window` gives null.
         */
        private fun parseLatch(line: String): LatchState {
            val f = fields(line)
            fun mask(key: String, max: Int): Int {
                val s = f[key]
                if (s == null || s.length != 3 || !s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw ParseException(line)
                return s.toInt(16).takeIf { it <= max } ?: throw ParseException(line)
            }
            return LatchState(
                mask("vent", LatchState.MAX_TILE_MASK), mask("dry", LatchState.MAX_TILE_MASK),
                mask("batlow", LatchState.MAX_SLOT_MASK), mask("stale", LatchState.MAX_SLOT_MASK),
                if (f.containsKey("wall")) mask("wall", LatchState.MAX_TILE_MASK) else null,
                if (f.containsKey("window")) mask("window", LatchState.MAX_TILE_MASK) else null
            )
        }

        /** `QUIET start_h=<H> end_h=<H> active=<0|1|--> night_interval_s=<N>`; an hour above 23, a bad `active` or a bad number throws. */
        private fun parseQuiet(line: String): QuietHoursState {
            val f = fields(line)
            val active = when (f["active"]) {
                "0" -> false
                "1" -> true
                "--" -> null
                else -> throw ParseException(line)
            }
            return QuietHoursState(
                counter(f, "start_h", line, QuietHoursState.MAX_HOUR.toLong()).toInt(),
                counter(f, "end_h", line, QuietHoursState.MAX_HOUR.toLong()).toInt(),
                active, counter(f, "night_interval_s", line).toInt()
            )
        }

        /** `BLE rejected=<N> last=<MAC|--> ...`; `unassigned` and `jumps` are optional, a bad number throws. */
        private fun parseBleRejects(line: String): BleRejectState {
            val f = fields(line)
            val rejected = counter(f, "rejected", line, BleRejectState.MAX_COUNT).toInt()
            val last = format(f["last"])
            fun optional(key: String): Int? =
                if (f[key] == null) null else counter(f, key, line, BleRejectState.MAX_COUNT).toInt()
            return BleRejectState(rejected, last, optional("unassigned"), optional("jumps"))
        }

        /** `WAKE cause=<POWER_ON|TIMER|KEY|OTHER> boots=<N> battery_interval_s=<N>`; an unknown cause or a bad number throws. */
        private fun parseWake(line: String): WakeState {
            val f = fields(line)
            val cause = f["cause"]
            if (cause == null || cause !in WakeState.CAUSES) throw ParseException(line)
            return WakeState(cause, counter(f, "boots", line, UInt.MAX_VALUE.toLong()), counter(f, "battery_interval_s", line).toInt())
        }

        private fun parseMemory(line: String): MemoryState {
            val f = fields(line)
            return MemoryState(
                counter(f, "free", line), counter(f, "min", line), counter(f, "largest", line), counter(f, "psram", line)
            )
        }

        /** `NTP ok=<0|1|--> failures=<N> age_s=<N|--> next_s=<N|-->`; `ok=--` gives null, a bad field throws. */
        private fun parseNtp(line: String): NtpState? {
            val f = fields(line)
            val ok = f["ok"]
            if (ok == "--") return null
            if (ok != "0" && ok != "1") throw ParseException(line)
            val failures = digits(f["failures"]) ?: throw ParseException(line)
            if (failures > 255) throw ParseException(line)
            val age = f["age_s"]
            val next = f["next_s"]
            val ageValue = if (age == "--") null else digits(age) ?: throw ParseException(line)
            val nextValue = if (next == "--") null else digits(next) ?: throw ParseException(line)
            return NtpState(ok == "1", failures.toInt(), ageValue, nextValue)
        }

        /** `RECEPTION s1=<N>/<M> ... s5=<N>/<M>` (up to s10); a missing s1 to s5 or a bad number throws. */
        private fun parseReception(line: String): List<ReceptionRate?> {
            val f = fields(line)
            val out = mutableListOf<ReceptionRate?>()
            for (i in 1..10) {
                val s = f["s$i"]
                if (s == null) {
                    if (i <= 5) throw ParseException(line)
                    break
                }
                if (s == "--") {
                    out.add(null)
                    continue
                }
                val parts = s.split('/')
                val digits = parts.size == 2 && parts.all { p -> p.isNotEmpty() && p.all { it in '0'..'9' } }
                val n = if (digits) parts[0].toIntOrNull() else null
                val m = if (digits) parts[1].toIntOrNull() else null
                if (n == null || m == null || m < 1 || m > 24 || n > m) throw ParseException(line)
                out.add(ReceptionRate(n, m))
            }
            return out
        }
    }
}
