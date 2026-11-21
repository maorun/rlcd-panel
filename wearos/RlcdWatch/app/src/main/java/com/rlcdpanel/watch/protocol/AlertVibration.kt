package com.rlcdpanel.watch.protocol

/**
 * Vibration timing of the alert bits, so the wearer knows the alert without a look at the watch.
 */
object AlertVibration {
    private const val SHORT_MS = 120L
    private const val LONG_MS = 400L
    private const val GAP_MS = 120L

    /** Timing array for `VibrationEffect.createWaveform`: delay, on, off, on, ... Empty for mask 0. */
    fun pattern(alerts: AlertMask): LongArray {
        val raw = alerts.rawValue
        if (raw == 0) return LongArray(0)
        return when (raw and -raw) {
            AlertMask.FREEZER.rawValue -> pulses(3, SHORT_MS)
            AlertMask.HUMIDITY.rawValue -> pulses(2, LONG_MS)
            AlertMask.HEAT.rawValue -> pulses(4, SHORT_MS)
            else -> pulses(1, LONG_MS)
        }
    }

    private fun pulses(count: Int, onMs: Long): LongArray {
        val timings = LongArray(1 + count * 2 - 1)
        for (i in 0 until count) {
            timings[1 + i * 2] = onMs
            if (i < count - 1) timings[2 + i * 2] = GAP_MS
        }
        return timings
    }
}
