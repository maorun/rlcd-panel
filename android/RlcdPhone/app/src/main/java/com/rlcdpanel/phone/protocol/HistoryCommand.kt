package com.rlcdpanel.phone.protocol

/** Console command of the panel history; N is 0 (onboard) to 10, `null` reads all sensors. */
object HistoryCommand {
    /** `HISTORY <N>` for 0 to 10, `HISTORY` for `null`. Another number throws [IllegalArgumentException]. */
    fun line(sensor: Int?): String {
        if (sensor == null) return "HISTORY"
        require(sensor in 0..10) { "sensor $sensor" }
        return "HISTORY $sensor"
    }

    /** `HISTORY <N> <H>`: only the newest [hours] rows (firmware with the hour window). Sensor 0 to 10 and hours 1 to 24, else [IllegalArgumentException]. */
    fun lineHours(sensor: Int, hours: Int): String {
        require(sensor in 0..10) { "sensor $sensor" }
        require(hours in 1..24) { "hours $hours" }
        return "HISTORY $sensor $hours"
    }

    /** True when [replyLine] shows that an older firmware rejects `HISTORY <N> <H>`: `ERR BAD_ARG`. */
    fun shouldFallBackFromHours(replyLine: String): Boolean = replyLine == "ERR BAD_ARG"

    /** Keeps the rows of [sensor]. An older firmware answers `HISTORY <N>` with `ERR`, so the full reply is cut here. */
    fun filter(rows: List<PanelHistoryRow>, sensor: Int): List<PanelHistoryRow> = rows.filter { it.sensor == sensor }

    /** True when [replyLine] shows that an older firmware rejects `HISTORY <N>`: only `ERR UNKNOWN` of a one-sensor read (the old parser takes no argument). */
    fun shouldFallBack(sensor: Int?, replyLine: String): Boolean = sensor != null && replyLine == "ERR UNKNOWN"
}
