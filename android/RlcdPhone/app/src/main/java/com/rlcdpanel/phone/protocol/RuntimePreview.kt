package com.rlcdpanel.phone.protocol

/**
 * Battery runtime for an edited `wake_interval_s` and `night_wake_interval_s` (phone only).
 *
 * Port of the macOS `RuntimePreview.swift` ratio model on top of `BatteryHistory::estimate`: the drain per hour is
 * proportional to the wake cycles per hour, weighted by `interval + awake`. The `RUNTIME h` value of the last
 * `STATUS` was measured with the current schedule; the preview scales it with the ratio of the drain rates. The
 * sleep current and `scan_saved_ms` are not modelled, so the value is an estimate.
 */
object RuntimePreview {
    val WAKE_RANGE = 30..900
    val NIGHT_RANGE = 60..1800

    /** `BatteryHistory::AWAKE_MS`, `MIN_AWAKE_MS`, `AwakeTimeStats::MIN_SAMPLES` of the firmware. */
    private const val DEFAULT_AWAKE_MS = 10000
    private const val MIN_AWAKE_MS = 2000
    private const val MIN_SAMPLES = 8

    /** `BatteryHistory::estimate` caps the hours at 99 days and 12 hours; a capped value is no measurement. */
    private const val CAP_HOURS = 99.0 * 24.0 + 12.0

    /** One wake schedule. A [nightS] of 0 uses [wakeS] also in the quiet hours. */
    data class Schedule(val wakeS: Int, val nightS: Int, val quietStartHour: Int, val quietEndHour: Int) {
        val valid: Boolean
            get() = wakeS in WAKE_RANGE && (nightS == 0 || nightS in NIGHT_RANGE) &&
                quietStartHour in 0..23 && quietEndHour in 0..23
    }

    /** Hours of the quiet window in 24 hours (22 to 7 gives 9). An equal start and end give 0. */
    fun nightHours(startHour: Int, endHour: Int): Int {
        if (startHour !in 0..23 || endHour !in 0..23 || startHour == endHour) return 0
        return (endHour - startHour + 24) % 24
    }

    /** Wake cycles per hour of the whole day, weighted by the cost of one cycle; null for an invalid schedule. */
    internal fun drainRate(s: Schedule, awakeS: Double): Double? {
        if (!s.valid) return null
        val night = nightHours(s.quietStartHour, s.quietEndHour).toDouble()
        val nightInterval = if (s.nightS > 0) s.nightS else s.wakeS
        val day = 24.0 - night
        return (day / (s.wakeS + awakeS) + night / (nightInterval + awakeS)) / 24.0
    }

    /**
     * Expected runtime in whole hours for [edited]. Null without an `AWAKE` mean or a `RUNTIME h` value, for a
     * capped `RUNTIME h` and for an interval outside the key range. Like the firmware, a mean with fewer than 8
     * samples or at most 2000 ms is no measurement: the constant awake time of 10 s applies.
     */
    fun hours(runtimeHours: Double?, avgMs: Int?, samples: Int?, current: Schedule, edited: Schedule): Int? {
        if (runtimeHours == null || runtimeHours < 0 || runtimeHours >= CAP_HOURS || avgMs == null) return null
        val measured = (samples ?: 0) >= MIN_SAMPLES && avgMs > MIN_AWAKE_MS
        val awakeS = (if (measured) avgMs else DEFAULT_AWAKE_MS) / 1000.0
        val now = drainRate(current, awakeS) ?: return null
        val next = drainRate(edited, awakeS) ?: return null
        val scaled = runtimeHours * now / next
        if (!scaled.isFinite()) return null
        return Math.round(minOf(scaled, CAP_HOURS)).toInt()
    }

    /**
     * Preview for the config form: the current schedule comes from [config] (the `CONFIG` values at the time of the
     * `STATUS` read), the edited one from [draft] over it. A draft value that is no integer gives null. The quiet
     * window of the draft wins over the `QUIET` line, which holds the window of the panel.
     */
    fun hours(config: Map<String, String>, draft: Map<String, String>, status: StatusReply?): Int? {
        fun int(key: String, fallback: Int, edited: Boolean): Int? {
            val text = (if (edited) draft[key] else null) ?: config[key] ?: return fallback
            // Like the Swift reference, strip only spaces and tabs.
            return text.trim { it == ' ' || it == '\t' }.toIntOrNull()
        }
        fun schedule(edited: Boolean): Schedule? {
            val wake = int("wake_interval_s", 60, edited) ?: return null
            val night = int("night_wake_interval_s", 0, edited) ?: return null
            val start = int("quiet_hours_start_hour", status?.quiet?.startHour ?: 22, edited) ?: return null
            val end = int("quiet_hours_end_hour", status?.quiet?.endHour ?: 7, edited) ?: return null
            return Schedule(wake, night, start, end)
        }
        val current = schedule(false) ?: return null
        val edited = schedule(true) ?: return null
        return hours(status?.runtime?.hours?.toDouble(), status?.awake?.avgMs, status?.awake?.samples, current, edited)
    }

    /** `Laufzeit ca. <N> h` or `Laufzeit --`. */
    fun text(hours: Int?): String = if (hours == null) "Laufzeit --" else "Laufzeit ca. $hours h"
}
