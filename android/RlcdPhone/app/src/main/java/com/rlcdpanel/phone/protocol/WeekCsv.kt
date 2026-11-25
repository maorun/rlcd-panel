package com.rlcdpanel.phone.protocol

import java.util.Locale

/** CSV text of the `WEEK` rows for the share intent. The rows keep the order of the reply. */
object WeekCsv {
    const val HEADER = WeekReply.HEADER + ",name"

    /** One header line, then one line for each row. Each line ends with a newline. */
    fun format(rows: List<WeekRow>, panelConfig: PanelConfig): String {
        val out = StringBuilder(HEADER).append('\n')
        for (row in rows) {
            out.append(
                listOf(
                    row.sensor.toString(), row.day.toString(), row.date,
                    number(row.temperatureMinC), number(row.temperatureMaxC),
                    number(row.humidityMinPct), number(row.humidityMaxPct),
                    row.moldHours?.toString() ?: "",
                    row.dryHours?.toString() ?: "",
                    panelConfig.tileName(row.sensor)
                ).joinToString(",") { EventCsv.quote(it) }
            ).append('\n')
        }
        return out.toString()
    }

    /** One decimal place, empty for null. */
    private fun number(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: ""
}
