package com.rlcdpanel.watch.protocol

/** One `WEEK` row: the daily minimum and maximum of one sensor. Day 0 is the day before the stamp. */
data class WeekRow(
    val sensor: Int,
    val day: Int,
    /** Empty without a stamp, else `YYYY-MM-DD`. */
    val date: String,
    val temperatureMinC: Double?,
    val temperatureMaxC: Double?,
    val humidityMinPct: Double?,
    val humidityMaxPct: Double?,
    /** Hours above `mold_alert_limit_rh` (0 to 24). Null for a gap day, an empty field and a reply with 7 columns. */
    val moldHours: Int? = null
) {
    /** A gap day has no temperature pair and no humidity pair. */
    val isGap: Boolean get() = temperatureMinC == null && humidityMinPct == null
}

/** Port of `WeekReply.parse` of `Replies.swift`. The rule set and the errors stay the same. */
object WeekReply {
    const val HEADER = "sensor,day,date,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct,mold_h"

    /** Header of a firmware without the `mold_h` column. */
    const val LEGACY_HEADER = "sensor,day,date,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct"

    private val DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")

    private val DECIMAL = Regex("^-?[0-9]+(\\.[0-9]+)?$")

    /** `--` and an empty text give null. Only a plain decimal counts; padding, `NaN` and `Infinity` are bad. */
    private fun number(s: String, line: String): Double? {
        if (s == "--" || s.isEmpty()) return null
        if (!DECIMAL.matches(s)) throw ParseException(line)
        return s.toDouble()
    }

    /** [lines] hold `BEGIN WEEK`, the header and the rows. A bad line throws [ParseException]. */
    fun parse(lines: List<String>): List<WeekRow> {
        if (lines.firstOrNull() != "BEGIN WEEK") throw ParseException("BEGIN WEEK")
        if (lines.size < 2 || (lines[1] != HEADER && lines[1] != LEGACY_HEADER)) throw ParseException(HEADER)
        val seen = HashSet<Int>()
        return lines.drop(2).map { line ->
            val c = line.split(",")
            val s = c.getOrNull(0)?.toIntOrNull()
            val d = c.getOrNull(1)?.toIntOrNull()
            if (c.size != (if (lines[1] == HEADER) 8 else 7) || s == null || d == null || s !in 0..10 || d !in 0..6 ||
                !(c[2].isEmpty() || DATE.matches(c[2])) || !seen.add(s * 7 + d)
            ) throw ParseException(line)
            val mold = c.getOrNull(7)?.takeIf { it.isNotEmpty() }?.let { text ->
                text.toIntOrNull()?.takeIf { it in 0..24 && text.all { ch -> ch in '0'..'9' } } ?: throw ParseException(line)
            }
            val row = WeekRow(s, d, c[2], number(c[3], line), number(c[4], line), number(c[5], line), number(c[6], line), mold)
            // The firmware sends each value pair or none; a minimum above the maximum is a bad row.
            val tLo = row.temperatureMinC
            val tHi = row.temperatureMaxC
            if (tLo != null && tHi != null && tLo > tHi) throw ParseException(line)
            val hLo = row.humidityMinPct
            val hHi = row.humidityMaxPct
            if (hLo != null && hHi != null && hLo > hHi) throw ParseException(line)
            row
        }
    }
}
