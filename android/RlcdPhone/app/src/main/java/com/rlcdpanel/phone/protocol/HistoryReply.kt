package com.rlcdpanel.phone.protocol

/** One `HISTORY` row: one hourly sample of one sensor. Age 0 is the newest hour. */
data class PanelHistoryRow(
    val sensor: Int,
    val ageHours: Int,
    val temperatureC: Double?,
    val humidityPct: Double?,
    val noiseDb: Double?
)

/** Curve of the panel history. The last two derive from the temperature and humidity pair of one hour. */
enum class HistoryCurve(val label: String, val unit: String) {
    TEMPERATURE("Temperature", "°C"),
    HUMIDITY("Humidity", "%RH"),
    FEELS_LIKE("Feels like", "°C"),
    ABSOLUTE_HUMIDITY("Absolute humidity", "g/m³")
}

/** Value of [curve] for this hour. An hour without a pair of values gives null for a derived curve. */
fun PanelHistoryRow.value(curve: HistoryCurve): Double? = when (curve) {
    HistoryCurve.TEMPERATURE -> temperatureC
    HistoryCurve.HUMIDITY -> humidityPct
    HistoryCurve.FEELS_LIKE ->
        if (temperatureC == null || humidityPct == null) null else ClimateMath.heatIndexC(temperatureC, humidityPct)
    HistoryCurve.ABSOLUTE_HUMIDITY ->
        if (temperatureC == null || humidityPct == null) null else ClimateMath.absoluteHumidityGm3(temperatureC, humidityPct)
}

/** Port of `HistoryReply.parse` of `Replies.swift`. The rule set and the errors stay the same. */
object HistoryReply {
    const val HEADER = "sensor,age_h,temp_c,rh_pct,noise_db"

    private val DECIMAL = Regex("^-?[0-9]+(\\.[0-9]+)?$")

    /** `--` and an empty text give null. Only a plain decimal counts; padding, `NaN` and `Infinity` are bad. */
    private fun number(s: String, line: String): Double? {
        if (s == "--" || s.isEmpty()) return null
        if (!DECIMAL.matches(s)) throw ParseException(line)
        return s.toDouble()
    }

    /** [lines] hold `BEGIN HISTORY`, the header and the rows. A bad line throws [ParseException]. */
    fun parse(lines: List<String>): List<PanelHistoryRow> {
        if (lines.firstOrNull() != "BEGIN HISTORY") throw ParseException("BEGIN HISTORY")
        if (lines.size < 2 || lines[1] != HEADER) throw ParseException(HEADER)
        return lines.drop(2).map { line ->
            val c = line.split(",")
            val s = c.getOrNull(0)?.toIntOrNull()
            val a = c.getOrNull(1)?.toIntOrNull()
            if (c.size != 5 || s == null || a == null || s !in 0..10 || a !in 0..23) throw ParseException(line)
            PanelHistoryRow(s, a, number(c[2], line), number(c[3], line), number(c[4], line))
        }
    }
}
