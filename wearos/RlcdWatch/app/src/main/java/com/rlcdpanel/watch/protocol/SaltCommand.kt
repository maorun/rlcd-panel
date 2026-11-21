package com.rlcdpanel.watch.protocol

/** Console command of the salt check; N is 0 (onboard) to 10. */
object SaltCommand {
    const val MAX_SENSOR = 10

    /** Throws [IllegalArgumentException] for a sensor outside 0 to 10 (the firmware answers `ERR BAD_ARG`). */
    fun line(sensor: Int, magnesium: Boolean): String {
        require(sensor in 0..MAX_SENSOR) { "sensor $sensor outside 0 to $MAX_SENSOR" }
        return if (magnesium) "SALT $sensor MGCL2" else "SALT $sensor"
    }
}
