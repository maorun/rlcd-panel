package com.rlcdpanel.phone.protocol

/** Reference sensors of the calibration screen: 0 to 5 always, a slot 6 to 10 only with a reading. */
object CalibrationSensors {
    private val WITH_READING = setOf("OK", "STALE", "BATLOW")

    /** [keep] stays in the list, so a picked slot does not vanish when a `STATUS` read resets [rows]. */
    fun selectable(rows: List<SensorHealth>, keep: Int? = null): List<Int> =
        (0..SaltCommand.MAX_SENSOR).filter { n ->
            n <= 5 || n == keep || rows.any { it.slot == n && it.state in WITH_READING }
        }
}
