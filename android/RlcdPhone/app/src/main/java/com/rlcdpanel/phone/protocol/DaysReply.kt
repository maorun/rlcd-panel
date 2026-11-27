package com.rlcdpanel.phone.protocol

import java.util.Locale

/** One sensor of a `DAYS` row. A missing value is null; a pair is both values or none. */
data class DaysSensor(
    val temperatureMinC: Double?,
    val temperatureMaxC: Double?,
    val humidityMinPct: Double?,
    val humidityMaxPct: Double?,
    /** Hours above `mold_alert_limit_rh` (0 to 24). Null for an empty field. */
    val moldHours: Int?,
    /** Hours at or below `dry_alert_limit_rh` (0 to 24). Null for an empty field and for a `daily_v5` row. */
    val dryHours: Int? = null,
    /** Hours at or above `heat_alert_limit_c` (0 to 24). Null for an empty field and for a `daily_v5` or `daily_v6` row. */
    val heatHours: Int? = null
) {
    /** True without a temperature pair, a humidity pair, mold hours, dry hours and heat hours. */
    val isEmpty: Boolean
        get() = temperatureMinC == null && humidityMinPct == null && moldHours == null && dryHours == null && heatHours == null
}

/** One `DAYS` row: a line of `daily_v7_YYYY.csv` (or of `daily_v6_YYYY.csv` and `daily_v5_YYYY.csv` with empty heat or dry hours). [sensors] holds the onboard sensor, then slots 1 to 10. */
data class DaysRow(
    /** `YYYY-MM-DD`. */
    val date: String,
    val sensors: List<DaysSensor>
)

/**
 * Parser of the `DAYS <N>` reply (`SerialConsoleRunner::showDays` of the firmware). The column layout and the
 * value rules are those of `SdLogCodec::parseDailyRow`: 105 columns under the `daily_v7` header, 94 columns under the `daily_v6` header, 83 columns under the `daily_v5` header, an empty field is a missing value, a sensor
 * needs both values of a pair. The mean and noise columns are not read.
 */
object DaysReply {
    const val COLUMNS = 105
    const val COLUMNS_V6 = 94
    const val COLUMNS_V5 = 83
    const val SENSOR_COUNT = 11
    const val MAX_ROWS = 31
    val HEADER_V5 =
        "date,onboard_temp_min_c,onboard_temp_max_c,onboard_temp_mean_c,onboard_rh_min,onboard_rh_max,onboard_rh_mean," +
            (1..10).joinToString(",") { "s${it}_temp_min_c,s${it}_temp_max_c,s${it}_temp_mean_c,s${it}_rh_min,s${it}_rh_max,s${it}_rh_mean" } +
            ",noise_min_db,noise_max_db,noise_peak_db,noise_peak_hour,noise_events_sum," +
            (0..10).joinToString(",") { "mold_h_$it" }
    val HEADER_V6 = HEADER_V5 + "," + (0..10).joinToString(",") { "dry_h_$it" }
    val HEADER = HEADER_V6 + "," + (0..10).joinToString(",") { "heat_h_$it" }

    private const val NOISE_COLUMNS = 5
    private const val MOLD_FIRST = 1 + 6 * SENSOR_COUNT + NOISE_COLUMNS
    private const val DRY_FIRST = COLUMNS_V5
    private const val HEAT_FIRST = COLUMNS_V6
    private const val MAX_HOURS = 24

