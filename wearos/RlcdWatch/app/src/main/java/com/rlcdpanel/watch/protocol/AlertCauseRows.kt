package com.rlcdpanel.watch.protocol

/** Watch-only text of the alert causes screen: the shared cause text plus the limit with its unit and `Acked`. */
object AlertCauseRows {
    const val ACKED = "Acked"
    private val UNITS = mapOf("FREEZER" to "°C", "HUMIDITY" to "%", "NOISE" to "dB", "SENSOR_BATTERY" to "%", "HEAT" to "°C")

    /** One line for each active alert, in reply order. */
    fun lines(log: AlertsLog, tileNames: List<String>): List<String> = log.rows.mapNotNull { row ->
        val cause = log.causeText(row.name, tileNames) ?: return@mapNotNull null
        val parts = ArrayList<String>()
        parts.add(cause)
        row.limit?.let { parts.add(if (UNITS[row.name] != null) "$it ${UNITS[row.name]}" else it) }
        if (row.acked) parts.add(ACKED)
        parts.joinToString(" · ")
    }
}
