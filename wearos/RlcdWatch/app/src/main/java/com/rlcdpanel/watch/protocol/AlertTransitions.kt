package com.rlcdpanel.watch.protocol

/**
 * Detects rising alert bits between consecutive telemetry frames.
 */
object AlertTransitions {
    /**
     * Compares two telemetry frames and returns the rising alert bits.
     * Returns `AlertMask.NONE` if either frame is null, if both frames have the same sequence number,
     * or if no new alert bits were raised.
     */
    fun risingAlerts(previous: TelemetryFrame?, current: TelemetryFrame?): AlertMask {
        if (previous == null || current == null) return AlertMask.NONE
        if (previous.sequence == current.sequence) return AlertMask.NONE
        val rising = (current.alerts.rawValue and previous.alerts.rawValue.inv()) and 0xFF
        return AlertMask(rising)
    }
}
