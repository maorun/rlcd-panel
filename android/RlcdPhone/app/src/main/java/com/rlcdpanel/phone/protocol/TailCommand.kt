package com.rlcdpanel.phone.protocol

import java.util.Locale

/** Console command `TAIL <N>` and the texts of its reply. All functions are pure. */
object TailCommand {
    /** Row counts that the user can select. */
    val CHOICES = listOf(6, 24, 72)

    /** `TAIL <N>` for [rows] 1 to 72, `null` for each other value. */
    fun text(rows: Int): String? = if (rows in 1..TailReply.MAX_ROWS) "TAIL $rows" else null

    /** Clear text of the error line [line], or `null` when [line] is no known `TAIL` error. */
    fun errorText(line: String): String? = when (line.trim()) {
        "ERR NO_CARD" -> "Keine MicroSD-Karte im Panel"
        "ERR NO_FILE" -> "Keine Logdatei oder keine gültige Uhrzeit im Panel"
        "ERR READ_FAILED" -> "Die MicroSD-Karte lässt sich nicht lesen"
        else -> null
    }

    /** `MM-DD hh:mm` of a panel time stamp (`YYYY-MM-DD hh:mm:ss`, local panel time). A bad format stays unchanged. */
    fun timeText(timestamp: String): String =
        if (timestamp.length == 19) timestamp.substring(5, 16) else timestamp

    /** `<T> °C <H> %`; a missing value shows `--`. */
    fun valueText(sensor: TailSensor): String {
        val t = sensor.temperatureC?.let { tenths(it) } ?: "--"
        val h = sensor.humidityPct?.let { tenths(it) } ?: "--"
        return "$t °C  $h %"
    }

    /** One decimal digit; a small negative value shows `0.0`, not `-0.0`. */
    private fun tenths(v: Double): String = String.format(Locale.ROOT, "%.1f", v).let { if (it == "-0.0") "0.0" else it }

    /** The rows of the reply, newest first. The reply has the oldest row first. */
    fun newestFirst(rows: List<TailRow>): List<TailRow> = rows.asReversed()

    /**
     * Text lines of one row: the time, the onboard line and one line for each slot of [slots]
     * (slot number 1 to 10 and its name, in the given order). A slot number outside 1 to 10 gives no line.
     */
    fun rowLines(row: TailRow, onboardName: String, slots: List<Pair<Int, String>>): List<String> =
        listOf(timeText(row.timestamp), "$onboardName: ${valueText(row.onboard)}") +
            slots.filter { it.first in 1..row.slots.size }
                .map { (slot, name) -> "$name: ${valueText(row.slots[slot - 1])}" }
}
