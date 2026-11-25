package com.rlcdpanel.watch.protocol

/** Console command of the today screen; `TODAY <N>` reads one room, `TODAY` reads all rooms. */
object TodayCommand {
    const val MAX_SENSOR = 10
    const val ALL = "TODAY"

    /** Throws [IllegalArgumentException] for a sensor outside 0 to 10 (the firmware answers `ERR BAD_ARG`). */
    fun line(sensor: Int?): String {
        if (sensor == null) return ALL
        require(sensor in 0..MAX_SENSOR) { "sensor $sensor outside 0 to $MAX_SENSOR" }
        return "$ALL $sensor"
    }

    /** True when an older firmware rejected `TODAY <N>`; then one `TODAY` follows. */
    fun needsFallback(sensor: Int?, statusLine: String): Boolean =
        sensor != null && statusLine.trim().let { it == "ERR BAD_ARG" || it.startsWith("ERR BAD_ARG ") }

    /** Keeps only the row of [sensor]; null keeps all rows. */
    fun select(rows: List<TodayRow>, sensor: Int?): List<TodayRow> =
        if (sensor == null) rows else rows.filter { it.sensor == sensor }
}