    private val BEGIN = Regex("^BEGIN DAYS (0|[1-9][0-9]?)$")
    private val DATE = Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})$")
    private val DECIMAL = Regex("^-?[0-9]+(\\.[0-9])?$")
    private val HOURS = Regex("^[0-9]{1,2}$")

    /** `DAYS <N>` for [rows] 1 to 31, `null` for each other value. */
    fun command(rows: Int): String? = if (rows in 1..MAX_ROWS) "DAYS $rows" else null

    /** Row counts that the user can select. */
    val CHOICES = listOf(7, 14, 31)

    /** Clear text of the error line [line], or `null` when [line] is no known `DAYS` error. */
    fun errorText(line: String): String? = when (line.trim()) {
        "ERR NO_CARD" -> "Keine MicroSD-Karte im Panel"
        "ERR NO_FILE" -> "Keine Tagesdatei oder keine gültige Uhrzeit im Panel"
        "ERR READ_FAILED" -> "Die MicroSD-Karte lässt sich nicht lesen"
        else -> null
    }

    /** Decimal with at most one fractional digit in [lo, hi], like `SdLogCodec::parseTenths`. */
    private fun tenths(s: String, lo: Double, hi: Double, line: String): Double? {
        if (s.isEmpty()) return null
        if (!DECIMAL.matches(s)) throw ParseException(line)
        val v = s.toDouble()
        if (v < lo || v > hi) throw ParseException(line)
        return v
    }

    private fun validDate(y: Int, m: Int, d: Int): Boolean {
        val leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return m in 1..12 && d in 1..days[m - 1]
    }

    /** Hours 0 to 24 of one mold, dry or heat field; an empty field is null. */
    private fun hours(text: String, line: String): Int? {
        if (text.isEmpty()) return null
        if (!HOURS.matches(text)) throw ParseException(line)
        return text.toInt().also { if (it > MAX_HOURS) throw ParseException(line) }
    }

    private fun row(line: String, columns: Int): DaysRow {
        val c = line.split(",")
        if (c.size != columns) throw ParseException(line)
        val d = DATE.matchEntire(c[0])?.groupValues?.drop(1)?.map { it.toInt() } ?: throw ParseException(line)
        if (d[0] !in 2000..2099 || !validDate(d[0], d[1], d[2])) throw ParseException(line)
        val sensors = List(SENSOR_COUNT) { s ->
            val col = 1 + 6 * s
            val tLo = tenths(c[col], -100.0, 100.0, line)
            val tHi = tenths(c[col + 1], -100.0, 100.0, line)
            val hLo = tenths(c[col + 3], 0.0, 100.0, line)
            val hHi = tenths(c[col + 4], 0.0, 100.0, line)
            if ((tLo == null) != (tHi == null) || (hLo == null) != (hHi == null)) throw ParseException(line)
            val mold = hours(c[MOLD_FIRST + s], line)
            val dry = if (columns >= COLUMNS_V6) hours(c[DRY_FIRST + s], line) else null
            val heat = if (columns == COLUMNS) hours(c[HEAT_FIRST + s], line) else null
            DaysSensor(tLo, tHi, hLo, hHi, mold, dry, heat)
        }
        return DaysRow(c[0], sensors)
    }

    /**
     * [lines] hold `BEGIN DAYS <n>`, the header and the rows, without the `CRC32` line (oldest row first).
     * The `daily_v7` header gives 105 columns, the `daily_v6` header 94 columns (no heat hours), the `daily_v5` header 83 columns (no dry and heat hours). A wrong header, a count that differs from the row count, a wrong column count, a bad date or a bad value
     * throws [ParseException].
     */
    fun parse(lines: List<String>): List<DaysRow> {
        val count = lines.firstOrNull()?.let { BEGIN.matchEntire(it) }?.groupValues?.get(1)?.toInt()
            ?: throw ParseException(lines.firstOrNull() ?: "BEGIN DAYS")
        val columns = when (lines.getOrNull(1)) {
            HEADER -> COLUMNS
            HEADER_V6 -> COLUMNS_V6
            HEADER_V5 -> COLUMNS_V5
            else -> throw ParseException(lines.getOrNull(1) ?: HEADER)
        }
        val rows = lines.drop(2).map { row(it, columns) }
        if (rows.size != count || count > MAX_ROWS) throw ParseException("BEGIN DAYS $count")
        return rows
    }

    /** Sensor numbers (0 onboard, 1 to 10 slots) with at least one value in [rows], ascending. */
    fun sensors(rows: List<DaysRow>): List<Int> =
        (0 until SENSOR_COUNT).filter { s -> rows.any { !it.sensors[s].isEmpty } }

    /** `MM-DD` of a `YYYY-MM-DD` date. A bad format stays unchanged. */
    fun dayLabel(date: String): String = if (date.length == 10) date.substring(5) else date

    /** CSV text of the rows for the share intent: one line per day and sensor with data, in the order of the reply. */
    fun csv(rows: List<DaysRow>, panelConfig: PanelConfig): String {
        val out = StringBuilder("date,sensor,name,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct,mold_h,dry_h\n")
        for (row in rows) {
            row.sensors.forEachIndexed { s, v ->
                if (v.isEmpty) return@forEachIndexed
                out.append(
                    listOf(
                        row.date, s.toString(), panelConfig.tileName(s),
                        number(v.temperatureMinC), number(v.temperatureMaxC),
                        number(v.humidityMinPct), number(v.humidityMaxPct),
                        v.moldHours?.toString() ?: "",
                        v.dryHours?.toString() ?: ""
                    ).joinToString(",") { EventCsv.quote(it) }
                ).append('\n')
            }
        }
        return out.toString()
    }

    private fun number(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: ""
}
