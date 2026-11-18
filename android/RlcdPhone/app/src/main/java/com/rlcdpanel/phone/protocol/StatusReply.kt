package com.rlcdpanel.phone.protocol

class ParseException(val line: String) : Exception("Bad console line: $line")

/** `SENSOR <index> <name> temp=<C> rh=<%RH> bat=<%> rssi=<dBm> age=<s> state=<...> format=<...>`; `--` gives null. */
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
    val format: String? = null
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

/** Parsed `STATUS` reply. Unknown lines are ignored, so a newer firmware does not break the parser. */
data class StatusReply(
    val sensors: List<StatusSensor>,
    /** One entry for each slot 1 to 5 (null for an empty slot); null without a `RECEPTION` line. */
    val reception: List<ReceptionRate?>?,
    val powerSource: String?,
    /** The raw `SENSOR` lines, for the strict parser of [SensorHealth]. */
    val sensorLines: List<String>,
    /** Null for `ok=--` (no attempt, no Wi-Fi credentials) and without an `NTP` line. */
    val ntp: NtpState? = null
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

        fun parse(lines: List<String>): StatusReply {
            val sensors = mutableListOf<StatusSensor>()
            val sensorLines = mutableListOf<String>()
            var reception: List<ReceptionRate?>? = null
            var src: String? = null
            var ntp: NtpState? = null
            for (line in lines) {
                val words = line.split(' ').filter { it.isNotEmpty() }
                val head = words.firstOrNull() ?: continue
                val f = fields(line)
                when (head) {
                    "SENSOR" -> {
                        sensorLines.add(line)
                        val idx = words.getOrNull(1)?.toIntOrNull()
                        if (words.size < 3 || idx == null) throw ParseException(line)
                        sensors.add(
                            StatusSensor(
                                idx, words[2], number(f["temp"]), number(f["rh"]), number(f["bat"]),
                                number(f["rssi"]), number(f["age"]), f["state"], format(f["format"])
                            )
                        )
                    }
                    "POWER" -> src = f["src"]
                    "RECEPTION" -> reception = parseReception(line)
                    "NTP" -> ntp = parseNtp(line)
                }
            }
            if (sensors.isEmpty()) throw ParseException("SENSOR")
            return StatusReply(sensors, reception, src, sensorLines, ntp)
        }

        private fun digits(s: String?): Long? =
            if (s != null && s.isNotEmpty() && s.length <= 12 && s.all { it in '0'..'9' }) s.toLongOrNull() else null

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
