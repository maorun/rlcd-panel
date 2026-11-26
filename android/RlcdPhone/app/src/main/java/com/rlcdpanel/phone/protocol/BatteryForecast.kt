package com.rlcdpanel.phone.protocol

/**
 * Date forecast for the battery change of one SwitchBot slot, from the hourly `TAIL` rows.
 * Port of `BatteryForecast.swift` of the macOS app with the same rules.
 */
object BatteryForecast {
    /** Only rows of the last 60 days before the newest row count. */
    const val WINDOW_DAYS = 60

    /** A rise of this many percent between two rows is a new battery. */
    const val RISE_RESET_PCT = 10

    /** The remaining rows must span at least this many days. */
    const val MIN_SPAN_DAYS = 7

    /** The battery must fall at least this many percent over the remaining rows. */
    const val MIN_DROP_PCT = 3

    /** Used when no `CONFIG` reply gives `sensor_battery_limit_pct`. */
    const val DEFAULT_LIMIT_PCT = 10.0

    const val NOT_ENOUGH_DATA = "Battery forecast: not enough data"
    const val LONGER_LOG_HINT = "Read the log archive on the Mac for a longer forecast"

    private const val DAY_MS = 86_400_000.0

    /** `sensor_battery_limit_pct` of the config, else 10 %. The value 0 means `BAT LOW` is off, so it also gives 10 %. */
    fun limitPct(config: PanelConfig?): Double {
        val v = config?.values?.get("sensor_battery_limit_pct")?.trim()?.toDoubleOrNull()
        return if (v != null && v.isFinite() && v > 0) v else DEFAULT_LIMIT_PCT
    }

    private data class Sample(val time: Double, val pct: Double)

    private fun epoch(timestamp: String): Long? = PanelClock.parse(timestamp.replace(' ', 'T'))

    /**
     * Epoch milliseconds when the least-squares line over the battery of slot [sensor] (1 to 10) reaches [limitPct].
     * Uses the rows of the last 60 days after the last rise of 10 % or more. Null for the onboard sensor, for less
     * than 7 days of data, for a drop below 3 %, or for a line that does not fall. A line that already reached the
     * limit gives the time of the newest row. The window counts back from the newest row, not from today.
     */
    fun estimate(rows: List<TailRow>, sensor: Int, limitPct: Double): Long? {
        if (sensor !in 1..TailReply.SLOT_COUNT || !limitPct.isFinite()) return null
        var samples = rows.mapNotNull { row ->
            val pct = row.slots.getOrNull(sensor - 1)?.batteryPct ?: return@mapNotNull null
            val t = epoch(row.timestamp) ?: return@mapNotNull null
            Sample(t.toDouble(), pct.toDouble())
        }.sortedBy { it.time }
        val newest = samples.lastOrNull()?.time ?: return null
        samples = samples.filter { it.time >= newest - WINDOW_DAYS * DAY_MS }
        val reset = (1 until samples.size).lastOrNull { samples[it].pct - samples[it - 1].pct >= RISE_RESET_PCT }
        if (reset != null) samples = samples.subList(reset, samples.size)
        val first = samples.firstOrNull() ?: return null
        val last = samples.last()
        if (samples.size < 2 || last.time - first.time < MIN_SPAN_DAYS * DAY_MS ||
            first.pct - last.pct < MIN_DROP_PCT
        ) return null
        // Least squares on days since the first row.
        val xs = samples.map { (it.time - first.time) / DAY_MS }
        val ys = samples.map { it.pct }
        val n = samples.size.toDouble()
        val meanX = xs.sum() / n
        val meanY = ys.sum() / n
        var sxx = 0.0
        var sxy = 0.0
        for (i in xs.indices) {
            sxx += (xs[i] - meanX) * (xs[i] - meanX)
            sxy += (xs[i] - meanX) * (ys[i] - meanY)
        }
        if (sxx <= 0) return null
        val slope = sxy / sxx
        if (slope >= 0) return null
        val day = meanX + (limitPct - meanY) / slope
        val target = first.time + day * DAY_MS
        if (!target.isFinite()) return null
        return maxOf(target, newest).toLong()
    }

    /** Days between the oldest and the newest row; 0 for fewer than 2 rows with a valid time stamp. */
    fun spanDays(rows: List<TailRow>): Double {
        val times = rows.mapNotNull { epoch(it.timestamp) }
        if (times.size < 2) return 0.0
        return (times.max() - times.min()) / DAY_MS
    }

    /** True when the rows span fewer than 7 days, so the forecast needs the log archive of the Mac. */
    fun needsLongerLog(rows: List<TailRow>): Boolean = spanDays(rows) < MIN_SPAN_DAYS

    /** `Battery change near <YYYY-MM-DD>` (panel local date) or the not-enough-data text for null. */
    fun text(estimate: Long?): String {
        val date = estimate?.let { PanelClock.format(it) }?.substring(0, 10) ?: return NOT_ENOUGH_DATA
        return "Battery change near $date"
    }
}
