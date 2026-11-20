package com.rlcdpanel.phone.protocol

class ParseException(val line: String) : Exception("Bad console line: $line")

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
    val moldHours: Int? = null
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
    val rtc: RtcState? = null
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
            var sdStatus: String? = null
            var sdFreeMb: Int? = null
            var reset: ResetState? = null
            var shtc3: Shtc3State? = null
            var memory: MemoryState? = null
            var rtc: RtcState? = null
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
                                number(f["rssi"]), number(f["age"]), f["state"], format(f["format"]), moldHours
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
                }
            }
            if (sensors.isEmpty()) throw ParseException("SENSOR")
            return StatusReply(sensors, reception, src, sensorLines, ntp, muted, sdStatus, sdFreeMb, reset, shtc3, memory, rtc)
        }

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
