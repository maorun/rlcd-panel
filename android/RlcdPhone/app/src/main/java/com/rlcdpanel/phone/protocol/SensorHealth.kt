package com.rlcdpanel.phone.protocol

/** One verdict for each registry slot, from the `SENSOR` and `RECEPTION` lines of one `STATUS` reply. */
data class SensorHealth(
    val slot: Int,
    val name: String,
    val rssi: Double?,
    val battery: Double?,
    val ageSeconds: Double?,
    val state: String,
    /** `format=` of the `SENSOR` line; null for a missing field or `--`. */
    val format: String?,
    /** Null without a `RECEPTION` line or for an empty slot. */
    val reception: ReceptionRate?,
    val verdict: Verdict
) {
    /** The protocol source badge of the panel tiles; null without a known format. */
    val badge: String? get() = badgeFor(format)

    /** The verdicts in evaluation order: the first match wins. Same rules as `SensorHealth.swift`. */
    enum class Verdict(val advice: String) {
        EMPTY("No sensor in this slot. Assign a sensor or ignore the slot."),
        STALE("No recent packet. Check the sensor battery and the distance to the panel."),
        BATTERY("Replace the sensor battery."),
        WEAK_SIGNAL("Move the sensor nearer to the panel."),
        GOOD("No action needed.")
    }

    companion object {
        /** `[I2C]`, `[SB]`, `[ATC]`, `[BTH]` or `[TP]`; null for null or an unknown format. Same rules as `SensorHealth.swift`. */
        fun badgeFor(format: String?): String? = when (format) {
            "SHTC3" -> "[I2C]"
            "WOIO", "METER" -> "[SB]"
            "ATC" -> "[ATC]"
            "BTHOME" -> "[BTH]"
            "THERMOPRO" -> "[TP]"
            else -> null
        }

        /** An RSSI below this value (dBm) counts as weak. */
        const val WEAK_RSSI = -85.0
        /** A reception rate counts as weak from this many counted scans. */
        const val MIN_SCANS = 6
        /** The battery verdict starts this many percent above `sensor_battery_limit_pct`. */
        const val BATTERY_MARGIN = 5.0
        const val SLOT_COUNT = 10

        /**
         * One row for each slot 1 to 10 that the reply lists; other slots are skipped unparsed. [limit] is
         * `sensor_battery_limit_pct` when known (0 is off). A non-numeric field, a missing state or a
         * duplicate slot throws [ParseException].
         */
        fun rows(status: StatusReply, limit: Double?): List<SensorHealth> {
            val out = mutableListOf<SensorHealth>()
            val seen = mutableSetOf<Int>()
            for (line in status.sensorLines) {
                val words = line.split(' ').filter { it.isNotEmpty() }
                val slot = words.getOrNull(1)?.toIntOrNull()
                if (words.size < 3 || slot == null) throw ParseException(line)
                if (slot !in 1..SLOT_COUNT) continue
                if (!seen.add(slot)) throw ParseException(line)
                val f = StatusReply.fields(line)
                val rssi = value(f, "rssi", line)
                val battery = value(f, "bat", line)
                val age = value(f, "age", line)
                val state = f["state"]?.takeIf { it.isNotEmpty() } ?: throw ParseException(line)
                val rate = status.reception?.getOrNull(slot - 1)
                out.add(SensorHealth(slot, words[2], rssi, battery, age, state, StatusReply.format(f["format"]), rate, verdict(state, rssi, battery, rate, limit)))
            }
            return out
        }

        fun verdict(state: String, rssi: Double?, battery: Double?, rate: ReceptionRate?, limit: Double?): Verdict {
            if (state == "EMPTY") return Verdict.EMPTY
            if (state == "STALE") return Verdict.STALE
            if (state == "BATLOW") return Verdict.BATTERY
            if (limit != null && limit > 0 && battery != null && battery <= limit + BATTERY_MARGIN) return Verdict.BATTERY
            if (rssi != null && rssi < WEAK_RSSI) return Verdict.WEAK_SIGNAL
            if (rate != null && rate.scans >= MIN_SCANS && rate.isWeak) return Verdict.WEAK_SIGNAL
            return Verdict.GOOD
        }

        /** `--` gives null; plain signed decimal text only, else [ParseException]. */
        private fun value(f: Map<String, String>, key: String, line: String): Double? {
            val s = f[key] ?: throw ParseException(line)
            if (s == "--") return null
            val body = s.removePrefix("-")
            val parts = body.split('.')
            if (parts.size > 2 || parts.any { p -> p.isEmpty() || !p.all { it in '0'..'9' } }) throw ParseException(line)
            return s.toDoubleOrNull() ?: throw ParseException(line)
        }
    }
}
