package com.rlcdpanel.watch.protocol

/** One `TODAY` row: minimum, maximum and mean of one sensor since local midnight. A null value is an empty field. */
data class TodayRow(
    val sensor: Int,
    val name: String,
    /** Larger sample count of the two histories; 0 for a sensor without a sample since midnight. */
    val hours: Int,
    val temperatureMinC: Double?,
    val temperatureMaxC: Double?,
    val temperatureMeanC: Double?,
    val humidityMinPct: Double?,
    val humidityMaxPct: Double?,
    val humidityMeanPct: Double?,
    /** Hours of the day above the mold limit; null for an empty field and for the old header. */
    val moldHours: Int? = null,
    /** Hours of the day at or below the dry limit; null for an empty field and for the old header. */
    val dryHours: Int? = null,
    /** Hours of the day at or above the heat limit; null for an empty field and for a header without `heat_h`. */
    val heatHours: Int? = null
)

/** Reply of the console command `TODAY`. */
object TodayReply {
    const val HEADER = "sensor,name,hours,temp_min_c,temp_max_c,temp_mean_c,rh_min_pct,rh_max_pct,rh_mean_pct"
    const val HEADER_V2 = "$HEADER,mold_h,dry_h"
    const val HEADER_V3 = "$HEADER_V2,heat_h"
    const val COLUMNS = 9
    const val COLUMNS_V2 = 11
    const val COLUMNS_V3 = 12

    /** Text of `ERR NO_TIME` (invalid, stopped or untrusted RTC time). */
    const val NO_TIME_TEXT = "Panel clock not set"

    private val DECIMAL = Regex("^-?[0-9]+(\\.[0-9]+)?$")

    private fun number(s: String, line: String): Double? {
        if (s.isEmpty()) return null
        if (!DECIMAL.matches(s)) throw ParseException(line)
        return s.toDouble()
    }

    /** Plain digits only; a sign, padding and an empty text give null. */
    private fun unsigned(s: String): Int? = s.takeIf { t -> t.isNotEmpty() && t.all { it in '0'..'9' } }?.toIntOrNull()

    /** Empty gives null; else plain digits not above [hours] (and so not above 24). */
    private fun hourCount(s: String, hours: Int, line: String): Int? {
        if (s.isEmpty()) return null
        val n = unsigned(s) ?: throw ParseException(line)
        if (n > hours) throw ParseException(line)
        return n
    }

    /** Text for a status line, or null when the line needs no special text. */
    fun statusText(line: String): String? = if (line.trim() == "ERR NO_TIME") NO_TIME_TEXT else null

    /** [lines] hold `BEGIN TODAY`, the header and the rows. A bad line throws [ParseException]. */
    fun parse(lines: List<String>): List<TodayRow> {
        if (lines.firstOrNull() != "BEGIN TODAY") throw ParseException("BEGIN TODAY")
        val header = lines.getOrNull(1)
        if (header != HEADER && header != HEADER_V2 && header != HEADER_V3) throw ParseException(HEADER)
        val columns = when (header) {
            HEADER_V3 -> COLUMNS_V3
            HEADER_V2 -> COLUMNS_V2
            else -> COLUMNS
        }
        val seen = HashSet<Int>()
        return lines.drop(2).map { line ->
            val c = line.split(",")
            val s = c.getOrNull(0)?.let(::unsigned)
            val h = c.getOrNull(2)?.let(::unsigned)
            if (c.size != columns || s == null || s !in 0..10 || h == null || h > 24 || !seen.add(s)) throw ParseException(line)
            val row = TodayRow(
                s, c[1], h,
                number(c[3], line), number(c[4], line), number(c[5], line),
                number(c[6], line), number(c[7], line), number(c[8], line),
                if (columns >= COLUMNS_V2) hourCount(c[9], h, line) else null,
                if (columns >= COLUMNS_V2) hourCount(c[10], h, line) else null,
                if (columns == COLUMNS_V3) hourCount(c[11], h, line) else null
            )
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
