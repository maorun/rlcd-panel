package com.rlcdpanel.phone.protocol

/** One sensor of a `TAIL` row. A missing value is null. [batteryPct] is null for the onboard sensor. */
data class TailSensor(val temperatureC: Double?, val humidityPct: Double?, val batteryPct: Int?)

/** One `TAIL` row: a line of `climate_v6_YYYYMM.csv`. [slots] holds the 10 registry slots in order. */
data class TailRow(
    /** `YYYY-MM-DD hh:mm:ss`, local panel time. */
    val timestamp: String,
    val panelBatteryPct: Int?,
    val panelBatteryV: Double?,
    /** True on USB power, false on battery power, null for an empty field. */
    val usb: Boolean?,
    val onboard: TailSensor,
    val slots: List<TailSensor>
)

/**
 * Parser of the `TAIL` reply (`SerialConsoleRunner::showTail` of the firmware). The column layout and the
 * value rules are those of `SdLogCodec::parseClimateRow`: 42 columns, an empty field is a missing value.
 */
object TailReply {
    const val COLUMNS = 42
    const val SLOT_COUNT = 10
    const val MAX_ROWS = 72
    const val HEADER =
        "timestamp,battery_pct,battery_v,usb,onboard_temp_c,onboard_rh," +
            "s1_temp_c,s1_rh,s1_battery_pct,s2_temp_c,s2_rh,s2_battery_pct,s3_temp_c,s3_rh,s3_battery_pct," +
            "s4_temp_c,s4_rh,s4_battery_pct,s5_temp_c,s5_rh,s5_battery_pct,s6_temp_c,s6_rh,s6_battery_pct," +
            "s7_temp_c,s7_rh,s7_battery_pct,s8_temp_c,s8_rh,s8_battery_pct,s9_temp_c,s9_rh,s9_battery_pct," +
            "s10_temp_c,s10_rh,s10_battery_pct,noise_rms,noise_db,noise_band,noise_events,noise_l10,noise_l90"

    private val BEGIN = Regex("^BEGIN TAIL (0|[1-9][0-9]?)$")
    private val TIMESTAMP = Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2}) ([0-9]{2}):([0-9]{2}):([0-9]{2})$")
    private val DECIMAL = Regex("^-?[0-9]+(\\.[0-9])?$")
    private val UINT = Regex("^[0-9]{1,3}$")
    private val VOLTS = Regex("^[0-9]+(\\.[0-9]+)?$")

    /** Decimal with at most one fractional digit in [lo, hi], like `SdLogCodec::parseTenths`. */
    private fun tenths(s: String, lo: Double, hi: Double, line: String): Double? {
        if (s.isEmpty()) return null
        if (!DECIMAL.matches(s)) throw ParseException(line)
        val v = s.toDouble()
        if (v < lo || v > hi) throw ParseException(line)
        return v
    }

    /** `SdLogCodec::parseClimateRow` ignores the battery columns, so a bad value gives null and keeps the row. */
    private fun percent(s: String): Int? = s.takeIf { UINT.matches(it) }?.toIntOrNull()?.takeIf { it <= 100 }

    private fun validDate(y: Int, m: Int, d: Int): Boolean {
        val leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return m in 1..12 && d in 1..days[m - 1]
    }

    private fun sensor(c: List<String>, col: Int, hasBattery: Boolean, line: String) = TailSensor(
        tenths(c[col], -100.0, 100.0, line),
        tenths(c[col + 1], 0.0, 100.0, line),
        if (hasBattery) percent(c[col + 2]) else null
    )

    private fun row(line: String): TailRow {
        val c = line.split(",")
        if (c.size != COLUMNS) throw ParseException(line)
        val t = TIMESTAMP.matchEntire(c[0]) ?: throw ParseException(line)
        val v = t.groupValues.drop(1).map { it.toInt() }
        if (v[0] !in 2000..2099 || !validDate(v[0], v[1], v[2]) || v[3] > 23 || v[4] > 59 || v[5] > 59) {
            throw ParseException(line)
        }
        val volts = c[2].takeIf { VOLTS.matches(it) }?.toDouble()
        val usb = when (c[3]) {
            "" -> null
            "0" -> false
            "1" -> true
            else -> throw ParseException(line)
        }
        return TailRow(
            c[0],
            percent(c[1]),
            volts,
            usb,
            sensor(c, 4, false, line),
            List(SLOT_COUNT) { sensor(c, 6 + 3 * it, true, line) }
        )
    }

    /**
     * [lines] hold `BEGIN TAIL <n>`, the header and the rows, without the `CRC32` line (oldest row first).
     * A wrong header, a count that differs from the row count, a wrong column count or a bad timestamp, USB flag or sensor value throws [ParseException]. A bad panel battery value or slot battery value gives null, like the firmware.
     */
    fun parse(lines: List<String>): List<TailRow> {
        val count = lines.firstOrNull()?.let { BEGIN.matchEntire(it) }?.groupValues?.get(1)?.toInt()
            ?: throw ParseException(lines.firstOrNull() ?: "BEGIN TAIL")
        if (lines.getOrNull(1) != HEADER) throw ParseException(lines.getOrNull(1) ?: HEADER)
        val rows = lines.drop(2).map { row(it) }
        if (rows.size != count || count > MAX_ROWS) throw ParseException("BEGIN TAIL $count")
        return rows
    }
}
